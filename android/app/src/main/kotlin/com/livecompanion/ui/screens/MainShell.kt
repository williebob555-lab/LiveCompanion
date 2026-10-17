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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.livecompanion.ui.theme.LiveShapes
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.unit.sp
import com.livecompanion.data.Prefs
import com.livecompanion.net.ConnStatus
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType
import kotlinx.coroutines.delay

enum class Tab(val label: String, val lowPriority: Boolean = false) {
    Arrange("Arrange"), Session("Session"), Tracks("Tracks"), Clip("Clip"),
    Devices("Devices"), Browser("Browser"), Mix("Mix"), Takes("Takes", lowPriority = true), More("More"),
}

/** App-level actions that screens (e.g. More/Settings) need without owning navigation. */
class AppActions(
    val disconnect: () -> Unit = {},
    val prefs: Prefs? = null,
)

val LocalAppActions = staticCompositionLocalOf { AppActions() }

/** Lets any screen switch the shell's tab (e.g. Session "Edit clip" -> Clip). */
val LocalTabNavigator = staticCompositionLocalOf<(Tab) -> Unit> { {} }

/** Width at/above which the shell uses the left tab rail even in portrait (tablets). */
val TWO_PANE_MIN_WIDTH = 600.dp

/** Minimum cutout inset that is used for the read-only [InfoStrip]. */
private val STRIP_MIN = 28.dp

/**
 * Main shell: fixed transport bar, track header, tab navigation, tab body. TRACK VIEW (Arrange) is the front tab.
 * Landscape / wide: slim tab rail on the left; portrait: two-line tab row. There is no permanent side pane:
 * Session is a normal tab.
 *
 * Landscape + display cutout: the cutout-side inset is not left blank, it renders a read-only [InfoStrip]
 * (position, tempo, signature, CPU, connection, focused-track meter). [cutoutOverride] (left, right) lets
 * screenshot tests simulate a cutout without a real window.
 *
 * While the connection is not CONNECTED (lost, retrying) the top bar is replaced in place by a status strip with
 * Disconnect (no layout change) and the body is dimmed and ignores touches, so nothing can be edited optimistically.
 * [statusOverride] lets screenshot tests render a connected shell without a socket.
 */
