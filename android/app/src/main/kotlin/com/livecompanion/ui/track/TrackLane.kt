package com.livecompanion.ui.track

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livecompanion.net.Peaks
import com.livecompanion.state.ArrClip
import com.livecompanion.state.CuePoint
import com.livecompanion.state.TakeLane
import com.livecompanion.ui.components.TLoop
import com.livecompanion.ui.components.TimelineView
import com.livecompanion.ui.components.dim
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.components.onColor
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Everything the lane draws, as one immutable snapshot per recomposition. */
class LaneModel(
    val clips: List<ArrClip>,
    val takes: List<TakeLane> = emptyList(),
    val showTakes: Boolean = false,
    val sel: Int = -1,
    val selTake: Pair<Int, Int>? = null,
    val bpb: Double = 4.0,
    val unit: Double = 1.0,
    /** Snap grid in beats; 0 = free. */
    val grid: Double = 0.25,
    val tempo: Double = 120.0,
    val loop: TLoop = TLoop(false, 0.0, 4.0),
    val punchIn: Boolean = false,
    val punchOut: Boolean = false,
    val cues: List<CuePoint> = emptyList(),
    val midi: Boolean = false,
    /** Track this lane shows; a change (e.g. the track was deleted) cancels any drag in flight. */
    val trackRef: String = "",
)

class LaneActions(
    val onScrub: (beat: Double, done: Boolean) -> Unit = { _, _ -> },
    val onLoop: (start: Double, len: Double, done: Boolean) -> Unit = { _, _, _ -> },
    val onLoopToggle: () -> Unit = {},
    val onSelectArr: (Int) -> Unit = {},
    val onOpenArr: (Int) -> Unit = {},
    val onSelectTake: (lane: Int, i: Int) -> Unit = { _, _ -> },
    val onDeselect: () -> Unit = {},
    val onMove: (i: Int, start: Double) -> Unit = { _, _ -> },
    val onTrim: (i: Int, start: Double?, end: Double?) -> Unit = { _, _, _ -> },
    val onCreate: (beat: Double) -> Unit = {},
)

/** Recording progression: while active a red region grows from [start] to the playhead. */
@Stable
class RecState {
    var active by mutableStateOf(false)
    var start by mutableDoubleStateOf(0.0)
    var punchIn by mutableStateOf(false)
    var punchOut by mutableStateOf(false)
    var loopStart by mutableDoubleStateOf(0.0)
    var loopEnd by mutableDoubleStateOf(0.0)
}

/** In-flight clip edit shown while dragging / until the server section catches up. */
private class DragEdit(val i: Int, val start: Double, val end: Double, val committed: Boolean, val serial: Int)

private enum class Zone { Scrub, Brace, Main, Take, None }
private enum class Mode { Pending, Hold, Pan, Move, TrimL, TrimR, Scrub, LoopL, LoopR, LoopBody, Multi }

private const val HOLD_MS = com.livecompanion.ui.components.HOLD_DRAG_MS
private const val TAKE_H_DP = 24f
private const val BRACE_H_DP = 9f
/** Scrub (bar number) row height. */
private const val SCRUB_H_DP = 16f

/** Height of one take lane: [TAKE_H_DP] unless the take lanes would take more than ~35% of the space under the ruler. */
private fun takeHFor(totalH: Float, rulerPx: Float, n: Int, base: Float): Float =
    if (n <= 0) base else min(base, max(0.35f * (totalH - rulerPx) / n, 10f))
/** Visible trim handle width == edge grab zone (selected clip only). */
private const val EDGE_DP = 14f
/** No clip may be trimmed shorter than 1/16 beat (also enforced by the server). */
const val MIN_CLIP_BEATS = 0.0625

/** Minimum lane height needed for the main lane to stay usable (dp). */
const val MIN_MAIN_LANE_DP = 56f

