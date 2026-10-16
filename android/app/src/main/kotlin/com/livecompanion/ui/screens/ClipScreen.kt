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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.livecompanion.ui.clip.Btn
import com.livecompanion.ui.clip.ClipEdits
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Clip tab: full-width, left-to-right editor (MIDI piano roll or audio waveform) for the clip opened with clip.open,
 * with the properties in a thin collapsible strip at the bottom. With no clip open, a picker of the focused track's
 * slot clips and arrangement clips (a recording slot is shown in red).
 */
@Composable
fun ClipScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val clip by store.clip.collectAsState()
    val focus by store.focus.collectAsState()
    val slots by store.slots.collectAsState()
    // P2-1-15: the Clip tab follows focus. A clip open on another track is closed; then the new track's playing /
    // recording slot clip opens if there is one, else the clip picker shows.
    var followFor by remember { mutableStateOf<String?>(null) }
    var seenFocus by remember { mutableStateOf(focus.ref) }
    LaunchedEffect(focus.ref) {
        val l = clip.loc
        val ct = l?.get("tr")?.jsonPrimitive?.content
        val stale = l != null && ct != null && ct != focus.ref
        if (stale) client.op("clip.close")
        if (stale || focus.ref != seenFocus) followFor = focus.ref
        seenFocus = focus.ref
    }
    LaunchedEffect(followFor, slots.tr, slots.slots) {
        val f = followFor ?: return@LaunchedEffect
        if (slots.tr != f) return@LaunchedEffect
        followFor = null
        if (clip.loc != null) return@LaunchedEffect
        val live = slots.slots.firstOrNull { it.hasClip && (it.status == "recording" || it.status == "playing") }
        if (live != null) client.op("clip.open", "loc" to Loc.slot(f, live.i))
    }
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
                val rec = s.status == "recording"
                PickRow(
                    c.name.ifBlank { if (c.audio) "Audio" else "MIDI" }, if (rec) "REC  Slot ${s.i + 1}" else "Slot ${s.i + 1}", c.color, c.audio, rec,
                ) { client.op("clip.open", "loc" to Loc.slot(slots.tr.ifBlank { focus.ref }, s.i)) }
            }
            if (arr.clips.isNotEmpty()) item { LiveLabel("Arrangement") }
            items(arr.clips, key = { "a${it.i}" }) { a ->
                PickRow(a.name.ifBlank { if (a.audio) "Audio" else "MIDI" }, "Bar ${(a.start / 4).toInt() + 1}", a.color, a.audio, false) { client.op("clip.open", "loc" to Loc.arr(arr.tr.ifBlank { focus.ref }, a.i)) }
            }
        }
    }
}

@Composable
private fun PickRow(name: String, sub: String, color: Int, audio: Boolean, rec: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(DenseH).background(if (rec) LiveColors.Rec.copy(alpha = 0.28f) else LiveColors.Panel)
            .clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ColorChip(color)
        LiveText(name, Modifier.weight(1f), style = LiveType.Small)
        LiveText(if (audio) "audio" else "midi", style = LiveType.Label)
        LiveText(sub, style = LiveType.Small, color = if (rec) LiveColors.Rec else LiveColors.TextDim)
    }
}

