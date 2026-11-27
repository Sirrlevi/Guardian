package com.sirrlevi.guardian.parent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    Tab.Messages -> GIcon.Chat
    Tab.More -> GIcon.Dots
    Tab.Reports -> GIcon.Chart
    Tab.Settings -> GIcon.Settings
    Tab.Media -> GIcon.Photo
    Tab.Preview -> GIcon.Screen
    Tab.Controls -> GIcon.Lock
    Tab.Recovery -> GIcon.Pin
    Tab.Appearance -> GIcon.Photo
    Tab.Safety -> GIcon.Shield
}

/**
 * The whole Parent app: sign-in, then five bottom tabs (Home, Apps, Activity, Messages, More). Reports and
 * Settings open from "More". [requestedTab] lets a notification jump straight to a screen (e.g. App updates).
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
    var tabName by rememberSaveable { mutableStateOf(Tab.Home.name) }
    var sheetPkg by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val barTabs = Tab.values().filter { it.inBar }
    val tab = Tab.values().firstOrNull { it.name == tabName } ?: Tab.Home
    val go: (Tab) -> Unit = { tabName = it.name }
    val device = vm.device

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tabName = requestedTab.name
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
    BackHandler(enabled = tab != Tab.Home && sheetPkg == null) { go(if (tab.inBar) Tab.Home else Tab.More) }

    val pending = device?.requests?.count { it.status == "pending" && System.currentTimeMillis() - it.ts < 24L * 3600_000L } ?: 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopBar(vm)
                if (!tab.inBar) BackToMore { go(Tab.More) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                barTabs.forEach { t ->
                    val selected = t == tab || (t == Tab.More && !tab.inBar)
                    NavigationBarItem(
                        selected = selected,
                        onClick = { go(t) },
                        icon = {
                            val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
                Tab.Home -> HomeScreen(vm, padding, onOpenApp = { sheetPkg = it.pkg }, goTab = go)
                Tab.Apps -> AppsScreen(vm, padding, onOpenApp = { sheetPkg = it.pkg })
                Tab.Activity -> ActivityScreen(vm, padding, onOpenApp = { sheetPkg = it.pkg })
                Tab.Messages -> CommunicationScreen(vm, padding)
                Tab.More -> MoreScreen(vm, padding, open = go)
                Tab.Reports -> ReportsScreen(vm, padding, onOpenApp = { sheetPkg = it.pkg })
                Tab.Settings -> SettingsScreen(vm, padding)
                Tab.Media -> MediaScreen(vm, padding)
                Tab.Preview -> ScreenPreviewScreen(vm, padding)
                Tab.Controls -> DeviceControlsScreen(vm, padding)
                Tab.Recovery -> RecoveryScreen(vm, padding)
                Tab.Appearance -> AppearanceScreen(vm, padding)
                Tab.Safety -> SafetyScreen(vm, padding)
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
private fun BackToMore(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GIconView(GIcon.Back, MaterialTheme.colorScheme.primary, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        Text("More", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TopBar(vm: ParentViewModel) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GuardianLogo(32.dp)
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
