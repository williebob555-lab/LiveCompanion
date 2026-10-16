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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
 * Compact: one pane. Medium/Expanded (>= 600dp): left = Session slot list (always visible unless collapsed with the
 * rail's List toggle, so the Session tab is dropped from the rail meanwhile), right = selected tab.
 *
 * While the connection is not CONNECTED (lost, retrying) the top bar is replaced in place by a status strip with
 * Disconnect (no layout change) and the body is dimmed and ignores touches, so nothing can be edited optimistically.
 * [statusOverride] lets screenshot tests render a connected shell without a socket.
 */
@Composable
fun MainShell(
    store: LiveStore, client: LiveClient, modifier: Modifier = Modifier, initialTab: Tab = Tab.Session,
    statusOverride: ConnStatus? = null,
) {
    var selected by rememberSaveable { mutableStateOf(initialTab) }
    val conn by client.state.collectAsState()
    val live = (statusOverride ?: conn.status) == ConnStatus.CONNECTED
    val prefs = LocalAppActions.current.prefs
    var paneHidden by rememberSaveable { mutableStateOf(prefs?.sessionPaneHidden ?: false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) { store.notices.collect { notice = it } }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5000); notice = null
        }
    }

    val noticeBar: @Composable () -> Unit = {
        notice?.let { n ->
            LiveText(
                n,
                Modifier.fillMaxWidth().background(LiveColors.Panel).clickable { notice = null }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                LiveType.Small, LiveColors.Accent,
            )
        }
    }

    CompositionLocalProvider(LocalTabNavigator provides { t: Tab -> selected = t }) {
    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg)) {
        // Landscape phones and anything >= 600dp wide: tab rail on the left, one top bar, everything else editor.
        val rail = maxWidth >= TWO_PANE_MIN_WIDTH || maxWidth > maxHeight
        if (rail) {
            val fs = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val railW = ((if (maxWidth >= 840.dp) 68.dp else 56.dp) * fs).coerceIn(56.dp, 84.dp)
            val bodyW = maxWidth - railW - 1.dp
            val two = bodyW >= TWO_PANE_MIN_WIDTH
            val paneShown = two && !paneHidden
            val tab = if (paneShown && selected == Tab.Session) Tab.Clip else selected
            // Back: any non-home tab returns to Session first; only home asks to disconnect (MainActivity).
            val home = selected == Tab.Session || (paneShown && selected == Tab.Clip)
            BackHandler(enabled = !home) { selected = Tab.Session }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                TabRail(
                    Tab.entries.filter { !(paneShown && it == Tab.Session) }, tab, Modifier.width(railW).fillMaxHeight(),
                    footer = if (two) {
                        { RailToggle("List", paneShown) { paneHidden = !paneHidden; prefs?.sessionPaneHidden = paneHidden } }
                    } else null,
                ) { selected = it }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    TopChrome(live, client) { CompactTopBar(store, client) }
                    if (paneShown) {
                        Gated(live, Modifier.fillMaxWidth().weight(1f)) {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                SessionScreen(store, client, Modifier.width((bodyW * 0.38f).coerceIn(240.dp, 380.dp)).fillMaxHeight())
                                TabBody(tab, store, client, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    } else {
                        Gated(live, Modifier.fillMaxWidth().weight(1f)) { TabBody(tab, store, client, Modifier.fillMaxSize()) }
                    }
                    noticeBar()
                }
            }
        } else {
            BackHandler(enabled = selected != Tab.Session) { selected = Tab.Session }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                TopChrome(live, client) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        TransportBar(store, client)
                        TrackHeader(store, client)
                    }
                }
                TabRow(Tab.entries, selected) { selected = it }
                Gated(live, Modifier.fillMaxWidth().weight(1f)) { TabBody(selected, store, client, Modifier.fillMaxSize()) }
                noticeBar()
            }
        }
    }
    }
}

/** Swallows every pointer event inside its bounds (used to disable dimmed content). */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            e.changes.forEach { it.consume() }
        }
    }
}

/** Dims [content] and makes it inert while not [live]. */
@Composable
private fun Gated(live: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().alpha(if (live) 1f else 0.4f)) { content() }
        if (!live) Box(Modifier.matchParentSize().blockTouches())
    }
}

