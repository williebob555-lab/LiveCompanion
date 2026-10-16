package com.livecompanion.ui.clip

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.livecompanion.state.ClipState
import com.livecompanion.state.RtFrame
import com.livecompanion.ui.theme.LiveColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Edits the editors can request on the opened clip (wired in ClipScreen to optimistic store updates + ops). */
interface ClipEdits {
    fun set(key: String, value: Double)
    /** Moves/resizes the loop brace; ordered so start<end holds at every step on the server. */
    fun setLoop(start: Double, end: Double)
    fun setLooping(on: Boolean)
    fun warpAdd(beat: Double)
    fun warpMove(beat: Double, to: Double)
    fun warpRemove(beat: Double)
    /** Ruler scrub: [unit] = axis unit (beats or seconds) inside the clip; [done] on release. Optimistic in the view, throttled ops. */
    fun scrub(unit: Double, seconds: Boolean, done: Boolean)
}

/** Disables the per-frame playhead ticker (screenshot tests would never go idle). */
val LocalClipAnimate = compositionLocalOf { true }

/** Time axis description: beats with a signature, or seconds (un-warped audio). */
class AxisSpec(val seconds: Boolean, val num: Int, val den: Int) {
    val beatLen: Double = beatLenOf(den)
    val barLen: Double = barBeats(num, den)
}

/** Shared horizontal viewport + in-flight marker previews. Read inside draw lambdas / gesture code only. */
@Stable
class ClipView(var minPpu: Float = 3f, var maxPpu: Float = 1200f) {
    var ppu by mutableFloatStateOf(60f)       // px per axis unit (beat or second)
    var x0 by mutableDoubleStateOf(0.0)       // axis unit at the left edge of the content area
    var leftW = 0f                            // key / header column width (px)
    var braceH = 0f
    var rulerH = 0f
    var width by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
    var fitReq by androidx.compose.runtime.mutableIntStateOf(0)
    val topH get() = braceH + rulerH
    var inited = false
    fun requestFit() { inited = false; fitReq++ }

    var pvLs by mutableStateOf<Double?>(null)
    var pvLe by mutableStateOf<Double?>(null)
    var pvSm by mutableStateOf<Double?>(null)
    var pvEm by mutableStateOf<Double?>(null)
    /** Ruler scrub: playhead position (axis units) drawn locally while dragging and for ~600 ms after release. */
    var scrubU by mutableStateOf<Double?>(null)
    var scrubbing = false
    var scrubHoldUntil = 0L
    /** Playhead to draw: the local scrub position wins while scrubbing / held / when the clip has no live position. */
    fun headFor(live: Double?): Double? {
        val s = scrubU ?: return live
        return if (scrubbing || System.nanoTime() < scrubHoldUntil || live == null) s else live
    }

    fun xOf(u: Double): Float = leftW + ((u - x0) * ppu).toFloat()
    fun unitOf(x: Float): Double = x0 + (x - leftW) / ppu.toDouble()

    fun zoomX(factor: Float, anchorX: Float = leftW + (width - leftW) / 2f) {
        val a = unitOf(anchorX)
        ppu = (ppu * factor).coerceIn(minPpu, maxPpu)
        x0 = max(minX, a - (anchorX - leftW) / ppu.toDouble())
    }

    var minX = -2.0

    /**
     * Zoom limits: min = the whole content ([span] axis units + margin) fills the width; max = [unitsPer64] (one 1/64 note)
     * spans ~24dp. [dens] = px per dp. No-op until the size and a span are known.
     */
    fun setLimits(span: Double, unitsPer64: Double, dens: Float) {
        if (width <= leftW || span < 1e-3) return
        val gw = max(width - leftW, 50f)
        val lo = clipZoomOutPpu(gw, span)
        minPpu = lo
        maxPpu = max(lo, (24f * dens / unitsPer64).toFloat())
    }

    /** Two-finger horizontal transform step: zoom by finger-span ratio about the old midpoint, pan with the new one. */
    fun transformX(a: PointerInputChange, b: PointerInputChange) {
        val pcx = (a.previousPosition.x + b.previousPosition.x) / 2f
        val ccx = (a.position.x + b.position.x) / 2f
        val ps = abs(a.previousPosition.x - b.previousPosition.x)
        val cs = abs(a.position.x - b.position.x)
        val z = if (ps > 24f) cs / ps else 1f
        val anchor = unitOf(pcx)
        ppu = (ppu * z).coerceIn(minPpu, maxPpu)
        x0 = max(minX, anchor - (ccx - leftW) / ppu.toDouble())
    }

