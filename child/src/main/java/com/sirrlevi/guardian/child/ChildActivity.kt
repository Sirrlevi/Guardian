package com.sirrlevi.guardian.child

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sirrlevi.guardian.child.ui.ChildActions
import com.sirrlevi.guardian.child.ui.ChildApp
import com.sirrlevi.guardian.child.ui.ChildTab
import com.sirrlevi.guardian.child.ui.ChildTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Hosts the Child's Compose UI. Android-only chores (permission screens, VPN consent, installer) live here. */
class ChildActivity : ComponentActivity() {
    private val vm: ChildViewModel by viewModels()
    private var requestedTab by mutableStateOf<ChildTab?>(null)
    private var pendingInstall: File? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshLocal() }

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService()
            else vm.message = "Website filter permission wasn't allowed, so it stays off."
            vm.refreshLocal()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) routeIntent(intent)
        GuardianUpdateScheduler.schedule(this)
        if (vm.phase == ChildViewModel.Phase.Paired) vm.scheduleSync()

        // Keep the screen honest while it is visible: permissions and today's numbers refresh by themselves.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.refreshLocal()
                    vm.loadToday()
                    delay(20_000L)
                }
            }
        }

        val actions = ChildActions(
            grant = { id -> grantPermission(id) },
            openBrowser = { startActivity(Intent(this, GuardianBrowserActivity::class.java)) },
            startWebsiteFilter = { requestVpn() },
            install = { file -> install(file) }
        )
        setContent {
            val dark = isSystemInDarkTheme()
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                )
                onDispose { }
            }
            ChildTheme {
                ChildApp(vm, requestedTab, { requestedTab = null }, actions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (vm.phase == ChildViewModel.Phase.Paired) {
            GuardianMonitorService.startIfReady(this)
            vm.refreshLocal()
        }
        val file = pendingInstall
        if (file != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
            pendingInstall = null
            launchInstaller(file)
        }
    }

    private fun routeIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_UPDATES) requestedTab = ChildTab.Settings
    }

    private fun grantPermission(id: String) {
        if (id == "vpn") { requestVpn(); return }
        if (id == "notifications" && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val intent = ChildPermissions.settingsIntent(this, id) ?: return
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnConsent.launch(intent) else startVpnService()
    }

    private fun startVpnService() {
        ContextCompat.startForegroundService(this, Intent(this, DomainFilterVpnService::class.java))
        vm.message = "Website filter started. Android shows a key icon and Guardian shows a notification while it runs."
    }

    private fun install(file: File) {
        if (!file.isFile) {
            vm.message = "The downloaded update is no longer available. Download it again."
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            vm.message = "Allow Guardian Child to install updates, then come back."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        launchInstaller(file)
    }

    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            vm.message = "Couldn't open the Android installer: " + (e.message ?: "unknown error")
        }
    }

    companion object {
        private const val ACTION_OPEN_UPDATES = "com.sirrlevi.guardian.child.OPEN_APP_UPDATES"
    }
}