@Composable
private fun ClipEditor(clip: ClipState, store: LiveStore, client: LiveClient, modifier: Modifier) {
    val ops = remember(store, client) { ClipOps(store, client) }
    val ed = remember(clip.loc) { NoteEditor(clip.notes, client) }
    LaunchedEffect(clip.notes) { if (!clip.audio) ed.reconcile(clip.notes) }
    val rt = store.rt.collectAsState() // State object only; read in draw lambdas
    val session by store.session.collectAsState()
    val slots by store.slots.collectAsState()
    val conn by client.state.collectAsState()
    val host = conn.target?.host.orEmpty()
    val edits = remember(store, client) { storeClipEdits(store, client) }
    val close = { client.op("clip.close") }
    // Recording: the clip says so, or it sits in a session slot whose status is "recording".
    val loc = clip.loc
    val slotRec = loc != null && loc["w"]?.jsonPrimitive?.content == "slot" &&
        slots.slots.firstOrNull { it.i == loc["i"]?.jsonPrimitive?.intOrNull }?.status == "recording"
    val rec = clip.rec || slotRec

    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg)) {
        var open by remember(clip.loc) { mutableStateOf(false) }
        val mh = maxHeight
        val mw = maxWidth
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // Landscape: properties are a right-side column (the editor narrows, never shortens). Portrait: bottom
            // panel capped so the editor keeps >= 60% of the height.
            val side = mw > mh
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (clip.audio) AudioEditor(clip, host, rt, edits, session.tempo, rec, Modifier.fillMaxSize())
                    else PianoRoll(clip, ed, rt, edits, session.scale, session.tempo, rec, Modifier.fillMaxSize())
                }
                if (open && side) {
                    Column(
                        Modifier.width(minOf(260.dp, mw * 0.42f)).fillMaxHeight().background(LiveColors.Panel)
                            .verticalScroll(rememberScrollState()).padding(4.dp),
                    ) { ClipProps(clip, ed.takeIf { !clip.audio }, ops, client, null) }
                }
            }
            if (open && !side) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = mh * 0.4f).background(LiveColors.Panel)
                        .verticalScroll(rememberScrollState()).padding(4.dp),
                ) { ClipProps(clip, ed.takeIf { !clip.audio }, ops, client, null) }
            }
            // Thin always-visible handle: tap to expand/collapse the properties.
            Row(
                Modifier.fillMaxWidth().height(26.dp).background(LiveColors.PanelHi),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.weight(1f).fillMaxSize().clickable { open = !open }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiveText(if (open) "v" else "^", style = LiveType.Title, color = LiveColors.Accent)
                    LiveLabel("Properties")
                    ColorChip(clip.color)
                    LiveText(clip.name.ifBlank { if (clip.audio) "Audio" else "MIDI" }, Modifier.weight(1f, fill = false), style = LiveType.Small)
                    LiveText("${clip.signature.getOrElse(0) { 4 }}/${clip.signature.getOrElse(1) { 4 }}", style = LiveType.Small, color = LiveColors.TextDim)
                    if (rec) LiveText("REC", style = LiveType.Small, color = LiveColors.Rec)
                    else if (clip.looping) LiveText("Loop", style = LiveType.Small, color = LiveColors.Accent)
                    Box(Modifier.weight(1f))
                }
                Btn("Close", { close() }, minWidth = 44.dp)
            }
        }
    }
}

/** Marker/loop/warp edits for the opened clip: optimistic store update + op (sent on gesture end). */
private fun storeClipEdits(store: LiveStore, client: LiveClient) = object : ClipEdits {
    private fun loc(): JsonObject? = store.clip.value.loc
    private fun send(key: String, value: Any) = client.op("clip.set", "loc" to loc(), "k" to key, "v" to value)

    override fun set(key: String, value: Double) {
        store.updateClip {
            when (key) {
                "loop_start" -> it.copy(loopStart = value)
                "loop_end" -> it.copy(loopEnd = value)
                "start_marker" -> it.copy(startMarker = value)
                "end_marker" -> it.copy(endMarker = value)
                "sig_num" -> it.copy(signature = listOf(value.toInt(), it.signature.getOrElse(1) { 4 }))
                "sig_den" -> it.copy(signature = listOf(it.signature.getOrElse(0) { 4 }, value.toInt()))
                else -> it
            }
        }
        send(key, if (key.startsWith("sig_")) value.toInt() else value)
    }

    override fun setLoop(start: Double, end: Double) {
        val old = store.clip.value
        store.updateClip { it.copy(loopStart = start, loopEnd = end) }
        // Keep start<end valid at every step on the server.
        if (start < old.loopEnd) { send("loop_start", start); send("loop_end", end) }
        else { send("loop_end", end); send("loop_start", start) }
    }

    override fun setLooping(on: Boolean) {
        store.updateClip { it.copy(looping = on) }
        send("looping", on)
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
