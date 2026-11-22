package com.sirrlevi.guardian.parent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.parent.Connection
import com.sirrlevi.guardian.parent.ParentViewModel
import com.sirrlevi.guardian.parent.Tab
import java.io.File

private fun Tab.icon(): GIcon = when (this) {
    Tab.Home -> GIcon.Home
    Tab.Apps -> GIcon.Apps
    Tab.Activity -> GIcon.Activity
    Tab.Reports -> GIcon.Chart
    Tab.Settings -> GIcon.Settings
}

/**
 * The whole Parent app: sign-in, then five tabs (Home, Apps, Activity, Reports, Settings).
 * [requestedTab] lets a notification jump straight to a tab (e.g. App updates).
 */
@Composable
fun GuardianApp(vm: ParentViewModel, requestedTab: Tab?, onRequestedTabConsumed: () -> Unit, onInstall: (File) -> Unit) {
    LaunchedEffect(vm.installRequest) {
        val file = vm.installRequest
        if (file != null) {
            vm.installRequest = null
            onInstall(file)
        }
    }
    when (vm.phase) {
        ParentViewModel.Phase.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ParentViewModel.Phase.SignedOut -> AuthScreen(vm)
        ParentViewModel.Phase.Ready -> MainShell(vm, requestedTab, onRequestedTabConsumed)
    }
}

@Composable
private fun MainShell(vm: ParentViewModel, requestedTab: Tab?, onRequestedTabConsumed: () -> Unit) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var sheetPkg by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val tabs = Tab.values()
    val tab = tabs[tabIndex.coerceIn(0, tabs.size - 1)]
    val device = vm.device

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tabIndex = requestedTab.ordinal
            onRequestedTabConsumed()
        }
    }
    LaunchedEffect(vm.message) {
        val text = vm.message
        if (text != null) {
            vm.message = null
            snackbar.showSnackbar(text)
        }
    }
    BackHandler(enabled = tab != Tab.Home && sheetPkg == null) { tabIndex = Tab.Home.ordinal }

    val pending = device?.requests?.count { it.status == "pending" && System.currentTimeMillis() - it.ts < 24L * 3600_000L } ?: 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopBar(vm) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tabIndex = t.ordinal },
                        icon = {
                            val tint = if (t == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            if (t == Tab.Home && pending > 0) {
                                BadgedBox(badge = { Badge { Text(pending.toString()) } }) { GIconView(t.icon(), tint) }
                            } else GIconView(t.icon(), tint)
                        },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        Crossfade(targetState = tab, label = "tabs") { current ->
            when (current) {
                Tab.Home -> HomeScreen(vm, padding, onOpenApp = { sheetPkg = it.pkg }, goTab = { tabIndex = it.ordinal })
                Tab.Apps -> AppsScreen(vm, padding, onOpenApp = { sheetPkg = it.pkg })
                Tab.Activity -> ActivityScreen(vm, padding, onOpenApp = { sheetPkg = it.pkg })
                Tab.Reports -> ReportsScreen(vm, padding, onOpenApp = { sheetPkg = it.pkg })
                Tab.Settings -> SettingsScreen(vm, padding)
            }
        }
    }

    val sheetApp = sheetPkg?.let { pkg -> device?.app(pkg) }
    if (sheetApp != null) {
        AppControlSheet(vm, sheetApp, onDismiss = { sheetPkg = null })
    } else if (sheetPkg != null) {
        sheetPkg = null
    }
}

@Composable
private fun TopBar(vm: ParentViewModel) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GIconView(GIcon.Shield, MaterialTheme.colorScheme.primary, size = 26.dp)
        Spacer(Modifier.width(10.dp))
        Text("Guardian", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        val (label, color) = when (vm.connection) {
            Connection.Live -> "Live" to gColors.success
            Connection.Syncing -> "Syncing…" to gColors.info
            Connection.Connecting -> "Connecting…" to gColors.info
            Connection.Offline -> "Offline" to gColors.warning
        }
        Pill(label, color)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(38.dp).clip(CircleShape).clickable { vm.refreshAll(silent = false) },
            contentAlignment = Alignment.Center
        ) { GIconView(GIcon.Refresh, MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp) }
    }
}
