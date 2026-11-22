package com.sirrlevi.guardian.parent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
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
import com.sirrlevi.guardian.parent.ui.GuardianApp
import com.sirrlevi.guardian.parent.ui.GuardianTheme
import java.io.File

/** Hosts the Compose UI. All state lives in [ParentViewModel], so rotation and process restarts keep your place. */
class ParentActivity : ComponentActivity() {
    private val vm: ParentViewModel by viewModels()
    private var requestedTab by mutableStateOf<Tab?>(null)
    private var pendingInstall: File? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the update worker simply stays silent if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            routeIntent(intent)
            vm.checkUpdates(false)
        }
        GuardianUpdateScheduler.schedule(applicationContext)
        askForNotifications()

        setContent {
            val dark = when (vm.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                )
                onDispose { }
            }
            GuardianTheme(vm.themeMode) {
                GuardianApp(
                    vm = vm,
                    requestedTab = requestedTab,
                    onRequestedTabConsumed = { requestedTab = null },
                    onInstall = { file -> install(file) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val file = pendingInstall
        if (file != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
            pendingInstall = null
            launchInstaller(file)
        }
    }

    private fun routeIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_UPDATES) requestedTab = Tab.Settings
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun install(file: File) {
        if (!file.isFile) {
            vm.message = "The downloaded update is no longer available. Download it again."
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            vm.message = "Allow Guardian Parent to install updates, then come back."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        launchInstaller(file)
    }

    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            vm.message = "Couldn't open the Android installer: " + (e.message ?: "unknown error")
        }
    }

    companion object {
        private const val ACTION_OPEN_UPDATES = "com.sirrlevi.guardian.parent.OPEN_APP_UPDATES"
    }
}