@Composable
fun MainShell(
    store: LiveStore, client: LiveClient, modifier: Modifier = Modifier, initialTab: Tab = Tab.Arrange,
    statusOverride: ConnStatus? = null, cutoutOverride: Pair<Dp, Dp>? = null, cornersOverride: Corners? = null,
    topInsetOverride: Dp? = null, bottomInsetOverride: Dp? = null,
) {
    val corners = cornersOverride ?: rememberCorners()
    var selected by rememberSaveable { mutableStateOf(initialTab) }
    val conn by client.state.collectAsState()
    val live = (statusOverride ?: conn.status) == ConnStatus.CONNECTED
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) { store.notices.collect { notice = it } }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5000); notice = null
        }
    }

    val demo = client is com.livecompanion.net.DemoClient
    val script by store.script.collectAsState()
    val noticeBar: @Composable () -> Unit = {
        if (script.outdated && !demo) {
            LiveText(
                com.livecompanion.state.SCRIPT_OUTDATED_TEXT,
                Modifier.fillMaxWidth().background(LiveColors.Panel).padding(horizontal = 8.dp, vertical = 2.dp),
                LiveType.Small.copy(fontSize = 10.sp, lineHeight = 12.sp), LiveColors.Warn, maxLines = 2,
            )
        }
        if (notice == null && demo) {
            LiveText(
                "DEMO - built-in sample set, nothing is sent to Live",
                Modifier.fillMaxWidth().background(LiveColors.Panel).padding(horizontal = 8.dp, vertical = 1.dp),
                LiveType.Small.copy(fontSize = 10.sp, lineHeight = 12.sp), LiveColors.Accent,
            )
        }
        notice?.let { n ->
            LiveText(
                n,
                Modifier.fillMaxWidth().background(LiveColors.Panel).clickable { notice = null }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                LiveType.Small, LiveColors.Warn,
            )
        }
    }

    val d = LocalDensity.current
    val cut = WindowInsets.displayCutout
    val cutL = cutoutOverride?.first ?: with(d) { cut.getLeft(d, LayoutDirection.Ltr).toDp() }
    val cutR = cutoutOverride?.second ?: with(d) { cut.getRight(d, LayoutDirection.Ltr).toDp() }
    val sd = WindowInsets.safeDrawing
    val topIns = topInsetOverride ?: with(d) { sd.getTop(d).toDp() }
    val botIns = bottomInsetOverride ?: with(d) { sd.getBottom(d).toDp() }
    val hasNotice = (script.outdated && !demo) || demo || notice != null

    var bodyCorners by remember { mutableStateOf(BodyCorners()) }
    var secondaryBar by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalTabNavigator provides { t: Tab -> selected = t }, LocalBodyCorners provides bodyCorners) {
    // SEAMLESS EDGES (owner rule): no padding on this root. Every surface paints its background FIRST (edge to edge,
    // under the status/cutout/corner insets) and only then pads its own content.
    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg)) {
        val land = maxWidth > maxHeight
        val stripL = if (land && cutL >= STRIP_MIN) cutL else 0.dp
        val stripR = if (land && cutR >= STRIP_MIN) cutR else 0.dp
        // Sides without a strip keep their inset (cutout / nav bar) as content padding inside edge-to-edge surfaces.
        val padL = if (stripL > 0.dp) 0.dp else cutL.coerceAtLeast(2.dp)
        val padR = if (stripR > 0.dp) 0.dp else cutR.coerceAtLeast(2.dp)
        val contentW = maxWidth - stripL - stripR - padL - padR
        val rail = contentW >= TWO_PANE_MIN_WIDTH || land
        // Rounded display corners: top/bottom bars start/end clear of the arc not already covered by the rail / strips.
        val railOcc = if (rail) (52.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceIn(52.dp, 80.dp) else 0.dp
        val leftOcc = stripL + padL + railOcc
        val rightOcc = stripR + padR
        val insStart = (corners.tl - leftOcc).coerceAtLeast(0.dp)
        val insEnd = (corners.tr - rightOcc).coerceAtLeast(0.dp)
        val botStart = (corners.bl - leftOcc).coerceAtLeast(0.dp)
        val botEnd = (corners.br - rightOcc).coerceAtLeast(0.dp)
        SideEffect { if (bodyCorners.bottomStart != botStart || bodyCorners.bottomEnd != botEnd) bodyCorners = BodyCorners(botStart, botEnd) }
        val bodyBottom = if (hasNotice) 0.dp else botIns
        Row(Modifier.fillMaxSize()) {
            if (stripL > 0.dp) InfoStrip(store, statusOverride, client, Modifier.width(stripL).fillMaxHeight().background(LiveColors.Panel).padding(top = maxOf(corners.tl, topIns), bottom = maxOf(corners.bl, botIns)))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // Back: any non-home tab returns to Arrange first; only home asks to disconnect (MainActivity).
                BackHandler(enabled = selected != Tab.Arrange) { selected = Tab.Arrange }
                if (rail) {
                    val fs = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val railW = (52.dp * fs).coerceIn(52.dp, 80.dp)
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        TabRail(Tab.entries, selected, Modifier.width(railW + padL).fillMaxHeight().background(LiveColors.Panel)
                            .padding(start = padL, top = if (stripL > 0.dp) topIns else maxOf(corners.tl, topIns), bottom = if (stripL > 0.dp) botIns else maxOf(corners.bl, botIns))) { selected = it }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Box(Modifier.fillMaxWidth().background(LiveColors.Panel).padding(top = topIns, start = insStart, end = insEnd + padR)) { TopChrome(live, client) { CompactTopBar(store, client, onSecondary = { secondaryBar = it }) } }
                            // The body's corner/cutout inset is painted Panel like the top bar and rail, so the right edge is one
                            // continuous frame instead of a near-black app-background sliver (re-check test4).
                            Gated(live, Modifier.fillMaxWidth().weight(1f).background(LiveColors.Panel).padding(end = padR, bottom = bodyBottom)) { TabBody(selected, store, client, Modifier.fillMaxSize()) }
                            // Small phones: the controls that do not fit the single 40dp top row live in a bottom strip.
                            if (secondaryBar && live) Box(Modifier.fillMaxWidth().background(LiveColors.Panel).padding(start = botStart, end = botEnd + padR)) { CompactSecondaryBar(store, client) }
                            if (hasNotice) Box(Modifier.fillMaxWidth().background(LiveColors.Panel).padding(start = botStart, end = botEnd + padR, bottom = botIns)) { noticeBar() }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Box(Modifier.fillMaxWidth().background(LiveColors.Panel).padding(top = topIns, start = insStart + padL, end = insEnd + padR)) { TopChrome(live, client) {
                            if (contentW >= 340.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) PortraitTopBar(store, client)
                            else Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                TransportBar(store, client)
                                TrackHeader(store, client)
                            }
                        } }
                        TabRow(Tab.entries, selected, Modifier.background(LiveColors.Panel).padding(start = padL, end = padR)) { selected = it }
                        Gated(live, Modifier.fillMaxWidth().weight(1f).background(LiveColors.Panel).padding(start = padL, end = padR, bottom = bodyBottom)) { TabBody(selected, store, client, Modifier.fillMaxSize()) }
                        if (hasNotice) Box(Modifier.fillMaxWidth().background(LiveColors.Panel).padding(start = botStart + padL, end = botEnd + padR, bottom = botIns)) { noticeBar() }
                    }
                }
            }
            if (stripR > 0.dp) InfoStrip(store, statusOverride, client, Modifier.width(stripR).fillMaxHeight().background(LiveColors.Panel).padding(top = maxOf(corners.tr, topIns), bottom = maxOf(corners.br, botIns)))
        }
    }
    }
}

