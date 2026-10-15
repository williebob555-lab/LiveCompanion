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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.ClipState
import com.livecompanion.state.Loc
import com.livecompanion.state.LiveStore
import com.livecompanion.state.WarpMarker
import com.livecompanion.ui.clip.AudioEditor
import com.livecompanion.ui.clip.AudioOps
import com.livecompanion.ui.clip.Btn
import com.livecompanion.ui.clip.ClipOps
import com.livecompanion.ui.clip.ClipProps
import com.livecompanion.ui.clip.DenseH
import com.livecompanion.ui.clip.NoteEditor
import com.livecompanion.ui.clip.PianoRoll
import com.livecompanion.ui.components.ColorChip
import com.livecompanion.ui.components.LiveLabel
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType
import kotlinx.serialization.json.JsonObject

/**
 * Clip tab: properties panel + MIDI piano roll or audio waveform for the clip opened with clip.open.
 * With no clip open, a picker of the focused track's slot clips and arrangement clips.
 */
@Composable
fun ClipScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val clip by store.clip.collectAsState()
    if (clip.loc == null) ClipPicker(store, client, modifier) else ClipEditor(clip, store, client, modifier)
}

@Composable
private fun ClipPicker(store: LiveStore, client: LiveClient, modifier: Modifier) {
    val focus by store.focus.collectAsState()
    val slots by store.slots.collectAsState()
    val arr by store.arr.collectAsState()
    val slotClips = slots.slots.filter { it.hasClip && it.clip != null }
    Column(modifier.fillMaxSize().background(LiveColors.Bg).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LiveText("Open a clip on ${focus.name.ifBlank { "this track" }}", style = LiveType.Title)
        if (slotClips.isEmpty() && arr.clips.isEmpty()) {
            LiveText("No clips on this track.", style = LiveType.Small, color = LiveColors.TextDim)
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (slotClips.isNotEmpty()) item { LiveLabel("Session slots") }
            items(slotClips, key = { "s${it.i}" }) { s ->
                val c = s.clip!!
                PickRow(c.name.ifBlank { "Clip" }, "Slot ${s.i + 1}", c.color, c.audio) { client.op("clip.open", "loc" to Loc.slot(slots.tr.ifBlank { focus.ref }, s.i)) }
            }
            if (arr.clips.isNotEmpty()) item { LiveLabel("Arrangement") }
            items(arr.clips, key = { "a${it.i}" }) { a ->
                PickRow(a.name.ifBlank { "Clip" }, "Bar ${(a.start / 4).toInt() + 1}", a.color, a.audio) { client.op("clip.open", "loc" to Loc.arr(arr.tr.ifBlank { focus.ref }, a.i)) }
            }
        }
    }
}

@Composable
private fun PickRow(name: String, sub: String, color: Int, audio: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(DenseH).background(LiveColors.Panel).clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ColorChip(color)
        LiveText(name, Modifier.weight(1f), style = LiveType.Small)
        LiveText(if (audio) "audio" else "midi", style = LiveType.Label)
        LiveText(sub, style = LiveType.Small, color = LiveColors.TextDim)
    }
}

@Composable
private fun ClipEditor(clip: ClipState, store: LiveStore, client: LiveClient, modifier: Modifier) {
    val ops = remember(store, client) { ClipOps(store, client) }
    val ed = remember(clip.loc) { NoteEditor(clip.notes, client) }
    LaunchedEffect(clip.notes) { if (!clip.audio) ed.reconcile(clip.notes) }
    val rt = store.rt.collectAsState() // State object only; read in draw lambdas
    val conn by client.state.collectAsState()
    val host = conn.target?.host.orEmpty()
    val audioOps = remember(store, client) { storeAudioOps(store, client) }
    val close = { client.op("clip.close") }

    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg)) {
        val editor: @Composable (Modifier) -> Unit = { m ->
            if (clip.audio) AudioEditor(clip, host, rt, audioOps, m)
            else PianoRoll(clip, ed, rt, m)
        }
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Column(Modifier.width(272.dp).fillMaxHeight().background(LiveColors.Panel).verticalScroll(rememberScrollState()).padding(4.dp)) {
                    ClipProps(clip, ed.takeIf { !clip.audio }, ops, client, close)
                }
                editor(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val mh = maxHeight
            var open by remember(maxHeight > 420.dp) { mutableStateOf(maxHeight > 420.dp) }
            val short = maxHeight < 360.dp
            Column(
                Modifier.fillMaxSize().then(if (short) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(DenseH).background(LiveColors.Panel).padding(start = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.weight(1f).fillMaxHeight().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LiveText(if (open) "v" else ">", style = LiveType.Small, color = LiveColors.TextDim)
                        ColorChip(clip.color)
                        LiveText(clip.name.ifBlank { "Clip" }, Modifier.weight(1f), style = LiveType.Small)
                    }
                    Btn("Close", { close() })
                }
                if (open) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = mh * 0.42f).background(LiveColors.Panel)
                            .verticalScroll(rememberScrollState()).padding(4.dp),
                    ) { ClipProps(clip, ed.takeIf { !clip.audio }, ops, client, null) }
                }
                editor(if (short) Modifier.fillMaxWidth().height(280.dp) else Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

private fun storeAudioOps(store: LiveStore, client: LiveClient) = object : AudioOps {
    private fun loc(): JsonObject? = store.clip.value.loc
    override fun set(key: String, value: Double) {
        store.updateClip {
            when (key) {
                "loop_start" -> it.copy(loopStart = value)
                "loop_end" -> it.copy(loopEnd = value)
                "start_marker" -> it.copy(startMarker = value)
                "end_marker" -> it.copy(endMarker = value)
                else -> it
            }
        }
        client.op("clip.set", "loc" to loc(), "k" to key, "v" to value)
    }
    override fun warpAdd(beat: Double) = client.op("warp.add", "loc" to loc(), "beat" to beat)
    override fun warpMove(beat: Double, to: Double) {
        store.updateClip { c -> c.copy(warpMarkers = c.warpMarkers.map { if (kotlin.math.abs(it.beat - beat) < 1e-9) WarpMarker(to, it.sampleTime) else it }) }
        client.op("warp.move", "loc" to loc(), "beat" to beat, "to_beat" to to)
    }
    override fun warpRemove(beat: Double) {
        store.updateClip { c -> c.copy(warpMarkers = c.warpMarkers.filter { kotlin.math.abs(it.beat - beat) > 1e-9 }) }
        client.op("warp.remove", "loc" to loc(), "beat" to beat)
    }
}
