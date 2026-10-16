package com.livecompanion.ui.clip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.livecompanion.state.ClipState
import com.livecompanion.state.Note
import com.livecompanion.state.RtFrame
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.theme.LiveColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class RollMode { Draw, Select }
enum class Lane { Vel, Prob }

const val MIN_PPB = 3f
const val MAX_PPB = 1200f

/** Viewport + in-flight gesture preview for the roll. All reads happen in draw lambdas / gesture code. */
class RollView {
    var ppb by mutableFloatStateOf(60f)      // pixels per beat
    var ppk by mutableFloatStateOf(24f)      // pixels per key row
    var x0 by mutableDoubleStateOf(0.0)      // beat at the left edge of the grid area
    var top by mutableFloatStateOf(72f)      // pitch value at the top edge of the grid area
    var kbW = 40f
    var rulerH = 20f
    var inited = false

    // gesture previews
    var dt by mutableDoubleStateOf(0.0)
    var dp by mutableIntStateOf(0)
    var dd by mutableDoubleStateOf(0.0)
    var box by mutableStateOf<Rect?>(null)
    var ghost by mutableStateOf<Note?>(null)

    fun xOf(beat: Double): Float = kbW + ((beat - x0) * ppb).toFloat()
    fun beatOf(x: Float): Double = x0 + (x - kbW) / ppb.toDouble()
    fun yTop(pitch: Int): Float = rulerH + (top - pitch - 1) * ppk
    fun pitchValOf(y: Float): Float = top - (y - rulerH) / ppk
    fun pitchOf(y: Float): Int = floor(pitchValOf(y)).toInt()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PianoRoll(clip: ClipState, ed: NoteEditor, rt: State<RtFrame>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var mode by remember { mutableStateOf(RollMode.Select) }
    var snap by remember { mutableStateOf(true) }
    var gridIdx by remember { mutableIntStateOf(1) }
    var lane by remember { mutableStateOf(Lane.Vel) }
    val view = remember(clip.loc) { RollView() }
    view.kbW = with(density) { 34.dp.toPx() }
    view.rulerH = with(density) { 18.dp.toPx() }
    val bb = barBeats(clip.signature.getOrElse(0) { 4 }, clip.signature.getOrElse(1) { 4 })
    val grid = GRID_OPTS[gridIdx].len(bb)
    val tm = rememberTextMeasurer()
    var gridSize by remember { mutableStateOf(IntSize.Zero) }

    // Initial fit once the size is known (and per clip).
    if (gridSize.width > 0 && gridSize.height > 0 && !view.inited) {
        view.inited = true
        val (a, b) = if (clip.looping || clip.endMarker <= clip.startMarker) clip.loopStart to clip.loopEnd else clip.startMarker to clip.endMarker
        val span = if (b > a) b - a else if (clip.len > 0) clip.len else 4.0
        val gw = gridSize.width - view.kbW
        view.ppb = (gw / (span * 1.02)).toFloat().coerceIn(MIN_PPB, MAX_PPB)
        view.x0 = (if (b > a) a else 0.0) - span * 0.01
        val ps = ed.notes.map { it.p }
        val lo = ps.minOrNull() ?: 55
        val hi = ps.maxOrNull() ?: 72
        val rows = (hi - lo + 5).coerceAtLeast(12)
        val gh = gridSize.height - view.rulerH
        val minPpk = with(density) { 9.dp.toPx() }
        val maxPpk = with(density) { 22.dp.toPx() }
        view.ppk = (gh / rows).coerceIn(minPpk, maxPpk)
        val visRows = gh / view.ppk
        view.top = ((lo + hi) / 2f + visRows / 2f + 0.5f).coerceIn(12f, 135f)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Btn("Draw", { mode = RollMode.Draw }, on = mode == RollMode.Draw)
            Btn("Select", { mode = RollMode.Select }, on = mode == RollMode.Select)
            Btn("Snap", { snap = !snap }, on = snap)
            ChoiceField("Grid", GRID_OPTS.map { it.label }, gridIdx, { gridIdx = it }, Modifier.width(92.dp).height(DenseH))
            Btn("Vel", { lane = Lane.Vel }, on = lane == Lane.Vel)
            Btn("Prob", { lane = Lane.Prob }, on = lane == Lane.Prob)
            Btn("All", { ed.selectAll() })
            Btn("Dup", { ed.duplicateSelected() }, enabled = ed.sel.isNotEmpty())
            Btn("Del", { ed.deleteSelected() }, enabled = ed.sel.isNotEmpty())
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Velocity / probability lane: bottom strip, ~20% of the editor, never below 40dp.
            val laneH = (maxHeight * 0.2f).coerceIn(40.dp, 96.dp)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(LiveColors.Bg).onSizeChanged { gridSize = it }) {
                    NotesCanvas(clip, ed, view, tm, bb, grid, snap, mode)
                    // Playhead: rt is read only inside this draw lambda -> no recomposition on ticks.
                    Canvas(Modifier.fillMaxSize()) {
                        val pos = rt.value.clipPos ?: return@Canvas
                        val x = view.xOf(pos)
                        if (x >= view.kbW && x <= size.width) drawLine(LiveColors.Play, Offset(x, 0f), Offset(x, size.height), 1.5f)
                    }
                }
                LaneCanvas(clip, ed, view, lane, Modifier.height(laneH).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NotesCanvas(
    clip: ClipState, ed: NoteEditor, view: RollView, tm: TextMeasurer,
    bb: Double, grid: Double, snap: Boolean, mode: RollMode,
) {
    val density = LocalDensity.current
    val labelSp = with(density) { 9.dp.toSp() }
    val noteColor = liveColor(clip.color)
    val gridS = rememberUpdatedState(grid)
    val snapS = rememberUpdatedState(snap)
    val modeS = rememberUpdatedState(mode)
    val touchMin = with(density) { 10.dp.toPx() }
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position
                val region = when {
                    start.y < view.rulerH -> 2
                    start.x < view.kbW -> 1
                    else -> 0
                }
                val beat0 = view.beatOf(start.x)
                val pitch0 = view.pitchOf(start.y)
                val minBeats = touchMin / view.ppb.toDouble()
                val hit = if (region == 0) hitNote(ed.notes, beat0, pitch0, minBeats) else -1
                val anchor = if (hit >= 0) ed.notes[hit] else null
                var moved = false
                var transforming = false
                var kind = 0 // 1 move, 2 resize, 3 box, 4 draw
                var selMinT = 0.0
                var selMinD = 0.0
                var selMinP = 0
                var selMaxP = 0
                val mode0 = modeS.value
                val grid0 = gridS.value
                val snap0 = snapS.value
                val minLen = if (snap0) grid0 else 0.0625

                fun beginDrag() {
                    if (region != 0) return
                    if (mode0 == RollMode.Draw) {
                        if (anchor == null && pitch0 in 0..127) {
                            kind = 4
                            val t0 = max(0.0, snapFloor(beat0, grid0, snap0))
                            view.ghost = Note(id = -999, p = pitch0, t = t0, d = if (snap0) grid0 else 0.25, v = 100)
                        }
                    } else if (anchor != null) {
                        if (anchor.id !in ed.sel) ed.sel = setOf(anchor.id)
                        val s = ed.selectedNotes()
                        selMinT = s.minOf { it.t }
                        selMinD = s.minOf { it.d }
                        selMinP = s.minOf { it.p }
                        selMaxP = s.maxOf { it.p }
                        kind = if (inResizeZone(anchor, beat0, 12.0 / view.ppb)) 2 else 1
                        ed.busy = true
                    } else {
                        kind = 3
                    }
                }

                fun drag(pos: Offset) {
                    when (kind) {
                        1 -> {
                            val raw = (pos.x - start.x) / view.ppb.toDouble()
                            view.dt = moveDelta(anchor!!.t, raw, selMinT, grid0, snap0)
                            view.dp = clampPitchDelta(((start.y - pos.y) / view.ppk).roundToInt(), selMinP, selMaxP)
                        }
                        2 -> {
                            val raw = (pos.x - start.x) / view.ppb.toDouble()
                            view.dd = resizeDelta(anchor!!.t + anchor.d, raw, selMinD, grid0, snap0, minLen)
                        }
                        3 -> {
                            view.box = Rect(min(start.x, pos.x), min(start.y, pos.y), max(start.x, pos.x), max(start.y, pos.y))
                            ed.sel = notesInBox(ed.notes, beat0, view.beatOf(pos.x), pitch0, view.pitchOf(pos.y))
                        }
                        4 -> {
                            val g = view.ghost ?: return
                            val end = if (snap0) snapNearest(view.beatOf(pos.x), grid0, true) else view.beatOf(pos.x)
                            view.ghost = g.copy(d = max(minLen, end - g.t))
                        }
                    }
                }

                fun clearPreview() {
                    view.dt = 0.0; view.dp = 0; view.dd = 0.0; view.box = null; view.ghost = null
                }

                fun finish() {
                    when (kind) {
                        1, 2 -> {
                            val dt = view.dt
                            val dp = view.dp
                            val dd = view.dd
                            if (dt != 0.0 || dp != 0 || dd != 0.0) {
                                ed.modify(
                                    ed.selectedNotes().map {
                                        NoteEdit(
                                            it.id, p = if (dp != 0) it.p + dp else null, t = if (dt != 0.0) it.t + dt else null,
                                            d = if (dd != 0.0) it.d + dd else null,
                                        )
                                    },
                                )
                            }
                        }
                        4 -> view.ghost?.let { ed.add(listOf(it.copy(id = 0))) }
                    }
                    clearPreview()
                    if (kind == 1 || kind == 2) ed.endGesture()
                    kind = 0
                }

                fun tap() {
                    if (region != 0) return
                    if (mode0 == RollMode.Draw) {
                        if (anchor != null) ed.remove(setOf(anchor.id))
                        else if (pitch0 in 0..127 && beat0 >= 0) {
                            ed.add(listOf(Note(p = pitch0, t = max(0.0, snapFloor(beat0, grid0, snap0)), d = if (snap0) grid0 else 0.25, v = 100)))
                        }
                    } else {
                        ed.sel = if (anchor != null) {
                            if (anchor.id in ed.sel) ed.sel - anchor.id else ed.sel + anchor.id
                        } else emptySet()
                    }
                }

                fun transform(a: PointerInputChange, b: PointerInputChange) {
                    val pc = (a.previousPosition + b.previousPosition) / 2f
                    val cc = (a.position + b.position) / 2f
                    val psx = abs(a.previousPosition.x - b.previousPosition.x)
                    val psy = abs(a.previousPosition.y - b.previousPosition.y)
                    val csx = abs(a.position.x - b.position.x)
                    val csy = abs(a.position.y - b.position.y)
                    val zx = if (psx > 24f) csx / psx else 1f
                    val zy = if (psy > 24f) csy / psy else 1f
                    val bA = view.beatOf(pc.x)
                    val pA = view.pitchValOf(pc.y)
                    view.ppb = (view.ppb * zx).coerceIn(MIN_PPB, MAX_PPB)
                    view.ppk = (view.ppk * zy).coerceIn(touchMin * 0.5f, touchMin * 4.5f)
                    view.x0 = max(-4.0, bA - (cc.x - view.kbW) / view.ppb.toDouble())
                    view.top = (pA + (cc.y - view.rulerH) / view.ppk).coerceIn(10f, 140f)
                }

                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (!moved && !transforming) tap() else finish()
                            break
                        }
                        if (pressed.size >= 2) {
                            if (!transforming) {
                                transforming = true
                                if (kind != 0) { clearPreview(); if (kind == 1 || kind == 2) ed.endGesture(); kind = 0 }
                            }
                            transform(pressed[0], pressed[1])
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (transforming) {
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        val c = pressed[0]
                        if (!moved) {
                            if ((c.position - start).getDistance() <= viewConfiguration.touchSlop) continue
                            moved = true
                            beginDrag()
                        }
                        when (region) {
                            1 -> view.top = (view.top + c.positionChange().y / view.ppk).coerceIn(10f, 140f)
                            2 -> view.x0 = max(-4.0, view.x0 - c.positionChange().x / view.ppb.toDouble())
                            else -> if (kind != 0) drag(c.position)
                        }
                        c.consume()
                    }
                } finally {
                    if (kind != 0) {
                        clearPreview()
                        if (kind == 1 || kind == 2) ed.endGesture()
                    }
                }
            }
        },
    ) {
        drawRoll(clip, ed, view, tm, labelSp, noteColor, bb)
    }
}

