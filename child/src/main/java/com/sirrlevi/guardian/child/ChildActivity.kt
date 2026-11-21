package com.sirrlevi.guardian.child

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.GuardianApi
import com.sirrlevi.guardian.core.GuardianSession
import com.sirrlevi.guardian.core.UpdateRules
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChildActivity : AppCompatActivity() {
    private val api by lazy { GuardianApi(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_DATABASE_URL) }
    private val vault by lazy { SessionVault(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val updater by lazy { GuardianUpdater(this) }
    private val prefs by lazy { getSharedPreferences("guardian_child", MODE_PRIVATE) }
    private var session: GuardianSession? = null
    private var activeTab = "Protection"
    private var statusMessage = "This device is not paired."
    private var latestUpdate: UpdateCheckResult? = null
    private var pendingInstallFile: File? = null

    private lateinit var root: LinearLayout
    private lateinit var page: LinearLayout
    private lateinit var emailField: EditText
    private lateinit var passwordField: EditText
    private lateinit var pairingCodeField: EditText
    private lateinit var updateTokenField: EditText
    private lateinit var updateStatusView: TextView
    private lateinit var installUpdateButton: MaterialButton
    private lateinit var updateProgress: ProgressBar
    private lateinit var updateNotesView: TextView
    private val familyId: String get() = prefs.getString("family_id", "").orEmpty()
    private val navViews = linkedMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_BG
        GuardianUpdateScheduler.schedule(this)
        session = vault.load()
        showAuth()
        if (session != null && familyId.isNotBlank()) showControls()
    }

    private fun buildShell(showNavigation: Boolean) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(12))
        }
        val mark = TextView(this).apply {
            text = "G"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_BG)
            background = rounded(COLOR_ACCENT, 15)
        }
        top.addView(mark, LinearLayout.LayoutParams(dp(42), dp(42)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brand.addView(TextView(this).apply {
            text = "GUARDIAN"
            textSize = 16f
            letterSpacing = 0.16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
        })
        brand.addView(TextView(this).apply {
            text = "CHILD COMPANION"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(COLOR_MUTED)
        })
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val state = TextView(this).apply {
            text = if (session == null) "NOT PAIRED" else "PAIRED"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_MINT)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = rounded(COLOR_CARD_ALT, 30)
        }
        top.addView(state)
        root.addView(top)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, 0, 0, dp(10))
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(20))
        }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(10))
            setBackgroundColor(COLOR_NAV)
            visibility = if (showNavigation) View.VISIBLE else View.GONE
        }
        navViews.clear()
        val navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(nav)
        }
        listOf("Protection", "Browser", "Settings").forEach { tab ->
            val item = TextView(this).apply {
                text = tab
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setTextColor(if (tab == activeTab) COLOR_TEXT else COLOR_MUTED)
                background = rounded(if (tab == activeTab) COLOR_ACCENT_DARK else COLOR_NAV, 30)
                setOnClickListener {
                    activeTab = tab
                    renderTab()
                }
            }
            navViews[tab] = item
            nav.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
        root.addView(navScroll)
        setContentView(root)
    }

    private fun showAuth() {
        buildShell(false)
        val card = sectionCard("Connect this device to your family",
            "Use the separate child account and the one-time code from Guardian Parent.")
        emailField = input(card, "Child email address", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        passwordField = input(card, "Password · minimum 6 characters",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pairingCodeField = input(card, "8-digit pairing code", InputType.TYPE_CLASS_NUMBER)
        actionButton(card, "Create account and pair") { authenticate(true) }
        actionButton(card, "Sign in and pair", true) { authenticate(false) }
        note(card, "This companion openly reports app usage and applies the family policies configured on the parent phone. Android permission prompts remain visible.")
        val info = sectionCard("What gets shared")
        note(info, "App usage summaries, installed-app inventory, recent foreground events and Guardian Browser visits can be synced to the paired parent account.")
        note(info, statusMessage)
    }

    private fun authenticate(create: Boolean) {
        val email = emailField.text.toString().trim()
        val password = passwordField.text.toString()
        val code = pairingCodeField.text.toString().trim()
        if (email.isBlank() || password.length < 6 || code.length != 8) {
            statusMessage = "Enter a valid email, password (6+ characters) and the full 8-digit code."
            showAuth()
            return
        }
        statusMessage = "Checking account and pairing code…"
        io.execute {
            try {
                val auth = if (create) api.signUp(email, password) else api.signIn(email, password)
                val invite = api.get("pairingCodes/" + code, auth)?.asJsonObject
                    ?: throw IllegalStateException("Pairing code not found or unavailable.")
                val expiry = invite.get("expiresAt")?.asLong ?: 0L
                val family = invite.get("familyId")?.asString.orEmpty()
                if (family.isBlank() || expiry < System.currentTimeMillis()) {
                    throw IllegalStateException("Pairing code has expired. Ask the parent to generate a new one.")
                }
                val member = JsonObject().apply {
                    addProperty("joinedAt", System.currentTimeMillis())
                    addProperty("role", "child")
                    addProperty("deviceName", Build.MODEL ?: "Android device")
                }
                api.put("families/" + family + "/members/" + auth.uid, member, auth)
                val device = JsonObject().apply {
                    addProperty("deviceName", Build.MODEL ?: "Android device")
                    addProperty("manufacturer", Build.MANUFACTURER ?: "Android")
                    addProperty("androidVersion", Build.VERSION.RELEASE ?: "unknown")
                    addProperty("joinedAt", System.currentTimeMillis())
                    addProperty("lastSeen", System.currentTimeMillis())
                    addProperty("monitoringDisclosure", "visible")
                }
                api.put("families/" + family + "/devices/" + auth.uid, device, auth)
                val result = PairingResult(auth, family)
                runOnUiThread {
                    session = result.session
                    vault.save(result.session)
                    prefs.edit().putString("family_id", result.familyId).apply()
                    statusMessage = "Paired. Complete the permission steps below."
                    showControls()
                    scheduleSync()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusMessage = "Pairing failed: " + (e.message ?: "request failed")
                    showAuth()
                }
            }
        }
    }

    private data class PairingResult(val session: GuardianSession, val familyId: String)

    private fun showControls() {
        activeTab = if (intent?.action == ACTION_OPEN_UPDATES) "Settings" else "Protection"
        buildShell(true)
        renderTab()
        scheduleSync()
        if (updater.hasToken()) checkForUpdates(false)
    }

    private fun renderTab() {
        navViews.forEach { (tab, view) ->
            val selected = tab == activeTab
            view.setTextColor(if (selected) COLOR_TEXT else COLOR_MUTED)
            view.background = rounded(if (selected) COLOR_ACCENT_DARK else COLOR_NAV, 30)
        }
        page.removeAllViews()
        when (activeTab) {
            "Protection" -> renderProtection()
            "Browser" -> renderBrowser()
            "Settings" -> renderSettings()
        }
    }

    private fun renderProtection() {
        pageHeading("Device protection", "A transparent overview of the Guardian services on this phone.")
        val status = sectionCard("Family connection", "Paired with family " + familyId.take(8))
        statusPill(status, "PAIRED", COLOR_MINT)
        note(status, "Device: " + (Build.MANUFACTURER ?: "Android") + " " + (Build.MODEL ?: "phone"))
        note(status, "Last successful sync: " + formatDate(prefs.getLong("last_sync", 0L)))
        note(status, "Usage Access: " + (if (hasUsageAccess()) "Granted" else "Needs permission"))
        note(status, "VPN filtering: " + (if (isVpnPermissionReady()) "Permission granted previously" else "Needs permission or activation"))

        val steps = sectionCard("Required permissions", "Guardian cannot enable protected Android permissions silently.")
        permissionStep(steps, "01", "Usage Access",
            if (hasUsageAccess()) "Granted · app usage reports can be collected" else "Needed for screen-time, app inventory and recent foreground events") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        permissionStep(steps, "02", "Domain-filtering VPN",
            "Android will show a system approval screen and a visible VPN indicator while filtering is active") {
            requestVpn()
        }
        permissionStep(steps, "03", "Notifications",
            if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                "Notification permission is granted or not required on this Android version"
            else "Allow notifications for sync and update status") {
            requestUpdateNotificationPermission()
        }

        val actions = sectionCard("Quick actions")
        actionButton(actions, "Sync usage and parent policies now") {
            WorkManager.getInstance(this).enqueue(
                OneTimeWorkRequestBuilder<ChildSyncWorker>().build()
            )
            statusMessage = "Sync requested. Android may take a short time to run the worker."
            renderTab()
        }
        actionButton(actions, "Open Guardian Browser", true) {
            startActivity(Intent(this, GuardianBrowserActivity::class.java))
        }
        actionButton(actions, "Device Owner setup information", true) { showDeviceOwnerInfo() }

        val inventory = sectionCard("Local app inventory")
        val appsCount = try { InstalledAppsCollector.collect(this).size() } catch (_: Exception) { 0 }
        label(inventory, appsCount.toString() + " installed apps detected", 21, COLOR_TEXT, true)
        note(inventory, "The parent receives the inventory on the next successful sync. Remote app suspension and time-limit enforcement require Device Owner provisioning.")
        note(sectionCard("Sync status"), statusMessage)
    }

    private fun renderBrowser() {
        pageHeading("Guardian Browser", "A dedicated browser with family-approved web restrictions.")
        val browser = sectionCard("Browser activity visibility")
        note(browser, "Pages opened inside Guardian Browser can be recorded with full URL, page title, visit time and approximate dwell time.")
        note(browser, "Other browsers such as Chrome do not provide their complete HTTPS history to Guardian. Domain filtering can block supported domains, but not encrypted URL paths.")
        actionButton(browser, "Open Guardian Browser") {
            startActivity(Intent(this, GuardianBrowserActivity::class.java))
        }
        val filter = sectionCard("Filtering status")
        val blockedDomains = prefs.getString("blocked_domains", "[]").orEmpty()
        val blockedUrls = prefs.getString("blocked_urls", "[]").orEmpty()
        label(filter, "Domain rules cached: " + countJsonArray(blockedDomains), 13, COLOR_TEXT, true)
        label(filter, "Browser URL rules cached: " + countJsonArray(blockedUrls), 13, COLOR_TEXT, true)
        note(filter, "The latest parent policy is applied by the child sync worker. VPN permission and an active VPN are both needed for DNS filtering.")
    }

    private fun renderSettings() {
        pageHeading("Settings", "Updates, account and local diagnostics.")
        val account = sectionCard("Paired account")
        label(account, session?.uid ?: "No active session", 12, COLOR_TEXT)
        note(account, "Family ID: " + familyId)
        val update = sectionCard("Check for updates", "Downloads the latest signed Guardian Child build from your private GitHub Releases.")
        label(update, "Installed: v" + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")", 13, COLOR_TEXT, true)
        note(update, "Use a fine-grained GitHub token restricted to this repository with Contents: Read-only access. The token is encrypted with Android Keystore and stored only on this device.")
        updateTokenField = input(update,
            if (updater.hasToken()) "Token saved · enter another token to replace" else "GitHub fine-grained token",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actionButton(buttons, "Save token") { saveUpdateToken() }
        actionButton(buttons, "Check now", true) { checkForUpdates(true) }
        update.addView(buttons)
        installUpdateButton = makeButton("Download & install update", false) { downloadAndInstallUpdate() }.apply {
            visibility = View.GONE
        }
        update.addView(installUpdateButton, matchWrap().apply { topMargin = dp(7) })
        updateStatusView = label(update,
            if (updater.hasToken()) "Token saved. Checking for a new release…" else "No update token saved.",
            12, COLOR_MUTED)
        updateProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
        }
        update.addView(updateProgress, matchWrap().apply { topMargin = dp(8) })
        updateNotesView = note(update, "").apply { visibility = View.GONE }
        note(update, "Guardian checks for new builds in the background about every 15 minutes (Android may delay this) and shows a notification when one is ready. Android's installer still asks you to confirm; your data and settings are kept.")
        val local = sectionCard("Local data")
        note(local, "Session credentials, family pairing ID, cached policies and update files are stored in this app's private storage. An in-place update with the same package ID and signing key does not clear them.")
        note(local, "Last successful sync: " + formatDate(prefs.getLong("last_sync", 0L)))
        val signout = sectionCard("Account actions")
        actionButton(signout, "Sign out and unpair this installation", true) {
            AlertDialog.Builder(this)
                .setTitle("Sign out of Guardian Child?")
                .setMessage("This clears the local sign-in session and family pairing from this phone. It does not delete your Firebase account.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign out") { _, _ ->
                    vault.clear()
                    prefs.edit().remove("family_id").apply()
                    session = null
                    statusMessage = "This device is signed out."
                    showAuth()
                }.show()
        }
        if (updater.hasToken()) checkForUpdates(false)
    }

    private fun permissionStep(parent: LinearLayout, number: String, title: String, detail: String, action: () -> Unit) {
        val card = sectionCard(parent, number + "  " + title, detail)
        actionButton(card, "Open Android settings / approve", false, action)
    }

    private fun saveUpdateToken() {
        try {
            updater.saveToken(updateTokenField.text.toString())
            updateTokenField.setText("")
            updateStatusView.text = "Token encrypted and saved on this phone."
            requestUpdateNotificationPermission()
            checkForUpdates(true)
        } catch (e: Exception) {
            updateStatusView.text = "Token not saved: " + (e.message ?: "invalid token")
        }
    }

    private fun requestUpdateNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7311)
        }
    }

    private fun checkForUpdates(showErrors: Boolean) {
        if (::updateStatusView.isInitialized) updateStatusView.text = "Checking the latest GitHub release…"
        io.execute {
            try {
                val result = updater.checkForUpdate()
                updater.notifyIfAvailable(result)
                runOnUiThread {
                    latestUpdate = result
                    if (::updateStatusView.isInitialized) {
                        updateStatusView.text = result.message + "\nLast checked: " + formatDate(updater.lastCheckedAt())
                        installUpdateButton.visibility = if (result.updateAvailable) View.VISIBLE else View.GONE
                        if (::updateNotesView.isInitialized) {
                            val notes = result.release?.notes?.trim().orEmpty()
                            if (result.updateAvailable && notes.isNotEmpty()) {
                                updateNotesView.text = "What's new:\n" + notes.take(400)
                                updateNotesView.visibility = View.VISIBLE
                            } else {
                                updateNotesView.visibility = View.GONE
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    latestUpdate = null
                    if (::installUpdateButton.isInitialized) installUpdateButton.visibility = View.GONE
                    if (showErrors && ::updateStatusView.isInitialized) {
                        updateStatusView.text = "Update check failed: " + (e.message ?: "network or token error")
                    }
                }
            }
        }
    }

    private fun downloadAndInstallUpdate() {
        val release = latestUpdate?.release
        if (release == null || latestUpdate?.updateAvailable != true) {
            updateStatusView.text = "Check for updates first."
            return
        }
        installUpdateButton.isEnabled = false
        updateProgress.progress = 0
        updateProgress.visibility = View.VISIBLE
        updateStatusView.text = "Downloading " + release.title + "…"
        io.execute {
            try {
                val file = updater.download(release) { done, total ->
                    runOnUiThread {
                        if (::updateProgress.isInitialized) {
                            val percent = UpdateRules.percent(done, total)
                            updateProgress.progress = percent
                            updateStatusView.text = "Downloading… " + UpdateRules.formatSize(done) +
                                " of " + UpdateRules.formatSize(total) + " (" + percent + "%)"
                        }
                    }
                }
                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    if (::updateProgress.isInitialized) updateProgress.visibility = View.GONE
                    updateStatusView.text = "Download complete. Opening Android installer…"
                    installDownloadedApk(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    if (::updateProgress.isInitialized) updateProgress.visibility = View.GONE
                    updateStatusView.text = "Download failed: " + (e.message ?: "unknown error")
                }
            }
        }
    }

    private fun installDownloadedApk(file: File) {
        if (!file.isFile) {
            updateStatusView.text = "Downloaded APK is no longer available. Download it again."
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = file
            updateStatusView.text = "Allow Guardian Child to install updates, then return here."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
            return
        }
        launchAndroidInstaller(file)
    }

    private fun launchAndroidInstaller(file: File) {
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_UPDATES && session != null) {
            activeTab = "Settings"
            renderTab()
        }
    }

    override fun onResume() {
        super.onResume()
        val file = pendingInstallFile
        if (file != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
            pendingInstallFile = null
            launchAndroidInstaller(file)
        }
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQUEST_VPN) else startVpnService()
    }

    private fun startVpnService() {
        ContextCompat.startForegroundService(this, Intent(this, DomainFilterVpnService::class.java))
        statusMessage = "Domain-filtering VPN started. Android shows the VPN indicator and Guardian notification."
        if (activeTab == "Protection" || activeTab == "Browser") renderTab()
    }

    @Deprecated("Used for Android VPN consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == Activity.RESULT_OK) startVpnService()
            else {
                statusMessage = "VPN permission was not granted. Domain filtering remains off."
                renderTab()
            }
        }
    }

    private fun scheduleSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ChildSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "guardian-child-sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<ChildSyncWorker>().setConstraints(constraints).build()
        )
    }

    private fun showDeviceOwnerInfo() {
        AlertDialog.Builder(this)
            .setTitle("Fully managed device required")
            .setMessage(
                "Strong app blocking and daily app limits require Guardian Child to be provisioned as Device Owner. " +
                    "Android normally permits this only on a fresh or unprovisioned device. Installing the APK does not grant that role. " +
                    "For a test device you control, see the documented ADB command in the project README."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun hasUsageAccess(): Boolean = try {
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun isVpnPermissionReady(): Boolean = try {
        VpnService.prepare(this) == null
    } catch (_: Exception) { false }

    private fun countJsonArray(value: String): Int = try {
        com.google.gson.JsonParser.parseString(value).asJsonArray.size()
    } catch (_: Exception) { 0 }

    private fun renderStatusText(parent: LinearLayout) {
        note(parent, statusMessage)
    }

    private fun pageHeading(title: String, subtitle: String) {
        label(page, title, 24, COLOR_TEXT, true)
        label(page, subtitle, 12, COLOR_MUTED)
    }

    private fun sectionCard(title: String, subtitle: String? = null): LinearLayout =
        sectionCard(page, title, subtitle)

    private fun sectionCard(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            strokeColor = COLOR_STROKE
            setCardBackgroundColor(COLOR_CARD)
            useCompatPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        if (title.isNotBlank()) label(body, title, 15, COLOR_TEXT, true)
        if (!subtitle.isNullOrBlank()) label(body, subtitle, 11, COLOR_MUTED)
        card.addView(body)
        parent.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(7); bottomMargin = dp(7) })
        return body
    }

    private fun input(parent: LinearLayout, hint: String, type: Int): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = type
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            textSize = 13f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(COLOR_FIELD, 13)
            parent.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(6) })
        }

    private fun label(parent: LinearLayout, value: String, size: Int, color: Int, bold: Boolean = false): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(5))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        parent.addView(view, matchWrap())
        return view
    }

    private fun note(parent: LinearLayout, value: String): TextView = label(parent, value, 11, COLOR_MUTED)

    private fun actionButton(parent: LinearLayout, title: String, secondary: Boolean = false, action: () -> Unit) {
        parent.addView(makeButton(title, secondary, action), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(3) })
    }

    private fun makeButton(title: String, secondary: Boolean, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = title
            textSize = 12f
            isAllCaps = false
            cornerRadius = dp(13)
            setTextColor(if (secondary) COLOR_ACCENT else COLOR_BG)
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (secondary) COLOR_CARD_ALT else COLOR_ACCENT)
            strokeWidth = if (secondary) dp(1) else 0
            strokeColor = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            minHeight = dp(44)
            setOnClickListener { action() }
        }

    private fun statusPill(parent: LinearLayout, value: String, tint: Int) {
        parent.addView(TextView(this).apply {
            text = value
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tint)
            background = rounded(COLOR_CARD_ALT, 25)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun formatDate(time: Long): String =
        if (time <= 0L) "Not yet" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_OPEN_UPDATES = "com.sirrlevi.guardian.child.OPEN_APP_UPDATES"
        private const val REQUEST_VPN = 4107
        private const val COLOR_BG = 0xFF091321.toInt()
        private const val COLOR_NAV = 0xFF0D1B2D.toInt()
        private const val COLOR_CARD = 0xFF13243A.toInt()
        private const val COLOR_CARD_ALT = 0xFF1A3049.toInt()
        private const val COLOR_FIELD = 0xFF0D1C2E.toInt()
        private const val COLOR_STROKE = 0xFF263D58.toInt()
        private const val COLOR_TEXT = 0xFFF3F7FD.toInt()
        private const val COLOR_MUTED = 0xFF92A7C0.toInt()
        private const val COLOR_ACCENT = 0xFF62D7C5.toInt()
        private const val COLOR_ACCENT_DARK = 0xFF174C55.toInt()
        private const val COLOR_MINT = 0xFF67DDB4.toInt()
    }
}