    /** Fit [a,b] into the content width with a little margin. */
    fun fit(a: Double, b: Double) {
        val span = max(b - a, 1e-6)
        val gw = max(width - leftW, 50f)
        ppu = (gw / (span * 1.04)).toFloat().coerceIn(minPpu, maxPpu)
        x0 = a - span * 0.02
    }

    fun clearPreviews() { pvLs = null; pvLe = null; pvSm = null; pvEm = null }
}

// ---------------------------------------------------------------- drawing

private val BarLine = Color(0xFF6C6C6C)
private val BeatLine = Color(0xFF474747)
private val SubLine = Color(0xFF353535)
private val FlagColor = Color(0xFFE2E2E2)

/** The grid for the content area between [y0] and [y1]: bar shading + bar/beat/subdivision lines. */
fun DrawScope.drawTimeGrid(v: ClipView, axis: AxisSpec, step: Double, y0: Float, y1: Float) {
    val w = size.width
    val from = v.unitOf(v.leftW)
    val to = v.unitOf(w)
    clipRect(v.leftW, y0, w, y1) {
        if (axis.seconds) {
            for (l in secondsLines(from, to, v.ppu)) {
                val x = v.xOf(l.t)
                drawLine(if (l.level == GridLevel.Bar) BarLine else SubLine, Offset(x, y0), Offset(x, y1), if (l.level == GridLevel.Bar) 1.5f else 1f)
            }
            return@clipRect
        }
        // alternate bar shading (Live-like): even bars slightly darker
        val firstBar = max(0L, floor(from / axis.barLen).toLong())
        val lastBar = ceil(to / axis.barLen).toLong()
        if ((lastBar - firstBar) < 400) {
            for (b in firstBar..lastBar) if (isDarkBar(b)) {
                val xa = v.xOf(b * axis.barLen)
                drawRect(Color(0x1E000000), Offset(xa, y0), Size((axis.barLen * v.ppu).toFloat(), y1 - y0))
            }
        }
        val lines = gridLines(from, to, step, axis.beatLen, axis.barLen)
        // when the step is coarser than a beat, still show bar lines only (fine) - lines already include levels
        for (l in lines) {
            val x = v.xOf(l.t)
            when (l.level) {
                GridLevel.Bar -> drawLine(BarLine, Offset(x, y0), Offset(x, y1), 1.5f)
                GridLevel.Beat -> drawLine(BeatLine, Offset(x, y0), Offset(x, y1), 1f)
                GridLevel.Sub -> drawLine(SubLine, Offset(x, y0), Offset(x, y1), 1f)
            }
        }
        // beats are always visible when zoomed enough, even if the grid step is coarser than a beat
        if (step < axis.beatLen - 1e-9 || step > axis.beatLen + 1e-9) {
            if (axis.beatLen * v.ppu >= 14f && step > axis.beatLen + 1e-9 && step < axis.barLen - 1e-9) {
                var k = max(0L, floor(from / axis.beatLen).toLong())
                val ke = ceil(to / axis.beatLen).toLong()
                while (k <= ke) {
                    val t = k * axis.beatLen
                    val inBar = t % axis.barLen
                    if (inBar > 1e-6 && axis.barLen - inBar > 1e-6) drawLine(BeatLine, Offset(v.xOf(t), y0), Offset(v.xOf(t), y1), 1f)
                    k++
                }
            }
        }
    }
}

/** Dim everything outside the active region (loop when looping, else start..end marker). */
fun DrawScope.drawOutsideDim(v: ClipView, a: Double, b: Double, y0: Float, y1: Float) {
    if (b <= a) return
    val w = size.width
    val xa = v.xOf(a).coerceIn(v.leftW, w)
    val xb = v.xOf(b).coerceIn(v.leftW, w)
    if (xa > v.leftW) drawRect(Color(0x70000000), Offset(v.leftW, y0), Size(xa - v.leftW, y1 - y0))
    if (xb < w) drawRect(Color(0x70000000), Offset(xb, y0), Size(w - xb, y1 - y0))
}

/**
 * Top chrome: loop-brace row, ruler (bars / beats / subdivisions, or seconds), start & end flags, corner label.
 * Marker values honour the live drag previews in [v].
 */
