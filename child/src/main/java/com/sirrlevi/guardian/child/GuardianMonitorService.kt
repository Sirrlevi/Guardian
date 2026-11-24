package com.sirrlevi.guardian.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.BlockReason
import com.sirrlevi.guardian.core.DayClock
import com.sirrlevi.guardian.core.Enforcement
import com.sirrlevi.guardian.core.FamilyPolicy
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.PkgKey
import com.sirrlevi.guardian.core.PolicyCodec
import com.sirrlevi.guardian.core.ScreenEvent
import com.sirrlevi.guardian.core.ScreenEventCodec
import com.sirrlevi.guardian.core.ScreenKind
import com.sirrlevi.guardian.core.Session
import com.sirrlevi.guardian.core.UsageSessionTracker
import com.sirrlevi.guardian.core.GuardianApi
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The always-on heart of Guardian Child. While it runs it:
 *  - watches which app is open (Usage Access) and counts per-app time,
 *  - listens to the parent's rules in real time and applies them within about a second,
 *  - shows the blocked screen on top of an app that is blocked or over its limit,
 *  - streams the current app, timeline, hourly totals and device status to the parent,
 *  - tells the parent when a rule has really been applied (the "ack").
 * It runs as a visible foreground service with a permanent notification.
 */
class GuardianMonitorService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val loop = Executors.newSingleThreadScheduledExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val outbox = Outbox()
    private val gson = Gson()

    private lateinit var cloud: ChildCloud
    private lateinit var prefs: SharedPreferences
    private lateinit var usage: UsageStatsManager
    private lateinit var overlay: BlockOverlay
    private lateinit var tracker: UsageSessionTracker

    @Volatile private var alive = false
    @Volatile private var policy = FamilyPolicy()
    @Volatile private var exempt: Set<String> = emptySet()
    @Volatile private var streamCall: GuardianApi.StreamHandle? = null
    private var streamThread: Thread? = null

    private var dayClock = DayClock.of(System.currentTimeMillis())
    private var lastEventTs = 0L
    private var replaying = false
    private val replayedScreen = ArrayList<Pair<String, JsonObject>>()
    private var blockedPackage: String? = null
    private var overlayKey: String? = null
    private var overlayFailed = false
    private var lastLiveKey = "init"
    private var lastLiveAt = 0L
    private var lastAggregateAt = 0L
    private var lastAggregateHash = 0
    private var lastInfoAt = 0L
    private var lastExemptAt = 0L
    private var lastDoApplyAt = 0L
    private var lastAckedPolicy = -1L
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mediaObserver: android.database.ContentObserver? = null
    private val mediaRun = Runnable { MediaBackupScheduler.runNow(applicationContext) }

    /** Notices new photos and videos so the parent gets them within minutes, but only while backup is switched on. */
    private fun registerMediaObserver() {
        if (mediaObserver != null) return
        val observer = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                if (!MediaBackupState(applicationContext).lastKnownEnabled()) return
                mainHandler.removeCallbacks(mediaRun)
                mainHandler.postDelayed(mediaRun, 20_000L)
            }
        }
        try {
            contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            mediaObserver = observer
        } catch (e: Exception) {
            Log.w(TAG, "media observer not registered", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (alive) return START_STICKY
        cloud = ChildCloud(this)
        if (!cloud.isPaired()) {
            stopSelf()
            return START_NOT_STICKY
        }
        alive = true
        running = true
        prefs = getSharedPreferences("guardian_child", Context.MODE_PRIVATE)
        usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        overlay = BlockOverlay(this) { pkg, name, reason -> requestMoreTime(pkg, name, reason) }
        policy = try {
            PolicyCodec.parse(parseObject(prefs.getString("policy_json", null)))
        } catch (_: Exception) { FamilyPolicy() }
        refreshExempt()
        dayClock = DayClock.of(System.currentTimeMillis())
        tracker = newTracker()
        replayToday()
        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        loop.scheduleWithFixedDelay({ tick() }, 0L, 1000L, TimeUnit.MILLISECONDS)
        startPolicyStream()
        registerMediaObserver()
        // Lets the parent's screen-sharing requests reach this phone while the monitor runs. Nothing is captured without the child's OK.
        CaptureRuntime.retain(this)
        net.execute {
            refreshPolicy()
            syncCatalog(force = false)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (alive) CaptureRuntime.release()
        alive = false
        running = false
        try { streamCall?.cancel() } catch (_: Exception) { }
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) { }
        mediaObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) { } }
        mediaObserver = null
        mainHandler.removeCallbacks(mediaRun)
        loop.shutdownNow()
        net.shutdown()
        main.post { if (::overlay.isInitialized) overlay.hide() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- loop

    private fun tick() {
        if (!alive) return
        try {
            val now = System.currentTimeMillis()
            val clock = DayClock.of(now)
            if (clock.dayKey != dayClock.dayKey) rolloverDay(clock)
            pollEvents(now)
            val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            if (!interactive) {
                tracker.onScreenOff(now)
                blockedPackage = null
            }
            if (now - lastExemptAt > 5 * 60_000L) refreshExempt()
            evaluate(now, interactive)
            pushPeriodic(now, interactive)
            if (outbox.isNotEmpty()) net.execute { drainOutbox() }
        } catch (e: SecurityException) {
            Log.w(TAG, "Usage access missing", e)
        } catch (e: Exception) {
            Log.w(TAG, "tick failed", e)
        }
    }

    private fun newTracker(): UsageSessionTracker =
        UsageSessionTracker(dayClock.dayStartMs, launcherPackages() + setOf(packageName, "android", "com.android.systemui")).also { t ->
            t.onSessionClosed = { s -> if (!replaying) enqueueSession(s) }
        }

    private fun replayToday() {
        replaying = true
        replayedScreen.clear()
        try {
            tracker.reset(dayClock.dayStartMs)
            lastEventTs = dayClock.dayStartMs
            pollEvents(System.currentTimeMillis())
        } finally {
            replaying = false
        }
        val batch = JsonObject()
        tracker.sessions.filter { it.durationMs >= MIN_SESSION_MS }.takeLast(400).forEach { batch.add(it.startMs.toString(), sessionJson(it)) }
        if (batch.size() > 0) outbox.putPatch("sessions/" + dayClock.dayKey, batch)
        val screenBatch = JsonObject()
        replayedScreen.takeLast(400).forEach { (key, json) -> screenBatch.add(key, json) }
        replayedScreen.clear()
        if (screenBatch.size() > 0) outbox.putPatch("screen/" + dayClock.dayKey, screenBatch)
    }

    /** Screen on/off and lock/unlock moments, as Android's usage log reports them. */
    private fun recordScreen(kind: ScreenKind, ts: Long) {
        val key = ts.toString() + "_" + kind.code
        val json = ScreenEventCodec.toJson(ScreenEvent(ts, kind))
        if (replaying) replayedScreen.add(key to json)
        else outbox.putOrdered("screen/" + dayKeyOf(ts) + "/" + key, json)
    }

    private fun pollEvents(now: Long) {
        val events = usage.queryEvents(lastEventTs, now + 1) ?: return
        val event = UsageEvents.Event()
        var newest = lastEventTs - 1
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            if (ts > newest) newest = ts
            val pkg = event.packageName
            ScreenKind.fromUsageEvent(event.eventType)?.let { recordScreen(it, ts) }
            when (event.eventType) {
                EVENT_RESUMED -> if (pkg != null) {
                    if (pkg != blockedPackage) blockedPackage = null
                    tracker.onResumed(pkg, ts)
                }
                EVENT_PAUSED -> if (pkg != null) {
                    if (pkg == blockedPackage) blockedPackage = null
                    tracker.onPaused(pkg, ts)
                }
                EVENT_SCREEN_OFF, EVENT_KEYGUARD_SHOWN -> {
                    blockedPackage = null
                    tracker.onScreenOff(ts)
                }
            }
        }
        if (newest >= lastEventTs) lastEventTs = newest + 1
    }

    private fun rolloverDay(clock: DayClock) {
        val open = tracker.currentPackage
        if (open != null) tracker.stopCounting(clock.dayStartMs)
        pushAggregates(System.currentTimeMillis(), force = true)
        val old = dayClock
        tracker.reset(clock.dayStartMs)
        dayClock = clock
        lastEventTs = maxOf(lastEventTs, clock.dayStartMs)
        if (open != null) tracker.onResumed(open, clock.dayStartMs)
        net.execute { pruneOldDays(old) }
    }

    // ---------------------------------------------------------------- enforcement

    private fun evaluate(now: Long, interactive: Boolean) {
        val current = tracker.currentPackage ?: blockedPackage
        val p = policy
        val decision = if (current != null && interactive) {
            Enforcement.decide(current, p, tracker.usedSeconds(current, now), now, dayClock, exempt)
        } else null

        if (current != null && decision != null && decision.blocked) {
            // Time spent staring at the blocked screen must not count as use of that app.
            if (tracker.currentPackage == current) tracker.stopCounting(now)
            blockedPackage = current
            showOverlay(current, decision)
        } else {
            if (current != null && blockedPackage == current && tracker.currentPackage == null && interactive) {
                tracker.onResumed(current, now) // rule relaxed while the app is still open: count again
            }
            blockedPackage = null
            hideOverlay()
        }

        if (now - lastDoApplyAt > 5_000L) {
            lastDoApplyAt = now
            applyDeviceOwner(now, p)
        }

        val key = (current ?: "") + "|" + (decision?.blocked == true) + "|" + interactive
        if (key != lastLiveKey || now - lastLiveAt >= (if (current != null) 30_000L else 60_000L)) {
            lastLiveKey = key
            lastLiveAt = now
            outbox.putLatest("rt/live", JsonObject().apply {
                addProperty("p", current ?: "")
                addProperty("n", if (current != null) labelOf(current) else "")
                addProperty("since", if (tracker.currentPackage != null) tracker.currentSinceMs else now)
                addProperty("used", if (current != null) tracker.usedSeconds(current, now) else 0)
                addProperty("total", tracker.totalSeconds(now))
                addProperty("blocked", decision?.blocked == true)
                addProperty("reason", decision?.reason?.name ?: BlockReason.NONE.name)
                addProperty("screen", interactive)
                addProperty("ts", now)
            })
        }
    }

    private fun showOverlay(packageName: String, decision: com.sirrlevi.guardian.core.Decision) {
        val key = packageName + "|" + decision.reason.name
        if (key == overlayKey) return
        if (!ChildPermissions.hasOverlay(this)) {
            overlayFailed = true
            return
        }
        overlayFailed = false
        overlayKey = key
        val name = labelOf(packageName)
        main.post { overlay.show(packageName, name, decision) }
    }

    private fun hideOverlay() {
        if (overlayKey == null) return
        overlayKey = null
        main.post { overlay.hide() }
    }

    private fun applyDeviceOwner(now: Long, p: FamilyPolicy) {
        if (!ChildPermissions.isDeviceOwner(this)) return
        val wanted = HashSet<String>()
        p.appRules.keys.forEach { pkg ->
            if (Enforcement.decide(pkg, p, tracker.usedSeconds(pkg, now), now, dayClock, exempt).blocked) wanted.add(pkg)
        }
        try { DeviceOwnerEnforcer.apply(this, wanted) } catch (e: Exception) { Log.w(TAG, "suspend failed", e) }
    }

    // ---------------------------------------------------------------- uploads

    private fun pushPeriodic(now: Long, interactive: Boolean) {
        if (now - lastAggregateAt >= 60_000L) pushAggregates(now, force = false)
        if (now - lastInfoAt >= 60_000L) {
            lastInfoAt = now
            outbox.putLatest("info", DeviceInfoCollector.collect(this, interactive, monitorRunning = true))
        }
    }

    private fun pushAggregates(now: Long, force: Boolean) {
        lastAggregateAt = now
        val perApp = tracker.perAppSeconds(now)
        val total = tracker.totalSeconds(now)
        val hourly = tracker.hourlySeconds()
        val hash = total * 31 + hourly.contentHashCode() + perApp.size
        if (!force && hash == lastAggregateHash) return
        lastAggregateHash = hash
        val key = dayClock.dayKey
        outbox.putLatest("hourly/$key", JsonArray().also { array -> hourly.forEach { array.add(it) } })
        outbox.putLatest("daily/$key", JsonObject().apply {
            addProperty("total", total)
            addProperty("ts", now)
            add("apps", JsonObject().also { apps ->
                perApp.take(40).forEach { (pkg, sec) -> apps.addProperty(PkgKey.encode(pkg), sec) }
            })
        })
    }

    private fun enqueueSession(s: Session) {
        if (s.durationMs < MIN_SESSION_MS) return
        outbox.putOrdered("sessions/" + dayKeyOf(s.startMs) + "/" + s.startMs, sessionJson(s))
    }

    private fun sessionJson(s: Session) = JsonObject().apply {
        addProperty("p", s.packageName)
        addProperty("n", labelOf(s.packageName))
        addProperty("s", s.startMs)
        addProperty("e", s.endMs)
    }

    private fun dayKeyOf(ms: Long) = DayClock.of(ms).dayKey

    private fun drainOutbox() {
        try {
            val session = cloud.session() ?: return
            val base = GuardianPaths.device(cloud.familyId, session.uid)
            while (alive) {
                val item = outbox.next() ?: break
                try {
                    if (item.patch) cloud.api.patch("$base/${item.path}", item.value.asJsonObject, session)
                    else cloud.api.put("$base/${item.path}", item.value, session)
                } catch (e: Exception) {
                    outbox.requeue(item)
                    Log.w(TAG, "upload failed: ${item.path}", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "drain failed", e)
        }
    }

    private fun pruneOldDays(finished: DayClock) {
        try {
            val session = cloud.session() ?: return
            val base = GuardianPaths.device(cloud.familyId, session.uid)
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = finished.dayStartMs }
            cal.add(java.util.Calendar.DAY_OF_YEAR, -14)
            val old = DayClock.of(cal.timeInMillis).dayKey
            cloud.api.delete("$base/sessions/$old", session)
            cloud.api.delete("$base/hourly/$old", session)
            cloud.api.delete("$base/screen/$old", session)
            cal.add(java.util.Calendar.DAY_OF_YEAR, -76)
            cloud.api.delete("$base/daily/" + DayClock.of(cal.timeInMillis).dayKey, session)
        } catch (e: Exception) {
            Log.w(TAG, "prune failed", e)
        }
    }

    private fun syncCatalog(force: Boolean) {
        try { AppCatalog.sync(this, cloud, force) } catch (e: Exception) { Log.w(TAG, "catalog sync failed", e) }
    }

    private fun requestMoreTime(packageName: String, appName: String, reason: BlockReason) {
        val now = System.currentTimeMillis()
        outbox.putOrdered("requests/$now", JsonObject().apply {
            addProperty("p", packageName)
            addProperty("n", appName)
            addProperty("reason", reason.name)
            addProperty("ts", now)
            addProperty("status", "pending")
        })
        net.execute { drainOutbox() }
    }

    // ---------------------------------------------------------------- policy stream

    private fun startPolicyStream() {
        streamThread = Thread({
            var backoff = 2_000L
            while (alive) {
                try {
                    val session = cloud.session()
                    val family = cloud.familyId
                    if (session == null || family.isBlank()) {
                        Thread.sleep(10_000L)
                        continue
                    }
                    val handle = cloud.api.openStream(GuardianPaths.policy(family), session)
                    streamCall = handle
                    cloud.api.consumeStream(handle) { event ->
                        if (event == "put" || event == "patch") net.execute { refreshPolicy() }
                    }
                    backoff = 2_000L
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!alive) break
                    Log.w(TAG, "policy stream closed", e)
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }, "guardian-policy-stream").apply { isDaemon = true; start() }
    }

    private fun refreshPolicy() {
        try {
            val session = cloud.session() ?: return
            val family = cloud.familyId
            val element = cloud.api.get(GuardianPaths.policy(family), session)
            val json = element?.takeIf { it.isJsonObject }?.asJsonObject
            val parsed = PolicyCodec.parse(json)
            policy = parsed
            prefs.edit()
                .putString("policy_json", json?.toString() ?: "{}")
                .putString("blocked_domains", gson.toJson(parsed.blockedDomains))
                .putString("blocked_urls", gson.toJson(parsed.blockedUrls))
                .apply()
            loop.execute { if (alive) { lastDoApplyAt = 0L; tick(); sendAck(parsed.updatedAt) } }
        } catch (e: Exception) {
            Log.w(TAG, "policy refresh failed", e)
        }
    }

    /** Tells the parent which version of the rules is now being enforced on this phone. */
    private fun sendAck(policyUpdatedAt: Long) {
        lastAckedPolicy = policyUpdatedAt
        val owner = ChildPermissions.isDeviceOwner(this)
        val overlayOk = ChildPermissions.hasOverlay(this)
        val usageOk = ChildPermissions.hasUsageAccess(this)
        val missing = JsonArray()
        if (!usageOk) missing.add("usage")
        if (!overlayOk && !owner) missing.add("overlay")
        outbox.putLatest("rt/ack", JsonObject().apply {
            addProperty("policyUpdatedAt", policyUpdatedAt)
            addProperty("appliedAt", System.currentTimeMillis())
            addProperty("mode", if (owner) "device-owner" else if (overlayOk) "overlay" else "none")
            addProperty("enforcing", usageOk && (overlayOk || owner))
            add("missing", missing)
        })
        net.execute { drainOutbox() }
    }

    // ---------------------------------------------------------------- helpers

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            labels.clear()
            refreshExempt()
            net.execute { syncCatalog(force = false) }
        }
    }

    private fun labelOf(packageName: String): String = labels.getOrPut(packageName) {
        try {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) { packageName }
    }

    private fun launcherPackages(): Set<String> = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun refreshExempt() {
        lastExemptAt = System.currentTimeMillis()
        val set = HashSet<String>()
        set.add(packageName)
        set.addAll(listOf(
            "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
            "com.android.emergency", "com.android.permissioncontroller", "com.google.android.permissioncontroller"
        ))
        set.addAll(launcherPackages())
        try {
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { set.add(it) }
        } catch (_: Exception) { }
        exempt = set
    }

    private fun parseObject(raw: String?): JsonObject? = try {
        if (raw.isNullOrBlank()) null else com.google.gson.JsonParser.parseString(raw).asJsonObject
    } catch (_: Exception) { null }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Guardian protection", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows that family screen-time monitoring is active on this phone." }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ChildActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian protection is on")
            .setContentText("Your family can see app usage and apply screen-time limits on this phone.")
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** Pending uploads. "Latest" items replace older ones for the same path; ordered items keep their order. */
    private class Outbox {
        class Item(val path: String, val value: JsonElement, val patch: Boolean = false)

        private val latest = LinkedHashMap<String, Item>()
        private val ordered = ArrayDeque<Item>()

        @Synchronized fun putLatest(path: String, value: JsonElement) { latest.remove(path); latest[path] = Item(path, value) }
        @Synchronized fun putPatch(path: String, value: JsonObject) { ordered.addLast(Item(path, value, true)) }
        @Synchronized fun putOrdered(path: String, value: JsonElement) {
            if (ordered.size >= 400) ordered.removeFirst()
            ordered.addLast(Item(path, value))
        }
        @Synchronized fun requeue(item: Item) {
            if (item.patch || item.path.startsWith("sessions/") || item.path.startsWith("screen/") || item.path.startsWith("requests/")) ordered.addFirst(item)
            else if (!latest.containsKey(item.path)) latest[item.path] = item
        }
        @Synchronized fun isNotEmpty() = latest.isNotEmpty() || ordered.isNotEmpty()
        @Synchronized fun next(): Item? {
            val first = latest.entries.iterator()
            if (first.hasNext()) { val e = first.next(); first.remove(); return e.value }
            return ordered.removeFirstOrNull()
        }
    }

    companion object {
        private const val TAG = "GuardianMonitor"
        private const val CHANNEL_ID = "guardian_monitor"
        private const val NOTIFICATION_ID = 4107
        private const val MIN_SESSION_MS = 3_000L
        private const val EVENT_RESUMED = 1
        private const val EVENT_PAUSED = 2
        private const val EVENT_SCREEN_OFF = 16
        private const val EVENT_KEYGUARD_SHOWN = 17

        @Volatile var running = false
            private set

        /** Starts the monitor when this phone is paired and Usage Access is granted. Safe to call repeatedly. */
        fun startIfReady(context: Context) {
            try {
                val app = context.applicationContext
                if (running) return
                if (!ChildCloud(app).isPaired()) return
                if (!ChildPermissions.hasUsageAccess(app)) return
                ContextCompat.startForegroundService(app, Intent(app, GuardianMonitorService::class.java))
            } catch (e: Throwable) {
                Log.w(TAG, "could not start monitor", e)
            }
        }
    }
}
