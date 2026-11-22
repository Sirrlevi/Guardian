package com.sirrlevi.guardian.child

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.PkgKey
import com.sirrlevi.guardian.core.PolicyCodec
import com.sirrlevi.guardian.core.UpdateBlockedException
import com.sirrlevi.guardian.core.UpdateRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** All state of the Child app. The UI only reads these values and calls the functions below. */
class ChildViewModel(application: Application) : AndroidViewModel(application) {
    enum class Phase { Loading, Unpaired, Paired }

    private val app = application
    private var cloud = ChildCloud(app)
    private val vault = SessionVault(app)
    private val prefs = app.getSharedPreferences("guardian_child", Context.MODE_PRIVATE)
    private val updater = GuardianUpdater(app)

    var phase by mutableStateOf(Phase.Loading); private set
    var busy by mutableStateOf(false); private set
    var authError by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var familyId by mutableStateOf(prefs.getString("family_id", "").orEmpty()); private set

    // ---- protection (all read locally)
    var permissions by mutableStateOf<List<ChildPermissions.Item>>(emptyList()); private set
    var monitorRunning by mutableStateOf(false); private set
    var canEnforce by mutableStateOf(false); private set
    var isDeviceOwner by mutableStateOf(false); private set
    var lastSync by mutableLongStateOf(0L); private set
    var blockedDomainCount by mutableIntStateOf(0); private set
    var blockedUrlCount by mutableIntStateOf(0); private set
    var policy by mutableStateOf(FamilyPolicy()); private set

    // ---- today
    var todayTotalSec by mutableIntStateOf(0); private set
    var todayApps by mutableStateOf<List<Pair<String, Int>>>(emptyList()); private set
    var todayLoaded by mutableStateOf(false); private set
    var todayFromPhone by mutableStateOf(false); private set
    var todayUpdatedAt by mutableLongStateOf(0L); private set

    // ---- updates
    var hasToken by mutableStateOf(updater.hasToken()); private set
    var updateStatus by mutableStateOf("Not checked yet."); private set
    var updateResult by mutableStateOf<UpdateCheckResult?>(null); private set
    var updateBusy by mutableStateOf(false); private set
    var updateProgress by mutableIntStateOf(-1); private set
    var lastUpdateCheck by mutableLongStateOf(updater.lastCheckedAt()); private set
    var installRequest by mutableStateOf<File?>(null)
    /** Set when this phone cannot be updated in place (debug build / different signing key): browser link to the APK. */
    var reinstallUrl by mutableStateOf<String?>(null); private set

    init {
        phase = if (cloud.isPaired()) Phase.Paired else Phase.Unpaired
        if (phase == Phase.Paired) {
            refreshLocal()
            loadToday()
            checkUpdates(false)
        }
    }

    /** Lets UI smoke tests render the paired screens with fixed sample data. Never called by the app itself. */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(items: List<ChildPermissions.Item>, rules: FamilyPolicy, apps: List<Pair<String, Int>>) {
        permissions = items
        monitorRunning = true
        policy = rules
        todayApps = apps
        todayTotalSec = apps.sumOf { it.second }
        todayLoaded = true
        todayUpdatedAt = System.currentTimeMillis()
        lastSync = System.currentTimeMillis()
        blockedDomainCount = 2
        blockedUrlCount = 1
        familyId = "abcdef123456"
        phase = Phase.Paired
    }

    // ------------------------------------------------------------ pairing