fun DrawScope.drawClipTop(
    v: ClipView, c: ClipState, axis: AxisSpec, step: Double, tm: TextMeasurer, lblSp: TextUnit, cornerTop: String, cornerBottom: String,
) {
    val w = size.width
    val lbl = TextStyle(fontSize = lblSp, color = LiveColors.TextDim)
    val bh = v.braceH
    val th = v.topH
    drawRect(LiveColors.Panel, Offset(0f, 0f), Size(w, th))
    drawRect(Color(0xFF232323), Offset(v.leftW, 0f), Size(w - v.leftW, bh))

    val ls = v.pvLs ?: c.loopStart
    val le = v.pvLe ?: c.loopEnd
    val sm = v.pvSm ?: c.startMarker
    val em = v.pvEm ?: c.endMarker

    clipRect(v.leftW, 0f, w, th) {
        // loop brace
        if (le > ls) {
            val xa = v.xOf(ls)
            val xb = v.xOf(le)
            val col = if (c.looping) LiveColors.Accent else Color(0xFF707070)
            drawRect(col.copy(alpha = if (c.looping) 0.9f else 0.8f), Offset(xa, 1f), Size(max(xb - xa, 2f), bh - 2f))
            val hw = 6.dp.toPx()
            drawRect(Color(0x66000000), Offset(xa, 1f), Size(hw, bh - 2f))
            drawRect(Color(0x66000000), Offset(xb - hw, 1f), Size(hw, bh - 2f))
            // edge grips so the handles read as grabbable
            for (gx in floatArrayOf(xa + hw * 0.33f, xa + hw * 0.66f, xb - hw * 0.66f, xb - hw * 0.33f))
                drawLine(Color(0xCC000000), Offset(gx, 3.dp.toPx()), Offset(gx, bh - 3.dp.toPx()), 1f)
            drawText(tm, "LOOP", Offset(xa + hw + 3f, (bh - lblSp.value * 1.1f) / 2f - 1f), TextStyle(fontSize = lblSp, color = Color.Black))
        }
        // ruler
        if (axis.seconds) {
            var lastX = -1000f
            for (l in secondsLines(v.unitOf(v.leftW), v.unitOf(w), v.ppu)) {
                val x = v.xOf(l.t)
                val major = l.level == GridLevel.Bar
                drawLine(if (major) BarLine else BeatLine, Offset(x, th * (if (major) 0.5f else 0.75f)), Offset(x, th), 1f)
                if (major && x - lastX > 40f) { drawText(tm, fmtSeconds(l.t), Offset(x + 3f, bh), lbl); lastX = x }
            }
        } else {
            val from = v.unitOf(v.leftW)
            val to = v.unitOf(w)
            // always show bars; beats and subdivisions as zoom allows
            val stridePx = (step * v.ppu).toFloat()
            val lines = gridLines(from, to, step, axis.beatLen, axis.barLen)
            val barStride = labelStride(v.ppu, axis.barLen)
            // Label thinning (same idea as the audio ruler): bar numbers every 2/4/8/16/32 bars by available px (barStride);
            // beat / sixteenth labels only where they fit between already-placed labels (no overlap, 4px gap).
            val taken = ArrayList<ClosedFloatingPointRange<Float>>()
            fun fits(txt: String, x: Float): Boolean {
                val wTxt = tm.measure(txt, lbl).size.width.toFloat()
                val a0 = x + 3f - 4f; val a1 = x + 3f + wTxt + 4f
                return taken.none { it.start < a1 && a0 < it.endInclusive }
            }
            fun place(txt: String, x: Float) {
                val wTxt = tm.measure(txt, lbl).size.width.toFloat()
                taken.add((x + 3f)..(x + 3f + wTxt))
                drawText(tm, txt, Offset(x + 3f, bh), lbl)
            }
            // bar labels first (they win), then the finer ones
            run {
                var b = max(0L, floor(from / axis.barLen).toLong())
                val be = ceil(to / axis.barLen).toLong()
                while (b <= be) {
                    if (b % barStride == 0L) { val x = v.xOf(b * axis.barLen); val t = "${b + 1}"; if (fits(t, x)) place(t, x) }
                    b++
                }
            }
            for (l in lines) {
                val x = v.xOf(l.t)
                when (l.level) {
                    GridLevel.Bar -> {
                        drawLine(BarLine, Offset(x, bh + (th - bh) * 0.15f), Offset(x, th), 1f)
                    }
                    GridLevel.Beat -> {
                        drawLine(BeatLine, Offset(x, bh + (th - bh) * 0.5f), Offset(x, th), 1f)
                        rulerLabel(l, (axis.beatLen * v.ppu).toFloat(), axis.barLen, axis.beatLen)?.let { if (barStride == 1 && fits(it, x)) place(it, x) }
                    }
                    GridLevel.Sub -> {
                        if (stridePx >= 5f) drawLine(SubLine, Offset(x, bh + (th - bh) * 0.75f), Offset(x, th), 1f)
                        rulerLabel(l, stridePx, axis.barLen, axis.beatLen)?.let { if (barStride == 1 && fits(it, x)) place(it, x) }
                    }
                }
            }
            // bar lines when the step is coarser than a bar are still needed
            if (step >= axis.barLen) {
                var b = max(0L, floor(from / axis.barLen).toLong())
                val be = ceil(to / axis.barLen).toLong()
                while (b <= be) {
                    val x = v.xOf(b * axis.barLen)
                    drawLine(BarLine, Offset(x, bh + (th - bh) * 0.15f), Offset(x, th), 1f)
                    b++
                }
            }
        }
        // start / end flags in the ruler row
        val fw = (th - bh) * 0.7f
        for ((u, isStart) in listOf(sm to true, em to false)) {
            val x = v.xOf(u)
            drawLine(FlagColor, Offset(x, bh), Offset(x, th), 1.5f)
            val p = Path().apply {
                if (isStart) { moveTo(x, bh); lineTo(x + fw, bh + (th - bh) * 0.35f); lineTo(x, bh + (th - bh) * 0.7f) }
                else { moveTo(x, bh); lineTo(x - fw, bh + (th - bh) * 0.35f); lineTo(x, bh + (th - bh) * 0.7f) }
                close()
            }
            drawPath(p, FlagColor)
        }
    }
    drawLine(LiveColors.Line, Offset(0f, th), Offset(w, th), 1f)
    // corner: signature + grid
    drawRect(LiveColors.PanelHi, Offset(0f, 0f), Size(v.leftW, th))
    drawText(tm, cornerTop, Offset(3f, 1f), TextStyle(fontSize = lblSp, color = LiveColors.Text))
    drawText(tm, cornerBottom, Offset(3f, th / 2f), TextStyle(fontSize = lblSp, color = LiveColors.TextDim))
    drawLine(LiveColors.Line, Offset(v.leftW, 0f), Offset(v.leftW, th), 1f)
}

