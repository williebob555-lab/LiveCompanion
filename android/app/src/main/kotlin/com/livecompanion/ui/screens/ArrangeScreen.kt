package com.livecompanion.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livecompanion.net.LiveClient
import com.livecompanion.net.Peaks
import com.livecompanion.net.PeaksClient
import com.livecompanion.net.PeaksResult
import com.livecompanion.net.jsonArgs
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Loc
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.components.LiveTextField
import com.livecompanion.ui.components.TLoop
import com.livecompanion.ui.components.TimelineView
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.track.LaneActions
import com.livecompanion.ui.track.LaneModel
import com.livecompanion.ui.track.PlayheadClock
import com.livecompanion.ui.track.RecState
import com.livecompanion.ui.track.Snap
import com.livecompanion.ui.track.TrackLane
import com.livecompanion.ui.track.beatUnit
import com.livecompanion.ui.track.beatsPerBar
import com.livecompanion.ui.track.effectiveGrid
import com.livecompanion.ui.track.parsePos
import com.livecompanion.ui.track.posString
import com.livecompanion.ui.track.roundBeat
import com.livecompanion.ui.track.snapTo
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

private val DenseH = 28.dp

/** Live's clip palette approximations for the color picker. */
internal val ClipPalette = listOf(
    0xFF3A3A, 0xFF9B3D, 0xF7D12E, 0xBFFB00, 0x1AFF2F, 0x25FFA8, 0x5CFFE8, 0x1ABCF2,
    0x4F87FF, 0x9A6BFF, 0xFF7AF6, 0xFF4FA8, 0xB3B3B3, 0x7A7A7A, 0xA9835F, 0xD3A0D3,
)