    fun pair(create: Boolean, email: String, password: String, code: String) {
        val mail = email.trim()
        val digits = code.filter { it.isDigit() }
        if (!mail.contains("@") || password.length < 6 || digits.length != 8) {
            authError = "Enter a valid email, a password with at least 6 characters and the full 8-digit code."
            return
        }
        if (!cloud.api.isConfigured()) {
            authError = "This build has no Firebase connection configured."
            return
        }
        busy = true
        authError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val api = cloud.api
                val auth = if (create) api.signUp(mail, password) else api.signIn(mail, password)
                val invite = api.get("pairingCodes/$digits", auth)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalStateException("That pairing code wasn't found. Check the digits or ask for a new one.")
                val expiry = invite.get("expiresAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val family = invite.get("familyId")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (family.isBlank() || expiry < System.currentTimeMillis()) {
                    throw IllegalStateException("That pairing code has expired. Ask your parent for a new one.")
                }
                val now = System.currentTimeMillis()
                api.put("families/$family/members/${auth.uid}", JsonObject().apply {
                    addProperty("joinedAt", now)
                    addProperty("role", "child")
                    addProperty("deviceName", Build.MODEL ?: "Android device")
                }, auth)
                api.put("families/$family/devices/${auth.uid}", JsonObject().apply {
                    addProperty("deviceName", Build.MODEL ?: "Android device")
                    addProperty("manufacturer", Build.MANUFACTURER ?: "Android")
                    addProperty("androidVersion", Build.VERSION.RELEASE ?: "unknown")
                    addProperty("joinedAt", now)
                    addProperty("lastSeen", now)
                    addProperty("monitoringDisclosure", "visible")
                }, auth)
                vault.save(auth)
                prefs.edit().putString("family_id", family).apply()
                withContext(Dispatchers.Main) {
                    cloud = ChildCloud(app)
                    familyId = family
                    busy = false
                    phase = Phase.Paired
                    message = "Paired. Turn on the steps below so Guardian can work."
                    scheduleSync()
                    GuardianMonitorService.startIfReady(app)
                    refreshLocal()
                    loadToday()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    busy = false
                    authError = friendly(e)
                }
            }
        }
    }

    /** Unpairing needs the PARENT's account, so a child can't simply switch the protection off. */
    fun unpair(parentEmail: String, parentPassword: String, done: (String?) -> Unit) {
        val currentFamily = cloud.familyId
        viewModelScope.launch(Dispatchers.IO) {
            val error: String? = try {
                val parent = cloud.api.signIn(parentEmail.trim(), parentPassword)
                if (parent.uid != currentFamily) "That isn't the parent account of this family." else null
            } catch (e: Exception) {
                friendly(e)
            }
            if (error == null) {
                try { app.stopService(Intent(app, GuardianMonitorService::class.java)) } catch (_: Exception) { }
                try { app.stopService(Intent(app, DomainFilterVpnService::class.java)) } catch (_: Exception) { }
                try { WorkManager.getInstance(app).cancelUniqueWork("guardian-child-sync") } catch (_: Exception) { }
                vault.clear()
                prefs.edit().remove("family_id").remove("policy_json").remove("blocked_domains").remove("blocked_urls").remove("last_sync").apply()
            }
            withContext(Dispatchers.Main) {
                if (error == null) {
                    cloud = ChildCloud(app)
                    familyId = ""
                    policy = FamilyPolicy()
                    todayApps = emptyList(); todayTotalSec = 0; todayLoaded = false
                    phase = Phase.Unpaired
                    authError = null
                    message = "This phone is no longer paired."
                }
                done(error)
            }
        }
    }

    fun scheduleSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<ChildSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        val work = WorkManager.getInstance(app)
        work.enqueueUniquePeriodicWork("guardian-child-sync", ExistingPeriodicWorkPolicy.UPDATE, periodic)
        work.enqueue(OneTimeWorkRequestBuilder<ChildSyncWorker>().setConstraints(constraints).build())
    }

    fun syncNow() {
        WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<ChildSyncWorker>().build())
        message = "Sync requested. It runs in a moment."
    }

    // ------------------------------------------------------------ local state

    fun refreshLocal() {
        permissions = ChildPermissions.items(app)
        monitorRunning = GuardianMonitorService.running
        canEnforce = ChildPermissions.canEnforce(app)
        isDeviceOwner = ChildPermissions.isDeviceOwner(app)
        lastSync = prefs.getLong("last_sync", 0L)
        blockedDomainCount = countArray(prefs.getString("blocked_domains", "[]"))
        blockedUrlCount = countArray(prefs.getString("blocked_urls", "[]"))
        val cached = parseObject(prefs.getString("policy_json", null))
        if (cached != null) policy = PolicyCodec.parse(cached)
    }

    /** Today's screen time: the same numbers your parent sees. Falls back to the phone's own counters when offline. */
    fun loadToday() {
        if (phase != Phase.Paired) return
        viewModelScope.launch(Dispatchers.IO) {
            var total = 0
            var apps: List<Pair<String, Int>> = emptyList()
            var fromPhone = false
            var freshPolicy: FamilyPolicy? = null
            try {
                val session = cloud.session() ?: return@launch
                val fid = cloud.familyId
                val day = DayClock.of(System.currentTimeMillis()).dayKey
                val daily = cloud.api.get(GuardianPaths.daily(fid, session.uid) + "/" + day, session)?.takeIf { it.isJsonObject }?.asJsonObject
                if (daily != null) {
                    total = daily.get("total")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    val map = daily.get("apps")?.takeIf { it.isJsonObject }?.asJsonObject
                    apps = map?.entrySet()?.mapNotNull { (key, value) ->
                        val sec = value.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
                        PkgKey.decode(key) to sec
                    }?.sortedByDescending { it.second } ?: emptyList()
                }
                cloud.api.get(GuardianPaths.policy(fid), session)?.takeIf { it.isJsonObject }?.asJsonObject?.let { freshPolicy = PolicyCodec.parse(it) }
            } catch (_: Exception) {
                // offline or token problem – fall through to the local numbers
            }
            if (apps.isEmpty() && ChildPermissions.hasUsageAccess(app)) {
                try {
                    val local = UsageMonitor.collect(app, UsageMonitor.dayStartMillis())
                    apps = local.mapNotNull { element ->
                        val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        val pkg = o.get("packageName")?.asString ?: return@mapNotNull null
                        pkg to ((o.get("foregroundMs")?.asLong ?: 0L) / 1000L).toInt()
                    }.filter { it.first != app.packageName }.sortedByDescending { it.second }
                    total = apps.sumOf { it.second }
                    fromPhone = true
                } catch (_: Exception) { }
            }
            withContext(Dispatchers.Main) {
                todayTotalSec = maxOf(total, apps.sumOf { it.second })
                todayApps = apps
                todayFromPhone = fromPhone
                todayLoaded = true
                todayUpdatedAt = System.currentTimeMillis()
                freshPolicy?.let { policy = it }
            }
        }
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

    // ------------------------------------------------------------ helpers

    private fun parseObject(raw: String?): JsonObject? = try {
        if (raw.isNullOrBlank()) null else JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) { null }

    private fun countArray(raw: String?): Int = try {
        if (raw.isNullOrBlank()) 0 else JsonParser.parseString(raw).asJsonArray.size()
    } catch (_: Exception) { 0 }

    private fun friendly(e: Exception): String {
        val text = e.message ?: "network error"
        return when {
            text.contains("EMAIL_EXISTS") -> "That email already has an account. Choose Sign in."
            text.contains("INVALID_LOGIN_CREDENTIALS") || text.contains("INVALID_PASSWORD") || text.contains("EMAIL_NOT_FOUND") -> "Wrong email or password."
            text.contains("WEAK_PASSWORD") -> "Choose a password with at least 6 characters."
            text.contains("Unable to resolve host") || text.contains("timeout", ignoreCase = true) -> "No internet connection."
            else -> text.take(160)
        }
    }
}
