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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livecompanion.data.Prefs
import com.livecompanion.net.LiveClient
import com.livecompanion.net.Peaks
import com.livecompanion.net.PeaksClient
import com.livecompanion.net.PeaksResult
import com.livecompanion.net.jsonArgs
import com.livecompanion.net.peaksKey
import com.livecompanion.net.peaksVer
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Loc
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveIconButton
import com.livecompanion.ui.components.LiveIconKind
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.NameDialog
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

private val DenseH = 28.dp
/** Slim control height for the arrangement strip (keeps the lane tall). */
private val SlimH = 24.dp

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
    val bodyCorners = LocalBodyCorners.current
    val scope = rememberCoroutineScope()
    val tr = arr.tr.ifEmpty { focus.ref }
    val view = remember { TimelineView() }
    val clock = remember { PlayheadClock() }
    val rec = remember { RecState().also { it.env = com.livecompanion.state.RecEnvBuf.shared } }
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
        store.rt.collect { f -> clock.onFrame(f.pos, f.playing, System.nanoTime()); com.livecompanion.state.RecEnvBuf.shared.accept(f.recEnv) }
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

    // ---- waveform peaks for every audio clip of the focused track (in-Live clip.peaks, helper fallback).
    // One retrying request loop per clip (PeaksClient.await): requested on appear, on file/length change and when recording
    // STOPS (the effect restarts: immediate ask, then again after ~1.5 s - Live writes the .asd at record end). Failures and
    // approx results are never cached and never end the loop; the reason shows in the clip until the real waveform arrives.
    val peaks: SnapshotStateMap<String, Peaks> = remember { mutableStateMapOf() }
    val wfNote: SnapshotStateMap<String, String> = remember { mutableStateMapOf() }
    val host = conn.target?.host.orEmpty()
    SideEffect { PeaksClient.shared.live = client }
    val peakSrc = clips.filter { it.audio && it.filePath.isNotBlank() }.distinctBy { peaksKey(it.filePath, it.end - it.start) }
        .map { Triple(it.filePath, it.end - it.start, buildJsonObject { put("w", "arr"); put("tr", tr); put("i", it.i) }) }
    LaunchedEffect(peakSrc, host, recActive) {
        val alive = peakSrc.map { peaksKey(it.first, it.second) }.toSet()
        peaks.keys.filter { it !in alive }.forEach { peaks.remove(it) }
        wfNote.keys.filter { it !in alive }.forEach { wfNote.remove(it) }
        for ((p, len, loc) in peakSrc) {
            val key = peaksKey(p, len)
            launch {
                PeaksClient.shared.await(host, p, PEAK_N, loc, peaksVer(len)) { r ->
                    when (r) {
                        is PeaksResult.Ok -> {
                            peaks[key] = r.peaks
                            if (r.peaks.approx) wfNote[key] = "" else wfNote.remove(key)
                        }
                        is PeaksResult.Err -> wfNote[key] = if (r.msg.startsWith("Needs script update")) "Needs script update - restart Live" else r.msg.take(48)
                    }
                }
            }
        }
    }
    val anyReal = peaks.isNotEmpty()
    val waveFade by androidx.compose.animation.core.animateFloatAsState(if (anyReal) 1f else 0f, androidx.compose.animation.core.tween(350), label = "wf")

    // Zoom limits: whole arrangement (+ margin) fills the width .. 1/64 note per ~24dp.
    val dens = LocalDensity.current.density
    val contentEnd = max(max(clips.maxOfOrNull { it.end } ?: 0.0, bpb * 4), max(session.songLen, session.loopStart + session.loopLen))
    SideEffect { view.setLimits(contentEnd, dens, bpb) }

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
        session.punchIn, session.punchOut, session.cues, focus.kind, tr) {
        LaneModel(
            clips = clips, takes = if (takes.supported) takes.lanes else emptyList(), showTakes = showTakes && takes.supported,
            sel = sel, selTake = selTake, bpb = bpb, unit = unit, grid = grid, tempo = session.tempo.toDouble(),
            loop = TLoop(session.loop, session.loopStart, session.loopLen),
            punchIn = session.punchIn, punchOut = session.punchOut, cues = session.cues, midi = focus.kind == "midi", trackRef = tr,
        )
    }

    fun seek(beat: Double) {
        val b = max(0.0, roundBeat(beat))
        clock.jumpTo(b, System.nanoTime())
        client.op("song.seek", "beats" to b)
        store.editSession({ it.copy(pos = b) }, { it.playing || kotlin.math.abs(it.pos - b) < 0.05 })
    }

    // Move/trim: the server answers with the edited clip's NEW index (moving the left edge re-creates the clip in Live),
    // so the selection follows it. Audio cannot extend past its file: the server clamps and says so (not an error).
    suspend fun editArr(what: String, op: String, args: kotlinx.serialization.json.JsonObject) {
        try {
            val r = client.request(op, args) as? kotlinx.serialization.json.JsonObject
            r?.get("i")?.jsonPrimitive?.intOrNull?.let { sel = it; selTake = null }
            if (r?.get("clamped")?.jsonPrimitive?.booleanOrNull == true) store.notice("Limited to the length of the audio file")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val m = (e.message ?: "").removePrefix("unsupported: ").removePrefix("error: ")
            store.notice("Couldn't $what: ${m.ifBlank { "Live refused" }}")
        }
    }

    val takeOps = remember(store, client, scope) { com.livecompanion.ui.track.TakeOps(store, client, scope) }
    val actions = remember(client, store, tr, session.playing) {
        LaneActions(
            onPromote = { lane, i -> takeOps.promote(tr, lane, i) },
            onDemote = { i, lane -> takeOps.demote(tr, i, lane) },
            onTakeMove = { lane, i, to -> takeOps.move(tr, lane, i, to) },
            onScrub = { beat, done ->
                val now = System.nanoTime()
                clock.scrubTo(beat, now)
                if (done) {
                    clock.endScrub(now)
                    client.op("song.seek", "beats" to beat)
                    store.editSession({ it.copy(pos = beat) }, { it.playing || kotlin.math.abs(it.pos - beat) < 0.05 })
                } else {
                    client.sendThrottled("seek", "song.seek", jsonArgs("beats" to beat), 33)
                }
            },
            onLoop = { s, l, done ->
                client.sendThrottled("loop", "song.set", jsonArgs("k" to "loop_start", "v" to s), 40)
                client.sendThrottled("looplen", "song.set", jsonArgs("k" to "loop_len", "v" to l), 40)
                if (done) store.editSession({ it.copy(loopStart = s, loopLen = l) })
            },
            onCueMove = { i, t, done ->
                // The server fallback (delete + re-create) is not a per-frame op: only the final position is sent.
                if (done) {
                    client.op("cue.move", "i" to i, "time" to t)
                    store.editSession({ ss ->
                        ss.copy(cues = ss.cues.mapIndexed { ci, c -> if (ci == i) c.copy(t = t) else c }.sortedBy { it.t })
                    })
                }
            },
            onLoopToggle = {
                val on = !store.session.value.loop
                client.op("song.set", "k" to "loop", "v" to on)
                store.editSession({ it.copy(loop = on) })
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
                scope.launch { editArr("move the clip", "arr.move", jsonArgs("loc" to Loc.arr(tr, i), "start" to start)) }
            },
            onTrim = { i, s, e ->
                val a = ArrayList<Pair<String, Any?>>()
                a.add("loc" to Loc.arr(tr, i))
                if (s != null) a.add("start" to s)
                if (e != null) a.add("end" to e)
                scope.launch { editArr("trim the clip", "arr.trim", jsonArgs(*a.toTypedArray())) }
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

    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var nudgeTo by remember { mutableStateOf<Pair<Int, Double>?>(null) }
    LaunchedEffect(clips) { nudgeTo = null }

    Column(modifier.fillMaxSize().background(LiveColors.Bg), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        // ---- the lane (ruler + loop brace on top; every control lives in the slim strips BELOW so the lane stays tall)
        TrackLane(
            view = view, model = model, actions = actions, clock = clock, rec = rec, peaks = peaks, follow = follow, wfNote = wfNote, waveFade = waveFade,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        // ---- ONE slim control row: locate, step, snap (tap cycles), follow, punch, fit, takes
        FlowRow(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(start = bodyCorners.bottomStart, end = bodyCorners.bottomEnd).padding(horizontal = 2.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            PosReadout(clock, bpb, unit) { dialog = ArrDialog.Locate }
            SBtn("<", {
                val g = effectiveGrid(grid)
                seek(max(0.0, ceil(clock.shown / g - 1e-6) * g - g))
            })
            SBtn(">", {
                val g = effectiveGrid(grid)
                seek(floor(clock.shown / g + 1e-6) * g + g)
            })
            SBtn("Snap ${snap.label}", { snap = Snap.entries[(snap.ordinal + 1) % Snap.entries.size] }, minWidth = 62.dp)
            SBtn("Follow", { follow = !follow }, on = follow)
            SBtn("In", { val on = !session.punchIn; client.op("song.set", "k" to "punch_in", "v" to on); store.editSession({ it.copy(punchIn = on) }) }, on = session.punchIn, onColor = LiveColors.Rec)
            SBtn("Out", { val on = !session.punchOut; client.op("song.set", "k" to "punch_out", "v" to on); store.editSession({ it.copy(punchOut = on) }) }, on = session.punchOut, onColor = LiveColors.Rec)
            SBtn("Fit", { fitArrange(view, clips.map { it.start to it.end }, session.loopStart, session.loopLen, bpb) })
            if (takes.supported && takes.lanes.isNotEmpty()) SBtn("Takes", { showTakes = !showTakes }, on = showTakes)
        }

        // ---- first-run tip: shown once EVER (persisted), dismissible; the same text lives in More -> Help
        var tipGone by remember { mutableStateOf(false) }
        val tipFirstTime = remember { !prefs.arrangeTipSeen }
        val showTip = tipFirstTime && !tipGone && selClip == null && selTake == null
        LaunchedEffect(tipFirstTime) { if (tipFirstTime) prefs.arrangeTipSeen = true }
        if (showTip) Row(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(start = bodyCorners.bottomStart, end = bodyCorners.bottomEnd).padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LiveText(ARRANGE_HELP, Modifier.weight(1f), LiveType.Small, color = LiveColors.TextDim, maxLines = 2)
            SBtn("Got it", { tipGone = true })
        }

        // ---- selection strip (visible buttons, no menus); absent when nothing is selected
        if (selClip != null || selTake != null) Column(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(start = bodyCorners.bottomStart, end = bodyCorners.bottomEnd).padding(horizontal = 2.dp, vertical = 1.dp),
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
                        Modifier.widthIn(max = 110.dp), LiveType.Small,
                    )
                    LiveText(
                        posString(selClip.start, bpb, unit),
                        style = LiveType.Small, color = LiveColors.TextDim, modifier = Modifier.padding(horizontal = 2.dp),
                    )
                    // Non-gesture alternative to hold-drag: nudge by the snap step (1/16 when snap is off).
                    val g = effectiveGrid(grid)
                    val nudge = { dir: Int ->
                        val base = nudgeTo?.takeIf { it.first == selClip.i }?.second ?: selClip.start
                        val ns = max(0.0, roundBeat(base + dir * g))
                        if (ns != base) {
                            nudgeTo = selClip.i to ns
                            actions.onMove(selClip.i, ns)
                        }
                    }
                    LiveIconButton(LiveIconKind.Prev, { nudge(-1) }, minHeight = SlimH, minWidth = 30.dp)
                    LiveIconButton(LiveIconKind.Next, { nudge(1) }, minHeight = SlimH, minWidth = 30.dp)
                    SBtn("Edit", { client.op("clip.open", "loc" to loc); goTab(Tab.Clip) })
                    SBtn("Dup", { client.op("clip.to_arr", "loc" to loc, "time" to selClip.end) })
                    SBtn("Mute", { client.op("clip.set", "loc" to loc, "k" to "muted", "v" to !selClip.muted) }, on = selClip.muted, onColor = LiveColors.MuteOn)
                    SBtn("Rename", { dialog = ArrDialog.Rename })
                    SBtn("Color", { dialog = ArrDialog.Color })
                    SBtn("Delete", { client.op("clip.delete", "loc" to loc); sel = -1 }, on = true, onColor = LiveColors.Rec)
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
                    SBtn("To main lane", { takeOps.promote(tr, st.first, st.second) })
                    SBtn("Edit", { client.op("clip.open", "loc" to Loc.take(tr, st.first, st.second)); goTab(Tab.Clip) })
                }
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
        ArrDialog.Locate -> LocateDialog(posString(clock.shown, bpb, unit), bpb, unit, (session.songLen / bpb).toInt() + 1000, { dialog = null }) { b ->
            seek(b); dialog = null
        }
        null -> Unit
    }
}

private const val PEAK_N = 1024

internal const val ARRANGE_HELP = "Tap clip = select, hold + drag = move, drag selected edges = trim, drag ruler = scrub, double-tap empty lane = new MIDI clip. With Takes shown: hold + drag a take up into the main lane = promote, a clip down into a take lane = demote."


private enum class ArrDialog { Rename, Color, Locate }

/** Playhead position as bar.beat.sixteenth, drawn (not composed) so it ticks at frame rate for free. Tap to type a position. */
@Composable
private fun PosReadout(clock: PlayheadClock, bpb: Double, unit: Double, onClick: () -> Unit) {
    val tm = rememberTextMeasurer()
    val style = LiveType.Mono.copy(fontSize = 12.sp, color = LiveColors.Text)
    val w = with(LocalDensity.current) { (12.sp * 7.2f).toDp() } + 12.dp
    Canvas(
        Modifier.size(w, SlimH)
            .background(LiveColors.Bg, LiveShapes.Small).border(1.dp, LiveColors.Line, LiveShapes.Small)
            .clickable(onClick = onClick),
    ) {
        val l = tm.measure(posString(clock.shown, bpb, unit), style, maxLines = 1)
        drawText(l, topLeft = Offset((size.width - l.size.width) / 2f, (size.height - l.size.height) / 2f))
    }
}

@Composable
private fun LocateDialog(current: String, bpb: Double, unit: Double, maxBar: Int, onDismiss: () -> Unit, onOk: (Double) -> Unit) {
    var text by remember { mutableStateOf(current) }
    var touched by remember { mutableStateOf(false) }
    val r = com.livecompanion.ui.track.Bbt.parse(text, bpb, unit, maxBar)
    val go = { r.beats?.let(onOk) ?: Unit }
    LiveDialog("Locate (bar.beat.sixteenth)", onDismiss) {
        LiveTextField(text, { text = it; touched = true }, onDone = { go() }, autoFocus = true)
        val e = r.error
        if (e != null && touched) LiveText(e, style = LiveType.Small, color = LiveColors.Rec, maxLines = 2)
        LiveDialogButtons {
            LiveTextButton("Cancel", onDismiss, minWidth = 72.dp)
            LiveTextButton("Go", { go() }, on = r.beats != null, enabled = r.beats != null, minWidth = 72.dp)
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

internal fun barPos(beat: Double, bpb: Double, unit: Double = 1.0): String =
    posString(beat, bpb, unit).substringBeforeLast('.')

internal fun fmtBars(v: Double): String = if (v == Math.rint(v)) v.toInt().toString() else "%.2f".format(v)

/** Slim (24dp) text button for the arrangement strips. */
@Composable
private fun SBtn(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false,
    onColor: androidx.compose.ui.graphics.Color = LiveColors.Accent, minWidth: androidx.compose.ui.unit.Dp = 34.dp,
) = LiveTextButton(label, onClick, modifier, on, onColor, true, minHeight = SlimH, minWidth = minWidth)

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
internal fun RenameClipDialog(initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) =
    NameDialog("Rename", initial, onDismiss, onOk)

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