/**
 * Horizontal room the tab BODY must leave at its bottom-left / bottom-right so interactive edges there stay out of the
 * rounded display corners (the part of the arc not already covered by the rail / info strips). Editors read this to inset
 * their bottom bars and corner controls; plain list tabs get half of it as side padding.
 */
data class BodyCorners(val bottomStart: Dp = 0.dp, val bottomEnd: Dp = 0.dp)

val LocalBodyCorners = androidx.compose.runtime.compositionLocalOf { BodyCorners() }

/** Rounded display-corner radii (dp). */
data class Corners(val tl: Dp = 0.dp, val tr: Dp = 0.dp, val bl: Dp = 0.dp, val br: Dp = 0.dp)

/** Reads the real corner radii (API 31+); 0 when the device reports none. */
@Composable
fun rememberCorners(): Corners {
    val view = androidx.compose.ui.platform.LocalView.current
    val d = LocalDensity.current
    return remember(view, d.density) {
        if (android.os.Build.VERSION.SDK_INT < 31) return@remember Corners()
        val ins = view.rootWindowInsets ?: return@remember Corners()
        fun r(pos: Int) = with(d) { (ins.getRoundedCorner(pos)?.radius ?: 0).toDp() }
        Corners(r(android.view.RoundedCorner.POSITION_TOP_LEFT), r(android.view.RoundedCorner.POSITION_TOP_RIGHT),
            r(android.view.RoundedCorner.POSITION_BOTTOM_LEFT), r(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT))
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
    Box(modifier.background(LiveColors.Panel.copy(alpha = 0.92f))) {
        // The touch blocker is a SIBLING under the row: a consuming parent would starve the Disconnect button of events.
        Box(Modifier.matchParentSize().blockTouches())
        Row(
            Modifier.fillMaxSize().padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LiveText(text, Modifier.weight(1f), LiveType.Small, LiveColors.Warn, maxLines = 2)
            LiveTextButton(
                if (conn.status == ConnStatus.CONNECTING) "Cancel" else "Disconnect",
                actions.disconnect, minWidth = 88.dp, minHeight = 32.dp,
            )
        }
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
 * Slim vertical tab rail for landscape / wide layouts: full labels, items grouped at the top at a fixed
 * 30-42dp pitch (scrolls if the screen is too short). Low-priority tabs (Takes) are drawn dimmer.
 */
@Composable
fun TabRail(tabs: List<Tab>, selected: Tab, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    BoxWithConstraints(modifier.background(LiveColors.Panel)) {
        // every item fits when the rail is tall enough (never clip "More"); otherwise 28dp items and the rail scrolls
        val itemH = ((maxHeight - 4.dp - 1.dp * (tabs.size - 1)) / tabs.size).coerceIn(28.dp, 42.dp)
        val style = LiveType.Small
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            for (t in tabs) {
                val on = t == selected
                Box(
                    Modifier.fillMaxWidth().height(itemH)
                        .clip(LiveShapes.Small)
                        .background(if (on) LiveColors.PanelHi else LiveColors.Panel)
                        .clickable { onSelect(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    LiveText(
                        t.label, Modifier.padding(horizontal = 2.dp), style,
                        if (on) LiveColors.Accent else if (t.lowPriority) LiveColors.Text.copy(alpha = 0.7f) else LiveColors.Text,
                        textAlign = TextAlign.Center,
                    )
                    if (on) Box(Modifier.align(Alignment.CenterStart).width(2.dp).fillMaxHeight(0.6f).background(LiveColors.Accent))
                }
            }
        }
    }
}

@Composable
private fun TabBody(tab: Tab, store: LiveStore, client: LiveClient, modifier: Modifier) {
    val bc = LocalBodyCorners.current
    // Editors (Arrange, Clip) inset their own bottom bars precisely; list-like tabs give up half the corner room on each side.
    val m = if (tab == Tab.Arrange || tab == Tab.Clip) modifier else modifier.padding(start = bc.bottomStart * 0.5f, end = bc.bottomEnd * 0.5f)
    TabBodyInner(tab, store, client, m)
}

@Composable
private fun TabBodyInner(tab: Tab, store: LiveStore, client: LiveClient, modifier: Modifier) {
    when (tab) {
        Tab.Arrange -> ArrangeScreen(store, client, modifier)
        Tab.Session -> SessionScreen(store, client, modifier)
        Tab.Tracks -> TracksScreen(store, client, modifier)
        Tab.Takes -> TakesScreen(store, client, modifier)
        Tab.Clip -> ClipScreen(store, client, modifier)
        Tab.Devices -> DevicesScreen(store, client, modifier)
        Tab.Browser -> BrowserScreen(store, client, modifier)
        Tab.Mix -> MixScreen(store, client, modifier)
        Tab.More -> MoreScreen(store, client, modifier)
    }
}

/**
 * Portrait tab row, 28dp per line, full labels always: one line when all tabs fit at the current font scale,
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
                            .height(28.dp)
                            .clip(LiveShapes.Small)
                            .background(if (on) LiveColors.PanelHi else LiveColors.Panel)
                            .clickable { onSelect(t) }
                            .padding(horizontal = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LiveText(
                            t.label,
                            style = LiveType.Small,
                            color = if (on) LiveColors.Accent else if (t.lowPriority) LiveColors.Text.copy(alpha = 0.7f) else LiveColors.Text,
                            textAlign = TextAlign.Center,
                        )
                        if (on) Box(Modifier.align(Alignment.BottomCenter).width(24.dp).height(2.dp).background(LiveColors.Accent))
                    }
                }
            }
        }
    }
}
