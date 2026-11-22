package com.sirrlevi.guardian.parent

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
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
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.GuardianApi
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.PolicyCodec
import com.sirrlevi.guardian.core.Schedule
import com.sirrlevi.guardian.core.UpdateRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    val device: ChildDevice? get() = devices.firstOrNull { it.id == selectedDeviceId } ?: devices.firstOrNull()

    private val iconCache = HashMap<String, String>()
    private var lastIconFetch = 0L
    private var refreshing = false
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
                if (hasToken) checkUpdates(false)
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
        stopRealtime()
        startRealtime()
        refreshAll(silent = false)
    }

    fun selectDay(dayKey: String) {
        selectedDay = dayKey
        val d = device ?: return
        if (d.sessions.containsKey(dayKey) && dayKey != DayClock.of(System.currentTimeMillis()).dayKey) return
        val s0 = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val rows = repo.fetchSessionsFor(s, d.id, dayKey)
                withContext(Dispatchers.Main) {
                    devices = devices.map { if (it.id == d.id) it.copy(sessions = it.sessions + (dayKey to rows)) else it }
                }
            } catch (_: Exception) { }
        }
    }

    fun refreshAll(silent: Boolean = true) {
        val s0 = session ?: return
        if (refreshing) return
        refreshing = true
        if (!silent) connection = Connection.Syncing
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val raw = repo.fetchPolicyRaw(s)
                val parsedPolicy = PolicyCodec.parse(raw)
                val metas = repo.fetchDeviceList(s)
                val previous = devices
                val day = selectedDay
                val loaded = metas.map { meta -> repo.fetchDevice(s, meta, day, previous.firstOrNull { it.id == meta.id }) }
                val target = loaded.firstOrNull { it.id == selectedDeviceId } ?: loaded.firstOrNull()
                var newIcons: Map<String, ImageBitmap> = emptyMap()
                if (target != null && System.currentTimeMillis() - lastIconFetch > 90_000L &&
                    target.apps.any { !iconCache.containsKey(it.pkg) }
                ) {
                    lastIconFetch = System.currentTimeMillis()
                    val fetched = repo.fetchIcons(s, target.id)
                    if (fetched.isNotEmpty()) {
                        iconCache.putAll(fetched)
                        repo.saveIcons(iconCache)
                        newIcons = fetched.mapNotNull { (pkg, b64) -> repo.decodeIcon(b64)?.let { pkg to it } }.toMap()
                    }
                }
                withContext(Dispatchers.Main) {
                    devices = loaded
                    policy = parsedPolicy
                    if (selectedDeviceId == null || loaded.none { it.id == selectedDeviceId }) selectedDeviceId = loaded.firstOrNull()?.id
                    newIcons.forEach { (pkg, bmp) -> icons[pkg] = bmp }
                    lastRefreshAt = System.currentTimeMillis()
                    loadedOnce = true
                    connection = if (loaded.isEmpty()) Connection.Offline else Connection.Live
                }
                repo.saveCache(loaded, raw)
                if (loaded.isNotEmpty() && rtActive && rtThread == null) startRealtime()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connection = Connection.Offline
                    if (!silent) message = "Couldn't refresh: " + friendly(e)
                }
            } finally {
                refreshing = false
            }
        }
    }

    // ------------------------------------------------------------ real time

    /** Call when the app comes to the front: opens the live connection and keeps the heavy data fresh. */
    fun onForeground() {
        if (session == null) return
        startRealtime()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                refreshAll(silent = true)
            }
        }
        refreshAll(silent = true)
    }

    fun onBackground() {
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
                val (live, ack) = repo.fetchRealtime(s, deviceId)
                var packageChanged = false
                withContext(Dispatchers.Main) {
                    val old = devices.firstOrNull { it.id == deviceId }?.live
                    packageChanged = old?.pkg != live?.pkg
                    devices = devices.map { if (it.id == deviceId) it.copy(live = live, ack = ack) else it }
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

    fun saveRule(pkg: String, rule: AppRule, doneMessage: String = "Saved. Waiting for the child's phone to confirm.") {
        val s0 = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val now = System.currentTimeMillis()
                repo.patchPolicy(s, PolicyCodec.ruleUpdate(pkg, rule, policy, now))
                withContext(Dispatchers.Main) {
                    policy = policy.withRule(pkg, rule).copy(updatedAt = now)
                    message = doneMessage
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "Couldn't save: " + friendly(e) }
            }
        }
    }

    fun setBlocked(pkg: String, blocked: Boolean) =
        saveRule(pkg, ruleFor(pkg).copy(blocked = blocked), if (blocked) "Blocked. Waiting for the child's phone to confirm." else "Unblocked. Waiting for the child's phone to confirm.")

    fun grantBonus(pkg: String, seconds: Int) {
        val today = DayClock.of(System.currentTimeMillis()).dayKey
        val rule = ruleFor(pkg)
        val existing = if (rule.bonusDay == today) rule.bonusSec else 0
        saveRule(pkg, rule.copy(bonusDay = today, bonusSec = existing + seconds), "Added " + (seconds / 60) + " extra minutes for today.")
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
        val s0 = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = repo.fresh(s0); session = s
                val now = System.currentTimeMillis()
                update.addProperty("updatedAt", now)
                repo.patchPolicy(s, update)
                withContext(Dispatchers.Main) {
                    policy = local(policy).copy(updatedAt = now)
                    message = doneMessage
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "Couldn't save: " + friendly(e) }
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
}
