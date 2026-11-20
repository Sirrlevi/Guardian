package com.sirrlevi.guardian.child

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.GuardianApi
import com.sirrlevi.guardian.core.GuardianSession
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChildActivity : AppCompatActivity() {
    private val api by lazy { GuardianApi(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_DATABASE_URL) }
    private val vault by lazy { SessionVault(this) }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var page: LinearLayout
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var pairingCode: EditText
    private lateinit var status: TextView
    private var session: GuardianSession? = null
    private val prefs by lazy { getSharedPreferences("guardian_child", MODE_PRIVATE) }
    private val familyId: String get() = prefs.getString("family_id", "").orEmpty()

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
        session = vault.load()
        showAuth()
        if (session != null && familyId.isNotBlank()) showControls()
    }

    private fun showAuth() {
        page.removeAllViews()
        heading("GUARDIAN CHILD", 28)
        text("This app reports usage and applies family-approved controls. Monitoring status remains visible on this device.")
        email = field("Child email")
        password = field("Child account password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pairingCode = field("8-digit family pairing code", InputType.TYPE_CLASS_NUMBER)
        button("Create child account and pair") { authenticate(true) }
        button("Sign in to child account and pair") { authenticate(false) }
        status = text("Pairing requires a valid code generated in Guardian Parent.")
        text("Firebase project configuration is required. The parent and child use separate accounts.")
    }

    private fun authenticate(create: Boolean) {
        val e = email.text.toString().trim()
        val p = password.text.toString()
        val code = pairingCode.text.toString().trim()
        if (e.isBlank() || p.length < 6 || code.length != 8) {
            status.text = "Enter email, password (6+ characters) and the full 8-digit code."
            return
        }
        status.text = "Checking account and pairing code…"
        task({
            val auth = if (create) api.signUp(e, p) else api.signIn(e, p)
            val invite = api.get("pairingCodes/$code", auth)?.asJsonObject
                ?: throw IllegalStateException("Pairing code not found or already unavailable.")
            val expiry = invite.get("expiresAt")?.asLong ?: 0L
            val family = invite.get("familyId")?.asString.orEmpty()
            if (family.isBlank() || expiry < System.currentTimeMillis()) {
                throw IllegalStateException("Pairing code has expired. Ask the parent to generate a new one.")
            }
            val member = JsonObject().apply {
                addProperty("joinedAt", System.currentTimeMillis())
                addProperty("role", "child")
                addProperty("deviceName", android.os.Build.MODEL ?: "Android device")
            }
            api.put("families/$family/members/${auth.uid}", member, auth)
            val device = JsonObject().apply {
                addProperty("deviceName", android.os.Build.MODEL ?: "Android device")
                addProperty("manufacturer", android.os.Build.MANUFACTURER ?: "Android")
                addProperty("androidVersion", android.os.Build.VERSION.RELEASE ?: "unknown")
                addProperty("joinedAt", System.currentTimeMillis())
                addProperty("lastSeen", System.currentTimeMillis())
                addProperty("monitoringDisclosure", "visible")
            }
            api.put("families/$family/devices/${auth.uid}", device, auth)
            PairingResult(auth, family)
        }) { result ->
            session = result.session
            vault.save(result.session)
            prefs.edit().putString("family_id", result.familyId).apply()
            showControls()
            scheduleSync()
        }
    }

    private data class PairingResult(val session: GuardianSession, val familyId: String)

    private fun showControls() {
        page.removeAllViews()
        heading("GUARDIAN CHILD", 28)
        text("Paired and active · Family ${familyId.take(8)}")
        text("This device's usage and Guardian Browser history can be shared with the paired parent account.")
        button("Grant usage access") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        button("Enable domain filtering VPN") { requestVpn() }
        button("Open Guardian Browser") { startActivity(Intent(this, GuardianBrowserActivity::class.java)) }
        button("Sync usage and parent policy now") {
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<ChildSyncWorker>().build())
            status.text = "Sync requested. It may take a short time to finish."
        }
        button("Device Owner setup information") { showDeviceOwnerInfo() }
        button("Sign out and unpair this installation") {
            vault.clear()
            prefs.edit().remove("family_id").apply()
            session = null
            showAuth()
        }
        divider()
        status = text("Protection services are permission-gated. Android's VPN indicator appears while domain filtering is running.")
        text("Automatic policy and usage sync runs about every 15 minutes when Android allows background work.")
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQUEST_VPN) else startVpnService()
    }

    private fun startVpnService() {
        ContextCompat.startForegroundService(this, Intent(this, DomainFilterVpnService::class.java))
        status.text = "Domain-filtering VPN started. Android will show its VPN indicator and Guardian notification."
    }

    @Deprecated("Used for Android VPN consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == Activity.RESULT_OK) startVpnService()
            else status.text = "VPN permission was not granted. Domain filtering remains off."
        }
    }

    private fun scheduleSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<ChildSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "guardian-child-sync", ExistingPeriodicWorkPolicy.UPDATE, periodic
        )
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<ChildSyncWorker>().setConstraints(constraints).build())
    }

    private fun showDeviceOwnerInfo() {
        AlertDialog.Builder(this)
            .setTitle("Fully managed device required")
            .setMessage(
                "Strong app blocking and daily app limits require Guardian Child to be provisioned as Device Owner. " +
                    "Android normally permits this only on a fresh/unprovisioned device. This is not granted by installing the app. " +
                    "For a test device you control, the documented ADB command is shown in project docs."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun task(work: () -> Any, done: (PairingResult) -> Unit) {
        io.execute {
            try {
                val result = work() as PairingResult
                runOnUiThread { done(result) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error: ${e.message ?: "pairing failed"}" }
            }
        }
    }

    private fun heading(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(android.graphics.Color.WHITE)
        setPadding(0, dp(16), 0, dp(8))
        page.addView(this, matchWrap())
    }

    private fun text(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(android.graphics.Color.rgb(197, 207, 224))
        setPadding(0, dp(6), 0, dp(8))
        page.addView(this, matchWrap())
    }

    private fun field(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        hint = hintText
        inputType = type
        setTextColor(android.graphics.Color.WHITE)
        setHintTextColor(android.graphics.Color.GRAY)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(android.graphics.Color.rgb(29, 39, 57))
        page.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
    }

    private fun button(label: String, action: () -> Unit) {
        page.addView(MaterialButton(this).apply {
            text = label
            setOnClickListener { action() }
        }, matchWrap().apply { bottomMargin = dp(8) })
    }

    private fun divider() {
        page.addView(View(this).apply { setBackgroundColor(android.graphics.Color.rgb(55, 69, 91)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(10) })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    companion object { private const val REQUEST_VPN = 4107 }
}
