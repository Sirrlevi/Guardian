package com.sirrlevi.guardian.parent

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.GuardianApi
import com.sirrlevi.guardian.core.GuardianSession
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.max

private data class GuardianUsageBar(
    val name: String,
    val packageName: String,
    val foregroundMs: Long,
    val lastUsed: Long
)

private data class GuardianAppRow(
    val name: String,
    val packageName: String,
    val systemApp: Boolean,
    val enabled: Boolean,
    val foregroundMs: Long,
    val lastUsed: Long
)

private data class GuardianBrowserVisit(
    val url: String,
    val title: String,
    val startedAt: Long,
    val durationMs: Long,
    val blocked: Boolean
)

private data class GuardianRecentEvent(
    val appName: String,
    val packageName: String,
    val timestamp: Long
)

private data class GuardianDeviceReport(
    val id: String,
    val name: String,
    val manufacturer: String,
    val androidVersion: String,
    val lastSeen: Long,
    val usageAccess: Boolean,
    val deviceOwner: Boolean,
    val lastForegroundPackage: String,
    val lastForegroundAt: Long,
    val today: List<GuardianUsageBar>,
    val week: List<GuardianUsageBar>,
    val month: List<GuardianUsageBar>,
    val apps: List<GuardianAppRow>,
    val visits: List<GuardianBrowserVisit>,
    val recent: List<GuardianRecentEvent>
)