/**
 * Top chrome (transport + track bars). When not [live] the same area shows the connection status instead, drawn over
 * the dimmed bars, so the indicator never changes the layout.
 */
@Composable
private fun TopChrome(live: Boolean, client: LiveClient, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.alpha(if (live) 1f else 0.3f)) { content() }
        if (!live) ConnectionStrip(client, Modifier.matchParentSize())
    }
}

@Composable
private fun ConnectionStrip(client: LiveClient, modifier: Modifier) {
    val conn by client.state.collectAsState()
    val actions = LocalAppActions.current
    val text = when (conn.status) {
        ConnStatus.CONNECTING -> "Connecting to ${conn.target?.label ?: ""}..."
        ConnStatus.RECONNECTING -> "Connection lost - retrying ${conn.target?.label ?: ""} (${conn.attempt})"
        ConnStatus.IDLE -> conn.lastError ?: "Not connected"
        ConnStatus.CONNECTED -> ""
    }
    Row(
        modifier.background(LiveColors.Panel.copy(alpha = 0.92f)).blockTouches().padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LiveText(text, Modifier.weight(1f), LiveType.Small, LiveColors.Accent, maxLines = 2)
        LiveTextButton(
            if (conn.status == ConnStatus.CONNECTING) "Cancel" else "Disconnect",
            actions.disconnect, minWidth = 88.dp, minHeight = 32.dp,
        )
    }
}

/** Fixed rail toggle (no animation): lit while the left Session pane is visible. */
@Composable
private fun RailToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(36.dp).background(if (on) LiveColors.PanelHi else LiveColors.Panel).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LiveText(label, Modifier.padding(horizontal = 2.dp), LiveType.Small, if (on) LiveColors.Accent else LiveColors.TextDim, textAlign = TextAlign.Center)
    }
}

/**
 * Safe-area padding that is symmetric left/right (the larger of the two cutout/nav insets, min 4dp) so a one-sided
 * display cutout does not leave the other side flush to the screen edge; top/bottom use the system insets.
 */
@Composable
fun Modifier.symmetricSafePadding(): Modifier {
    val ins = WindowInsets.safeDrawing
    val d = LocalDensity.current
    val side = with(d) { maxOf(ins.getLeft(d, LayoutDirection.Ltr), ins.getRight(d, LayoutDirection.Ltr)).toDp() }.coerceAtLeast(4.dp)
    return this.windowInsetsPadding(ins.only(WindowInsetsSides.Vertical)).padding(horizontal = side)
}

/**
 * Narrow vertical tab rail for landscape / wide layouts: full labels, items grouped at the top at a fixed
 * 36-44dp pitch (not stretched over the full height), then the optional [footer] (pane toggle).
 */
@Composable
fun TabRail(
    tabs: List<Tab>, selected: Tab, modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null, onSelect: (Tab) -> Unit,
) {
    BoxWithConstraints(modifier.background(LiveColors.Panel)) {
        val itemH = (maxHeight / (tabs.size + 1)).coerceIn(34.dp, 44.dp)
        val style = if (maxWidth >= 64.dp) LiveType.Body else LiveType.Small
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            for (t in tabs) {
                val on = t == selected
                Box(
                    Modifier.fillMaxWidth().height(itemH)
                        .background(if (on) LiveColors.PanelHi else LiveColors.Panel)
                        .clickable { onSelect(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    LiveText(
                        t.label, Modifier.padding(horizontal = 2.dp), style,
                        if (on) LiveColors.Accent else LiveColors.Text, textAlign = TextAlign.Center,
                    )
                    if (on) Box(Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight(0.6f).background(LiveColors.Accent))
                }
            }
            if (footer != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(LiveColors.Line))
                footer()
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

/**
 * Portrait tab row, 32dp per line, full labels always: one line when all tabs fit at the current font scale,
 * otherwise two balanced lines (never abbreviated).
 */
@Composable
fun TabRow(tabs: List<Tab>, selected: Tab, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth().background(LiveColors.Panel)) {
        val oneLine = maxWidth >= 64.dp * fontScale * tabs.size
        val lines = if (oneLine) listOf(tabs) else tabs.chunked((tabs.size + 1) / 2)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            for (line in lines) Row(Modifier.fillMaxWidth()) {
                for (t in line) {
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
                            t.label,
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
}