private fun DrawScope.drawRoll(clip: ClipState, ed: NoteEditor, view: RollView, tm: TextMeasurer, labelSp: TextUnit, noteColor: Color, bb: Double) {
    val kbW = view.kbW
    val rulerH = view.rulerH
    val ppb = view.ppb
    val ppk = view.ppk
    val w = size.width
    val h = size.height
    val bgDark = Color(0xFF202020)
    val bgLight = Color(0xFF282828)
    val lblStyle = TextStyle(fontSize = labelSp, color = LiveColors.TextDim)
    val pHi = min(127, ceil(view.top.toDouble()).toInt())
    val pLo = max(0, floor(view.pitchValOf(h)).toInt())

    clipRect(kbW, rulerH, w, h) {
        for (p in pLo..pHi) {
            val y = view.yTop(p)
            drawRect(if (isBlackKey(p)) bgDark else bgLight, Offset(kbW, y), Size(w - kbW, ppk))
            if (p % 12 == 0) drawLine(Color(0xFF3A3A3A), Offset(kbW, y + ppk), Offset(w, y + ppk), 1f)
        }
        // loop / region shading (outside = dim)
        val (ra, rb) = if (clip.looping) clip.loopStart to clip.loopEnd else clip.startMarker to clip.endMarker
        if (rb > ra) {
            val xa = view.xOf(ra)
            val xb = view.xOf(rb)
            if (xa > kbW) drawRect(Color(0x66000000), Offset(kbW, rulerH), Size(min(xa, w) - kbW, h - rulerH))
            if (xb < w) drawRect(Color(0x66000000), Offset(max(xb, kbW), rulerH), Size(w - max(xb, kbW), h - rulerH))
        }
        // vertical grid
        val step = gridStep(ppb)
        var k = floor(view.beatOf(kbW) / step).toLong()
        val kEnd = ceil(view.beatOf(w) / step).toLong()
        while (k <= kEnd) {
            val t = k * step
            val x = view.xOf(t)
            val bar = abs(t / bb - Math.rint(t / bb)) < 1e-6
            val beat = abs(t - Math.rint(t)) < 1e-6
            val c = if (bar) Color(0xFF666666) else if (beat) Color(0xFF3F3F3F) else Color(0xFF2F2F2F)
            drawLine(c, Offset(x, rulerH), Offset(x, h), if (bar) 1.5f else 1f)
            k++
        }
        // notes
        val sel = ed.sel
        val dt = view.dt
        val dpv = view.dp
        val dd = view.dd
        val muted = Color(0xFF777777)
        for (n in ed.notes) {
            val isSel = n.id in sel
            val t = if (isSel) n.t + dt else n.t
            val p = if (isSel) n.p + dpv else n.p
            val d = if (isSel) n.d + dd else n.d
            val x = view.xOf(t)
            val nw = (d * ppb).toFloat()
            if (x + nw < kbW || x > w) continue
            val y = view.yTop(p)
            if (y + ppk < rulerH || y > h) continue
            val base = if (n.m) muted else noteColor
            val a = 0.35f + 0.65f * (n.v / 127f)
            drawRect(base.copy(alpha = a), Offset(x, y + 0.5f), Size(max(nw - 1f, 2f), max(ppk - 1f, 1f)))
            if (isSel) drawRect(Color.White, Offset(x, y + 0.5f), Size(max(nw - 1f, 2f), max(ppk - 1f, 1f)), style = Stroke(1.5f))
        }
        view.ghost?.let { g ->
            drawRect(noteColor.copy(alpha = 0.7f), Offset(view.xOf(g.t), view.yTop(g.p) + 0.5f), Size(max((g.d * ppb).toFloat() - 1f, 2f), ppk - 1f))
        }
        view.box?.let { r ->
            drawRect(LiveColors.Accent.copy(alpha = 0.15f), r.topLeft, r.size)
            drawRect(LiveColors.Accent, r.topLeft, r.size, style = Stroke(1f))
        }
    }

    // ruler
    drawRect(LiveColors.Panel, Offset(0f, 0f), Size(w, rulerH))
    val (ra, rb) = if (clip.looping) clip.loopStart to clip.loopEnd else clip.startMarker to clip.endMarker
    if (rb > ra) {
        val xa = max(view.xOf(ra), kbW)
        val xb = min(view.xOf(rb), w)
        if (xb > xa) drawRect(LiveColors.Accent.copy(alpha = 0.55f), Offset(xa, rulerH - 3f), Size(xb - xa, 3f))
    }
    clipRect(kbW, 0f, w, rulerH) {
        val stride = barLabelStride(ppb, bb)
        val firstBar = floor(view.beatOf(kbW) / bb).toLong() - 1
        val lastBar = ceil(view.beatOf(w) / bb).toLong()
        var bar = floor(firstBar.toDouble() / stride).toLong() * stride
        while (bar <= lastBar) {
            if (bar >= 0) {
                val x = view.xOf(bar * bb)
                drawLine(Color(0xFF666666), Offset(x, rulerH * 0.35f), Offset(x, rulerH), 1f)
                drawText(tm, "${bar + 1}", Offset(x + 3f, 0f), lblStyle)
                if (stride == 1 && ppb * bb >= 150f) {
                    for (b in 1 until bb.toInt()) {
                        val xb = view.xOf(bar * bb + b)
                        drawLine(Color(0xFF555555), Offset(xb, rulerH * 0.6f), Offset(xb, rulerH), 1f)
                        if (ppb >= 70f) drawText(tm, "${bar + 1}.${b + 1}", Offset(xb + 3f, 0f), lblStyle)
                    }
                }
            }
            bar += stride
        }
    }

    // keyboard column
    clipRect(0f, rulerH, kbW, h) {
        drawRect(Color(0xFF1E1E1E), Offset(0f, rulerH), Size(kbW, h - rulerH))
        for (p in pLo..pHi) {
            val y = view.yTop(p)
            val black = isBlackKey(p)
            drawRect(
                if (black) Color(0xFF3A3A3A) else Color(0xFFBFBFBF), Offset(0f, y + 0.5f),
                Size(if (black) kbW * 0.62f else kbW, max(ppk - 1f, 1f)),
            )
            if (p % 12 == 0) {
                drawText(tm, pitchName(p), Offset(3f, y + ppk / 2f - 6f), TextStyle(fontSize = labelSp, color = Color(0xFF222222)))
            }
        }
    }
    drawRect(LiveColors.Panel, Offset(0f, 0f), Size(kbW, rulerH))
    drawLine(LiveColors.Line, Offset(kbW, 0f), Offset(kbW, h), 1f)
}