class ParentActivity : AppCompatActivity() {
    private val api by lazy { GuardianApi(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_DATABASE_URL) }
    private val vault by lazy { SessionVault(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val updater by lazy { GuardianUpdater(this) }
    private var session: GuardianSession? = null
    private var pairingCode = ""
    private var devices: List<GuardianDeviceReport> = emptyList()
    private var policy = JsonObject()
    private var activeTab = "Overview"
    private var lastRefreshAt = 0L
    private var statusMessage = "Waiting for child device data."
    private var latestUpdate: UpdateCheckResult? = null
    private var pendingInstallFile: File? = null

    private lateinit var root: LinearLayout
    private lateinit var page: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var headerStatus: TextView
    private lateinit var emailField: EditText
    private lateinit var passwordField: EditText
    private lateinit var pairingDisplay: TextView
    private lateinit var updateTokenField: EditText
    private lateinit var updateStatusView: TextView
    private lateinit var installUpdateButton: MaterialButton
    private val navViews = linkedMapOf<String, TextView>()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            if (session != null) {
                refreshDevices(true)
                refreshHandler.postDelayed(this, 30_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_BG
        session = vault.load()
        GuardianUpdateScheduler.schedule(this)
        showAuth()
        if (session != null) showDashboard()
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
            text = "PARENT CONSOLE"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(COLOR_MUTED)
        })
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerStatus = TextView(this).apply {
            text = if (session == null) "SECURE LOGIN" else "SYNCING"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_MINT)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = rounded(COLOR_CARD_ALT, 30)
        }
        top.addView(headerStatus)
        root.addView(top)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, 0, 0, dp(10))
            isFillViewport = false
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(20))
        }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(10))
            setBackgroundColor(COLOR_NAV)
            visibility = if (showNavigation) View.VISIBLE else View.GONE
        }
        navViews.clear()
        val tabs = listOf("Overview", "Apps", "Web", "Controls", "Reports", "Settings")
        val navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(nav)
        }
        tabs.forEach { tab ->
            val item = TextView(this).apply {
                text = tab
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(15), dp(12), dp(15), dp(12))
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
            ).apply { rightMargin = dp(5) })
        }
        root.addView(navScroll)
        setContentView(root)
    }

    private fun showAuth() {
        activeTab = "Overview"
        buildShell(false)
        val intro = sectionCard("A clearer view of your child's digital day",
            "Pair a supervised Android device, review activity and set family-approved boundaries.")
        val email = input(intro, "Parent email address", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = input(intro, "Password · minimum 6 characters",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        emailField = email
        passwordField = password
        actionButton(intro, "Create parent account") { authenticate(true) }
        actionButton(intro, "Sign in to your account", true) { authenticate(false) }
        note(intro, "Your account and reports sync through your Firebase project. Your session remains encrypted on this phone.")
        val info = sectionCard("What Guardian can report", "Usage access and child-device permissions determine which reports are available.")
        note(info, "App usage totals, installed-app inventory, recent foreground events and Guardian Browser visits are supported. Android does not expose full Chrome HTTPS page history or a hidden live screen feed to ordinary apps.")
        note(info, statusMessage)
    }

    private fun authenticate(create: Boolean) {
        val email = emailField.text.toString().trim()
        val password = passwordField.text.toString()
        if (email.isBlank() || password.length < 6) {
            statusMessage = "Enter a valid email and a password of at least 6 characters."
            showAuth()
            return
        }
        statusMessage = "Connecting to Firebase…"
        io.execute {
            try {
                val result = if (create) api.signUp(email, password) else api.signIn(email, password)
                vault.save(result)
                runOnUiThread {
                    session = result
                    showDashboard()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusMessage = "Sign-in failed: " + (e.message ?: "Firebase request failed")
                    showAuth()
                }
            }
        }
    }

    private fun showDashboard() {
        activeTab = if (intent?.action == ACTION_OPEN_UPDATES) "Settings" else "Overview"
        buildShell(true)
        headerStatus.text = "WAITING FOR DEVICE"
        renderTab()
        refreshDevices(false)
        refreshHandler.removeCallbacks(autoRefresh)
        refreshHandler.postDelayed(autoRefresh, 30_000L)
        if (updater.hasToken()) {
            // The background worker also checks releases approximately every six hours.
            checkForUpdates(false)
        }
    }

    private fun renderTab() {
        if (!::page.isInitialized) return
        navViews.forEach { (tab, view) ->
            val selected = tab == activeTab
            view.setTextColor(if (selected) COLOR_TEXT else COLOR_MUTED)
            view.background = rounded(if (selected) COLOR_ACCENT_DARK else COLOR_NAV, 30)
        }
        page.removeAllViews()
        when (activeTab) {
            "Overview" -> renderOverview()
            "Apps" -> renderApps()
            "Web" -> renderWeb()
            "Controls" -> renderControls()
            "Reports" -> renderReports()
            "Settings" -> renderSettings()
        }
    }

    private fun renderOverview() {
        pageHeading("Your family overview", "Review the latest synced activity and device health.")
        val device = devices.firstOrNull()
        if (device == null) {
            val empty = sectionCard("No child device paired yet", "Start with a one-time pairing code.")
            actionButton(empty, "Generate pairing code") { generatePairingCode() }
            pairingBlock(empty)
            val guide = sectionCard("How the connection works")
            note(guide, "Install Guardian Child on the supervised device, create its separate account and enter the pairing code. Grant Usage Access on the child phone. Domain filtering requires a separately approved VPN connection.")
            note(guide, "Parent data refreshes while this app is open. The child uploads in Android's background schedule, which is best-effort rather than guaranteed real-time.")
            return
        }

        val deviceCard = sectionCard(device.name, device.manufacturer + " · Android " + device.androidVersion)
        val seenRecently = device.lastSeen > 0L && System.currentTimeMillis() - device.lastSeen < 30L * 60L * 1000L
        statusPill(deviceCard, if (seenRecently) "RECENTLY CONNECTED" else "DATA MAY BE STALE",
            if (seenRecently) COLOR_MINT else COLOR_AMBER)
        note(deviceCard, "Last sync: " + formatDate(device.lastSeen) +
            "   ·   Usage access: " + (if (device.usageAccess) "Granted" else "Not granted") +
            "   ·   Device Owner: " + (if (device.deviceOwner) "Enabled" else "Not enabled"))
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metricCard(metrics, "TODAY", formatDuration(totalMs(device.today)), "screen time")
        metricCard(metrics, "WEEK AVG", formatDuration(totalMs(device.week) / 7L), "per day")
        metricCard(metrics, "MONTH AVG", formatDuration(totalMs(device.month) / 30L), "per day")
        page.addView(metrics, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(8) })

        val focus = sectionCard("Latest app activity", "Most recent Usage Access foreground event")
        val latestName = findAppName(device, device.lastForegroundPackage)
        val active = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addMonogram(active, latestName)
        val activeDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        label(activeDetails, latestName, 16, COLOR_TEXT, true)
        label(activeDetails, if (device.lastForegroundAt > 0L) formatDate(device.lastForegroundAt) else "No foreground event reported", 11, COLOR_MUTED)
        active.addView(activeDetails, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        focus.addView(active)
        note(focus, "This is the last detected foreground event, not proof that the app is still open right now.")
        actionButton(focus, "Refresh device data", false) { refreshDevices(false) }

        val chart = sectionCard("Most used apps today", "Share of reported foreground time")
        if (device.today.isEmpty()) {
            note(chart, "No usage report yet. Check Usage Access on the child device and run Sync now.")
        } else {
            chart.addView(GuardianUsageChartView(this, device.today.take(6)), matchWrap())
        }

        val recent = sectionCard("Recent activity", "Foreground app events reported from the child device")
        if (device.recent.isEmpty()) {
            note(recent, "No recent event feed is available yet. It will appear after the next child sync.")
        } else {
            device.recent.take(8).forEach { event ->
                activityRow(recent, event.appName, event.packageName, formatDate(event.timestamp))
            }
        }
        pairingBlock(page)
        val statusCard = sectionCard("Report freshness")
        note(statusCard, "Parent checks the cloud report every 30 seconds while open. Android generally schedules the child upload no more frequently than every 15 minutes, and battery/network restrictions can delay it.")
        note(statusCard, "Last parent refresh: " + if (lastRefreshAt == 0L) "Not refreshed yet" else formatDate(lastRefreshAt))
    }

    private fun renderApps() {
        pageHeading("Apps on the supervised device", "Usage cards, block rules and daily limits.")
        val device = devices.firstOrNull()
        if (device == null) {
            note(sectionCard("No device data"), "Pair the child device and wait for its first sync.")
            return
        }
        val overview = sectionCard("App inventory", device.apps.size.toString() + " installed apps reported")
        note(overview, "App blocking and daily time limits are enforced only when Guardian Child has the required Device Owner management privileges. Normal installation alone does not grant that authority.")
        if (device.apps.isEmpty()) {
            note(overview, "No installed-app inventory received. Check Usage Access and wait for a sync.")
            return
        }
        val blocked = stringSet(policy.get("blockedPackages"))
        val limits = policy.getAsJsonObject("appTimeLimitsMinutes") ?: JsonObject()
        device.apps.sortedByDescending { it.foregroundMs }.take(60).forEach { app ->
            val card = sectionCard(app.name, app.packageName)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addMonogram(row, app.name)
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            label(details, formatDuration(app.foregroundMs) + " today", 14, COLOR_TEXT, true)
            label(details, if (app.lastUsed > 0L) "Last used " + formatDate(app.lastUsed) else "Not used today", 11, COLOR_MUTED)
            label(details, if (app.systemApp) "System app" else "User app", 10, COLOR_MUTED)
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            val limit = try { limits.get(app.packageName)?.asInt ?: 0 } catch (_: Exception) { 0 }
            val rule = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            label(rule, if (blocked.contains(app.packageName)) "BLOCK RULE ON" else "NO BLOCK RULE",
                10, if (blocked.contains(app.packageName)) COLOR_AMBER else COLOR_MINT, true)
            label(rule, if (limit > 0) "   DAILY LIMIT  " + limit + " MIN" else "   NO DAILY LIMIT", 10, COLOR_MUTED)
            card.addView(rule)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            actionButton(actions, if (blocked.contains(app.packageName)) "Unblock" else "Block", true) {
                toggleBlocked(app.packageName)
            }
            actionButton(actions, "Set limit", false) { showLimitDialog(app) }
            card.addView(actions)
        }
    }

    private fun renderWeb() {
        pageHeading("Web activity", "Full URL logging is available inside Guardian Browser.")
        val device = devices.firstOrNull()
        if (device == null) {
            note(sectionCard("No device data"), "Pair the child device to see browser activity.")
            return
        }
        val intro = sectionCard("Browser visibility")
        note(intro, "Guardian Browser can record the page URL, title, visit time and approximate dwell time. Chrome, Firefox and other browsers do not expose their complete private HTTPS browsing history to a third-party Android app.")
        note(intro, "The DNS filter can block configured domains in supported traffic, but it cannot see encrypted URL paths such as /watch/123.")
        val history = sectionCard("Recent Guardian Browser visits", device.visits.size.toString() + " stored visit events")
        if (device.visits.isEmpty()) note(history, "No visits yet. Open Guardian Browser on the child phone to begin recording.")
        device.visits.take(50).forEach { visit ->
            val item = sectionCard(visit.title.ifBlank { hostFromUrl(visit.url) }, if (visit.blocked) "BLOCKED ATTEMPT" else formatDate(visit.startedAt))
            label(item, visit.url, 12, COLOR_TEXT)
            label(item, "Time on page: " + formatDuration(visit.durationMs), 11, COLOR_MUTED)
            if (visit.blocked) statusPill(item, "BLOCKED", COLOR_AMBER)
        }
        val controls = sectionCard("Manage web restrictions")
        note(controls, "Use Controls to add domains for DNS filtering and full URL/path patterns for Guardian Browser only.")
        actionButton(controls, "Open web controls") {
            activeTab = "Controls"
            renderTab()
        }
    }

    private fun renderControls() {
        pageHeading("Family controls", "Publish rules to the paired device.")
        val info = sectionCard("Enforcement status")
        val device = devices.firstOrNull()
        note(info, if (device?.deviceOwner == true)
            "Device Owner is enabled. App suspension and time-limit enforcement can be applied by Guardian Child."
        else "Device Owner is not enabled. Rules can sync to the child device, but Android will not allow this app to forcibly suspend arbitrary apps without managed-device privileges.")
        val webCard = sectionCard("Website restrictions", "One domain or Guardian Browser URL pattern per line")
        val domainInput = input(webCard, "Blocked domains · example.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, arrayText(policy.get("blockedDomains")), true)
        val urlInput = input(webCard, "Guardian Browser URLs · https://site/path/*", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, arrayText(policy.get("blockedUrls")), true)
        val appCard = sectionCard("App restrictions", "Package names and daily usage limits")
        val packageInput = input(appCard, "Blocked package names · one per line", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, arrayText(policy.get("blockedPackages")), true)
        val limitsInput = input(appCard, "Daily limits · package.name=minutes", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, limitsText(), true)
        actionButton(appCard, "Publish all family rules") {
            val next = JsonObject().apply {
                add("blockedDomains", toJsonArray(domainInput.text.toString()))
                add("blockedUrls", toJsonArray(urlInput.text.toString()))
                add("blockedPackages", toJsonArray(packageInput.text.toString()))
                val limitObject = JsonObject()
                limitsInput.text.toString().lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        val packageName = parts[0].trim()
                        val minutes = parts[1].trim().toIntOrNull()
                        if (packageName.isNotBlank() && minutes != null && minutes > 0) limitObject.addProperty(packageName, minutes)
                    }
                }
                add("appTimeLimitsMinutes", limitObject)
                addProperty("updatedAt", System.currentTimeMillis())
            }
            publishPolicy(next)
        }
        note(appCard, "A time limit is based on reported foreground duration and policy sync timing. It is not a guaranteed second-by-second lockout.")
    }

    private fun renderReports() {
        pageHeading("Usage reports", "Daily, weekly and monthly summaries from Android Usage Access.")
        val device = devices.firstOrNull()
        if (device == null) {
            note(sectionCard("No report data"), "Pair and sync the child device first.")
            return
        }
        val today = sectionCard("Today", "Top reported foreground apps")
        reportSummary(today, totalMs(device.today), device.today.size)
        if (device.today.isNotEmpty()) today.addView(GuardianUsageChartView(this, device.today.take(8)), matchWrap())
        else note(today, "No usage data.")
        val week = sectionCard("Last 7 days", "Aggregated package usage across the requested period")
        reportSummary(week, totalMs(device.week), device.week.size)
        if (device.week.isNotEmpty()) week.addView(GuardianUsageChartView(this, device.week.take(8)), matchWrap())
        val month = sectionCard("Last 30 days", "Aggregated package usage across the requested period")
        reportSummary(month, totalMs(device.month), device.month.size)
        if (device.month.isNotEmpty()) month.addView(GuardianUsageChartView(this, device.month.take(8)), matchWrap())
        note(sectionCard("How to interpret these reports"), "Android provides aggregated app usage statistics, not a perfect activity recorder. Totals may be delayed or incomplete when Usage Access is off, Android restricts background work, or the device has rebooted.")
    }

    private fun renderSettings() {
        pageHeading("Settings", "Account, update delivery and app diagnostics.")
        val account = sectionCard("Parent account")
        label(account, session?.uid ?: "No active account", 12, COLOR_TEXT)
        actionButton(account, "Refresh child reports") { refreshDevices(false) }
        val update = sectionCard("App updates", "Checks the private GitHub Releases feed for Guardian-Parent.apk.")
        note(update, "Save a fine-grained GitHub token for this private repository with Contents: Read-only access. It is encrypted with Android Keystore on this device.")
        updateTokenField = input(update, if (updater.hasToken()) "Token saved · enter a new token to replace" else "GitHub fine-grained token", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actionButton(actions, "Save token") { saveUpdateToken() }
        actionButton(actions, "Check now", true) { checkForUpdates(true) }
        update.addView(actions)
        installUpdateButton = makeButton("Download & install", true) { downloadAndInstallUpdate() }.apply {
            visibility = View.GONE
        }
        update.addView(installUpdateButton, matchWrap().apply { topMargin = dp(7) })
        updateStatusView = label(update, if (updater.hasToken()) "Token saved. Checking release…" else "No update token saved.", 12, COLOR_MUTED)
        note(update, "Automatic checks run about every six hours when Android allows background work. The Android package installer still asks you to confirm installation.")
        val diag = sectionCard("Device sync")
        note(diag, "Parent refresh interval: 30 seconds while this screen is open.")
        note(diag, "Last report pull: " + if (lastRefreshAt > 0L) formatDate(lastRefreshAt) else "Not yet")
        note(diag, "Latest status: " + statusMessage)
        actionButton(sectionCard("Session"), "Sign out of Guardian Parent", true) {
            refreshHandler.removeCallbacks(autoRefresh)
            vault.clear()
            session = null
            devices = emptyList()
            pairingCode = ""
            showAuth()
        }
        if (updater.hasToken()) checkForUpdates(false)
    }

    private fun pairingBlock(parent: LinearLayout) {
        val card = sectionCard(parent, "Pair a child device", "Codes expire 15 minutes after creation.")
        actionButton(card, "Generate pairing code") { generatePairingCode() }
        pairingDisplay = label(card, if (pairingCode.isBlank()) "No active code" else pairingCode,
            26, COLOR_TEXT, true)
        note(card, "On Guardian Child, sign in or create a separate child account, then enter this code.")
    }

    private fun generatePairingCode() {
        val current = session ?: return
        val code = (10000000 + Random().nextInt(90000000)).toString()
        val invite = JsonObject().apply {
            addProperty("familyId", current.uid)
            addProperty("createdAt", System.currentTimeMillis())
            addProperty("expiresAt", System.currentTimeMillis() + 15L * 60L * 1000L)
        }
        io.execute {
            try {
                api.put("pairingCodes/" + code, invite, current)
                runOnUiThread {
                    pairingCode = code
                    statusMessage = "Pairing code created. It expires in 15 minutes."
                    if (activeTab == "Overview") renderTab()
                    else if (::pairingDisplay.isInitialized) pairingDisplay.text = code
                }
            } catch (e: Exception) {
                runOnUiThread { statusMessage = "Pairing failed: " + (e.message ?: "Firebase error"); renderTab() }
            }
        }
    }

    private fun refreshDevices(silent: Boolean) {
        val current = session ?: return
        if (!silent) {
            statusMessage = "Fetching child-device reports…"
            headerStatus.text = "REFRESHING"
        }
        io.execute {
            try {
                val base = "families/" + current.uid + "/devices"
                val response = api.get(base, current)
                val loaded = mutableListOf<GuardianDeviceReport>()
                if (response != null && response.isJsonObject) {
                    response.asJsonObject.entrySet().forEach { (id, element) ->
                        if (!element.isJsonObject) return@forEach
                        val device = element.asJsonObject
                        val path = base + "/" + id
                        val state = api.get(path + "/status", current).asObjectOrNull()
                        val today = parseUsage(api.get(path + "/usage/today", current))
                        val week = parseUsage(api.get(path + "/usage/week", current))
                        val month = parseUsage(api.get(path + "/usage/month", current))
                        val installed = parseApps(api.get(path + "/apps/installed", current), today)
                        val visits = parseVisits(api.get(path + "/browserHistory", current))
                        val recent = parseRecent(api.get(path + "/recentActivities", current))
                        loaded.add(
                            GuardianDeviceReport(
                                id = id,
                                name = device.stringValue("deviceName", "Child Android"),
                                manufacturer = device.stringValue("manufacturer", "Android"),
                                androidVersion = device.stringValue("androidVersion", state?.stringValue("androidVersion", "unknown") ?: "unknown"),
                                lastSeen = state?.longValue("lastSeen", device.longValue("lastSeen", 0L)) ?: device.longValue("lastSeen", 0L),
                                usageAccess = state?.booleanValue("usageAccessGranted", false) ?: false,
                                deviceOwner = state?.booleanValue("deviceOwner", false) ?: false,
                                lastForegroundPackage = state?.stringValue("lastForegroundPackage", "") ?: "",
                                lastForegroundAt = state?.longValue("lastForegroundAt", 0L) ?: 0L,
                                today = today,
                                week = week,
                                month = month,
                                apps = installed,
                                visits = visits,
                                recent = recent
                            )
                        )
                    }
                }
                val loadedPolicy = api.get("families/" + current.uid + "/policies", current).asObjectOrNull() ?: JsonObject()
                runOnUiThread {
                    devices = loaded.sortedByDescending { it.lastSeen }
                    policy = loadedPolicy
                    lastRefreshAt = System.currentTimeMillis()
                    statusMessage = if (devices.isEmpty()) "No child device paired yet." else "Reports refreshed."
                    headerStatus.text = if (devices.isEmpty()) "WAITING FOR DEVICE" else "REPORTS UPDATED"
                    if (activeTab != "Settings") renderTab()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusMessage = "Refresh failed: " + (e.message ?: "network error")
                    headerStatus.text = "SYNC ISSUE"
                    if (!silent) renderTab()
                }
            }
        }
    }

    private fun publishPolicy(next: JsonObject) {
        val current = session ?: return
        statusMessage = "Publishing family rules…"
        io.execute {
            try {
                next.addProperty("updatedAt", System.currentTimeMillis())
                api.put("families/" + current.uid + "/policies", next, current)
                runOnUiThread {
                    policy = next
                    statusMessage = "Family policy published. Child applies it on the next successful sync."
                    renderTab()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusMessage = "Policy publish failed: " + (e.message ?: "Firebase error")
                    renderTab()
                }
            }
        }
    }

    private fun toggleBlocked(packageName: String) {
        val blocked = stringSet(policy.get("blockedPackages")).toMutableSet()
        if (!blocked.add(packageName)) blocked.remove(packageName)
        val next = policy.deepCopyObject().apply {
            add("blockedPackages", JsonArray().apply { blocked.sorted().forEach(::add) })
        }
        publishPolicy(next)
    }

    private fun showLimitDialog(app: GuardianAppRow) {
        val limits = policy.getAsJsonObject("appTimeLimitsMinutes") ?: JsonObject()
        val current = try { limits.get(app.packageName)?.asInt?.toString().orEmpty() } catch (_: Exception) { "" }
        val field = EditText(this).apply {
            hint = "Minutes per day · 0 removes limit"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Daily limit · " + app.name)
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                val minutes = field.text.toString().toIntOrNull() ?: 0
                val nextLimits = (policy.getAsJsonObject("appTimeLimitsMinutes") ?: JsonObject()).deepCopyObject()
                if (minutes <= 0) nextLimits.remove(app.packageName) else nextLimits.addProperty(app.packageName, minutes)
                val next = policy.deepCopyObject().apply { add("appTimeLimitsMinutes", nextLimits) }
                publishPolicy(next)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7310)
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
                        updateStatusView.text = result.message
                        installUpdateButton.visibility = if (result.updateAvailable) View.VISIBLE else View.GONE
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
        updateStatusView.text = "Downloading " + release.assetName + "…"
        io.execute {
            try {
                val file = updater.download(release)
                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    updateStatusView.text = "Download complete. Opening Android installer…"
                    installDownloadedApk(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    updateStatusView.text = "Download failed: " + (e.message ?: "unknown error")
                }
            }
        }
    }

    private fun installDownloadedApk(file: File) {
        if (!file.isFile) {
            updateStatusView.text = "Downloaded APK is no longer available. Download again."
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = file
            updateStatusView.text = "Allow Guardian Parent to install updates, then return here."
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

    private fun parseUsage(element: JsonElement?): List<GuardianUsageBar> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val packageName = obj.stringValue("packageName", "")
            GuardianUsageBar(
                obj.stringValue("appName", packageName),
                packageName,
                obj.longValue("foregroundMs", 0L),
                obj.longValue("lastTimeUsed", 0L)
            )
        }.sortedByDescending { it.foregroundMs }
    }

    private fun parseApps(element: JsonElement?, usage: List<GuardianUsageBar>): List<GuardianAppRow> {
        if (element == null || !element.isJsonArray) return emptyList()
        val usageMap = usage.associateBy { it.packageName }
        return element.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val packageName = obj.stringValue("packageName", "")
            val used = usageMap[packageName]
            GuardianAppRow(
                obj.stringValue("appName", packageName),
                packageName,
                obj.booleanValue("systemApp", false),
                obj.booleanValue("enabled", true),
                used?.foregroundMs ?: 0L,
                used?.lastUsed ?: 0L
            )
        }.sortedByDescending { it.foregroundMs }
    }

    private fun parseVisits(element: JsonElement?): List<GuardianBrowserVisit> {
        if (element == null || !element.isJsonObject) return emptyList()
        return element.asJsonObject.entrySet().mapNotNull { entry ->
            if (!entry.value.isJsonObject) return@mapNotNull null
            val obj = entry.value.asJsonObject
            GuardianBrowserVisit(
                obj.stringValue("url", ""),
                obj.stringValue("title", ""),
                obj.longValue("startedAt", 0L),
                obj.longValue("durationMs", 0L),
                obj.stringValue("event", "") == "blocked_attempt"
            )
        }.sortedByDescending { it.startedAt }
    }

    private fun parseRecent(element: JsonElement?): List<GuardianRecentEvent> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            GuardianRecentEvent(
                obj.stringValue("appName", obj.stringValue("packageName", "App")),
                obj.stringValue("packageName", ""),
                obj.longValue("timestamp", 0L)
            )
        }.sortedByDescending { it.timestamp }
    }

    private fun findAppName(device: GuardianDeviceReport, packageName: String): String =
        device.apps.firstOrNull { it.packageName == packageName }?.name
            ?: device.today.firstOrNull { it.packageName == packageName }?.name
            ?: packageName.ifBlank { "No app event yet" }

    private fun appName(packageName: String): String {
        val device = devices.firstOrNull() ?: return packageName
        return findAppName(device, packageName)
    }

    private fun activityRow(parent: LinearLayout, title: String, subtitle: String, time: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        addMonogram(row, title)
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        label(detail, title, 13, COLOR_TEXT, true)
        label(detail, subtitle, 10, COLOR_MUTED)
        row.addView(detail, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        label(row, time, 10, COLOR_MUTED)
        parent.addView(row)
        divider(parent)
    }

    private fun pairingCodeView(parent: LinearLayout) {
        label(parent, if (pairingCode.isBlank()) "Not generated" else pairingCode, 25, COLOR_TEXT, true)
    }

    private fun pageHeading(title: String, subtitle: String) {
        label(page, title, 24, COLOR_TEXT, true)
        label(page, subtitle, 12, COLOR_MUTED)
    }

    private fun sectionCard(title: String, subtitle: String? = null): LinearLayout = sectionCard(page, title, subtitle)

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

    private fun metricCard(parent: LinearLayout, title: String, value: String, caption: String) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = COLOR_STROKE
            setCardBackgroundColor(COLOR_CARD_ALT)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(13), dp(11), dp(13))
        }
        label(body, title, 9, COLOR_MUTED, true)
        label(body, value, 17, COLOR_TEXT, true)
        label(body, caption, 10, COLOR_MUTED)
        card.addView(body)
        parent.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(5); leftMargin = dp(5)
        })
    }

    private fun input(parent: LinearLayout, hint: String, type: Int, initial: String = "", multiline: Boolean = false): EditText {
        return EditText(this).apply {
            this.hint = hint
            inputType = type
            setText(initial)
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            textSize = 13f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(COLOR_FIELD, 13)
            if (multiline) {
                minLines = 2
                maxLines = 6
                gravity = Gravity.TOP
            }
            parent.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(6) })
        }
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

    private fun actionButton(parent: LinearLayout, title: String, secondary: Boolean, action: () -> Unit) {
        val button = makeButton(title, secondary, action)
        parent.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(3) })
    }

    private fun actionButton(parent: LinearLayout, title: String, action: () -> Unit) =
        actionButton(parent, title, false, action)

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
        val pill = TextView(this).apply {
            text = value
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tint)
            background = rounded(COLOR_CARD_ALT, 25)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        parent.addView(pill, wrap())
    }

    private fun addMonogram(parent: LinearLayout, name: String) {
        val icon = TextView(this).apply {
            text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_ACCENT)
            background = rounded(COLOR_ACCENT_DARK, 14)
        }
        parent.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
    }

    private fun divider(parent: LinearLayout = page) {
        parent.addView(View(this).apply { setBackgroundColor(COLOR_STROKE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(5); bottomMargin = dp(5)
            })
    }

    private fun toJsonArray(value: String): JsonArray = JsonArray().apply {
        value.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().forEach(::add)
    }

    private fun arrayText(value: JsonElement?): String =
        if (value != null && value.isJsonArray) value.asJsonArray.joinToString("\n") { it.asString } else ""

    private fun limitsText(): String {
        val value = policy.getAsJsonObject("appTimeLimitsMinutes") ?: return ""
        return value.entrySet().joinToString("\n") { it.key + "=" + it.value.asInt }
    }

    private fun stringSet(value: JsonElement?): Set<String> =
        if (value != null && value.isJsonArray) value.asJsonArray.mapNotNull {
            try { it.asString } catch (_: Exception) { null }
        }.toSet() else emptySet()

    private fun totalMs(value: List<GuardianUsageBar>): Long = value.sumOf { it.foregroundMs }

    private fun reportSummary(parent: LinearLayout, total: Long, appCount: Int) {
        label(parent, formatDuration(total), 22, COLOR_TEXT, true)
        label(parent, appCount.toString() + " apps with foreground activity", 10, COLOR_MUTED)
    }

    private fun hostFromUrl(url: String): String = try {
        Uri.parse(url).host ?: url
    } catch (_: Exception) { url }

    private fun formatDuration(milliseconds: Long): String {
        val minutes = max(0L, milliseconds) / 60_000L
        val hours = minutes / 60L
        val remaining = minutes % 60L
        return if (hours > 0L) hours.toString() + "h " + remaining.toString() + "m" else remaining.toString() + "m"
    }

    private fun formatDate(time: Long): String =
        if (time <= 0L) "Unknown" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        if (this != null && isJsonObject) asJsonObject else null

    private fun JsonObject.stringValue(key: String, fallback: String = ""): String =
        try { get(key)?.takeIf { !it.isJsonNull }?.asString ?: fallback } catch (_: Exception) { fallback }

    private fun JsonObject.longValue(key: String, fallback: Long = 0L): Long =
        try { get(key)?.takeIf { !it.isJsonNull }?.asLong ?: fallback } catch (_: Exception) { fallback }

    private fun JsonObject.booleanValue(key: String, fallback: Boolean = false): Boolean =
        try { get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: fallback } catch (_: Exception) { fallback }

    private fun JsonObject.deepCopyObject(): JsonObject =
        com.google.gson.JsonParser.parseString(toString()).asJsonObject

    override fun onDestroy() {
        refreshHandler.removeCallbacks(autoRefresh)
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_OPEN_UPDATES = "com.sirrlevi.guardian.parent.OPEN_APP_UPDATES"
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
        private const val COLOR_AMBER = 0xFFFFC16B.toInt()
    }
}

