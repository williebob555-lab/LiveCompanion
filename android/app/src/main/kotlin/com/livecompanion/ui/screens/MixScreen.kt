package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.livecompanion.state.TrackInfo
import com.livecompanion.ui.components.ColorChip
import com.livecompanion.ui.components.ColorPaletteDialog
import com.livecompanion.ui.components.ConfirmDialog
import com.livecompanion.ui.components.DenseButton
import com.livecompanion.ui.components.DenseSelect
import com.livecompanion.ui.components.DenseTextField
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveFader
import com.livecompanion.ui.components.LiveLabel
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.MeterBar
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType

/**
 * Mix tab: sends (with return names), routing, and the returns + master strip. Name / arm / mute / solo / monitor
 * (and vol / pan outside the 700..900dp band) live in the top bar and are NOT repeated here; track duplicate /
 * delete / add live in the Tracks tab.
 */
@Composable
fun MixScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val focus by store.focus.collectAsState()
    val tracks by store.tracks.collectAsState()
    val rt = store.rt.collectAsState()
    var rename by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf(false) }

    if (focus.ref.isEmpty()) {
        Box(modifier.fillMaxSize().background(LiveColors.Bg), contentAlignment = Alignment.Center) {
            LiveText("No track selected", color = LiveColors.TextDim)
        }
        return
    }
    val f = focus
    val ref = f.ref
    fun set(k: String, v: Any?) = client.op("track.set", "tr" to ref, "k" to k, "v" to v)
    val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)

    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg)) {
        val wide = maxWidth >= 520.dp
        // The top bar already carries name / arm / mute / solo / monitor, and vol+pan except in the 700..900dp band.
        val topBarHasFaders = maxWidth < 700.dp || maxWidth >= 900.dp * fs
        val title: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().background(LiveColors.Panel, LiveShapes.Small).padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(Modifier.clickable { palette = true }.padding(4.dp)) { ColorChip(f.color, size = 14.dp) }
                LiveText(f.name, Modifier.weight(1f).clickable { rename = true }.padding(vertical = 4.dp), LiveType.Title)
                LiveLabel("Sends / routing")
            }
        }
        val left: @Composable () -> Unit = {
            title()
            if (!topBarHasFaders) VolPanStrip(f, rt, 28.dp, store, client, ref)
            SendsAndRouting(f, store, client, ref, set = ::set)
        }
        val right: @Composable () -> Unit = { ReturnsStrip(tracks.returns, tracks.master, ref, store, client, rt) }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) { left() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) { right() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                left(); right()
            }
        }
    }

    if (rename) com.livecompanion.ui.components.NameDialog("Rename track", f.name, { rename = false }) { set("name", it); rename = false }
    if (palette) ColorPaletteDialog(f.color, { c -> store.updateFocus { it.copy(color = c) }; set("color", c) }, { palette = false })
}