@Composable
private fun LaneCanvas(clip: ClipState, ed: NoteEditor, view: RollView, lane: Lane, modifier: Modifier) {
    val density = LocalDensity.current
    val tm = rememberTextMeasurer()
    val labelSp = with(density) { 9.dp.toSp() }
    val laneS = rememberUpdatedState(lane)
    val barW = with(density) { 4.dp.toPx() }
    val reach = with(density) { 16.dp.toPx() }
    val noteColor = liveColor(clip.color)
    Canvas(
        modifier.background(LiveColors.Bg).clipToBounds().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.x < view.kbW) return@awaitEachGesture
                val lane0 = laneS.value
                val hh = size.height.toFloat()
                val fracAt = { y: Float -> (1f - (y - 2f) / (hh - 4f)).coerceIn(0f, 1f) }
                // nearest bar by x; among ties prefer the one whose value is closest to the touch
                var best: Note? = null
                var bestDx = Float.MAX_VALUE
                for (n in ed.notes) {
                    val dx = abs(view.xOf(n.t) + barW / 2 - down.position.x)
                    if (dx > reach) continue
                    val b = best
                    if (b == null || dx < bestDx - 3f) {
                        best = n; bestDx = dx
                    } else if (abs(dx - bestDx) <= 3f) {
                        val f = fracAt(down.position.y)
                        val vn = if (lane0 == Lane.Vel) n.v / 127f else n.prob
                        val vb = if (lane0 == Lane.Vel) b.v / 127f else b.prob
                        if (abs(vn - f) < abs(vb - f)) best = n
                        bestDx = min(dx, bestDx)
                    }
                }
                val target = best ?: return@awaitEachGesture
                val ids = if (target.id in ed.sel && ed.sel.size > 1) ed.sel else setOf(target.id)
                ed.busy = true
                var last: List<NoteEdit> = emptyList()
                fun apply(y: Float) {
                    val f = fracAt(y)
                    last = ids.map { id -> if (lane0 == Lane.Vel) NoteEdit(id, v = velFromFrac(f)) else NoteEdit(id, prob = (f * 20).roundToInt() / 20f) }
                    ed.modify(last, send = false)
                }
                try {
                    apply(down.position.y)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        apply(c.position.y)
                        c.consume()
                    }
                } finally {
                    ed.modify(last)
                    ed.endGesture()
                }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        drawRect(LiveColors.Panel, Offset(0f, 0f), Size(view.kbW, h))
        drawText(tm, if (lane == Lane.Vel) "VEL" else "PROB", Offset(3f, 3f), TextStyle(fontSize = labelSp, color = LiveColors.TextDim))
        drawLine(LiveColors.Line, Offset(view.kbW, 0f), Offset(view.kbW, h), 1f)
        clipRect(view.kbW, 0f, w, h) {
            for (f in listOf(0.25f, 0.5f, 0.75f)) drawLine(Color(0xFF2A2A2A), Offset(view.kbW, h * (1 - f)), Offset(w, h * (1 - f)), 1f)
            val sel = ed.sel
            for (n in ed.notes) {
                val x = view.xOf(n.t + (if (n.id in sel) view.dt else 0.0))
                if (x < view.kbW - barW || x > w) continue
                val f = if (lane == Lane.Vel) n.v / 127f else n.prob
                val bh = f * (h - 4f)
                val c = if (n.id in sel) LiveColors.Accent else noteColor
                drawRect(c.copy(alpha = 0.85f), Offset(x, h - bh - 1f), Size(barW, bh))
            }
        }
    }
}