/**
 * Track view: the focused track's arrangement lane, full width. Live-style bar/beat ruler (follows the song
 * signature), clip blocks with waveform / note previews, loop brace, punch, locators and a growing RED region while
 * recording. Direct manipulation: drag ruler = scrub, hold+drag clip = move, drag selected edges = trim, drag loop
 * brace, pinch/two-finger zoom+pan, double-tap empty lane = new MIDI clip. See [TrackLane] for the gestures.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArrangeScreen(
    store: LiveStore, client: LiveClient, modifier: Modifier = Modifier,
    initialSel: Int = -1, initialShowTakes: Boolean = false,
) {
    val arr by store.arr.collectAsState()
    val session by store.session.collectAsState()
    val focus by store.focus.collectAsState()
    val takes by store.takes.collectAsState()
    val clipSec by store.clip.collectAsState()
    val conn by client.state.collectAsState()
    val goTab = LocalTabNavigator.current
    val scope = rememberCoroutineScope()
    val tr = arr.tr.ifEmpty { focus.ref }
    val view = remember { TimelineView() }
    val clock = remember { PlayheadClock() }
    val rec = remember { RecState() }
    var follow by remember { mutableStateOf(false) }
    var sel by remember { mutableIntStateOf(initialSel) }
    var selTake by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var snap by remember { mutableStateOf(Snap.S16) }
    var showTakes by remember { mutableStateOf(initialShowTakes) }
    var dialog by remember { mutableStateOf<ArrDialog?>(null) }

    // Selection belongs to a track; drop it when the focus changes.
    var lastTr by remember { mutableStateOf(tr) }
    LaunchedEffect(tr) { if (tr != lastTr) { sel = -1; selTake = null; lastTr = tr } }

    // Sync selection from the opened clip (e.g. chosen elsewhere).
    LaunchedEffect(clipSec.loc) {
        val l = clipSec.loc?.jsonObject
        if (l != null && l["w"]?.jsonPrimitive?.contentOrNull == "arr" && l["tr"]?.jsonPrimitive?.contentOrNull == tr) {
            l["i"]?.jsonPrimitive?.intOrNull?.let { sel = it; selTake = null }
        }
    }

    val bpb = beatsPerBar(session.sigNum, session.sigDen)
    val unit = beatUnit(session.sigDen)
    val clips = arr.clips
    val selClip = clips.firstOrNull { it.i == sel }
    val grid = snap.beats(unit, bpb)

    // ---- playhead clock + recording progression (draw-only state, see PlayheadClock)
    SideEffect { clock.setSong(session.tempo.toDouble(), session.loop, session.loopStart, session.loopLen) }
    LaunchedEffect(store) {
        clock.onFrame(session.pos, session.playing, System.nanoTime())
        store.rt.collect { f -> clock.onFrame(f.pos, f.playing, System.nanoTime()) }
    }
    val recActive = session.rec && session.playing && focus.armable && focus.arm
    SideEffect {
        rec.punchIn = session.punchIn; rec.punchOut = session.punchOut
        rec.loopStart = session.loopStart; rec.loopEnd = session.loopStart + session.loopLen
    }
    LaunchedEffect(recActive) {
        if (recActive) rec.start = clock.shown
        rec.active = recActive
    }

    // ---- waveform peaks for audio clips (helper, cached)
    val peaks: SnapshotStateMap<String, Peaks> = remember { mutableStateMapOf() }
    val host = conn.target?.host.orEmpty()
    val paths = clips.filter { it.audio && it.filePath.isNotBlank() }.map { it.filePath }.distinct()
    LaunchedEffect(paths, host) {
        for (p in paths) {
            if (p in peaks) continue
            val cached = PeaksClient.shared.cached(p, PEAK_N)
            if (cached != null) { peaks[p] = cached; continue }
            val r = PeaksClient.shared.get(host, p, PEAK_N)
            if (r is PeaksResult.Ok) peaks[p] = r.peaks
        }
    }

    // Fit once content (or at least a few bars) is known; refit on track change.
    var fitKey by remember { mutableStateOf("") }
    val contentKey = "$tr:${clips.size}"
    LaunchedEffect(contentKey, view.widthPx) {
        if (view.widthPx > 0f && (!view.fitted || fitKey != tr)) {
            fitArrange(view, clips.map { it.start to it.end }, session.loopStart, session.loopLen, bpb)
            fitKey = tr
        }
    }

    val model = remember(clips, takes, showTakes, sel, selTake, bpb, unit, grid, session.tempo, session.loop, session.loopStart, session.loopLen,
        session.punchIn, session.punchOut, session.cues, focus.kind) {
        LaneModel(
            clips = clips, takes = if (takes.supported) takes.lanes else emptyList(), showTakes = showTakes && takes.supported,
            sel = sel, selTake = selTake, bpb = bpb, unit = unit, grid = grid, tempo = session.tempo.toDouble(),
            loop = TLoop(session.loop, session.loopStart, session.loopLen),
            punchIn = session.punchIn, punchOut = session.punchOut, cues = session.cues, midi = focus.kind == "midi",
        )
    }

    fun seek(beat: Double) {
        val b = max(0.0, roundBeat(beat))
        clock.jumpTo(b, System.nanoTime())
        client.op("song.seek", "beats" to b)
        store.updateSession { it.copy(pos = b) }
    }

    val actions = remember(client, store, tr, session.playing) {
        LaneActions(
            onScrub = { beat, done ->
                val now = System.nanoTime()
                clock.scrubTo(beat, now)
                if (done) {
                    clock.endScrub(now)
                    client.op("song.seek", "beats" to beat)
                    store.updateSession { it.copy(pos = beat) }
                } else {
                    client.sendThrottled("seek", "song.seek", jsonArgs("beats" to beat), 33)
                }
            },
            onLoop = { s, l, done ->
                client.sendThrottled("loop", "song.set", jsonArgs("k" to "loop_start", "v" to s), 40)
                client.sendThrottled("looplen", "song.set", jsonArgs("k" to "loop_len", "v" to l), 40)
                if (done) store.updateSession { it.copy(loopStart = s, loopLen = l) }
            },
            onLoopToggle = {
                client.op("song.set", "k" to "loop", "v" to !store.session.value.loop)
                store.updateSession { it.copy(loop = !it.loop) }
            },
            onSelectArr = { i -> sel = i; selTake = null },
            onOpenArr = { i ->
                sel = i; selTake = null
                client.op("clip.open", "loc" to Loc.arr(tr, i))
                goTab(Tab.Clip)
            },
            onSelectTake = { lane, i -> selTake = lane to i; sel = -1 },
            onDeselect = { sel = -1; selTake = null },
            onMove = { i, start ->
                scope.launch {
                    val r = client.requestOrNull("arr.move", jsonArgs("loc" to Loc.arr(tr, i), "start" to start))
                    (r as? kotlinx.serialization.json.JsonObject)?.get("i")?.jsonPrimitive?.intOrNull?.let { sel = it }
                }
            },
            onTrim = { i, s, e ->
                val a = ArrayList<Pair<String, Any?>>()
                a.add("loc" to Loc.arr(tr, i))
                if (s != null) a.add("start" to s)
                if (e != null) a.add("end" to e)
                client.send("arr.trim", jsonArgs(*a.toTypedArray()))
            },
            onCreate = { beat ->
                if (store.focus.value.kind != "midi") {
                    store.notice("Double-tap creates MIDI clips on MIDI tracks only")
                } else {
                    val g = grid
                    val len = bpb
                    val start = if (g > 0.0 && g < bpb) snapTo(beat, g) else floor(beat / bpb) * bpb
                    client.op("arr.create_midi", "tr" to tr, "start" to max(0.0, start), "len" to len)
                }
            },
        )
    }

    Column(modifier.fillMaxSize().background(LiveColors.Bg), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // ---- locate + snap + toggles
        FlowRow(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val m = Modifier
            PosReadout(clock, bpb, unit) { dialog = ArrDialog.Locate }
            DBtn("<", {
                val g = effectiveGrid(grid)
                seek(max(0.0, ceil(clock.shown / g - 1e-6) * g - g))
            }, m)
            DBtn(">", {
                val g = effectiveGrid(grid)
                seek(floor(clock.shown / g + 1e-6) * g + g)
            }, m)
            for (s in Snap.entries) DBtn(s.label, { snap = s }, m, on = snap == s)
            DBtn("Follow", { follow = !follow }, m, on = follow)
            DBtn("Loop", { actions.onLoopToggle() }, m, on = session.loop)
            DBtn("In", { client.op("song.set", "k" to "punch_in", "v" to !session.punchIn); store.updateSession { it.copy(punchIn = !it.punchIn) } }, m, on = session.punchIn, onColor = LiveColors.Rec)
            DBtn("Out", { client.op("song.set", "k" to "punch_out", "v" to !session.punchOut); store.updateSession { it.copy(punchOut = !it.punchOut) } }, m, on = session.punchOut, onColor = LiveColors.Rec)
            DBtn("Fit", { fitArrange(view, clips.map { it.start to it.end }, session.loopStart, session.loopLen, bpb) }, m)
            if (takes.supported && takes.lanes.isNotEmpty()) DBtn("Takes", { showTakes = !showTakes }, m, on = showTakes)
        }

        // ---- the lane
        TrackLane(
            view = view, model = model, actions = actions, clock = clock, rec = rec, peaks = peaks, follow = follow,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        // ---- selection toolbar (rare actions as visible buttons, no menus)
        Column(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).background(LiveColors.Panel).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val st = selTake
            if (selClip != null) {
                val loc = Loc.arr(tr, selClip.i)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(liveColor(selClip.color), LiveShapes.Small))
                    LiveText(
                        selClip.name.ifEmpty { if (selClip.audio) "Audio" else "MIDI" },
                        Modifier.widthIn(max = 140.dp), LiveType.Small,
                    )
                    LiveText(
                        "${posString(selClip.start, bpb, unit)} - ${posString(selClip.end, bpb, unit)}",
                        style = LiveType.Small, color = LiveColors.TextDim, modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    DBtn("Edit", { client.op("clip.open", "loc" to loc); goTab(Tab.Clip) })
                    DBtn("Dup", { client.op("clip.to_arr", "loc" to loc, "time" to selClip.end) })
                    DBtn("Mute", { client.op("clip.set", "loc" to loc, "k" to "muted", "v" to !selClip.muted) }, on = selClip.muted, onColor = LiveColors.MuteOn)
                    DBtn("Rename", { dialog = ArrDialog.Rename })
                    DBtn("Color", { dialog = ArrDialog.Color })
                    DBtn("Delete", { client.op("clip.delete", "loc" to loc); sel = -1 }, on = true, onColor = LiveColors.Rec)
                }
            } else if (st != null) {
                val tl = takes.lanes.firstOrNull { it.i == st.first }
                val tc = tl?.clips?.firstOrNull { it.i == st.second }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    LiveText(tc?.name?.ifEmpty { tl.name } ?: "Take", Modifier.widthIn(max = 160.dp), LiveType.Small)
                    if (tc != null) LiveText("${posString(tc.start, bpb, unit)} - ${posString(tc.end, bpb, unit)}", style = LiveType.Small, color = LiveColors.TextDim, modifier = Modifier.padding(horizontal = 4.dp))
                    DBtn("To main lane", { client.op("takes.promote", "loc" to Loc.take(tr, st.first, st.second)) })
                    DBtn("Edit", { client.op("clip.open", "loc" to Loc.take(tr, st.first, st.second)); goTab(Tab.Clip) })
                }
            } else {
                LiveText(
                    if (clips.isEmpty()) "No clips on this track - double-tap the lane to create a MIDI clip"
                    else "Tap clip = select, hold + drag = move, drag selected edges = trim, drag ruler = scrub",
                    style = LiveType.Small, color = LiveColors.TextDim, maxLines = 2,
                )
            }
        }
    }

    val sc = selClip
    when (dialog) {
        ArrDialog.Rename -> if (sc != null) RenameClipDialog(sc.name, { dialog = null }) { n ->
            client.op("clip.set", "loc" to Loc.arr(tr, sc.i), "k" to "name", "v" to n); dialog = null
        } else dialog = null
        ArrDialog.Color -> if (sc != null) ColorDialog({ dialog = null }) { c ->
            client.op("clip.set", "loc" to Loc.arr(tr, sc.i), "k" to "color", "v" to c); dialog = null
        } else dialog = null
        ArrDialog.Locate -> LocateDialog(posString(clock.shown, bpb, unit), bpb, unit, { dialog = null }) { b ->
            seek(b); dialog = null
        }
        null -> Unit
    }
}

private const val PEAK_N = 1024

private enum class ArrDialog { Rename, Color, Locate }

/** Playhead position as bar.beat.sixteenth, drawn (not composed) so it ticks at frame rate for free. Tap to type a position. */
@Composable
private fun PosReadout(clock: PlayheadClock, bpb: Double, unit: Double, onClick: () -> Unit) {
    val tm = rememberTextMeasurer()
    val style = LiveType.Mono.copy(fontSize = 12.sp, color = LiveColors.Text)
    val w = with(LocalDensity.current) { (12.sp * 7.2f).toDp() } + 12.dp
    Canvas(
        Modifier.size(w, DenseH)
            .background(LiveColors.Bg, LiveShapes.Small).border(1.dp, LiveColors.Line, LiveShapes.Small)
            .clickable(onClick = onClick),
    ) {
        val l = tm.measure(posString(clock.shown, bpb, unit), style, maxLines = 1)
        drawText(l, topLeft = Offset((size.width - l.size.width) / 2f, (size.height - l.size.height) / 2f))
    }
}