/** Red growing region (recording progression) from [a] to [b] plus a red strip on the ruler. */
fun DrawScope.drawRecRegion(v: ClipView, a: Double, b: Double, y0: Float, y1: Float) {
    if (b <= a) return
    val w = size.width
    val xa = v.xOf(a).coerceIn(v.leftW, w)
    val xb = v.xOf(b).coerceIn(v.leftW, w)
    if (xb <= xa) return
    drawRect(LiveColors.Rec.copy(alpha = 0.22f), Offset(xa, y0), Size(xb - xa, y1 - y0))
    drawRect(LiveColors.Rec, Offset(xa, y0 - 4f), Size(xb - xa, 4f))
}

/** Playhead: thin line + small head on the ruler. */
fun DrawScope.drawPlayhead(v: ClipView, u: Double, color: Color, y0: Float, y1: Float) {
    val x = v.xOf(u)
    if (x < v.leftW || x > size.width) return
    drawLine(color, Offset(x, y0), Offset(x, y1), 1.6f)
    val p = Path().apply { moveTo(x - 5f, y0); lineTo(x + 5f, y0); lineTo(x, y0 + 7f); close() }
    drawPath(p, color)
}

// ---------------------------------------------------------------- top-region gesture

/**
 * Runs the whole gesture that started in the top chrome at [down]: loop brace (edges resize, body moves, tap toggles
 * looping), start/end flags drag, anything else pans horizontally; two fingers zoom. Returns when the pointer lifts.
 */
