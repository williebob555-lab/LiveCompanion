package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.FocusState
import com.livecompanion.state.LiveStore
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
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType

private val MONITOR_LABELS = listOf("IN", "AUTO", "OFF")

/**
 * Fixed track header for the focused track: prev/next, name (opens a simple picker dialog),
 * arm/mute/solo/monitor, vol/pan mini faders, meter. 3 rows <420dp, 2 rows <700dp, else 1 row.
 */
@Composable
fun TrackHeader(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val focus by store.focus.collectAsState()
    val tracks by store.tracks.collectAsState()
    val rt = store.rt.collectAsState() // read only in draw lambdas below: meters never recompose this
    var picker by remember { mutableStateOf(false) }

    val all = tracks.all
    val idx = all.indexOfFirst { it.ref == focus.ref }
    fun goto(i: Int) {
        val t = all.getOrNull(i) ?: return
        store.focusPending(t.ref)
        client.op("focus.set", "tr" to t.ref)
    }

    val prev: @Composable RowScope.() -> Unit = {
        LiveIconButton(LiveIconKind.Prev, { goto(if (idx <= 0) all.lastIndex else idx - 1) }, minWidth = 36.dp, enabled = all.size > 1)
    }
    val next: @Composable RowScope.() -> Unit = {
        LiveIconButton(LiveIconKind.Next, { goto(if (idx < 0 || idx >= all.lastIndex) 0 else idx + 1) }, minWidth = 36.dp, enabled = all.size > 1)
    }
    val nameBtn: @Composable RowScope.(Modifier) -> Unit = { m ->
        LiveButton({ picker = true }, m, offColor = LiveColors.PanelHi, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorChip(focus.color)
                LiveText(focus.name.ifEmpty { "-" }, Modifier.weight(1f, fill = false), LiveType.Title, c)
                LiveIcon(LiveIconKind.Down, c, size = 14.dp)
            }
        }
    }
    val toggles: @Composable RowScope.(Modifier) -> Unit = { m ->
        val ref = focus.ref
        fun set(k: String, v: Any, patch: (com.livecompanion.state.TrackInfo) -> com.livecompanion.state.TrackInfo, f: (FocusState) -> FocusState) {
            client.op("track.set", "tr" to ref, "k" to k, "v" to v)
            store.updateFocus(f); store.patchTrack(ref, patch)
        }
        if (focus.armable) {
            LiveIconButton(LiveIconKind.Rec, { set("arm", !focus.arm, { it.copy(arm = !focus.arm) }, { it.copy(arm = !focus.arm) }) }, m, focus.arm, LiveColors.Rec)
        }
        LiveTextButton("M", { set("mute", !focus.mute, { it.copy(mute = !focus.mute) }, { it.copy(mute = !focus.mute) }) }, m, focus.mute, LiveColors.MuteOn)
        LiveTextButton("S", { set("solo", !focus.solo, { it.copy(solo = !focus.solo) }, { it.copy(solo = !focus.solo) }) }, m, focus.solo, LiveColors.Solo)
        if (focus.kind == "audio" || focus.kind == "midi" || focus.kind == "group") {
            val mon = focus.monitor.coerceIn(0, 2)
            LiveTextButton(
                MONITOR_LABELS[mon],
                {
                    val nxt = (mon + 1) % 3
                    set("monitor", nxt, { it.copy(monitor = nxt) }, { it.copy(monitor = nxt) })
                },
                m, mon == 0, LiveColors.Accent, minWidth = 44.dp,
            )
        }
    }
    val faders: @Composable RowScope.() -> Unit = {
        VolFader(store, client, focus, Modifier.weight(2f))
        PanFader(store, client, focus, Modifier.weight(1f))
        MeterBar(
            left = { rt.value.meters.focusL },
            right = { rt.value.meters.focusR },
            modifier = Modifier.weight(0.8f).height(30.dp),
        )
    }

    BoxWithConstraints(modifier.fillMaxWidth().background(LiveColors.Panel)) {
        val gap = Arrangement.spacedBy(2.dp)
        when {
            maxWidth >= 700.dp -> Row(Modifier.padding(2.dp), gap, Alignment.CenterVertically) {
                prev(); nameBtn(Modifier.weight(1.2f)); next()
                toggles(Modifier)
                faders()
            }
            maxWidth >= 420.dp -> Column(Modifier.padding(2.dp), Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                    prev(); nameBtn(Modifier.weight(1f)); next()
                    toggles(Modifier)
                }
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { faders() }
            }
            else -> Column(Modifier.padding(2.dp), Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                    prev(); nameBtn(Modifier.weight(1f)); next()
                }
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { toggles(Modifier.weight(1f)) }
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { faders() }
            }
        }
    }

    if (picker) {
        TrackPickerDialog(store, focus.ref, onDismiss = { picker = false }) { ref ->
            picker = false
            store.focusPending(ref)
            client.op("focus.set", "tr" to ref)
        }
    }
}

@Composable
private fun VolFader(store: LiveStore, client: LiveClient, focus: FocusState, modifier: Modifier) {
    val v = focus.vol
    LiveFader(
        value = v.v,
        onValueChange = { nv ->
            store.updateFocus { it.copy(vol = it.vol.copy(v = nv, str = "")) }
            client.opThrottled("vol:${focus.ref}", "track.set", "tr" to focus.ref, "k" to "vol", "v" to nv)
        },
        modifier = modifier.height(30.dp),
        valueRange = v.min..v.max.coerceAtLeast(v.min + 0.001f),
        defaultValue = 0.85f,
        vertical = false,
        label = v.str.ifEmpty { null },
    )
}

@Composable
private fun PanFader(store: LiveStore, client: LiveClient, focus: FocusState, modifier: Modifier) {
    val p = focus.pan
    LiveFader(
        value = p.v,
        onValueChange = { nv ->
            store.updateFocus { it.copy(pan = it.pan.copy(v = nv, str = "")) }
            client.opThrottled("pan:${focus.ref}", "track.set", "tr" to focus.ref, "k" to "pan", "v" to nv)
        },
        modifier = modifier.height(30.dp),
        valueRange = p.min..p.max.coerceAtLeast(p.min + 0.001f),
        defaultValue = 0f,
        vertical = false,
        bipolar = true,
        color = LiveColors.Solo,
        label = p.str.ifEmpty { null },
    )
}

/** Simple dialog list of tracks, returns and master. No drawer. */
@Composable
fun TrackPickerDialog(store: LiveStore, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val tracks by store.tracks.collectAsState()
    LiveDialog("Track", onDismiss) {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(tracks.all, key = { it.ref }) { t ->
                val sel = t.ref == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .background(if (sel) LiveColors.PanelHi else LiveColors.Panel)
                        .clickable { onPick(t.ref) }
                        .padding(start = if (t.group != null) 20.dp else 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ColorChip(t.color)
                    LiveText(t.name, Modifier.weight(1f), LiveType.Body, if (sel) LiveColors.Accent else LiveColors.Text)
                    if (t.kind == "return" || t.kind == "master" || t.kind == "group") LiveLabel(t.kind)
                }
            }
        }
        LiveDialogButtons { LiveTextButton("Close", onDismiss, minWidth = 64.dp) }
    }
}