@Composable
fun TrackLane(
    view: TimelineView,
    model: LaneModel,
    actions: LaneActions,
    clock: PlayheadClock,
    rec: RecState,
    peaks: Map<String, Peaks>,
    follow: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dens = density.density
    val haptic = LocalHapticFeedback.current
    val slop = LocalViewConfiguration.current.touchSlop
    val tm = rememberTextMeasurer()
    val scrubPx = with(density) { maxOf(SCRUB_H_DP.dp, 10.sp.toDp() + 5.dp).toPx() }
    val bracePx = BRACE_H_DP * dens
    val rulerPx = scrubPx + bracePx
    val takeH0 = TAKE_H_DP * dens
    val edgeHit = 22f * dens
    val clipEdge = EDGE_DP * dens
    val tapPad = 4f * dens

    val curM by rememberUpdatedState(model)
    val curA by rememberUpdatedState(actions)
    val curFollow by rememberUpdatedState(follow)
    var heightPx by remember { mutableStateOf(0f) }
    var drag by remember { mutableStateOf<DragEdit?>(null) }
    var dragSerial by remember { mutableStateOf(0) }
    val lastTap = remember { longArrayOf(0L, 0L) } // time ns, key (clip index or -1)
    val lastTapX = remember { floatArrayOf(0f) }

    // The server section replaces our optimistic edit.
    LaunchedEffect(model.clips) { drag = null }
    LaunchedEffect(model.trackRef) { drag = null }
    LaunchedEffect(Unit) {
        snapshotFlow { drag }.collectLatest { d ->
            if (d != null && d.committed) { delay(1500); if (drag?.serial == d.serial) drag = null }
        }
    }

    // Per-frame playhead ticks, only while something moves; draw layer reads clock.shown.
    LaunchedEffect(clock.animating) {
        if (!clock.animating) { clock.tick(System.nanoTime()); return@LaunchedEffect }
        while (true) {
            withInfiniteFrame {
                clock.tick(System.nanoTime())
                if (curFollow && clock.isPlaying && view.fitted && view.widthPx > 0f && !clock.scrubbing) {
                    val vis = view.widthPx / view.ppb
                    val p = clock.shown
                    if (p < view.start || p > view.start + vis * 0.92) view.start = max(0.0, p - vis * 0.08)
                }
            }
        }
    }

    // Take lanes share at most ~35% of the space under the ruler; the main lane (clips) keeps the rest.
    fun takeH(): Float {
        val m = curM
        val n = if (m.showTakes) min(m.takes.size, 4) else 0
        return takeHFor(heightPx, rulerPx, n, takeH0)
    }

    fun mainH(): Float {
        val m = curM
        val takesTotal = if (m.showTakes) min(m.takes.size, 4) * takeH() else 0f
        return max(heightPx - rulerPx - takesTotal, 40f * dens)
    }

    fun zoneAt(y: Float): Zone {
        val m = curM
        return when {
            y < scrubPx -> Zone.Scrub
            y < rulerPx -> Zone.Brace
            y < rulerPx + mainH() -> Zone.Main
            m.showTakes && y < rulerPx + mainH() + min(m.takes.size, 4) * takeH() -> Zone.Take
            else -> Zone.None
        }
    }

    fun effClip(i: Int): Pair<Double, Double>? {
        val c = curM.clips.firstOrNull { it.i == i } ?: return null
        val d = drag
        return if (d != null && d.i == i) d.start to d.end else c.start to c.end
    }

    fun clipBottom(): Float = rulerPx + 2f * dens + (mainH() - 4f * dens)

    fun hitMain(x: Float, y: Float): ArrClip? {
        if (y > clipBottom() + tapPad) return null
        var best: ArrClip? = null
        var bestD = Float.MAX_VALUE
        for (c in curM.clips) {
            val (s, e) = effClip(c.i) ?: continue
            val sx = view.xOf(s); val ex = view.xOf(e)
            if (x >= sx - tapPad && x <= ex + tapPad) {
                val dd = if (x in sx..ex) 0f else min(abs(x - sx), abs(x - ex))
                if (dd <= bestD) { best = c; bestD = dd }
            }
        }
        return best
    }

    fun hitTake(x: Float, y: Float): Pair<Int, Int>? {
        val li = ((y - rulerPx - mainH()) / takeH()).toInt()
        val lane = curM.takes.getOrNull(li) ?: return null
        for (c in lane.clips) {
            val sx = view.xOf(c.start); val ex = view.xOf(c.end)
            if (x >= sx - tapPad && x <= ex + tapPad) return lane.i to c.i
        }
        return null
    }

    Box(
        modifier
            .background(LiveColors.Bg)
            .clipToBounds()
            .onSizeChanged { view.widthPx = it.width.toFloat(); heightPx = it.height.toFloat() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val p0 = down.position
                    if (!view.fitted) {
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    val m = curM
                    val a = curA
                    val zone = zoneAt(p0.y)
                    var mode = Mode.Pending
                    var hitClip: ArrClip? = null
                    var hitEdge = 0
                    var takeHit: Pair<Int, Int>? = null
                    var origStart = 0.0
                    var origEnd = 0.0
                    var loopOrig = m.loop
                    var loopNow = m.loop
                    var loopGrab = 0.0
                    var edgeLoop = 0
                    var moved = false
                    var holdable = false
                    val grid = m.grid

                    fun scrubAt(x: Float, done: Boolean) {
                        val cue = m.cues.firstOrNull { abs(view.xOf(it.t) - x) < 12f * dens }
                        val b = max(0.0, cue?.t ?: snapTo(view.beatAt(x), grid))
                        a.onScrub(b, done)
                    }

                    when (zone) {
                        Zone.Scrub -> { mode = Mode.Scrub; scrubAt(p0.x, false) }
                        Zone.Brace -> {
                            val lp = m.loop
                            val ds = abs(view.xOf(lp.start) - p0.x)
                            val de = abs(view.xOf(lp.start + lp.len) - p0.x)
                            if (min(ds, de) <= edgeHit) edgeLoop = if (ds <= de) 1 else 2
                            else if (p0.x > view.xOf(lp.start) && p0.x < view.xOf(lp.start + lp.len)) edgeLoop = 3
                        }
                        Zone.Main -> {
                            hitClip = hitMain(p0.x, p0.y)
                            val hc = hitClip
                            if (hc != null) {
                                val (s, e) = effClip(hc.i)!!
                                origStart = s; origEnd = e
                                hitEdge = edgeZone(p0.x, view.xOf(s), view.xOf(e), clipEdge)
                                if (hc.i == m.sel && hitEdge != 0) {
                                    // handles of the selected clip trim immediately
                                    mode = if (hitEdge < 0) Mode.TrimL else Mode.TrimR
                                } else holdable = true
                            }
                        }
                        Zone.Take -> takeHit = hitTake(p0.x, p0.y)
                        Zone.None -> Unit
                    }

                    var prevSpan = 0f
                    var prevCx = 0f
                    var multi = false
                    val tStart = System.nanoTime()
                    var lastPos = p0

                    fun cancelEdits() {
                        if (mode == Mode.Scrub) a.onScrub(max(0.0, snapTo(view.beatAt(lastPos.x), grid)), true)
                        if (mode == Mode.LoopL || mode == Mode.LoopR || mode == Mode.LoopBody) a.onLoop(loopOrig.start, loopOrig.len, true)
                        if (mode == Mode.Move || mode == Mode.TrimL || mode == Mode.TrimR) drag = null
                    }

                    gesture@ while (true) {
                        val ev = if (mode == Mode.Pending && holdable) {
                            val left = HOLD_MS - (System.nanoTime() - tStart) / 1_000_000L
                            if (left <= 0L) null else withTimeoutOrNull(left) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (ev == null) {
                            mode = Mode.Hold
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            continue@gesture
                        }
                        val pressed = ev.changes.filter { it.pressed }
                        // The focused track changed or the dragged clip vanished (deleted elsewhere): abandon the gesture.
                        if (curM.trackRef != m.trackRef || (hitClip != null && curM.clips.none { it.i == hitClip!!.i })) {
                            cancelEdits(); drag = null
                            do { val e2 = awaitPointerEvent() } while (e2.changes.any { it.pressed })
                            break@gesture
                        }
                        if (pressed.size >= 2) {
                            if (!multi) { cancelEdits(); mode = Mode.Multi; multi = true; prevSpan = 0f }
                            val pa = pressed[0].position
                            val pb = pressed[1].position
                            val cx = (pa.x + pb.x) / 2f
                            val span = max(abs(pa.x - pb.x), (pa - pb).getDistance() * 0.5f)
                            if (prevSpan > 0f && span > 1f) {
                                val anchor = view.beatAt(prevCx)
                                val np = view.clampPpb(view.ppb * span / prevSpan)
                                view.ppb = np
                                view.start = max(0.0, anchor - cx / np)
                            }
                            prevCx = cx
                            prevSpan = span
                            ev.changes.forEach { it.consume() }
                            continue@gesture
                        }
                        if (multi) {
                            if (pressed.isEmpty()) break@gesture
                            prevSpan = 0f
                            continue@gesture
                        }
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break@gesture
                        lastPos = c.position
                        if (!c.pressed) {
                            // ---------------------------------------------------- release
                            when (mode) {
                                Mode.Scrub -> scrubAt(c.position.x, true)
                                Mode.LoopL, Mode.LoopR, Mode.LoopBody -> a.onLoop(loopNow.start, loopNow.len, true)
                                Mode.Move -> drag?.let { d -> drag = DragEdit(d.i, d.start, d.end, true, d.serial); a.onMove(d.i, d.start) }
                                Mode.TrimL -> drag?.let { d -> drag = DragEdit(d.i, d.start, d.end, true, d.serial); a.onTrim(d.i, d.start, null) }
                                Mode.TrimR -> drag?.let { d -> drag = DragEdit(d.i, d.start, d.end, true, d.serial); a.onTrim(d.i, null, d.end) }
                                Mode.Pan -> Unit
                                Mode.Pending, Mode.Hold -> {
                                    // ---------------- tap
                                    val now = System.nanoTime()
                                    val quick = now - lastTap[0] < 330_000_000L && abs(c.position.x - lastTapX[0]) < 40f * dens
                                    when (zone) {
                                        Zone.Brace -> {
                                            if (edgeLoop == 3) a.onLoopToggle() else scrubAt(c.position.x, true)
                                        }
                                        Zone.Main -> {
                                            val hc = hitClip
                                            if (hc != null) {
                                                if (quick && lastTap[1] == hc.i.toLong()) { a.onOpenArr(hc.i); lastTap[0] = 0L }
                                                else { a.onSelectArr(hc.i); lastTap[0] = now; lastTap[1] = hc.i.toLong(); lastTapX[0] = c.position.x }
                                            } else {
                                                if (quick && lastTap[1] == -1L) { a.onCreate(max(0.0, view.beatAt(c.position.x))); lastTap[0] = 0L }
                                                else { a.onDeselect(); lastTap[0] = now; lastTap[1] = -1L; lastTapX[0] = c.position.x }
                                            }
                                        }
                                        Zone.Take -> {
                                            val th = takeHit
                                            if (th != null) a.onSelectTake(th.first, th.second) else a.onDeselect()
                                        }
                                        else -> Unit
                                    }
                                }
                                else -> Unit
                            }
                            break@gesture
                        }
                        // ---------------------------------------------------- move
                        val dist = (c.position - p0).getDistance()
                        if (!moved && dist > slop) {
                            moved = true
                            when (mode) {
                                Mode.Pending -> {
                                    mode = when (zone) {
                                        Zone.Brace -> when (edgeLoop) {
                                            1 -> Mode.LoopL; 2 -> Mode.LoopR; 3 -> Mode.LoopBody; else -> Mode.Scrub
                                        }
                                        else -> Mode.Pan
                                    }
                                    if (mode == Mode.LoopBody) loopGrab = view.beatAt(p0.x) - m.loop.start
                                }
                                Mode.Hold -> {
                                    val hc = hitClip
                                    // Hold-drag on an UNSELECTED clip always MOVES; only the selected clip's handles trim
                                    // (and those arm immediately on press, see above).
                                    mode = if (hc != null) Mode.Move else Mode.Pan
                                }
                                else -> Unit
                            }
                            if (mode == Mode.Scrub && zone == Zone.Brace) scrubAt(p0.x, false)
                        }
                        if (moved) {
                            val x = c.position.x
                            val g = effectiveGrid(grid)
                            when (mode) {
                                Mode.Scrub -> scrubAt(x, false)
                                Mode.Pan -> {
                                    view.start = max(0.0, view.start - (x - c.previousPosition.x) / view.ppb)
                                }
                                Mode.Move -> {
                                    val hc = hitClip!!
                                    val len = origEnd - origStart
                                    val ns = max(0.0, snapTo(origStart + (x - p0.x) / view.ppb, grid))
                                    drag = DragEdit(hc.i, ns, ns + len, false, ++dragSerial)
                                }
                                Mode.TrimL -> {
                                    val hc = hitClip!!
                                    val ns = max(0.0, min(snapTo(view.beatAt(x), grid), origEnd - max(g, MIN_CLIP_BEATS)))
                                    drag = DragEdit(hc.i, ns, origEnd, false, ++dragSerial)
                                }
                                Mode.TrimR -> {
                                    val hc = hitClip!!
                                    val ne = max(origStart + max(g, MIN_CLIP_BEATS), snapTo(view.beatAt(x), grid))
                                    drag = DragEdit(hc.i, origStart, ne, false, ++dragSerial)
                                }
                                Mode.LoopL -> {
                                    val end = loopOrig.start + loopOrig.len
                                    val s = snapTo(view.beatAt(x), g).coerceIn(0.0, end - g)
                                    val nl = TLoop(loopOrig.enabled, s, end - s)
                                    if (nl != loopNow) { loopNow = nl; a.onLoop(nl.start, nl.len, false) }
                                }
                                Mode.LoopR -> {
                                    val e = snapTo(view.beatAt(x), g)
                                    val nl = TLoop(loopOrig.enabled, loopOrig.start, max(g, e - loopOrig.start))
                                    if (nl != loopNow) { loopNow = nl; a.onLoop(nl.start, nl.len, false) }
                                }
                                Mode.LoopBody -> {
                                    val s = max(0.0, snapTo(view.beatAt(x) - loopGrab, g))
                                    val nl = TLoop(loopOrig.enabled, s, loopOrig.len)
                                    if (nl != loopNow) { loopNow = nl; a.onLoop(nl.start, nl.len, false) }
                                }
                                else -> Unit
                            }
                            c.consume()
                        }
                    }
                }
            },
    ) {
        // Base layer: ruler, grid, loop/punch, cues, clips. Redraws on view/state changes only.
        Canvas(Modifier.fillMaxSize()) {
            if (!view.fitted) return@Canvas
            val takesN = if (model.showTakes) min(model.takes.size, 4) else 0
            val takeH = takeHFor(size.height, rulerPx, takesN, takeH0)
            val mh = max(size.height - rulerPx - takesN * takeH, 40f * dens)
            drawBase(view, model, drag, peaks, scrubPx, rulerPx, mh, takeH, tm, dens)
        }
        // Overlay: recording region + playhead (reads clock.shown per frame; nothing recomposes).
        Canvas(Modifier.fillMaxSize()) {
            if (!view.fitted) return@Canvas
            val takesN = if (model.showTakes) min(model.takes.size, 4) else 0
            val takeH = takeHFor(size.height, rulerPx, takesN, takeH0)
            val mh = max(size.height - rulerPx - takesN * takeH, 40f * dens)
            val ph = clock.shown
            if (rec.active) drawRec(view, rec, ph, scrubPx, rulerPx, mh, tm, dens)
            val x = view.xOf(ph)
            if (x >= -4f && x <= size.width + 4f) {
                val col = LiveColors.Playhead
                drawLine(col, Offset(x, scrubPx * 0.5f), Offset(x, rulerPx + mh + takesN * takeH), 1.2f * dens)
                val p = Path().apply {
                    moveTo(x - 4f * dens, 1f * dens); lineTo(x + 4f * dens, 1f * dens); lineTo(x, scrubPx * 0.7f); close()
                }
                drawPath(p, col)
            }
        }
    }
}

/** Frame callback that does not hold test clocks hostage (falls back to a plain frame wait). */
private suspend fun withInfiniteFrame(block: (Long) -> Unit) {
    val policy = kotlin.coroutines.coroutineContext[InfiniteAnimationPolicy]
    if (policy != null) policy.onInfiniteOperation { androidx.compose.runtime.withFrameNanos(block) }
    else androidx.compose.runtime.withFrameNanos(block)
}

// ------------------------------------------------------------------------------------------------ drawing

private val RulerText = LiveType.Label.copy(fontSize = 10.sp, letterSpacing = 0.sp, color = LiveColors.TextDim)
private val BeatText = LiveType.Label.copy(fontSize = 9.sp, letterSpacing = 0.sp, color = Color(0xFF6E6E6E))
private val ClipText = LiveType.Small.copy(fontSize = 11.sp)
private val CueText = LiveType.Label.copy(fontSize = 9.sp, letterSpacing = 0.sp, color = LiveColors.Text)

private fun lighten(c: Color, t: Float): Color =
    Color(c.red + (1f - c.red) * t, c.green + (1f - c.green) * t, c.blue + (1f - c.blue) * t, c.alpha)

private fun barPxOf(ppb: Float, bpb: Double): Float = (ppb * bpb).toFloat()

private fun DrawScope.drawBase(
    view: TimelineView, m: LaneModel, drag: DragEdit?, peaks: Map<String, Peaks>,
    scrubPx: Float, rulerPx: Float, mainH: Float, takeH: Float, tm: TextMeasurer, dens: Float,
) {
    val w = size.width
    val ppb = view.ppb
    val bpb = m.bpb
    val unit = m.unit
    val takesN = if (m.showTakes) min(m.takes.size, 4) else 0
    val totalH = rulerPx + mainH + takesN * takeH
    val first = view.start
    val last = view.start + w / ppb

    // locator labels are measured first so bar / beat numbers underneath them can be skipped (no collisions)
    class CueBox(val cue: CuePoint, val x: Float, val lx: Float, val layout: androidx.compose.ui.text.TextLayoutResult?)
    val cueBoxes = ArrayList<CueBox>()
    for (cu in m.cues) {
        val x = view.xOf(cu.t)
        if (x < -2f || x > w) continue
        var lay: androidx.compose.ui.text.TextLayoutResult? = null
        var lx = x + 8f * dens
        if (cu.n.isNotEmpty() && barPxOf(ppb, bpb) >= 24f * dens) {
            lay = tm.measure(cu.n, CueText, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (64f * dens).toInt()))
            if (lx + lay.size.width + 3f * dens > w) lx = x - 8f * dens - lay.size.width
        }
        cueBoxes.add(CueBox(cu, x, lx, lay))
    }
    fun blocked(x0: Float, x1: Float): Boolean = cueBoxes.any { b ->
        val l = b.layout ?: return@any false
        x1 > b.lx - 3f * dens && x0 < b.lx + l.size.width + 3f * dens
    }

    // ---- backgrounds
    drawRect(LiveColors.Panel, Offset(0f, rulerPx), Size(w, mainH))
    for (k in 0 until takesN) {
        drawRect(if (k % 2 == 0) Color(0xFF262626) else Color(0xFF232323), Offset(0f, rulerPx + mainH + k * takeH), Size(w, takeH))
        drawLine(LiveColors.Line, Offset(0f, rulerPx + mainH + k * takeH), Offset(w, rulerPx + mainH + k * takeH), 1f)
    }
    drawRect(LiveColors.PanelHi, Offset.Zero, Size(w, scrubPx))
    drawRect(Color(0xFF2F2F2F), Offset(0f, scrubPx), Size(w, rulerPx - scrubPx))
    drawLine(LiveColors.Line, Offset(0f, rulerPx - 0.5f), Offset(w, rulerPx - 0.5f), 1f)

    // ---- grid: bars, beats, sixteenths follow the signature
    val barPx = (ppb * bpb).toFloat()
    val unitPx = (ppb * unit).toFloat()
    val sixPx = ppb * 0.25f
    val firstBar = max(0, floor(first / bpb).toInt())
    val lastBar = ceil(last / bpb).toInt()
    var step = 1
    while (step * barPx < 38f * dens && step < (1 shl 20)) step *= 2
    if (sixPx >= 9f * dens) {
        var s = ceil(first / 0.25).toLong()
        while (s * 0.25 <= last) {
            val b = s * 0.25
            val onBeat = abs(b / unit - Math.rint(b / unit)) < 1e-6
            if (!onBeat) {
                val x = view.xOf(b)
                drawLine(Color(0xFF2E2E2E), Offset(x, rulerPx), Offset(x, totalH), 1f)
            }
            s++
        }
    }
    if (unitPx >= 12f * dens) {
        var bt = ceil(first / unit).toLong()
        while (bt * unit <= last) {
            val b = bt * unit
            if (abs(b / bpb - Math.rint(b / bpb)) > 1e-6) {
                val x = view.xOf(b)
                drawLine(Color(0xFF3A3A3A), Offset(x, scrubPx * 0.55f), Offset(x, totalH), 1f)
                if (unitPx >= 44f * dens) {
                    val bar = floor(b / bpb + 1e-9).toInt()
                    val idx = Math.round((b - bar * bpb) / unit).toInt()
                    val l = tm.measure("${bar + 1}.${idx + 1}", BeatText, maxLines = 1)
                    if (!blocked(x + 3f * dens, x + 3f * dens + l.size.width)) drawText(l, topLeft = Offset(x + 3f * dens, (scrubPx - l.size.height) / 2f))
                }
            }
            bt++
        }
    }
    var bar = firstBar - (firstBar % step)
    while (bar <= lastBar) {
        val x = view.xOf(bar * bpb)
        if (x >= -1f && x <= w) {
            drawLine(Color(0xFF555555), Offset(x, 0f), Offset(x, totalH), 1f)
            val l = tm.measure("${bar + 1}", RulerText.copy(color = LiveColors.Text), maxLines = 1)
            if (!blocked(x + 3f * dens, x + 3f * dens + l.size.width)) drawText(l, topLeft = Offset(x + 3f * dens, (scrubPx - l.size.height) / 2f))
        }
        bar += step
    }
    if (step > 1 && barPx >= 5f * dens) {
        var b = firstBar
        while (b <= lastBar) {
            if (b % step != 0) {
                val x = view.xOf(b * bpb)
                drawLine(Color(0xFF353535), Offset(x, scrubPx * 0.6f), Offset(x, totalH), 1f)
            }
            b++
        }
    }

    // ---- loop brace + punch
    val lp = m.loop
    if (lp.len > 0) {
        val ls = view.xOf(lp.start)
        val le = view.xOf(lp.start + lp.len)
        val col = if (lp.enabled) LiveColors.Accent else LiveColors.TextDim
        drawRect(col.copy(alpha = if (lp.enabled) 0.10f else 0.05f), Offset(ls, rulerPx), Size(le - ls, mainH + takesN * takeH))
        // Brace row: loop brace on the upper part (always "LOOP", accent when on); punch in/out are separate thin red
        // flags on a second thin strip below it - never relabel or recolor the loop brace.
        val punchH = 2.5f * dens
        val punchOn = m.punchIn || m.punchOut
        val braceBot = if (punchOn) rulerPx - punchH - 1f * dens else rulerPx
        drawRect(col.copy(alpha = if (lp.enabled) 0.85f else 0.45f), Offset(ls, scrubPx + 1f * dens), Size(le - ls, braceBot - scrubPx - 2f * dens))
        val hw = 5f * dens
        drawRect(col, Offset(ls - 1f, scrubPx), Size(hw, braceBot - scrubPx))
        drawRect(col, Offset(le - hw + 1f, scrubPx), Size(hw, braceBot - scrubPx))
        drawLine(col.copy(alpha = 0.9f), Offset(ls, rulerPx), Offset(ls, totalH), 1f)
        drawLine(col.copy(alpha = 0.9f), Offset(le, rulerPx), Offset(le, totalH), 1f)
        if (punchOn) {
            val py = rulerPx - punchH
            val flag = min(18f * dens, (le - ls) / 2f)
            if (m.punchIn && m.punchOut) drawRect(LiveColors.Rec.copy(alpha = 0.5f), Offset(ls, py + punchH / 2f - 0.5f * dens), Size(le - ls, 1f * dens))
            if (m.punchIn) {
                drawRect(LiveColors.Rec, Offset(ls, py), Size(flag, punchH))
                drawLine(LiveColors.Rec, Offset(ls, rulerPx), Offset(ls, totalH), 2f * dens)
            }
            if (m.punchOut) {
                drawRect(LiveColors.Rec, Offset(le - flag, py), Size(flag, punchH))
                drawLine(LiveColors.Rec, Offset(le, rulerPx), Offset(le, totalH), 2f * dens)
            }
        }
    }

    // ---- cues / locators: small light flags on the ruler (never orange); labels sit right of the flag on a dark pill
    for (b in cueBoxes) {
        val x = b.x
        val flag = LiveColors.Playhead
        drawLine(flag.copy(alpha = 0.22f), Offset(x, rulerPx), Offset(x, totalH), 1f)
        drawLine(flag.copy(alpha = 0.85f), Offset(x, 1f * dens), Offset(x, scrubPx), 1f * dens)
        val path = Path().apply { moveTo(x, 1f * dens); lineTo(x + 6f * dens, 3.5f * dens); lineTo(x, 6f * dens); close() }
        drawPath(path, flag)
        val l = b.layout
        if (l != null) {
            val pillH = scrubPx - 2f * dens
            drawRoundRect(LiveColors.PanelHi.copy(alpha = 0.95f), Offset(b.lx - 3f * dens, 1f * dens), Size(l.size.width + 6f * dens, pillH),
                androidx.compose.ui.geometry.CornerRadius(2f * dens))
            drawText(l, topLeft = Offset(b.lx, 1f * dens + (pillH - l.size.height) / 2f))
        }
    }

    // ---- main lane clips
    val top = rulerPx + 2f * dens
    val h = mainH - 4f * dens
    // the clip being dragged is drawn last (on top); anything it would overwrite is flagged in red
    // z-order: unselected, then the SELECTED clip (labels + handles stay visible over neighbours), then the dragged one
    val ordered = m.clips.filter { it.i != m.sel && it.i != drag?.i } +
        m.clips.filter { it.i == m.sel && it.i != drag?.i } + m.clips.filter { it.i == drag?.i }
    for (c in ordered) {
        var s = c.start; var e = c.end
        val dragging = drag != null && drag.i == c.i
        if (dragging) { s = drag!!.start; e = drag.end }
        val sx = view.xOf(s); val ex = view.xOf(e)
        if (ex < 0f || sx > w) continue
        if (dragging) {
            for (o in m.clips) {
                if (o.i == c.i) continue
                val os = max(o.start, s); val oe = min(o.end, e)
                if (oe - os <= 1e-6) continue
                val ox0 = max(view.xOf(os), 0f); val ox1 = min(view.xOf(oe), w)
                if (ox1 <= ox0) continue
                drawRect(LiveColors.Rec.copy(alpha = 0.6f), Offset(ox0, top), Size(ox1 - ox0, h))
                drawRect(LiveColors.Rec, Offset(ox0, top), Size(ox1 - ox0, h), style = Stroke(2f * dens))
                if (ox1 - ox0 > 70f * dens) {
                    val l = tm.measure("Overwrites", BeatText.copy(color = Color.White), maxLines = 1)
                    drawText(l, topLeft = Offset(ox0 + 4f * dens, top + h - l.size.height - 3f * dens))
                }
            }
        }
        drawClip(c, s, e, sx, ex, top, h, c.i == m.sel, dragging && !drag!!.committed,
            peaks[c.filePath], view, m.tempo, tm, dens)
    }
    if (drag != null && !drag.committed) {
        // red outline on the covered parts again, above the dragged clip, so the overlap stays visible through it
        for (o in m.clips) {
            if (o.i == drag.i) continue
            val os = max(o.start, drag.start); val oe = min(o.end, drag.end)
            if (oe - os <= 1e-6) continue
            val ox0 = max(view.xOf(os), 0f); val ox1 = min(view.xOf(oe), w)
            if (ox1 > ox0) {
                drawRect(LiveColors.Rec.copy(alpha = 0.45f), Offset(ox0, top), Size(ox1 - ox0, h))
                drawRect(LiveColors.Rec, Offset(ox0, top), Size(ox1 - ox0, h), style = Stroke(2f * dens))
            }
        }
    }

    // ---- take lanes
    for (k in 0 until takesN) {
        val lane = m.takes[k]
        val ty = rulerPx + mainH + k * takeH + 2f * dens
        val th = takeH - 4f * dens
        for (c in lane.clips) {
            val sx = view.xOf(c.start); val ex = view.xOf(c.end)
            if (ex < 0f || sx > w) continue
            val x0 = max(sx, -2f); val x1 = min(ex, w + 2f); val cw = max(x1 - x0, 2f)
            val sel = m.selTake == (lane.i to c.i)
            val bg = dim(liveColor(c.color), 0.6f)
            drawRect(bg, Offset(x0, ty), Size(cw, th))
            if (cw > 24f * dens) clipRect(x0, ty, x0 + cw, ty + th) {
                val l = tm.measure(c.name.ifEmpty { lane.name }, ClipText.copy(fontSize = 10.sp, color = onColor(bg)), overflow = TextOverflow.Ellipsis, maxLines = 1,
                    constraints = Constraints(maxWidth = max(1, (cw - 6f * dens).toInt())))
                drawText(l, topLeft = Offset(max(sx, 0f) + 3f * dens, ty + (th - l.size.height) / 2f))
            }
            drawRect(if (sel) LiveColors.Accent else Color.Black.copy(alpha = 0.5f), Offset(x0, ty), Size(cw, th), style = Stroke(if (sel) 2f * dens else 1f))
        }
    }
    if (m.showTakes && m.takes.size > 4) {
        val l = tm.measure("+${m.takes.size - 4} more lanes", BeatText, maxLines = 1)
        drawText(l, topLeft = Offset(w - l.size.width - 4f * dens, totalH - l.size.height - 1f))
    }
}