suspend fun AwaitPointerEventScope.clipTopGesture(
    down: PointerInputChange, v: ClipView, c: ClipState, ops: ClipEdits, snap: Double, hit: Float, seconds: Boolean = false,
) {
    val x = down.position.x
    // The loop edges also grab from the ruler row and the strip under it (see [inTopGestureZone]); markers win in the ruler.
    val nearMarker = abs(x - v.xOf(c.startMarker)) <= hit || abs(x - v.xOf(c.endMarker)) <= hit
    val nearLoopEdge = c.loopEnd > c.loopStart && min(abs(x - v.xOf(c.loopStart)), abs(x - v.xOf(c.loopEnd))) <= hit
    val inBrace = down.position.y < v.braceH || (down.position.y >= v.topH) || (nearLoopEdge && !nearMarker)
    // 1 loop start, 2 loop end, 3 loop body, 4 start marker, 5 end marker, 0 pan
    var target = 0
    if (inBrace) {
        val xs = v.xOf(c.loopStart); val xe = v.xOf(c.loopEnd)
        val edge = min(hit, max(4f, (xe - xs) / 3f))
        target = when {
            abs(x - xs) <= edge && abs(x - xs) <= abs(x - xe) -> 1
            abs(x - xe) <= edge -> 2
            x in xs..xe -> 3
            else -> 0
        }
    } else {
        val xs = v.xOf(c.startMarker); val xe = v.xOf(c.endMarker)
        val ds = abs(x - xs); val de = abs(x - xe)
        target = when {
            ds <= hit && ds <= de -> 4
            de <= hit -> 5
            else -> 0
        }
    }
    // A press on the ruler row that grabs nothing scrubs the playhead (finger = playhead, like the track view).
    val scrubbing = target == 0 && down.position.y >= v.braceH
    val grab = v.unitOf(x) - when (target) { 1, 3 -> c.loopStart; 2 -> c.loopEnd; 4 -> c.startMarker; 5 -> c.endMarker; else -> 0.0 }
    val loopLen = c.loopEnd - c.loopStart
    var moved = false
    var transforming = false
    var scrubU = 0.0
    fun scrubTo(px: Float, done: Boolean) {
        scrubU = max(0.0, snapValue(v.unitOf(px), snap))
        v.scrubU = scrubU
        ops.scrub(scrubU, seconds, done)
    }
    if (scrubbing) { v.scrubbing = true; scrubTo(x, false); down.consume() }
    try {
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (scrubbing) {
                    if (!transforming) scrubTo(ev.changes.firstOrNull()?.position?.x ?: x, true)
                    v.scrubbing = false
                    v.scrubHoldUntil = System.nanoTime() + 600_000_000L
                    break
                }
                if (!moved && !transforming && target in 1..3) ops.setLooping(!c.looping)
                else if (moved && !transforming) {
                    when (target) {
                        1, 2, 3 -> {
                            val ls = v.pvLs ?: c.loopStart; val le = v.pvLe ?: c.loopEnd
                            if (abs(ls - c.loopStart) > 1e-9 || abs(le - c.loopEnd) > 1e-9) ops.setLoop(ls, le)
                        }
                        4 -> v.pvSm?.let { if (abs(it - c.startMarker) > 1e-9) ops.set("start_marker", it) }
                        5 -> v.pvEm?.let { if (abs(it - c.endMarker) > 1e-9) ops.set("end_marker", it) }
                    }
                }
                break
            }
            if (pressed.size >= 2) {
                transforming = true
                v.scrubbing = false
                v.clearPreviews()
                v.transformX(pressed[0], pressed[1])
                ev.changes.forEach { it.consume() }
                continue
            }
            if (transforming) { ev.changes.forEach { it.consume() }; continue }
            val p = pressed[0]
            if (scrubbing) { scrubTo(p.position.x, false); p.consume(); continue }
            if (!moved) {
                if ((p.position - down.position).getDistance() <= viewConfiguration.touchSlop) continue
                moved = true
            }
            val raw = snapValue(v.unitOf(p.position.x) - grab, snap)
            when (target) {
                1 -> v.pvLs = max(0.0, min(raw, c.loopEnd - MIN_GAP))
                2 -> v.pvLe = max(raw, c.loopStart + MIN_GAP)
                3 -> { val ls = max(0.0, raw); v.pvLs = ls; v.pvLe = ls + loopLen }
                4 -> v.pvSm = clampMarker("start_marker", raw, c)
                5 -> v.pvEm = clampMarker("end_marker", raw, c)
                else -> v.x0 = max(v.minX, v.x0 - p.positionChange().x / v.ppu.toDouble())
            }
            p.consume()
        }
    } finally {
        v.clearPreviews()
        v.scrubbing = false
    }
}

