package com.sirrlevi.guardian.child.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.child.CaptureRuntime
import com.sirrlevi.guardian.child.ChildViewModel
import java.io.File

enum class ChildTab(val label: String, val emoji: String) {
    Today("Today", "🏠"),
    Protection("Protection", "🛡️"),
    Browser("Browser", "🌐"),
    Settings("Settings", "⚙️")
}

/** Actions only an Activity can perform (Android permission screens, VPN consent, package installer). */
class ChildActions(
    val grant: (String) -> Unit,
    val openBrowser: () -> Unit,
    val startWebsiteFilter: () -> Unit,
    val install: (File) -> Unit,
    /** The child tapped Allow on a screen-sharing request: open Android's own screen-capture dialog. */
    val startCapture: () -> Unit = {},
    val declineCapture: () -> Unit = {},
    val stopCapture: () -> Unit = {}
)

@Composable
fun ChildApp(vm: ChildViewModel, requestedTab: ChildTab?, onRequestedTabConsumed: () -> Unit, actions: ChildActions) {
    LaunchedEffect(vm.installRequest) {
        val file = vm.installRequest
        if (file != null) {
            vm.installRequest = null
            actions.install(file)
        }
    }
    when (vm.phase) {
        ChildViewModel.Phase.Loading -> Box(Modifier.fillMaxSize().background(kc.bg), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        ChildViewModel.Phase.Unpaired -> PairScreen(vm)
        ChildViewModel.Phase.Paired -> Shell(vm, requestedTab, onRequestedTabConsumed, actions)
    }
}

@Composable
private fun Shell(vm: ChildViewModel, requestedTab: ChildTab?, onRequestedTabConsumed: () -> Unit, actions: ChildActions) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val tabs = ChildTab.values()
    val tab = tabs[tabIndex.coerceIn(0, tabs.size - 1)]

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
    BackHandler(enabled = tab != ChildTab.Today) { tabIndex = ChildTab.Today.ordinal }

    val missingRequired = vm.permissions.any { it.required && !it.granted }
    val capture by CaptureRuntime.state.collectAsState()

    Scaffold(
        containerColor = kc.bg,
        topBar = { TopBar(vm) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = kc.card) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tabIndex = t.ordinal },
                        icon = {
                            Box {
                                Text(t.emoji, style = MaterialTheme.typography.titleLarge)
                                if (t == ChildTab.Protection && missingRequired) {
                                    Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(RoundedCornerShape(50)).background(kc.coral))
                                }
                            }
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = kc.primarySoft, selectedTextColor = kc.primary, unselectedTextColor = kc.muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        CaptureRequestDialog(capture, onAllow = actions.startCapture, onDecline = actions.declineCapture)
        Crossfade(targetState = tab, label = "child-tabs") { current ->
            when (current) {
                ChildTab.Today -> TodayScreen(vm, padding) { tabIndex = it.ordinal }
                ChildTab.Protection -> ProtectionScreen(vm, padding, actions, capture)
                ChildTab.Browser -> BrowserScreen(vm, padding, actions)
                ChildTab.Settings -> ChildSettingsScreen(vm, padding)
            }
        }
    }
}

@Composable
private fun TopBar(vm: ChildViewModel) {
    val ready = vm.permissions.none { it.required && !it.granted } && vm.monitorRunning
    Row(
        Modifier.fillMaxWidth().background(kc.bg).statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(kc.primary), contentAlignment = Alignment.Center) {
            Text("G", color = kc.onPrimary, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Guardian", style = MaterialTheme.typography.titleLarge, color = kc.text)
            Text("Child companion", style = MaterialTheme.typography.labelSmall, color = kc.muted)
        }
        if (ready) KPill("Protection on", kc.good) else KPill("Needs setup", kc.sun)
    }
}

/** Every screen is a padded, scrolling column under the top bar and above the navigation bar. */
@Composable
fun ScreenColumn(padding: PaddingValues, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp, end = 18.dp,
            top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
fun Heading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = kc.text)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = kc.muted)
    }
}