@Composable
private fun LocateDialog(current: String, bpb: Double, unit: Double, onDismiss: () -> Unit, onOk: (Double) -> Unit) {
    var text by remember { mutableStateOf(current) }
    var err by remember { mutableStateOf(false) }
    val go = {
        val b = parsePos(text, bpb, unit)
        if (b == null) err = true else onOk(b)
    }
    LiveDialog("Locate (bar.beat.sixteenth)", onDismiss) {
        LiveTextField(text, { text = it; err = false }, onDone = { go() })
        if (err) LiveText("Use bar.beat.sixteenth, e.g. 5.2.3 (numbers from 1)", style = LiveType.Small, color = LiveColors.Rec, maxLines = 2)
        LiveDialogButtons {
            LiveTextButton("Cancel", onDismiss, minWidth = 72.dp)
            LiveTextButton("Go", { go() }, on = true, minWidth = 72.dp)
        }
    }
}

private fun fitArrange(view: TimelineView, spans: List<Pair<Double, Double>>, loopStart: Double, loopLen: Double, bpb: Double) {
    var from = spans.minOfOrNull { it.first } ?: 0.0
    var to = spans.maxOfOrNull { it.second } ?: 0.0
    if (spans.isEmpty()) { from = 0.0; to = bpb * 8 }
    if (loopLen > 0 && spans.isEmpty()) { from = min0(from, loopStart); to = max(to, loopStart + loopLen) }
    view.fit(from, max(to, from + bpb * 2))
}

