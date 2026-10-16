package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.FocusState
import com.livecompanion.state.LiveStore
import com.livecompanion.state.TrackInfo
import com.livecompanion.ui.components.ColorChip
import com.livecompanion.ui.components.LiveButton
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveFader
import com.livecompanion.ui.components.LiveIcon
import com.livecompanion.ui.components.LiveIconButton
import com.livecompanion.ui.components.LiveIconKind
import com.livecompanion.ui.components.LiveLabel
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.components.MeterBar
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType

private val MONITOR_LABELS = listOf("Mon IN", "Mon AUTO", "Mon OFF")

/**
 * Fixed track header for the focused track (portrait / narrow layouts): prev/next, name (opens a simple
 * picker dialog), arm/mute/solo/monitor, vol/pan mini faders, meter. 2 dense rows <640dp, else 1 row.
 * Phone landscape uses [CompactTopBar] instead.
 */
@Composable
fun TrackHeader(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().background(LiveColors.Panel)) {
        val gap = Arrangement.spacedBy(1.dp)
        when {
            maxWidth >= 640.dp -> Row(Modifier.padding(1.dp), gap, Alignment.CenterVertically) {
                TrackNav(store, client, Modifier.weight(1.2f)); TrackToggles(store, client)
                TrackFaders(store, client)
            }
            else -> Column(Modifier.padding(1.dp), Arrangement.spacedBy(1.dp)) {
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                    TrackNav(store, client, Modifier.weight(1f)); TrackToggles(store, client)
                }
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { TrackFaders(store, client) }
            }
        }
    }
}

/** prev / name picker / next. Owns the picker dialog. */
@Composable
internal fun RowScope.TrackNav(store: LiveStore, client: LiveClient, nameModifier: Modifier, h: Dp = 40.dp, btnW: Dp = 40.dp) {
    val focus by store.focus.collectAsState()
    val tracks by store.tracks.collectAsState()
    var picker by remember { mutableStateOf(false) }
    val all = tracks.all
    val idx = all.indexOfFirst { it.ref == focus.ref }
    fun goto(i: Int) {
        val t = all.getOrNull(i) ?: return
        store.focusPending(t.ref)
        client.op("focus.set", "tr" to t.ref)
    }
    LiveIconButton(LiveIconKind.Prev, { goto(if (idx <= 0) all.lastIndex else idx - 1) }, minWidth = btnW, minHeight = h, enabled = all.size > 1)
    LiveButton({ picker = true }, nameModifier, offColor = LiveColors.PanelHi, minHeight = h, contentPadding = PaddingValues(horizontal = 6.dp)) { c ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ColorChip(focus.color, size = 10.dp)
            LiveText(focus.name.ifEmpty { "-" }, Modifier.weight(1f, fill = false), LiveType.Body, c)
            LiveIcon(LiveIconKind.Down, c, size = 12.dp)
        }
    }
    LiveIconButton(LiveIconKind.Next, { goto(if (idx < 0 || idx >= all.lastIndex) 0 else idx + 1) }, minWidth = btnW, minHeight = h, enabled = all.size > 1)
    if (picker) {
        TrackPickerDialog(store, client, onDismiss = { picker = false })
    }
}

/** arm / mute / solo / monitor of the focused track. */
@Composable
internal fun RowScope.TrackToggles(store: LiveStore, client: LiveClient, h: Dp = 40.dp, btnW: Dp = 40.dp, monitor: Boolean = true) {
    val focus by store.focus.collectAsState()
    val m = Modifier
    val ref = focus.ref
    fun set(k: String, v: Any, patch: (TrackInfo) -> TrackInfo, f: (FocusState) -> FocusState) {
        client.op("track.set", "tr" to ref, "k" to k, "v" to v)
        store.updateFocus(f); store.patchTrack(ref, patch)
    }
    if (focus.armable) {
        LiveIconButton(LiveIconKind.Rec, { set("arm", !focus.arm, { it.copy(arm = !focus.arm) }, { it.copy(arm = !focus.arm) }) }, m, focus.arm, LiveColors.Rec, minHeight = h, minWidth = btnW)
    }
    LiveTextButton("M", { set("mute", !focus.mute, { it.copy(mute = !focus.mute) }, { it.copy(mute = !focus.mute) }) }, m, focus.mute, LiveColors.MuteOn, minHeight = h, minWidth = btnW)
    LiveTextButton("S", { set("solo", !focus.solo, { it.copy(solo = !focus.solo) }, { it.copy(solo = !focus.solo) }) }, m, focus.solo, LiveColors.Solo, minHeight = h, minWidth = btnW)
    if (monitor) MonitorToggle(store, client, h)
}

