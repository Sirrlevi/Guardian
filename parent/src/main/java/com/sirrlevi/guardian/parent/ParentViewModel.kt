package com.sirrlevi.guardian.parent

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.AppRule
import com.sirrlevi.guardian.core.CommRecord
import com.sirrlevi.guardian.core.MediaItem
import com.sirrlevi.guardian.core.MediaSettings
import com.sirrlevi.guardian.core.MediaStatusSnapshot
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.GuardianApi
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.PolicyCodec
import com.sirrlevi.guardian.core.Schedule
import com.sirrlevi.guardian.core.UpdateBlockedException
import com.sirrlevi.guardian.core.UpdateRules
import com.sirrlevi.guardian.parent.ui.Formats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ParentViewModel(application: Application) : AndroidViewModel(application) {
    enum class Phase { Loading, SignedOut, Ready }

    private val app = application
    private val repo = ParentRepository(app)
    private val updater = GuardianUpdater(app)
    private val prefs = app.getSharedPreferences("guardian_parent_ui", Context.MODE_PRIVATE)
    @Volatile private var session: GuardianSession? = null

    var phase by mutableStateOf(Phase.Loading); private set
    var authBusy by mutableStateOf(false); private set
    var authError by mutableStateOf<String?>(null)
    var email by mutableStateOf("")
    var message by mutableStateOf<String?>(null)

    var devices by mutableStateOf<List<ChildDevice>>(emptyList()); private set
    var selectedDeviceId by mutableStateOf<String?>(null); private set
    var policy by mutableStateOf(FamilyPolicy()); private set
    var connection by mutableStateOf(Connection.Connecting); private set
    var lastRefreshAt by mutableLongStateOf(0L); private set
    var loadedOnce by mutableStateOf(false); private set
    var selectedDay by mutableStateOf(DayClock.of(System.currentTimeMillis()).dayKey)
    val icons = mutableStateMapOf<String, ImageBitmap>()

    var pairingCode by mutableStateOf(""); private set
    var pairingExpiresAt by mutableLongStateOf(0L); private set
    var themeMode by mutableStateOf(ThemeMode.valueOf(prefs.getString("theme", ThemeMode.System.name) ?: ThemeMode.System.name)); private set

    // updates
    var hasToken by mutableStateOf(updater.hasToken()); private set
    var updateStatus by mutableStateOf("Not checked yet."); private set
    var updateResult by mutableStateOf<UpdateCheckResult?>(null); private set
    var updateBusy by mutableStateOf(false); private set
    var updateProgress by mutableStateOf(-1); private set
    var lastUpdateCheck by mutableLongStateOf(updater.lastCheckedAt()); private set
    var installRequest by mutableStateOf<File?>(null)
    /** Set when this phone cannot be updated in place (debug build / different signing key): browser link to the APK. */
    var reinstallUrl by mutableStateOf<String?>(null); private set

    // ---- message previews (loaded on demand by the Messages screen)
    var comms by mutableStateOf<List<CommRecord>>(emptyList()); private set
    var commsLoading by mutableStateOf(false); private set
    var commsError by mutableStateOf<String?>(null); private set
    /** True after the first load for the selected phone finished (successfully or not). */
    var commsLoaded by mutableStateOf(false); private set
    var commsDays by mutableIntStateOf(COMMS_PAGE_DAYS); private set
    private var commsFor: String? = null
    /** The child's `commsAt` the loaded list corresponds to, so a newer upload (never a clock comparison) triggers a reload. */
    private var commsMarker = -1L
    private val commsBusy = AtomicBoolean(false)

    /** Approved screen sharing with the selected phone: screenshots and a small live preview. Polls only while its screen is open. */
    val preview = ScreenPreviewModel(backend = { previewBackendForTest ?: RepoPreviewBackend(repo, { session }, { session = it }, { device?.id }) })
    @androidx.annotation.VisibleForTesting
    internal var previewBackendForTest: PreviewBackend? = null

    /** Find my phone: the location settings and the real positions the phone saved. Polls only while its screen is open. */
    val recovery = RecoveryModel(backend = { recoveryBackendForTest ?: RepoRecoveryBackend(repo, { session }, { session = it }, { device?.id }) })
    @androidx.annotation.VisibleForTesting
    internal var recoveryBackendForTest: RecoveryBackend? = null

    /** Device appearance: the wallpaper picture the child chose to share. Polls only while its screen is open. */
    val appearance = AppearanceModel(backend = { appearanceBackendForTest ?: RepoAppearanceBackend(repo, { session }, { session = it }, { device?.id }) })
    @androidx.annotation.VisibleForTesting
    internal var appearanceBackendForTest: AppearanceBackend? = null

    /** Safety alerts: rules over message previews, app use and the phone's own reports. Checked while its screen is open and now and then in the foreground. */
    val safety = SafetyModel(backend = { safetyBackendForTest ?: RepoSafetyBackend(repo, { session }, { session = it }, { device }) })
    @androidx.annotation.VisibleForTesting
    internal var safetyBackendForTest: SafetyBackend? = null

    /**
     * Optional Telegram reports. There is no Guardian server, so they are sent from here, only while the app is open; what could not be sent waits.
     * Each finished Safety check hands its alerts over, and the 30 s foreground loop sends whatever is due.
     */
    val telegram = TelegramModel(backend = { telegramBackendForTest ?: DeviceTelegramBackend(app) })
    @androidx.annotation.VisibleForTesting
    internal var telegramBackendForTest: TelegramBackend? = null

    init {
        safety.onChecked = { checked -> viewModelScope.launch { telegram.report(checked) } }
    }

    /** Lock now, restart, ring, find now and the phone's real status. Polls only while its screen is open. */
    val controls = DeviceControlModel(
        backend = { controlBackendForTest ?: RepoControlBackend(repo, { session }, { session = it }, { device?.id }) },
        locateGate = { recovery.locateOffer }
    )
    @androidx.annotation.VisibleForTesting
    internal var controlBackendForTest: ControlBackend? = null

    // ---- shared media (preview copies of the child's new photos and videos)
    var mediaItems by mutableStateOf<List<MediaItem>>(emptyList()); private set
    var mediaSettings by mutableStateOf(MediaSettings()); private set
    var mediaStatus by mutableStateOf<MediaStatusSnapshot?>(null); private set
    var mediaLoading by mutableStateOf(false); private set
    var mediaLoaded by mutableStateOf(false); private set
    var mediaError by mutableStateOf<String?>(null); private set
    var mediaHasMore by mutableStateOf(false); private set
    var mediaSaving by mutableStateOf(false); private set
    /** True once the backup switch and the phone's report have been read (Home uses this without loading pictures). */
    var mediaSummaryReady by mutableStateOf(false); private set
    private var mediaSummaryAt = 0L
    private val summaryBusy = AtomicBoolean(false)
    private var mediaCursor: String? = null
    private var mediaFor: String? = null
    private val mediaBusy = AtomicBoolean(false)
    private val previewCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 6
    }

    val device: ChildDevice? get() = devices.firstOrNull { it.id == selectedDeviceId } ?: devices.firstOrNull()

    /** Lets UI smoke tests render every screen with fixed sample data. Never called by the app itself. */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(list: List<ChildDevice>, rules: FamilyPolicy) {
        devices = list
        selectedDeviceId = list.firstOrNull()?.id
        policy = rules
        connection = Connection.Live
        loadedOnce = true
        phase = Phase.Ready
    }

    /** Lets UI tests show the Messages screen with fixed previews without a network. Never called by the app itself. */
    @androidx.annotation.VisibleForTesting
    internal fun showCommsForTest(records: List<CommRecord>, error: String? = null, loaded: Boolean = true) {
        commsFor = device?.id
        commsMarker = device?.commsAt ?: 0L
        comms = records
        commsError = error
        commsLoaded = loaded
        commsLoading = false
    }

    private val iconCache = HashMap<String, String>()
    private var lastIconFetch = 0L
    private val refreshing = AtomicBoolean(false)
    /** A refresh was asked for while one was running: run exactly one more when it ends (never silently drop it). */
    @Volatile private var refreshQueued = false

    // ---- policy writes: applied on screen at once, sent to the server strictly in order
    private class PolicyWrite(val update: JsonObject, val doneMessage: String)
    private val writes = Channel<PolicyWrite>(Channel.UNLIMITED)
    private var writer: Job? = null
    /** Bumped whenever a local write starts or ends, so a refresh that began earlier cannot overwrite it with older data. */
    @Volatile private var policyEpoch = 0
    /** Saves that are still on their way to the server. */
    var pendingWrites by mutableIntStateOf(0); private set
    private var todayJob: Job? = null
    private var pollJob: Job? = null
    @Volatile private var rtActive = false
    private var rtThread: Thread? = null
    @Volatile private var rtHandle: GuardianApi.StreamHandle? = null

    init {
        val saved = repo.loadSession()
        session = saved
        if (saved == null) {
            phase = Phase.SignedOut
        } else {
            phase = Phase.Ready
            viewModelScope.launch(Dispatchers.IO) {
                iconCache.putAll(repo.loadIcons())
                val decoded = iconCache.mapNotNull { (pkg, b64) -> repo.decodeIcon(b64)?.let { pkg to it } }
                val cached = repo.loadCache()
                withContext(Dispatchers.Main) {
                    decoded.forEach { (pkg, bmp) -> icons[pkg] = bmp }
                    if (cached != null && devices.isEmpty()) {
                        devices = cached.first
                        policy = cached.second
                        loadedOnce = true
                    }
                }
                refreshAll(silent = false)
            }
        }
    }

    // ------------------------------------------------------------ sign in / out

    fun signIn(create: Boolean, emailInput: String, password: String) {
        val mail = emailInput.trim()
        if (mail.isBlank() || password.length < 6) {
            authError = "Enter a valid email and a password of at least 6 characters."
            return
        }
        if (!repo.isConfigured()) {
            authError = "This build has no Firebase settings. Add FIREBASE_API_KEY and FIREBASE_DATABASE_URL to the repository secrets and rebuild."
            return
        }
        authBusy = true
        authError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = if (create) repo.signUp(mail, password) else repo.signIn(mail, password)
                session = result
                withContext(Dispatchers.Main) {
                    email = mail
                    authBusy = false
                    phase = Phase.Ready
                }
                refreshAll(silent = false)
                checkUpdates(false)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    authBusy = false
                    authError = friendly(e)
                }
            }
        }
    }

    fun signOut() {
        stopRealtime()
        preview.reset()
        controls.reset()
        recovery.reset()
        appearance.reset()
        safety.reset()
        viewModelScope.launch { telegram.wipe() }
        repo.clearSession()
        session = null
        devices = emptyList()
        policy = FamilyPolicy()
        selectedDeviceId = null
        loadedOnce = false
        icons.clear()
        iconCache.clear()
        phase = Phase.SignedOut
    }

    // ------------------------------------------------------------ data

    fun selectDevice(id: String) {
        if (id == selectedDeviceId) return
        selectedDeviceId = id
        resetComms()
        resetMedia()
        preview.reset()
        controls.reset()
        recovery.reset()
        appearance.reset()
        safety.reset()
        stopRealtime()
        startRealtime()
        refreshAll(silent = false)
    }

    private fun resetMedia() {
        mediaItems = emptyList(); mediaStatus = null; mediaSettings = MediaSettings(); mediaLoaded = false; mediaLoading = false
        mediaError = null; mediaHasMore = false; mediaCursor = null; mediaFor = null
        mediaSummaryReady = false; mediaSummaryAt = 0L
        synchronized(previewCache) { previewCache.clear() }
    }

    /** Cheap check for the Home screen: the backup switch and what the phone reports, without any pictures. */
    fun refreshMediaSummary() {
        val s0 = session ?: return
        val d = device ?: return
        if (mediaSaving) return
        if (mediaFor == d.id && mediaSummaryReady && System.currentTimeMillis() - mediaSummaryAt < 60_000L) return
        if (!summaryBusy.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val settings = repo.fetchMediaSettings(s, d.id)
                val status = try { repo.fetchMediaStatus(s, d.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                withContext(Dispatchers.Main) {
                    if (device?.id != d.id) return@withContext
                    mediaFor = d.id
                    mediaSettings = settings; mediaStatus = status
                    mediaSummaryReady = true; mediaSummaryAt = System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Home just keeps showing a neutral line; the Shared media screen reports real errors.
            } finally {
                summaryBusy.set(false)
            }
        }
    }

    /** Loads settings, child-reported status and the newest page of previews for the selected phone. */
    fun loadMedia() {
        val s0 = session ?: return
        val d = device ?: return
        if (!mediaBusy.compareAndSet(false, true)) return
        if (mediaFor != d.id) resetMedia()
        mediaFor = d.id
        mediaLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val settings = repo.fetchMediaSettings(s, d.id)
                val status = try { repo.fetchMediaStatus(s, d.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                val page = repo.fetchMediaPage(s, d.id, null)
                withContext(Dispatchers.Main) {
                    if (device?.id != d.id) return@withContext
                    mediaSettings = settings; mediaStatus = status
                    mediaItems = page.items; mediaHasMore = page.hasMore; mediaCursor = page.oldestKey
                    mediaError = null; mediaLoaded = true; mediaLoading = false
                    mediaSummaryReady = true; mediaSummaryAt = System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { mediaError = "Couldn't load photos: " + friendly(e); mediaLoaded = true; mediaLoading = false }
            } finally {
                mediaBusy.set(false)
            }
        }
    }

    fun loadMoreMedia() {
        val s0 = session ?: return
        val d = device ?: return
        val cursor = mediaCursor ?: return
        if (!mediaHasMore || !mediaBusy.compareAndSet(false, true)) return
        mediaLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val page = repo.fetchMediaPage(s, d.id, cursor)
                withContext(Dispatchers.Main) {
                    if (device?.id != d.id) return@withContext
                    val known = mediaItems.mapTo(HashSet()) { it.key }
                    mediaItems = mediaItems + page.items.filter { it.key !in known }
                    mediaHasMore = page.hasMore; mediaCursor = page.oldestKey ?: cursor
                    mediaError = null; mediaLoading = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { mediaError = "Couldn't load more: " + friendly(e); mediaLoading = false }
            } finally {
                mediaBusy.set(false)
            }
        }
    }

    /**
     * Applies a settings change at once and saves it; if saving fails the screen goes back to what is really stored.
     * Switching backup on stamps the moment, so only media added from then on is ever considered.
     */
    fun updateMediaSettings(change: (MediaSettings) -> MediaSettings) {
        val s0 = session ?: return
        val d = device ?: return
        val before = mediaSettings
        val now = System.currentTimeMillis()
        var next = change(before).copy(updatedAt = now)
        if (next.enabled && !before.enabled) next = next.copy(enabledAt = now)
        mediaSettings = next
        mediaSaving = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                repo.saveMediaSettings(s, d.id, next)
                withContext(Dispatchers.Main) { mediaSaving = false; mediaError = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (mediaSettings == next) mediaSettings = before
                    mediaSaving = false
                    mediaError = "Couldn't save that setting: " + friendly(e)
                }
            }
        }
    }

    /** The stored preview copy for the full-screen viewer (cached for a few items), or null if it can't be loaded. */
    suspend fun loadMediaPreview(item: MediaItem): ByteArray? {
        synchronized(previewCache) { previewCache[item.key] }?.let { return it }
        val s0 = session ?: return null
        val id = device?.id ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                repo.fetchMediaPreview(s, id, item)?.also { synchronized(previewCache) { previewCache[item.key] = it } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    fun deleteMedia(item: MediaItem, onDone: (Boolean) -> Unit = {}) {
        val s0 = session ?: return onDone(false)
        val d = device ?: return onDone(false)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val s = repo.fresh(s0); session = s
                repo.deleteMedia(s, d.id, item.key)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { mediaError = "Couldn't delete: " + friendly(e) }
                false
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    mediaItems = mediaItems.filter { it.key != item.key }
                    synchronized(previewCache) { previewCache.remove(item.key) }
                }
                onDone(ok)
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun showMediaForTest(items: List<MediaItem>, settings: MediaSettings = MediaSettings(), status: MediaStatusSnapshot? = null, hasMore: Boolean = false, error: String? = null) {
        mediaFor = device?.id
        mediaItems = items; mediaSettings = settings; mediaStatus = status; mediaHasMore = hasMore
        mediaError = error; mediaLoaded = true; mediaLoading = false; mediaSummaryReady = true
    }

    private fun resetComms() {
        comms = emptyList(); commsLoaded = false; commsError = null; commsLoading = false
        commsFor = null; commsMarker = -1L; commsDays = COMMS_PAGE_DAYS
    }

    /** Loads (or reloads) message previews for the selected phone; [silent] keeps the current list on screen meanwhile. */
    fun loadComms(silent: Boolean = false) {
        val s0 = session ?: return
        val d = device ?: return
        if (!commsBusy.compareAndSet(false, true)) return
        if (commsFor != d.id) { comms = emptyList(); commsLoaded = false; commsFor = d.id }
        if (!silent || !commsLoaded) commsLoading = true
        val marker = d.commsAt
        val days = Formats.lastDays(commsDays).reversed()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val result = repo.fetchComms(s, d.id, days)
                withContext(Dispatchers.Main) {
                    if (device?.id != d.id) return@withContext
                    val allFailed = days.isNotEmpty() && result.failedDays == days.size
                    if (!allFailed) { comms = result.records; commsMarker = marker }
                    commsError = when {
                        allFailed -> "Couldn't load messages: " + friendly(result.lastError ?: IllegalStateException("network error"))
                        result.failedDays > 0 -> "Some days could not be loaded. Pull to retry."
                        else -> null
                    }
                    commsLoaded = true
                    commsLoading = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    commsError = "Couldn't load messages: " + friendly(e)
                    commsLoaded = true
                    commsLoading = false
                }
            } finally {
                commsBusy.set(false)
            }
        }
    }

    /** Called by the Messages screen: reloads only when the child has uploaded something newer than what is shown. */
    fun refreshCommsIfStale() {
        val d = device ?: return
        if (!commsLoaded || commsFor != d.id || d.commsAt != commsMarker) loadComms(silent = true)
    }

    fun loadEarlierComms() {
        if (commsDays >= COMMS_MAX_DAYS) return
        commsDays = (commsDays + COMMS_PAGE_DAYS).coerceAtMost(COMMS_MAX_DAYS)
        commsMarker = -1L
        loadComms(silent = false)
    }

    fun selectDay(dayKey: String) {
        selectedDay = dayKey
        val d = device ?: return
        if (d.sessions.containsKey(dayKey) && d.screenEvents.containsKey(dayKey) && dayKey != DayClock.of(System.currentTimeMillis()).dayKey) return
        val s0 = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val rows = repo.fetchSessionsFor(s, d.id, dayKey)
                withContext(Dispatchers.Main) {
                    devices = devices.map { if (it.id == d.id) it.copy(sessions = it.sessions + (dayKey to rows)) else it }
                }
                val screen = try { repo.fetchScreenFor(s, d.id, dayKey) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (screen != null) withContext(Dispatchers.Main) {
                    devices = devices.map { if (it.id == d.id) it.copy(screenEvents = it.screenEvents + (dayKey to screen)) else it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    fun refreshAll(silent: Boolean = true) {
        val s0 = session ?: return
        if (!refreshing.compareAndSet(false, true)) {
            refreshQueued = true
            return
        }
        if (!silent) connection = Connection.Syncing
        val epochAtStart = policyEpoch
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val raw = repo.fetchPolicyRaw(s)
                val parsedPolicy = PolicyCodec.parse(raw)
                val metas = repo.fetchDeviceList(s)
                val previous = devices
                val day = selectedDay
                var lastError: Exception? = null
                val loaded = metas.mapNotNull { meta ->
                    val before = previous.firstOrNull { it.id == meta.id }
                    try {
                        repo.fetchDevice(s, meta, day, before)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One child failing to load must not wipe the others: keep what we showed last time.
                        lastError = e
                        before
                    }
                }
                if (metas.isNotEmpty() && loaded.isEmpty()) throw lastError ?: IllegalStateException("No data")
                val target = loaded.firstOrNull { it.id == selectedDeviceId } ?: loaded.firstOrNull()
                var newIcons: Map<String, ImageBitmap> = emptyMap()
                if (target != null && System.currentTimeMillis() - lastIconFetch > 90_000L &&
                    target.apps.any { !iconCache.containsKey(it.pkg) }
                ) {
                    lastIconFetch = System.currentTimeMillis()
                    val fetched = try { repo.fetchIcons(s, target.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
                    if (fetched.isNotEmpty()) {
                        iconCache.putAll(fetched)
                        repo.saveIcons(iconCache)
                        newIcons = fetched.mapNotNull { (pkg, b64) -> repo.decodeIcon(b64)?.let { pkg to it } }.toMap()
                    }
                }
                var policyApplied = false
                withContext(Dispatchers.Main) {
                    devices = loaded
                    // If the parent saved something while this refresh was running, what it fetched is older than
                    // what is on screen. Keep the screen's version; the follow-up refresh below confirms it.
                    if (pendingWrites == 0 && policyEpoch == epochAtStart) {
                        policy = parsedPolicy
                        policyApplied = true
                    } else {
                        refreshQueued = true
                    }
                    if (selectedDeviceId == null || loaded.none { it.id == selectedDeviceId }) selectedDeviceId = loaded.firstOrNull()?.id
                    newIcons.forEach { (pkg, bmp) -> icons[pkg] = bmp }
                    lastRefreshAt = System.currentTimeMillis()
                    loadedOnce = true
                    connection = if (loaded.isEmpty()) Connection.Offline else Connection.Live
                }
                if (policyApplied) repo.saveCache(loaded, raw)
                if (loaded.isNotEmpty() && rtActive && rtThread == null) startRealtime()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connection = Connection.Offline
                    if (!silent) message = "Couldn't refresh: " + friendly(e)
                }
            } finally {
                refreshing.set(false)
                if (refreshQueued) {
                    refreshQueued = false
                    refreshAll(silent = true)
                }
            }
        }
    }

    // ------------------------------------------------------------ real time

    /** Call when the app comes to the front: opens the live connection and keeps the heavy data fresh. */
    fun onForeground() {
        preview.foreground = true
        controls.foreground = true
        recovery.foreground = true
        appearance.foreground = true
        safety.foreground = true
        if (session == null) return
        startRealtime()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                refreshAll(silent = true)
                if (session != null && device != null) safety.checkIfDue()
                if (session != null) telegram.deliverDue()
            }
        }
        refreshAll(silent = true)
    }

    fun onBackground() {
        preview.foreground = false
        controls.foreground = false
        recovery.foreground = false
        appearance.foreground = false
        safety.foreground = false
        pollJob?.cancel()
        stopRealtime()
    }

    private fun startRealtime() {
        if (rtActive) return
        rtActive = true
        rtThread = Thread({
            var backoff = 2_000L
            while (rtActive) {
                try {
                    val s0 = session
                    val id = device?.id
                    if (s0 == null || id == null) {
                        Thread.sleep(2_000L)
                        continue
                    }
                    val s = repo.fresh(s0); session = s
                    val handle = repo.api.openStream(GuardianPaths.realtime(s.uid, id), s)
                    rtHandle = handle
                    repo.api.consumeStream(handle) { event -> if (event == "put" || event == "patch") onRealtimeSignal(id) }
                    backoff = 2_000L
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    if (!rtActive) break
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }, "guardian-parent-realtime").apply { isDaemon = true; start() }
    }

    private fun stopRealtime() {
        rtActive = false
        try { rtHandle?.cancel() } catch (_: Exception) { }
        rtThread?.interrupt()
        rtThread = null
    }

    private fun onRealtimeSignal(deviceId: String) {
        val s0 = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val snapshot = repo.fetchRealtime(s, deviceId)
                val live = snapshot?.live
                val ack = snapshot?.ack
                var packageChanged = false
                withContext(Dispatchers.Main) {
                    val old = devices.firstOrNull { it.id == deviceId }?.live
                    packageChanged = old?.pkg != live?.pkg
                    devices = devices.map {
                        if (it.id == deviceId) it.copy(live = live, ack = ack, commsAt = snapshot?.commsAt ?: it.commsAt) else it
                    }
                    connection = Connection.Live
                }
                if (packageChanged) scheduleTodayRefresh()
            } catch (_: Exception) { }
        }
    }

    /** After the child switches apps, refresh the timeline and totals once things settle. */
    private fun scheduleTodayRefresh() {
        todayJob?.cancel()
        todayJob = viewModelScope.launch {
            delay(4_000L)
            refreshAll(silent = true)
        }
    }

    // ------------------------------------------------------------ rules

    fun ruleFor(pkg: String): AppRule = policy.appRules[pkg] ?: AppRule()

    /**
     * Shows the new rule straight away, then sends it. Several quick saves keep their order and none is lost,
     * and a refresh that was already running can no longer put the old rule back on screen.
     */
    fun saveRule(pkg: String, rule: AppRule, doneMessage: String = "Saved. Waiting for the child's phone to confirm.") {
        val now = System.currentTimeMillis()
        val update = PolicyCodec.ruleUpdate(pkg, rule, policy, now)
        policy = policy.withRule(pkg, rule).copy(updatedAt = now)
        enqueueWrite(update, doneMessage)
    }

    fun setBlocked(pkg: String, blocked: Boolean) =
        saveRule(pkg, ruleFor(pkg).copy(blocked = blocked), if (blocked) "Blocked. Waiting for the child's phone to confirm." else "Unblocked. Waiting for the child's phone to confirm.")

    /** Adds ([deltaSec] > 0) or takes away (< 0) time for today only. Returns the rule that is now in force. */
    fun adjustToday(pkg: String, deltaSec: Int, doneMessage: String? = null): AppRule {
        val clock = DayClock.of(System.currentTimeMillis())
        val before = ruleFor(pkg)
        val after = before.adjustedBy(clock.dayOfWeek, clock.dayKey, deltaSec)
        if (after == before) return before
        val total = after.limitFor(clock.dayOfWeek, clock.dayKey)
        saveRule(pkg, after, doneMessage ?: ((if (deltaSec > 0) "+" else "\u2212") + (kotlin.math.abs(deltaSec) / 60) +
            " min for today." + (if (total != null) " Limit is now " + com.sirrlevi.guardian.core.Durations.compact(total) + "." else "")))
        return after
    }

    /** Removes today's extra time / reduction so the normal limit applies again. */
    fun resetToday(pkg: String) {
        val before = ruleFor(pkg)
        if (before.bonusSec == 0 && before.bonusDay.isEmpty()) return
        saveRule(pkg, before.withExtra("", 0), "Back to the normal limit.")
    }

    /** Kept for "give 15 minutes" on a request card. */
    fun grantBonus(pkg: String, seconds: Int) {
        adjustToday(pkg, seconds, "Added " + (seconds / 60) + " extra minutes for today.")
    }

    fun pauseApps(minutes: Int) = patchPolicy(JsonObject().apply {
        addProperty("pauseUntil", if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L)
    }, if (minutes > 0) "Apps paused for $minutes minutes." else "Apps resumed.") { it.copy(pauseUntil = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L) }

    fun setWebRules(domains: List<String>, urls: List<String>) = patchPolicy(JsonObject().apply {
        add("blockedDomains", JsonArray().also { a -> domains.distinct().forEach { a.add(it) } })
        add("blockedUrls", JsonArray().also { a -> urls.distinct().forEach { a.add(it) } })
    }, "Website rules saved. Waiting for the child's phone to confirm.") { it.copy(blockedDomains = domains.distinct(), blockedUrls = urls.distinct()) }

    fun saveSchedules(schedules: List<Schedule>) = patchPolicy(JsonObject().apply {
        add("schedules", JsonArray().also { a -> schedules.forEach { a.add(PolicyCodec.scheduleToJson(it)) } })
    }, "Schedules saved. Waiting for the child's phone to confirm.") { it.copy(schedules = schedules) }

    private fun patchPolicy(update: JsonObject, doneMessage: String, local: (FamilyPolicy) -> FamilyPolicy) {
        val now = System.currentTimeMillis()
        update.addProperty("updatedAt", now)
        policy = local(policy).copy(updatedAt = now)
        enqueueWrite(update, doneMessage)
    }

    private fun enqueueWrite(update: JsonObject, doneMessage: String) {
        policyEpoch++
        pendingWrites++
        writes.trySend(PolicyWrite(update, doneMessage))
        if (writer?.isActive != true) {
            writer = viewModelScope.launch(Dispatchers.IO) {
                for (w in writes) {
                    var failed = false
                    try {
                        val s0 = session
                        if (s0 != null) {
                            val s = repo.fresh(s0); session = s
                            repo.patchPolicy(s, w.update)
                        }
                        withContext(Dispatchers.Main) { message = w.doneMessage }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed = true
                        withContext(Dispatchers.Main) { message = "Couldn't save: " + friendly(e) }
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                            pendingWrites = (pendingWrites - 1).coerceAtLeast(0)
                            policyEpoch++
                        }
                    }
                    // A failed save means the screen is ahead of the server: pull the real state back.
                    if (failed || pendingWrites == 0) refreshAll(silent = true)
                }
            }
        }
    }

    fun respond(request: MoreTimeRequest, approve: Boolean) {
        val s0 = session ?: return
        val d = device ?: return
        if (approve) {
            when (request.reason) {
                "DAILY_LIMIT" -> grantBonus(request.pkg, 15 * 60)
                "PARENT_BLOCK" -> setBlocked(request.pkg, false)
                else -> message = "Edit the schedule to allow this app."
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                repo.setRequestStatus(s, d.id, request.id, if (approve) "approved" else "denied")
                withContext(Dispatchers.Main) {
                    devices = devices.map { dev ->
                        if (dev.id == d.id) dev.copy(requests = dev.requests.map { r -> if (r.id == request.id) r.copy(status = if (approve) "approved" else "denied") else r }) else dev
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------ pairing & theme

    fun generatePairingCode() {
        val s0 = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val (code, expires) = repo.createPairingCode(s)
                withContext(Dispatchers.Main) { pairingCode = code; pairingExpiresAt = expires }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "Couldn't create a code: " + friendly(e) }
            }
        }
    }

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    // ------------------------------------------------------------ updates

    fun saveToken(raw: String) {
        try {
            updater.saveToken(raw)
            hasToken = true
            updateStatus = "Token encrypted and saved on this phone."
            checkUpdates(true)
        } catch (e: Exception) {
            updateStatus = "Token not saved: " + (e.message ?: "invalid token")
        }
    }

    fun checkUpdates(showErrors: Boolean) {
        if (updateBusy) return
        updateBusy = true
        updateStatus = "Checking the latest release…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = updater.checkForUpdate()
                updater.notifyIfAvailable(result)
                withContext(Dispatchers.Main) {
                    updateResult = result
                    updateStatus = result.message
                    reinstallUrl = result.reinstallUrl.ifBlank { null }
                    lastUpdateCheck = updater.lastCheckedAt()
                    updateBusy = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateResult = null
                    updateBusy = false
                    if (showErrors || updateStatus.startsWith("Checking")) updateStatus = "Update check failed: " + (e.message ?: "network or token error")
                }
            }
        }
    }

    fun downloadUpdate() {
        val release = updateResult?.release
        if (release == null || updateResult?.updateAvailable != true || updateBusy) {
            updateStatus = "Check for updates first."
            return
        }
        updateBusy = true
        updateProgress = 0
        updateStatus = "Downloading " + release.title + "…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = updater.download(release) { done, total ->
                    val percent = UpdateRules.percent(done, total)
                    viewModelScope.launch(Dispatchers.Main) {
                        updateProgress = percent
                        updateStatus = "Downloading… " + UpdateRules.formatSize(done) + " of " + UpdateRules.formatSize(total) + " (" + percent + "%)"
                    }
                }
                withContext(Dispatchers.Main) {
                    updateBusy = false
                    updateProgress = -1
                    updateStatus = "Download complete. Opening the Android installer…"
                    installRequest = file
                }
            } catch (e: UpdateBlockedException) {
                withContext(Dispatchers.Main) {
                    updateBusy = false
                    updateProgress = -1
                    reinstallUrl = e.downloadUrl
                    updateStatus = e.message ?: UpdateRules.signatureMismatchMessage()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateBusy = false
                    updateProgress = -1
                    updateStatus = "Download failed: " + (e.message ?: "unknown error")
                }
            }
        }
    }

    private fun friendly(e: Exception): String {
        val text = e.message ?: "network error"
        return when {
            text.contains("EMAIL_EXISTS") -> "That email already has an account. Use Sign in."
            text.contains("INVALID_LOGIN_CREDENTIALS") || text.contains("INVALID_PASSWORD") || text.contains("EMAIL_NOT_FOUND") -> "Wrong email or password."
            text.contains("WEAK_PASSWORD") -> "Choose a password with at least 6 characters."
            text.contains("Unable to resolve host") || text.contains("timeout", ignoreCase = true) -> "No internet connection."
            else -> text.take(160)
        }
    }

    override fun onCleared() {
        stopRealtime()
        super.onCleared()
    }

    private companion object {
        const val COMMS_PAGE_DAYS = 7
        const val COMMS_MAX_DAYS = 28
    }
}