private fun min0(a: Double, b: Double) = if (a < b) a else b

internal fun barPos(beat: Double, bpb: Double): String {
    val bar = floor(beat / bpb).toInt() + 1
    val inBar = beat - (bar - 1) * bpb
    return "$bar.${floor(inBar).toInt() + 1}"
}

internal fun fmtBars(v: Double): String = if (v == Math.rint(v)) v.toInt().toString() else "%.2f".format(v)

/** Dense (28dp) text button used across the arrangement/takes screens. */
@Composable
internal fun DBtn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onColor: androidx.compose.ui.graphics.Color = LiveColors.Accent,
    enabled: Boolean = true,
) = LiveTextButton(label, onClick, modifier, on, onColor, enabled, minHeight = DenseH, minWidth = 36.dp)

@Composable
internal fun RenameClipDialog(initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    LiveDialog("Rename", onDismiss) {
        LiveTextField(text, { text = it }, onDone = { onOk(text) })
        LiveDialogButtons {
            LiveTextButton("Cancel", onDismiss, minWidth = 72.dp)
            LiveTextButton("OK", { onOk(text) }, on = true, minWidth = 72.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    LiveDialog("Clip color", onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in ClipPalette) {
                Box(
                    Modifier.size(34.dp).background(liveColor(c), LiveShapes.Small)
                        .border(1.dp, LiveColors.Line, LiveShapes.Small)
                        .clickable { onPick(c) },
                )
            }
        }
        LiveDialogButtons { LiveTextButton("Cancel", onDismiss, minWidth = 72.dp) }
    }
}