private class GuardianUsageChartView(
    context: android.content.Context,
    private val entries: List<GuardianUsageBar>
) : View(context) {
    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3F7FD.toInt()
        textSize = 12f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF92A7C0.toInt()
        textSize = 10f * density
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263D58.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF62D7C5.toInt() }
    private val maxValue = entries.maxOfOrNull { it.foregroundMs }?.coerceAtLeast(1L) ?: 1L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (entries.size * 58f * density + 10f * density).toInt()
        setMeasuredDimension(
            resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 2f * density
        val right = width - 4f * density
        val trackWidth = right - left
        entries.forEachIndexed { index, entry ->
            val top = 8f * density + index * 58f * density
            val label = if (entry.name.length > 25) entry.name.take(23) + "…" else entry.name
            canvas.drawText(label, left, top + 12f * density, textPaint)
            val duration = durationText(entry.foregroundMs)
            detailPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(duration, right, top + 12f * density, detailPaint)
            detailPaint.textAlign = Paint.Align.LEFT
            val track = RectF(left, top + 23f * density, right, top + 34f * density)
            canvas.drawRoundRect(track, 7f * density, 7f * density, trackPaint)
            val barRight = left + trackWidth * (entry.foregroundMs.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
            if (barRight > left) {
                canvas.drawRoundRect(RectF(left, top + 23f * density, barRight, top + 34f * density),
                    7f * density, 7f * density, barPaint)
            }
            canvas.drawText(entry.packageName, left, top + 49f * density, detailPaint)
        }
    }

    private fun durationText(ms: Long): String {
        val mins = ms.coerceAtLeast(0L) / 60_000L
        return if (mins >= 60L) (mins / 60L).toString() + "h " + (mins % 60L).toString() + "m" else mins.toString() + "m"
    }
}