@Composable
private fun VolPanStrip(
    f: FocusState, rt: androidx.compose.runtime.State<com.livecompanion.state.RtFrame>,
    faderH: androidx.compose.ui.unit.Dp, store: LiveStore, client: LiveClient, ref: String,
) {
    // Horizontal faders: sensible proportions at any size, no big empty block beside a tall fader.
    Column(
        Modifier.fillMaxWidth().background(LiveColors.Panel, LiveShapes.Small).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiveLabel("Vol", Modifier.width(30.dp))
            LiveFader(
                f.vol.v, { v ->
                    store.updateFocus { it.copy(vol = it.vol.copy(v = v)) }
                    client.opThrottled("mix:$ref:vol", "track.set", "tr" to ref, "k" to "vol", "v" to v)
                },
                Modifier.weight(1f).height(faderH),
                valueRange = f.vol.min..f.vol.max.coerceAtLeast(f.vol.min + 1e-3f), defaultValue = 0.85f.coerceIn(f.vol.min, f.vol.max.coerceAtLeast(0.85f)),
                vertical = false, label = f.vol.str.ifEmpty { null },
            )
            MeterBar({ rt.value.meters.focusL }, { rt.value.meters.focusR }, Modifier.width(48.dp).height(faderH), vertical = false)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiveLabel("Pan", Modifier.width(30.dp))
            LiveFader(
                f.pan.v, { v ->
                    store.updateFocus { it.copy(pan = it.pan.copy(v = v)) }
                    client.opThrottled("mix:$ref:pan", "track.set", "tr" to ref, "k" to "pan", "v" to v)
                },
                Modifier.weight(1f).height(faderH), valueRange = f.pan.min..f.pan.max.coerceAtLeast(f.pan.min + 1e-3f),
                defaultValue = 0f, vertical = false, bipolar = true, label = f.pan.str.ifEmpty { null },
            )
            Box(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun SendsAndRouting(f: FocusState, store: LiveStore, client: LiveClient, ref: String, set: (String, Any?) -> Unit) {
    val returns by store.tracks.collectAsState()
    Column(
        Modifier.fillMaxWidth().background(LiveColors.Panel, LiveShapes.Small).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (f.sends.isNotEmpty()) {
            LiveLabel("Sends")
            f.sends.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiveText(returns.returns.getOrNull(i)?.name ?: s.name.ifEmpty { "Send ${i + 1}" }, Modifier.weight(0.4f), LiveType.Small)
                    LiveFader(
                        s.v, { v ->
                            store.updateFocus { fs -> fs.copy(sends = fs.sends.mapIndexed { j, x -> if (j == i) x.copy(v = v) else x }) }
                            client.opThrottled("mix:$ref:send$i", "track.set", "tr" to ref, "k" to "send", "i" to i, "v" to v)
                        },
                        Modifier.weight(0.6f).height(26.dp), valueRange = s.min..s.max.coerceAtLeast(s.min + 1e-3f),
                        defaultValue = s.min, vertical = false, label = s.str.ifEmpty { null },
                    )
                }
            }
        }
        if (f.inTypes.isNotEmpty() || f.outTypes.isNotEmpty()) {
            LiveLabel("Routing")
            if (f.inTypes.isNotEmpty()) RouteRow("In", f.inType, f.inTypes) { set("in_type", it) }
            if (f.inChs.isNotEmpty()) RouteRow("In ch", f.inCh, f.inChs) { set("in_ch", it) }
            if (f.outTypes.isNotEmpty()) RouteRow("Out", f.outType, f.outTypes) { set("out_type", it) }
        }
        if (f.sends.isEmpty() && f.inTypes.isEmpty() && f.outTypes.isEmpty()) {
            LiveText("No sends or routing for this track", color = LiveColors.TextDim, style = LiveType.Small)
        }
    }
}

@Composable
private fun RouteRow(label: String, value: String, items: List<String>, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LiveText(label, Modifier.weight(0.4f), LiveType.Small, color = LiveColors.TextDim)
        DenseSelect(label, value, items, { _, s -> onPick(s) }, Modifier.weight(0.6f))
    }
}

@Composable
private fun ReturnsStrip(
    returns: List<TrackInfo>, master: TrackInfo?, focusRef: String, store: LiveStore, client: LiveClient,
    rt: androidx.compose.runtime.State<com.livecompanion.state.RtFrame>,
) {
    if (returns.isEmpty() && master == null) return
    Column(Modifier.fillMaxWidth().background(LiveColors.Panel, LiveShapes.Small).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LiveLabel("Returns / Master")
        for (t in returns + listOfNotNull(master)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ColorChip(t.color)
                LiveText(t.name, Modifier.weight(1f), LiveType.Small)
                t.vol?.let { vp ->
                    LiveFader(
                        vp.v, { v ->
                            store.patchTrack(t.ref) { it.copy(vol = vp.copy(v = v)) }
                            client.opThrottled("mix:${t.ref}:vol", "track.set", "tr" to t.ref, "k" to "vol", "v" to v)
                        },
                        Modifier.width(96.dp).height(24.dp), valueRange = vp.min..vp.max.coerceAtLeast(vp.min + 1e-3f),
                        defaultValue = 0.85f.coerceIn(vp.min, vp.max.coerceAtLeast(0.85f)), vertical = false,
                        label = vp.str.ifEmpty { null },
                    )
                }
                // Same columns for returns and master: fader | M | S | Edit (master shows its meter in the M/S spacer).
                if (t.kind == "master") Box(Modifier.width(67.dp).height(32.dp).padding(vertical = 12.dp)) {
                    MeterBar({ rt.value.meters.masterL }, { rt.value.meters.masterR }, Modifier.fillMaxWidth().height(8.dp))
                } else {
                    DenseButton("M", { store.patchTrack(t.ref) { it.copy(mute = !t.mute) }; client.op("track.set", "tr" to t.ref, "k" to "mute", "v" to !t.mute) }, Modifier.width(32.dp), on = t.mute, onColor = LiveColors.MuteOn)
                    DenseButton("S", { store.patchTrack(t.ref) { it.copy(solo = !t.solo) }; client.op("track.set", "tr" to t.ref, "k" to "solo", "v" to !t.solo) }, Modifier.width(32.dp), on = t.solo, onColor = LiveColors.Solo)
                }
                DenseButton("Edit", { client.op("focus.set", "tr" to t.ref) }, Modifier.width(44.dp), on = t.ref == focusRef)
            }
        }
    }
}
