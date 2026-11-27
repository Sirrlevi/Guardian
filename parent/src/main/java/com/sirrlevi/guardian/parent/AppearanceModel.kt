package com.sirrlevi.guardian.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.WallpaperMeta
import com.sirrlevi.guardian.core.WallpaperRules
import com.sirrlevi.guardian.core.WallpaperTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** What the Device appearance screen needs from the database. Every call blocks and may throw; the model runs them off the main thread. */
interface AppearanceBackend {
    fun meta(): WallpaperMeta?
    fun image(meta: WallpaperMeta): ByteArray?
    fun delete()
}

/** The real backend: the selected phone of the signed-in parent. */
class RepoAppearanceBackend(
    private val repo: ParentRepository,
    private val sessionOf: () -> GuardianSession?,
    private val remember: (GuardianSession) -> Unit,
    private val deviceOf: () -> String?
) : AppearanceBackend {
    private fun <T> call(block: (GuardianSession, String) -> T): T {
        val s0 = sessionOf() ?: throw IOException("Not signed in.")
        val id = deviceOf() ?: throw IOException("No phone is connected.")
        val s = repo.fresh(s0)
        if (s !== s0) remember(s)
        return block(s, id)
    }

    override fun meta() = call { s, d -> repo.fetchWallpaperMeta(s, d) }
    override fun image(meta: WallpaperMeta) = call { s, d -> repo.fetchWallpaperImage(s, d, meta) }
    override fun delete() = call { s, d -> repo.deleteWallpaper(s, d) }
}

/**
 * State of the Device appearance screen: the wallpaper picture the child chose to share, and when they shared it.
 *
 * Android does not let apps read a wallpaper, so there is nothing to fetch until the child shares. With nothing shared the screen
 * says so and how to share; with a damaged or missing picture it says that. A picture is only ever shown when the bytes fetched are
 * a complete JPEG of exactly the size the child's phone reported. Nothing is drawn, guessed or kept from an earlier phone.
 */
class AppearanceModel(
    private val backend: () -> AppearanceBackend,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Where blocking database calls run. Tests pass an empty context so everything stays on the test's own thread. */
    @Volatile var io: CoroutineContext = Dispatchers.IO
) {
    enum class Phase { LOADING, NOT_SHARED, SHOWN, DAMAGED, FAILED }

    var phase by mutableStateOf(Phase.LOADING); private set
    var meta by mutableStateOf<WallpaperMeta?>(null); private set
    var jpeg by mutableStateOf<ByteArray?>(null); private set
    /** Set when the last refresh could not reach the server; what is on screen is then the last known, not current. */
    var error by mutableStateOf<String?>(null); private set
    var checkedAt by mutableLongStateOf(0L); private set
    var notice by mutableStateOf<String?>(null); private set
    var noticeIsProblem by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set

    @Volatile var foreground = true

    private val mutex = Mutex()
    /** The description whose picture was last fetched, so the same picture is not downloaded again every few seconds. */
    private var fetched: WallpaperMeta? = null

    /** Polls until cancelled. Call it from a coroutine tied to the screen being visible. */
    suspend fun watch() {
        while (coroutineContext.isActive) {
            val wait = if (!foreground) 1_000L else withContext(io) { tick(force = false) }
            delay(wait)
        }
    }

    /** One refresh. Returns how long to wait before the next one. [force] downloads the picture again even if nothing changed. */
    suspend fun tick(force: Boolean = false): Long = mutex.withLock {
        try {
            val b = backend()
            val current = b.meta()
            if (current == null) {
                meta = null; jpeg = null; fetched = null
                phase = Phase.NOT_SHARED
            } else if (force || current != fetched) {
                val bytes = b.image(current)
                meta = current; fetched = current
                if (WallpaperRules.isComplete(current, bytes)) { jpeg = bytes; phase = Phase.SHOWN } else { jpeg = null; phase = Phase.DAMAGED }
            }
            error = null; checkedAt = clock()
            REFRESH_MS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describe(e)
            if (phase == Phase.LOADING) phase = Phase.FAILED
            RETRY_MS
        }
    }

    /** The parent asked to look again. */
    suspend fun refreshNow() { withContext(io) { tick(force = true) } }

    /** Deletes the shared picture from the server. The child's phone is not touched. */
    suspend fun delete(): Boolean = withContext(io) {
        mutex.withLock {
            busy = true
            try {
                backend().delete()
                meta = null; jpeg = null; fetched = null; phase = Phase.NOT_SHARED
                say(WallpaperTexts.REMOVED, problem = false)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(WallpaperTexts.REMOVE_FAILED + " " + describe(e), problem = true)
                false
            } finally {
                busy = false
            }
        }
    }

    /** Reports the outcome of saving a copy; the saving itself is done by the screen (the parent picks where it goes). */
    fun saved(ok: Boolean) = if (ok) say(WallpaperTexts.SAVED, problem = false) else say(WallpaperTexts.SAVE_FAILED, problem = true)

    private fun say(text: String?, problem: Boolean) { notice = text; noticeIsProblem = problem }

    /** Forget everything, e.g. when another phone is selected or the parent signs out. */
    fun reset() {
        phase = Phase.LOADING; meta = null; jpeg = null; error = null; checkedAt = 0L; notice = null; noticeIsProblem = false; busy = false; fetched = null
    }

    /** Lets UI tests show fixed data without a network. Never called by the app itself. */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(phase: Phase, meta: WallpaperMeta? = null, jpeg: ByteArray? = null, error: String? = null, notice: String? = null, noticeIsProblem: Boolean = false) {
        this.phase = phase; this.meta = meta; this.jpeg = jpeg; this.error = error; this.checkedAt = clock()
        this.notice = notice; this.noticeIsProblem = noticeIsProblem
        this.fetched = meta
    }

    private fun describe(e: Exception): String {
        val text = e.message ?: "network error"
        return when {
            text.contains("Unable to resolve host") || text.contains("timeout", ignoreCase = true) -> "No internet connection."
            else -> text.take(160)
        }
    }

    companion object {
        const val REFRESH_MS = 20_000L
        const val RETRY_MS = 8_000L
    }
}
