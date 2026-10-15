package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livecompanion.data.Prefs
import com.livecompanion.net.ConnStatus
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType
import kotlinx.coroutines.delay

enum class Tab(val label: String) {
    Session("Session"), Arrange("Arrange"), Takes("Takes"), Clip("Clip"),
    Devices("Devices"), Browser("Browser"), Mix("Mix"), More("More"),
}

/** App-level actions that screens (e.g. More/Settings) need without owning navigation. */
class AppActions(
    val disconnect: () -> Unit = {},
    val prefs: Prefs? = null,
)

val LocalAppActions = staticCompositionLocalOf { AppActions() }

/** Lets any screen switch the shell's tab (e.g. Session "Edit clip" -> Clip). In two-pane, Session maps to Clip. */
val LocalTabNavigator = staticCompositionLocalOf<(Tab) -> Unit> { {} }

/** Width at/above which the shell becomes two-pane (docs/UI.md: Medium/Expanded). */
val TWO_PANE_MIN_WIDTH = 600.dp

/**
 * Main shell: fixed transport bar, track header, fixed tab row, tab body.
 * Compact: one pane. Medium/Expanded (>= 600dp): left = Session slot list (always visible, so the
 * Session tab is dropped from the tab row), right = selected tab.
 */
@Composable
fun MainShell(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier, initialTab: Tab = Tab.Session) {
    var selected by rememberSaveable { mutableStateOf(initialTab) }
    val conn by client.state.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) { store.notices.collect { notice = it } }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5000); notice = null
        }
    }

    CompositionLocalProvider(LocalTabNavigator provides { t: Tab -> selected = t }) {
    Column(modifier.fillMaxSize().background(LiveColors.Bg), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (conn.status != ConnStatus.CONNECTED) ConnectionBanner(client)
        TransportBar(store, client)
        TrackHeader(store, client)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val availW = maxWidth
            if (availW >= TWO_PANE_MIN_WIDTH) {
                val tab = if (selected == Tab.Session) Tab.Clip else selected
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    SessionScreen(store, client, Modifier.width((availW * 0.38f).coerceIn(260.dp, 380.dp)).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        TabRow(Tab.entries.filter { it != Tab.Session }, tab) { selected = it }
                        TabBody(tab, store, client, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    TabRow(Tab.entries, selected) { selected = it }
                    TabBody(selected, store, client, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        notice?.let { n ->
            LiveText(
                n,
                Modifier.fillMaxWidth().background(LiveColors.Panel).clickable { notice = null }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                LiveType.Small, LiveColors.Accent,
            )
        }
    }
    }
}

@Composable
private fun TabBody(tab: Tab, store: LiveStore, client: LiveClient, modifier: Modifier) {
    when (tab) {
        Tab.Session -> SessionScreen(store, client, modifier)
        Tab.Arrange -> ArrangeScreen(store, client, modifier)
        Tab.Takes -> TakesScreen(store, client, modifier)
        Tab.Clip -> ClipScreen(store, client, modifier)
        Tab.Devices -> DevicesScreen(store, client, modifier)
        Tab.Browser -> BrowserScreen(store, client, modifier)
        Tab.Mix -> MixScreen(store, client, modifier)
        Tab.More -> MoreScreen(store, client, modifier)
    }
}

private val Tab.short: String
    get() = when (this) {
        Tab.Session -> "Sess"; Tab.Arrange -> "Arr"; Tab.Takes -> "Take"; Tab.Clip -> "Clip"
        Tab.Devices -> "Dev"; Tab.Browser -> "Brws"; Tab.Mix -> "Mix"; Tab.More -> "More"
    }

/** Fixed single-line tab row (32dp). Labels are abbreviated when full names would not fit at the current font scale. */
@Composable
fun TabRow(tabs: List<Tab>, selected: Tab, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth().background(LiveColors.Panel)) {
        val full = maxWidth >= 56.dp * fontScale * tabs.size
        Row(Modifier.fillMaxWidth()) {
            for (t in tabs) {
                val on = t == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .background(if (on) LiveColors.PanelHi else LiveColors.Panel)
                        .clickable { onSelect(t) }
                        .padding(horizontal = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LiveText(
                        if (full) t.label else t.short,
                        style = LiveType.Small,
                        color = if (on) LiveColors.Accent else LiveColors.Text,
                        textAlign = TextAlign.Center,
                    )
                    if (on) Box(Modifier.align(Alignment.BottomCenter).width(24.dp).height(2.dp).background(LiveColors.Accent))
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(client: LiveClient) {
    val conn by client.state.collectAsState()
    val actions = LocalAppActions.current
    val text = when (conn.status) {
        ConnStatus.CONNECTING -> "Connecting to ${conn.target?.label ?: ""}..."
        ConnStatus.RECONNECTING -> "Connection lost - retrying ${conn.target?.label ?: ""} (${conn.attempt})"
        ConnStatus.IDLE -> conn.lastError ?: "Not connected"
        ConnStatus.CONNECTED -> ""
    }
    Row(
        Modifier.fillMaxWidth().background(LiveColors.Panel).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LiveText(text, Modifier.weight(1f), LiveType.Small, LiveColors.Accent)
        LiveTextButton("Disconnect", actions.disconnect, minWidth = 88.dp, minHeight = 32.dp)
    }
}