private fun DrawScope.drawClip(
    c: ArrClip, s: Double, e: Double, sx: Float, ex: Float, top: Float, h: Float, selected: Boolean, ghost: Boolean,
    pk: Peaks?, view: TimelineView, tempo: Double, tm: TextMeasurer, dens: Float,
) {
    val w = size.width
    val x0 = max(sx, -2f); val x1 = min(ex, w + 2f); val cw = max(x1 - x0, 2f)
    val base = liveColor(c.color)
    val headH = min(15f * dens, h * 0.35f)
    var head = base
    var body = dim(base, 0.36f)
    if (c.muted) { head = dim(base, 0.4f); body = dim(base, 0.2f) }
    val alpha = if (ghost) 0.8f else 1f
    drawRect(body.copy(alpha = alpha), Offset(x0, top), Size(cw, h))
    drawRect(head.copy(alpha = alpha), Offset(x0, top), Size(cw, headH))
    val bodyTop = top + headH
    val bodyH = h - headH
    clipRect(x0, top, x0 + cw, top + h) {
        // ---- content preview
        val clipLen = e - s
        if (c.audio && pk != null && bodyH > 6f * dens) {
            val fileSec = pk.seconds
            val mid = bodyTop + bodyH / 2f
            val amp = bodyH / 2f * 0.92f
            val stepPx = max(1f, 1.5f * dens)
            val wf = onColor(body).copy(alpha = if (c.muted) 0.35f else 0.8f)
            var x = max(sx, 0f)
            val xEnd = min(ex, w)
            while (x < xEnd) {
                val rel = ((x - sx) / view.ppb).toDouble()
                val rel1 = ((x + stepPx - sx) / view.ppb).toDouble()
                val f0 = fileFraction(c, contentBeat(c, rel), tempo, fileSec)
                val f1 = fileFraction(c, contentBeat(c, rel1), tempo, fileSec)
                if (f0 in 0.0..1.0) {
                    val span = peakSpan(pk, f0, f1.coerceAtMost(1.0))
                    if (span != null) {
                        val (lo, hi) = span
                        val yTop = mid - hi * amp
                        val yBot = mid - lo * amp
                        drawLine(wf, Offset(x, yTop), Offset(x, max(yBot, yTop + 1f)), stepPx)
                    }
                }
                x += stepPx
            }
        } else if (!c.audio && c.pn.isNotEmpty() && bodyH > 6f * dens) {
            // Live-style mini piano roll: solid rounded note bars whose HEIGHT scales with the lane
            // (row = body height / pitch span; bar height clamped 2..10dp). Span = used range padded, at least one octave;
            // the range fills the whole body.
            var lo = 127; var hi = 0
            for (n in c.pn) { if (n.size >= 3) { val p = n[2].toInt(); if (p < lo) lo = p; if (p > hi) hi = p } }
            lo -= 1; hi += 1
            if (hi - lo + 1 < 12) { val mid = (hi + lo) / 2; lo = mid - 5; hi = lo + 11 }
            val rows = hi - lo + 1
            val pad = 2f * dens
            val avail = (bodyH - 2 * pad).coerceAtLeast(1f)
            val rowH = avail / rows          // pitch range spans the ENTIRE body, top = highest pitch
            val y0 = bodyTop + pad
            val nh = (rowH * 0.86f).coerceIn(2f * dens, 10f * dens)
            val fill = lighten(base, 0.55f).copy(alpha = if (c.muted) 0.4f else 1f)
            val edge = dim(base, 0.35f).copy(alpha = if (c.muted) 0.5f else 0.9f)
            val rad = androidx.compose.ui.geometry.CornerRadius(min(nh / 2f, 2f * dens))
            placeNotes(c, clipLen) { rel, dur, pitch ->
                val nx = view.xOf(s + rel)
                val nw = max((dur * view.ppb).toFloat() - 0.5f * dens, 3f * dens)
                if (nx + nw >= 0f && nx <= w) {
                    val ny = y0 + (hi - pitch) * rowH + (rowH - nh) / 2f
                    drawRoundRect(fill, Offset(nx, ny), Size(nw, nh), rad)
                    drawRoundRect(edge, Offset(nx, ny), Size(nw, nh), rad, style = Stroke(max(0.8f * dens, 1f)))
                }
            }
        }
        // ---- loop-wrap tick marks inside looping clips
        if (c.looping && c.loopEnd > c.loopStart + 1e-6) {
            val span = c.loopEnd - c.loopStart
            var rel = c.loopEnd - c.startMarker
            var guard = 0
            while (rel < clipLen && guard++ < 512) {
                val x = view.xOf(s + rel)
                if (x in 0f..w) drawLine(Color.Black.copy(alpha = 0.35f), Offset(x, bodyTop), Offset(x, top + h), 1f)
                rel += span
            }
        }
        // ---- name
        if (cw > 14f * dens) {
            val selPad = if (selected) min(EDGE_DP * dens, cw * 0.3f) else 0f
            val tw = (cw - 6f * dens - 2 * selPad).coerceAtLeast(0f)
            if (tw > 8f * dens) {
                val lbl = tm.measure(
                    c.name.ifEmpty { if (c.audio) "Audio" else "MIDI" }, ClipText.copy(color = onColor(head)),
                    overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = tw.toInt()),
                )
                drawText(lbl, topLeft = Offset(max(sx, 0f) + 3f * dens + selPad, top + (headH - lbl.size.height) / 2f))
            }
        }
    }
    if (selected) {
        drawRect(LiveColors.Accent, Offset(x0, top), Size(cw, h), style = Stroke(2f * dens))
        // trim handles = the edge grab zones (same width), full clip height with grip lines
        val hw = min(EDGE_DP * dens, cw * 0.3f)
        if (hw >= 4f) {
            drawRect(LiveColors.Accent, Offset(x0, top), Size(hw, h))
            drawRect(LiveColors.Accent, Offset(x0 + cw - hw, top), Size(hw, h))
            val gy0 = top + h * 0.3f; val gy1 = top + h * 0.7f
            for (gx in listOf(x0 + hw * 0.35f, x0 + hw * 0.65f, x0 + cw - hw * 0.35f, x0 + cw - hw * 0.65f))
                drawLine(Color.Black.copy(alpha = 0.55f), Offset(gx, gy0), Offset(gx, gy1), 1f * dens)
        }
    } else {
        drawRect(Color.Black.copy(alpha = 0.5f), Offset(x0, top), Size(cw, h), style = Stroke(1f))
    }
}

