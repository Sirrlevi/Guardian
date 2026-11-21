package com.sirrlevi.guardian.parent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.GuardianApi
import com.sirrlevi.guardian.core.GuardianSession
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Random
import java.util.concurrent.Executors

class ParentActivity : AppCompatActivity() {
    private val api by lazy { GuardianApi(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_DATABASE_URL) }
    private val vault by lazy { SessionVault(this) }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var page: LinearLayout
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var pairingDisplay: TextView
    private lateinit var domains: EditText
    private lateinit var urls: EditText
    private lateinit var packages: EditText
    private lateinit var limits: EditText
    private var session: GuardianSession? = null
    private var pairingCode = ""
    private val updater by lazy { GuardianUpdater(this) }
    private lateinit var updateToken: EditText
    private lateinit var updateStatus: TextView
    private lateinit var installUpdateButton: MaterialButton
    private var latestUpdate: UpdateCheckResult? = null
    private var pendingInstallFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 24, 39)
        window.navigationBarColor = android.graphics.Color.rgb(16, 24, 39)
        val scroll = ScrollView(this)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(32))
            setBackgroundColor(android.graphics.Color.rgb(12, 18, 30))
        }
        scroll.addView(page)
        setContentView(scroll)
        GuardianUpdateScheduler.schedule(this)
        session = vault.load()
        showHome()
        if (session != null) showDashboard()
    }

    private fun showHome() {
        page.removeAllViews()
        heading("GUARDIAN PARENT", 28)
        text("Create a family account, pair a child's device, and manage screen-time and website policies.")
        email = field("Parent email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = field("Password (6+ characters)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        button("Create parent account") { authenticate(true) }
        button("Sign in") { authenticate(false) }
        text("Firebase setup is required. Add FIREBASE_API_KEY and FIREBASE_DATABASE_URL to GitHub Actions secrets, then rebuild.")
        status = text("Not signed in")
    }

    private fun authenticate(create: Boolean) {
        val e = email.text.toString().trim()
        val p = password.text.toString()
        if (e.isBlank() || p.length < 6) { status.text = "Enter a valid email and a password of at least 6 characters."; return }
        status.text = "Connecting to Firebase…"
        task({ if (create) api.signUp(e, p) else api.signIn(e, p) }) { result ->
            session = result
            vault.save(result)
            showDashboard()
        }
    }

    private fun showDashboard() {
        page.removeAllViews()
        heading("GUARDIAN", 30)
        text("Parent dashboard · Family " + (session?.uid?.take(8) ?: ""))
        button("Generate child pairing code (15 min)") { generatePairingCode() }
        pairingDisplay = text("Pairing code: " + if (pairingCode.isBlank()) "Not generated" else pairingCode)
        text("On Guardian Child, create a separate child account and enter this code.")
        divider()
        heading("Website and app controls", 20)
        domains = field("Blocked domains · one per line", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, true)
        urls = field("Blocked URL paths in Guardian Browser · one per line; * wildcard", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, true)
        packages = field("Blocked app package names · one per line", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, true)
        limits = field("Daily app limits · package.name=minutes per line", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, true)
        button("Publish family policy") { savePolicy() }
        divider()
        heading("Connected devices", 20)
        button("Refresh usage, installed apps and browser history") { refreshDevices() }
        status = text("Dashboard ready. Policies sync when the child device next checks in.")
        divider()
        heading("Settings", 20)
        heading("App Updates", 18)
        text("Connect this private GitHub repository once. Update checks run when the app opens and periodically in the background.")
        updateToken = field("GitHub fine-grained token · saved encrypted on this phone", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        button("Save GitHub token") { saveUpdateToken() }
        button("Check for updates") { checkForUpdates() }
        installUpdateButton = MaterialButton(this).apply {
            text = "Download and install update"
            visibility = View.GONE
            setOnClickListener { downloadAndInstallUpdate() }
        }
        page.addView(installUpdateButton, matchWrap().apply { bottomMargin = dp(8) })
        updateStatus = text(if (updater.hasToken()) "GitHub token saved. Checking release…" else "Add a fine-grained token with Contents: Read-only access.")
        button("Sign out") { vault.clear(); session = null; pairingCode = ""; showHome() }
        if (updater.hasToken()) checkForUpdates()
    }


    private fun saveUpdateToken() {
        try {
            updater.saveToken(updateToken.text.toString())
            updateToken.setText("")
            updateStatus.text = "Token saved encrypted with Android Keystore."
            requestUpdateNotificationPermission()
            checkForUpdates()
        } catch (e: Exception) {
            updateStatus.text = "Token not saved: " + (e.message ?: "invalid token")
        }
    }

    private fun requestUpdateNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7310)
        }
    }

    private fun checkForUpdates() {
        updateStatus.text = "Checking the latest signed GitHub release…"
        io.execute {
            try {
                val result = updater.checkForUpdate()
                updater.notifyIfAvailable(result)
                runOnUiThread {
                    latestUpdate = result
                    updateStatus.text = result.message
                    installUpdateButton.visibility = if (result.updateAvailable) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    latestUpdate = null
                    installUpdateButton.visibility = View.GONE
                    updateStatus.text = "Update check failed: " + (e.message ?: "network or token error")
                }
            }
        }
    }

    private fun downloadAndInstallUpdate() {
        val release = latestUpdate?.release
        if (release == null || latestUpdate?.updateAvailable != true) {
            updateStatus.text = "Check for updates first."
            return
        }
        installUpdateButton.isEnabled = false
        updateStatus.text = "Downloading " + release.assetName + "…"
        io.execute {
            try {
                val file = updater.download(release)
                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    updateStatus.text = "Download complete. Opening Android installer…"
                    installDownloadedApk(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    updateStatus.text = "Download failed: " + (e.message ?: "unknown error")
                }
            }
        }
    }

    private fun installDownloadedApk(file: File) {
        if (!file.isFile) {
            updateStatus.text = "Downloaded APK is no longer available. Download it again."
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = file
            updateStatus.text = "Allow Guardian Parent to install updates, then return here."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
            return
        }
        launchAndroidInstaller(file)
    }

    private fun launchAndroidInstaller(file: File) {
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(installIntent)
    }

    override fun onResume() {
        super.onResume()
        val file = pendingInstallFile
        if (file != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
            pendingInstallFile = null
            launchAndroidInstaller(file)
        }
    }

    private fun generatePairingCode() {
        val s = session ?: return
        pairingCode = (10000000 + Random().nextInt(90000000)).toString()
        val invite = JsonObject().apply {
            addProperty("familyId", s.uid)
            addProperty("createdAt", System.currentTimeMillis())
            addProperty("expiresAt", System.currentTimeMillis() + 15 * 60 * 1000L)
        }
        task({ api.put("pairingCodes/" + pairingCode, invite, s); "Code created. Expires in 15 minutes." }) {
            pairingDisplay.text = "Pairing code: " + pairingCode
            status.text = it
        }
    }

    private fun savePolicy() {
        val s = session ?: return
        val policy = JsonObject().apply {
            add("blockedDomains", lines(domains.text.toString()))
            add("blockedUrls", lines(urls.text.toString()))
            add("blockedPackages", lines(packages.text.toString()))
            val appLimits = JsonObject()
            limits.text.toString().lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val app = parts[0].trim()
                    val mins = parts[1].trim().toIntOrNull()
                    if (app.isNotBlank() && mins != null && mins > 0) appLimits.addProperty(app, mins)
                }
            }
            add("appTimeLimitsMinutes", appLimits)
            addProperty("updatedAt", System.currentTimeMillis())
        }
        task({ api.put("families/" + s.uid + "/policies", policy, s); "Family policy published." }) { status.text = it }
    }

    private fun refreshDevices() {
        val s = session ?: return
        status.text = "Loading device reports…"
        task({
            val base = "families/" + s.uid + "/devices"
            val devices = api.get(base, s)
            val out = StringBuilder()
            if (devices == null || !devices.isJsonObject || devices.asJsonObject.size() == 0) {
                out.append("No child devices paired yet.\n")
            } else {
                for ((id, item) in devices.asJsonObject.entrySet()) {
                    val device = item.asJsonObject
                    val path = base + "/" + id
                    val state = api.get(path + "/status", s)?.asJsonObject
                    out.append("\nDEVICE: ").append(device.get("deviceName")?.asString ?: id).append('\n')
                    val last = state?.get("lastSeen")?.asLong ?: device.get("lastSeen")?.asLong ?: 0L
                    out.append("Last seen: ").append(if (last > 0) DateFormat.getDateTimeInstance().format(Date(last)) else "unknown").append('\n')
                    out.append("Device Owner: ").append(state?.get("deviceOwner")?.asBoolean ?: false)
                        .append(" · Usage access: ").append(state?.get("usageAccessGranted")?.asBoolean ?: false).append('\n')
                    out.append("Last foreground event: ").append(state?.get("lastForegroundPackage")?.asString ?: "not reported").append('\n')
                    appendUsage(out, "Today", api.get(path + "/usage/today", s))
                    appendUsage(out, "Last 7 days", api.get(path + "/usage/week", s))
                    appendUsage(out, "Last 30 days", api.get(path + "/usage/month", s))
                    out.append("Installed apps:\n")
                    val installed = api.get(path + "/apps/installed", s)
                    if (installed != null && installed.isJsonArray) {
                        installed.asJsonArray.take(30).forEach { app ->
                            val obj = app.asJsonObject
                            out.append(" • ").append(obj.get("appName")?.asString ?: obj.get("packageName")?.asString)
                                .append(" (").append(obj.get("packageName")?.asString ?: "").append(")\n")
                        }
                    } else out.append("   Not synced yet.\n")
                    out.append("Guardian Browser history:\n")
                    val history = api.get(path + "/browserHistory", s)
                    if (history != null && history.isJsonObject && history.asJsonObject.size() > 0) {
                        history.asJsonObject.entrySet().sortedByDescending {
                            it.value.asJsonObject.get("startedAt")?.asLong ?: 0L
                        }.take(40).forEach { entry ->
                            val visit = entry.value.asJsonObject
                            out.append(" • ").append(visit.get("url")?.asString ?: "").append('\n')
                            out.append("   ").append(DateFormat.getDateTimeInstance().format(Date(visit.get("startedAt")?.asLong ?: 0L)))
                                .append(" · ").append((visit.get("durationMs")?.asLong ?: 0L) / 1000).append(" sec")
                            if (visit.get("event")?.asString == "blocked_attempt") out.append(" · BLOCKED")
                            out.append('\n')
                        }
                    } else out.append("   No visits recorded yet.\n")
                    out.append("────────────────────────\n")
                }
            }
            out.toString()
        }) { status.text = it }
    }

    private fun appendUsage(out: StringBuilder, title: String, report: com.google.gson.JsonElement?) {
        out.append(title).append(" usage:\n")
        if (report != null && report.isJsonArray) {
            val entries = report.asJsonArray
            val total = entries.sumOf { it.asJsonObject.get("foregroundMs")?.asLong ?: 0L }
            out.append(" Total screen time: ").append(total / 60000).append(" min")
            if (title == "Last 7 days") out.append(" · avg/day: ").append(total / 7 / 60000).append(" min")
            if (title == "Last 30 days") out.append(" · avg/day: ").append(total / 30 / 60000).append(" min")
            out.append('\n')
            entries.take(10).forEach { entry ->
                val obj = entry.asJsonObject
                out.append(" • ").append(obj.get("appName")?.asString ?: obj.get("packageName")?.asString)
                    .append(" — ").append((obj.get("foregroundMs")?.asLong ?: 0L) / 60000).append(" min\n")
            }
        } else out.append(" No usage report yet.\n")
    }

    private fun lines(value: String) = JsonArray().apply {
        value.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().forEach(::add)
    }

    private fun <T> task(work: () -> T, done: (T) -> Unit) {
        io.execute {
            try { val result = work(); runOnUiThread { done(result) } }
            catch (e: Exception) { runOnUiThread { status.text = "Error: " + (e.message ?: "request failed") } }
        }
    }

    private fun heading(value: String, size: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(android.graphics.Color.WHITE)
        setPadding(0, dp(16), 0, dp(8)); page.addView(this, matchWrap())
    }
    private fun text(value: String): TextView = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(android.graphics.Color.rgb(197, 207, 224))
        setPadding(0, dp(6), 0, dp(8)); page.addView(this, matchWrap())
    }
    private fun field(hintText: String, type: Int, multiline: Boolean = false) = EditText(this).apply {
        hint = hintText; inputType = type
        if (multiline) { minLines = 3; maxLines = 7; gravity = android.view.Gravity.TOP }
        setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.GRAY)
        setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(android.graphics.Color.rgb(29, 39, 57))
        page.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }
    private fun button(label: String, action: () -> Unit) {
        page.addView(MaterialButton(this).apply { text = label; setOnClickListener { action() } },
            matchWrap().apply { bottomMargin = dp(8) })
    }
    private fun divider() {
        page.addView(View(this).apply { setBackgroundColor(android.graphics.Color.rgb(55, 69, 91)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(10) })
    }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