/** Monitor cycle button (In / Auto / Off) of the focused track. */
@Composable
internal fun RowScope.MonitorToggle(store: LiveStore, client: LiveClient, h: Dp = 40.dp) {
    val focus by store.focus.collectAsState()
    val ref = focus.ref
    if (focus.kind == "audio" || focus.kind == "midi" || focus.kind == "group") {
        val mon = focus.monitor.coerceIn(0, 2)
        LiveTextButton(
            MONITOR_LABELS[mon],
            {
                val nxt = (mon + 1) % 3
                client.op("track.set", "tr" to ref, "k" to "monitor", "v" to nxt)
                store.updateFocus { it.copy(monitor = nxt) }; store.patchTrack(ref) { it.copy(monitor = nxt) }
            },
            Modifier, mon == 0, LiveColors.Accent, minHeight = h, minWidth = 70.dp,
        )
    }
}

/** vol / pan mini faders and the focused meter, filling the remaining width of the row. */
@Composable
internal fun RowScope.TrackFaders(store: LiveStore, client: LiveClient, h: Dp = 34.dp) {
    val focus by store.focus.collectAsState()
    val rt = store.rt.collectAsState() // read only in draw lambdas below: meters never recompose this
    VolFader(store, client, focus, Modifier.weight(2f), h)
    PanFader(store, client, focus, Modifier.weight(1f), h)
    MeterBar(
        left = { rt.value.meters.focusL },
        right = { rt.value.meters.focusR },
        modifier = Modifier.weight(0.8f).height(h),
    )
}

@Composable
private fun VolFader(store: LiveStore, client: LiveClient, focus: FocusState, modifier: Modifier, h: Dp) {
    val v = focus.vol
    LiveFader(
        value = v.v,
        onValueChange = { nv ->
            store.updateFocus { it.copy(vol = it.vol.copy(v = nv, str = "")) }
            client.opThrottled("vol:${focus.ref}", "track.set", "tr" to focus.ref, "k" to "vol", "v" to nv)
        },
        modifier = modifier.height(h),
        valueRange = v.min..v.max.coerceAtLeast(v.min + 0.001f),
        defaultValue = 0.85f,
        vertical = false,
        label = v.str.ifEmpty { null },
    )
}

@Composable
private fun PanFader(store: LiveStore, client: LiveClient, focus: FocusState, modifier: Modifier, h: Dp) {
    val p = focus.pan
    LiveFader(
        value = p.v,
        onValueChange = { nv ->
            store.updateFocus { it.copy(pan = it.pan.copy(v = nv, str = "")) }
            client.opThrottled("pan:${focus.ref}", "track.set", "tr" to focus.ref, "k" to "pan", "v" to nv)
        },
        modifier = modifier.height(h),
        valueRange = p.min..p.max.coerceAtLeast(p.min + 0.001f),
        defaultValue = 0f,
        vertical = false,
        bipolar = true,
        color = LiveColors.Solo,
        label = p.str.ifEmpty { null },
    )
}

/** Track list dialog = the Tracks-tab editor (focus, rename, colour, arm/M/S, duplicate, delete, add), closes on focus. */
@Composable
fun TrackPickerDialog(store: LiveStore, client: LiveClient, onDismiss: () -> Unit) {
    LiveDialog("Tracks", onDismiss) {
        TracksScreen(store, client, Modifier.weight(1f, fill = false), onFocused = onDismiss)
        LiveDialogButtons { LiveTextButton("Close", onDismiss, minWidth = 64.dp) }
    }
}