/** True when a press at [pos] belongs to [clipTopGesture]: the top chrome, or the strip under it (up to [bandPx] from the top) next to a loop edge. */
fun inTopGestureZone(v: ClipView, c: ClipState, pos: androidx.compose.ui.geometry.Offset, hit: Float, bandPx: Float): Boolean {
    if (pos.x < v.leftW) return false
    if (pos.y < v.topH) return true
    if (pos.y >= max(v.topH, bandPx) || c.loopEnd <= c.loopStart) return false
    return min(abs(pos.x - v.xOf(c.loopStart)), abs(pos.x - v.xOf(c.loopEnd))) <= hit
}

// ---------------------------------------------------------------- playhead interpolation

/**
 * Predicts the clip position between ~20 Hz rt frames: [pos] sampled [dtSec] ago, advancing at [unitsPerSec],
 * wrapping inside the loop when looping, never beyond [endLimit] (and never more than [maxAheadSec] ahead of the sample).
 */
fun predictPos(
    pos: Double, dtSec: Double, unitsPerSec: Double, looping: Boolean, loopStart: Double, loopEnd: Double,
    endLimit: Double = Double.MAX_VALUE, maxAheadSec: Double = 0.25,
): Double {
    val dt = dtSec.coerceIn(0.0, maxAheadSec)
    var p = pos + dt * unitsPerSec
    val span = loopEnd - loopStart
    if (looping && span > 1e-9 && p >= loopEnd && pos < loopEnd + 1e-9) p = loopStart + (p - loopStart) % span
    else if (!looping && p > endLimit) p = max(pos, endLimit)
    return p
}

@Stable
class PlayheadInterp {
    private var lastClipPos: Double? = null
    var pos = 0.0
    var tNanos = 0L
    var playing = false

    fun sync(rt: RtFrame, nowNanos: Long) {
        val cp = rt.clipPos
        playing = rt.playing && cp != null
        if (cp == null) { lastClipPos = null; return }
        if (cp != lastClipPos || tNanos == 0L) { lastClipPos = cp; pos = cp; tNanos = nowNanos }
    }

    fun at(nowNanos: Long, unitsPerSec: Double, looping: Boolean, ls: Double, le: Double, endLimit: Double): Double? {
        if (lastClipPos == null) return null
        if (!playing) return pos
        return predictPos(pos, (nowNanos - tNanos) / 1e9, unitsPerSec, looping, ls, le, endLimit)
    }
}

/**
 * Returns a lambda giving the interpolated clip position (axis units) or null; call it ONLY from draw lambdas so just
 * the canvas redraws per frame. [follow] gets the head position every frame (used to scroll along while recording).
 */
@Composable
fun rememberPlayhead(
    rt: State<RtFrame>, c: ClipState, tempo: Float, seconds: Boolean, rec: Boolean, follow: ((Double) -> Unit)? = null,
): () -> Double? {
    val animate = LocalClipAnimate.current
    val interp = remember { PlayheadInterp() }
    val tick = remember { mutableLongStateOf(0L) }
    val cS = rememberUpdatedState(c)
    val tempoS = rememberUpdatedState(tempo)
    val recS = rememberUpdatedState(rec)
    val followS = rememberUpdatedState(follow)
    val secS = rememberUpdatedState(seconds)
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { n ->
                interp.sync(rt.value, n)
                tick.longValue = n
                val cc = cS.value
                val head = interp.at(n, if (secS.value) 1.0 else tempoS.value / 60.0, cc.looping && !recS.value, cc.loopStart, cc.loopEnd,
                    if (recS.value) Double.MAX_VALUE else max(cc.endMarker, cc.loopEnd))
                if (head != null) followS.value?.invoke(head)
            }
        }
    }
    return {
        if (!animate) rt.value.clipPos
        else {
            tick.longValue // subscribe this draw scope to frame ticks
            val cc = cS.value
            interp.at(tick.longValue, if (secS.value) 1.0 else tempoS.value / 60.0, cc.looping && !recS.value, cc.loopStart, cc.loopEnd,
                if (recS.value) Double.MAX_VALUE else max(cc.endMarker, cc.loopEnd))
        }
    }
}

/** Clip editor zoom-out limit: the clip ([span] axis units) occupies ~1/4 of the content width [gw] px. */
fun clipZoomOutPpu(gw: Float, span: Double): Float = (gw / (span * 4.0)).toFloat()