private fun DrawScope.drawRec(
    view: TimelineView, rec: RecState, ph: Double, scrubPx: Float, rulerPx: Float, mainH: Float,
    tm: TextMeasurer, dens: Float,
) {
    var from = rec.start
    var to = ph
    if (rec.punchIn) from = max(from, rec.loopStart)
    if (rec.punchOut) to = min(to, rec.loopEnd)
    if (to < from && rec.loopEnd > rec.loopStart + 1e-6) {
        // the playhead wrapped round the loop: the recorded region is [start..loop end] + [loop start..playhead]
        drawRecSpan(view, from, rec.loopEnd, scrubPx, rulerPx, mainH, tm, dens)
        drawRecSpan(view, rec.loopStart, ph, scrubPx, rulerPx, mainH, tm, dens)
        return
    }
    drawRecSpan(view, from, to, scrubPx, rulerPx, mainH, tm, dens)
}

private fun DrawScope.drawRecSpan(
    view: TimelineView, from: Double, to: Double, scrubPx: Float, rulerPx: Float, mainH: Float,
    tm: TextMeasurer, dens: Float,
) {
    if (to <= from) return
    val x0 = max(view.xOf(from), 0f)
    val x1 = min(view.xOf(to), size.width)
    if (x1 <= x0) return
    // semi-transparent red on top of whatever is on the lane (existing clips stay readable underneath)
    drawRect(LiveColors.Rec.copy(alpha = 0.38f), Offset(x0, rulerPx), Size(x1 - x0, mainH))
    drawRect(LiveColors.Rec, Offset(x0, rulerPx), Size(x1 - x0, 3f * dens))
    drawRect(LiveColors.Rec, Offset(x0, scrubPx - 3f * dens), Size(x1 - x0, 3f * dens))
    if (x1 - x0 > 40f * dens) {
        val l = tm.measure("REC", BeatText.copy(color = Color.White), maxLines = 1)
        drawText(l, topLeft = Offset(x0 + 4f * dens, rulerPx + mainH - l.size.height - 4f * dens))
    }
}
