package com.livecompanion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livecompanion.state.RtFrame
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Horizontal view of a [Timeline]: first visible beat and pixels per beat (0 = not fitted yet). */
@Stable
class TimelineView {
    var start by mutableDoubleStateOf(0.0)
    var ppb by mutableFloatStateOf(0f)
    /** Measured width, px (observable so screens can fit once it is known). */
    var widthPx by mutableFloatStateOf(0f)

    val fitted get() = ppb > 0f

    fun fit(from: Double, to: Double) {
        if (widthPx <= 0f) return
        val span = max(to - from, 4.0)
        val pad = span * 0.04
        start = max(0.0, from - pad)
        ppb = (widthPx / (span + 2 * pad)).toFloat().coerceIn(MIN_PPB, MAX_PPB)
    }

    fun beatAt(x: Float): Double = start + x / ppb
    fun xOf(beat: Double): Float = ((beat - start) * ppb).toFloat()

    companion object {
        const val MIN_PPB = 0.3f
        const val MAX_PPB = 900f
    }
}

/**
 * Vertical scroll of a [Timeline]'s lanes. The ruler stays fixed; lane labels outside the timeline
 * (gutter) should offset themselves by [offset] to stay aligned.
 */
@Stable
class TimelineVScroll {
    var offset by mutableFloatStateOf(0f)
    var content by mutableFloatStateOf(0f)
    var viewport by mutableFloatStateOf(0f)
    val max get() = max(0f, content - viewport)
    fun scrollBy(dy: Float) { offset = (offset + dy).coerceIn(0f, max) }
    val clamped get() = offset.coerceIn(0f, max)
}

data class TClip(
    val key: String,
    val name: String,
    val color: Int,
    val start: Double,
    val end: Double,
    val muted: Boolean = false,
    val selected: Boolean = false,
    /** 0 = none, 1..3 drawn as stars. */
    val stars: Int = 0,
    val dimmed: Boolean = false,
)

data class TLane(val clips: List<TClip>)

data class TLoop(val enabled: Boolean, val start: Double, val len: Double)

/** Height of the bar ruler: grows with font scale so ruler text never clips. */
@Composable
fun timelineRulerHeight(): Dp = with(LocalDensity.current) { 11.sp.toDp() } + 8.dp

/**
 * Lane height that uses the room on large screens (tablets / big windows): [frac] of the screen height shared by
 * [lanes] lanes, clamped to [min]..[max]. Small screens (phones, esp. landscape) keep [min].
 */
@Composable
fun adaptiveLaneHeight(lanes: Int, min: Dp, max: Dp, frac: Float): Dp {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    if (cfg.screenWidthDp < 600 || cfg.screenHeightDp < 500) return min
    val ruler = timelineRulerHeight()
    return ((cfg.screenHeightDp.dp * frac - ruler) / lanes.coerceAtLeast(1)).coerceIn(min, max)
}

/** Total height of a timeline with [lanes] lanes of [laneHeight]. */
@Composable
fun timelineHeight(lanes: Int, laneHeight: Dp): Dp = timelineRulerHeight() + laneHeight * lanes

/**
 * Bar ruler + stacked clip lanes. Pinch/drag pans and zooms horizontally inside its own bounds only.
 * Tap on the ruler -> [onRulerTap]; tap a clip -> [onClipTap]; tap empty lane -> [onEmptyTap].
 * Loop brace edges (in the ruler) drag when [onLoopChange] is set. The playhead is a separate leaf
 * that collects [rt]; nothing else recomposes on ticks.
 */
@Composable
fun Timeline(
    view: TimelineView,
    lanes: List<TLane>,
    beatsPerBar: Double,
    rt: StateFlow<RtFrame>,
    modifier: Modifier = Modifier,
    laneHeight: Dp = 48.dp,
    loop: TLoop? = null,
    onLoopChange: ((start: Double, len: Double, done: Boolean) -> Unit)? = null,
    follow: Boolean = false,
    vScroll: TimelineVScroll? = null,
    onRulerTap: (Double) -> Unit = {},
    onClipTap: (lane: Int, clip: TClip) -> Unit = { _, _ -> },
    onEmptyTap: (lane: Int, beat: Double) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val rulerH = timelineRulerHeight()
    val rulerPx = with(density) { rulerH.toPx() }
    val laneHPx = with(density) { laneHeight.toPx() }
    val edgeHit = with(density) { 20.dp.toPx() }
    val tapPad = with(density) { 5.dp.toPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    val measurer = rememberTextMeasurer()

    val curLanes by rememberUpdatedState(lanes)
    val curLoop by rememberUpdatedState(loop)
    val curBpb by rememberUpdatedState(beatsPerBar)
    val curOnLoop by rememberUpdatedState(onLoopChange)
    val curRuler by rememberUpdatedState(onRulerTap)
    val curClipTap by rememberUpdatedState(onClipTap)
    val curEmptyTap by rememberUpdatedState(onEmptyTap)
    val curV by rememberUpdatedState(vScroll)
    vScroll?.let { v -> androidx.compose.runtime.SideEffect { v.content = laneHPx * lanes.size } }
    // While dragging a loop edge we render the local value; cleared when the finger lifts.
    var dragLoop by remember { mutableStateOf<TLoop?>(null) }

    LaunchedEffect(follow) {
        if (!follow) return@LaunchedEffect
        rt.collect { f ->
            if (f.playing && view.fitted && view.widthPx > 0f) {
                val visible = view.widthPx / view.ppb
                if (f.pos < view.start || f.pos > view.start + visible * 0.92) {
                    view.start = max(0.0, f.pos - visible * 0.08)
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(rulerH + laneHeight * lanes.size)
            .background(LiveColors.Bg)
            .clipToBounds()
            .onSizeChanged { view.widthPx = it.width.toFloat(); vScroll?.viewport = it.height - rulerPx }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val p0 = down.position
                    // Loop edge grab (ruler only).
                    var edge = 0
                    val lp = curLoop
                    if (p0.y <= rulerPx && lp != null && curOnLoop != null && view.fitted) {
                        val ds = abs(view.xOf(lp.start) - p0.x)
                        val de = abs(view.xOf(lp.start + lp.len) - p0.x)
                        if (min(ds, de) <= edgeHit) edge = if (ds <= de) 1 else 2
                    }
                    var moved = false
                    var multi = false
                    var prevCx = p0.x
                    var prevSpan = 0f
                    var loopNow = lp
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (!view.fitted) continue
                        if (pressed.size >= 2) {
                            multi = true
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val cx = (a.x + b.x) / 2f
                            val span = max(abs(a.x - b.x), (a - b).getDistance() * 0.5f)
                            if (prevSpan > 0f && span > 1f) {
                                val anchor = view.beatAt(prevCx)
                                val np = (view.ppb * span / prevSpan).coerceIn(TimelineView.MIN_PPB, TimelineView.MAX_PPB)
                                view.ppb = np
                                view.start = max(0.0, anchor - cx / np)
                            }
                            prevCx = cx
                            prevSpan = span
                            ev.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && !multi) {
                            val c = pressed[0]
                            if (!moved && (c.position - p0).getDistance() > slop) {
                                moved = true
                                prevCx = c.previousPosition.x
                            }
                            if (moved) {
                                if (edge != 0 && lp != null) {
                                    val raw = view.beatAt(c.position.x)
                                    val grid = if (view.ppb * curBpb < 40f) curBpb else 1.0
                                    val snapped = (raw / grid).roundToInt() * grid
                                    val end = lp.start + lp.len
                                    val nl = if (edge == 1) {
                                        val s = snapped.coerceIn(0.0, end - 1.0)
                                        TLoop(lp.enabled, s, end - s)
                                    } else {
                                        TLoop(lp.enabled, lp.start, max(1.0, snapped - lp.start))
                                    }
                                    if (nl != loopNow) {
                                        loopNow = nl
                                        dragLoop = nl
                                        curOnLoop?.invoke(nl.start, nl.len, false)
                                    }
                                } else {
                                    view.start = max(0.0, view.start - (c.position.x - c.previousPosition.x) / view.ppb)
                                    if (p0.y > rulerPx) curV?.scrollBy(-(c.position.y - c.previousPosition.y))
                                }
                                c.consume()
                            }
                        } else if (pressed.size == 1) {
                            // finger left after pinch: re-baseline
                            prevCx = pressed[0].position.x
                            prevSpan = 0f
                        }
                        if (pressed.size < 2) prevSpan = 0f
                    } while (ev.changes.any { it.pressed })
                    if (edge != 0 && moved) {
                        loopNow?.let { curOnLoop?.invoke(it.start, it.len, true) }
                        dragLoop = null
                    } else if (!moved && !multi && view.fitted) {
                        if (p0.y <= rulerPx) {
                            curRuler(max(0.0, view.beatAt(p0.x)))
                        } else {
                            val li = ((p0.y - rulerPx + (curV?.clamped ?: 0f)) / laneHPx).toInt()
                            val lane = curLanes.getOrNull(li)
                            if (lane != null) {
                                val beat = view.beatAt(p0.x)
                                var best: TClip? = null
                                var bestD = Float.MAX_VALUE
                                for (c in lane.clips) {
                                    val sx = view.xOf(c.start)
                                    val ex = view.xOf(c.end)
                                    if (p0.x >= sx - tapPad && p0.x <= ex + tapPad) {
                                        val d = if (p0.x in sx..ex) 0f else min(abs(p0.x - sx), abs(p0.x - ex))
                                        if (d <= bestD) { best = c; bestD = d }
                                    }
                                }
                                if (best != null) curClipTap(li, best) else curEmptyTap(li, beat)
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (!view.fitted) return@Canvas
            val vs = vScroll
            if (vs == null) {
                drawTimeline(view, lanes, curBpb, dragLoop ?: loop, rulerPx, laneHPx, measurer, density.density)
            } else {
                // Lanes scroll vertically under a fixed ruler: draw everything shifted, then the ruler on top.
                translate(top = -vs.clamped) {
                    drawTimeline(view, lanes, curBpb, dragLoop ?: loop, rulerPx, laneHPx, measurer, density.density)
                }
                clipRect(0f, 0f, size.width, rulerPx) {
                    drawTimeline(view, emptyList(), curBpb, dragLoop ?: loop, rulerPx, laneHPx, measurer, density.density)
                }
            }
        }
        PlayheadLayer(rt, view, Modifier.fillMaxSize())
    }
}

@Composable
private fun PlayheadLayer(rt: StateFlow<RtFrame>, view: TimelineView, modifier: Modifier) {
    val f by rt.collectAsState()
    Canvas(modifier) {
        if (!view.fitted) return@Canvas
        val x = view.xOf(f.pos)
        if (x in 0f..size.width) {
            val c = if (f.playing) LiveColors.Play else LiveColors.Text
            drawLine(c, Offset(x, 0f), Offset(x, size.height), 1.5f)
        }
    }
}

private val RulerText = LiveType.Label.copy(fontSize = 10.sp, letterSpacing = 0.sp, color = LiveColors.TextDim)
private val ClipText = LiveType.Small.copy(fontSize = 11.sp)

private fun DrawScope.drawTimeline(
    view: TimelineView,
    lanes: List<TLane>,
    bpb: Double,
    loop: TLoop?,
    rulerPx: Float,
    laneH: Float,
    tm: TextMeasurer,
    dens: Float,
) {
    val w = size.width
    val ppb = view.ppb
    val barPx = (ppb * bpb).toFloat()
    val firstBeat = view.start
    val lastBeat = view.start + w / ppb

    // lane backgrounds
    lanes.forEachIndexed { i, _ ->
        val top = rulerPx + i * laneH
        drawRect(if (i % 2 == 0) LiveColors.Panel else Color(0xFF262626), Offset(0f, top), Size(w, laneH))
        drawLine(LiveColors.Line, Offset(0f, top + laneH - 0.5f), Offset(w, top + laneH - 0.5f), 1f)
    }
    drawRect(LiveColors.PanelHi, Offset.Zero, Size(w, rulerPx))
    drawLine(LiveColors.Line, Offset(0f, rulerPx - 0.5f), Offset(w, rulerPx - 0.5f), 1f)

    // grid + ruler labels
    val minLabelPx = 38f * dens
    var step = 1
    while (step * barPx < minLabelPx && step < (1 shl 20)) step *= 2
    val firstBar = max(0, floor(firstBeat / bpb).toInt())
    val lastBar = ceil(lastBeat / bpb).toInt()
    val totalH = rulerPx + laneH * lanes.size
    val barLines = barPx >= 5f * dens
    var bar = firstBar - (firstBar % step)
    while (bar <= lastBar) {
        val x = view.xOf(bar * bpb)
        if (x >= -1f && x <= w) {
            drawLine(if (bar % step == 0) Color(0xFF4A4A4A) else Color(0xFF353535), Offset(x, 0f), Offset(x, totalH), 1f)
            val label = tm.measure("${bar + 1}", RulerText, maxLines = 1)
            drawText(label, topLeft = Offset(x + 3f * dens, (rulerPx - label.size.height) / 2f))
        }
        bar += step
    }
    if (barLines && step > 1) {
        var b = firstBar
        while (b <= lastBar) {
            if (b % step != 0) {
                val x = view.xOf(b * bpb)
                drawLine(Color(0xFF2F2F2F), Offset(x, rulerPx * 0.6f), Offset(x, totalH), 1f)
            }
            b++
        }
    }
    if (ppb >= 10f * dens) {
        var bt = ceil(firstBeat).toInt()
        while (bt <= lastBeat) {
            if (abs(bt / bpb - Math.rint(bt / bpb)) > 1e-6) {
                val x = view.xOf(bt.toDouble())
                drawLine(Color(0xFF303030), Offset(x, rulerPx * 0.7f), Offset(x, totalH), 1f)
            }
            bt++
        }
    }

    // loop region
    if (loop != null && loop.len > 0) {
        val ls = view.xOf(loop.start)
        val le = view.xOf(loop.start + loop.len)
        val col = if (loop.enabled) LiveColors.Accent else LiveColors.TextDim
        drawRect(col.copy(alpha = if (loop.enabled) 0.12f else 0.06f), Offset(ls, rulerPx), Size(le - ls, laneH * lanes.size))
        drawRect(col.copy(alpha = 0.55f), Offset(ls, rulerPx - 4f * dens), Size(le - ls, 4f * dens))
        val hw = 4f * dens
        drawRect(col, Offset(ls - 1f, 0f), Size(hw, rulerPx))
        drawRect(col, Offset(le - hw + 1f, 0f), Size(hw, rulerPx))
        drawLine(col.copy(alpha = 0.8f), Offset(ls, rulerPx), Offset(ls, totalH), 1f)
        drawLine(col.copy(alpha = 0.8f), Offset(le, rulerPx), Offset(le, totalH), 1f)
    }

    // clips
    val pad = 3f * dens
    lanes.forEachIndexed { li, lane ->
        val top = rulerPx + li * laneH + 2f * dens
        val h = laneH - 4f * dens
        for (c in lane.clips) {
            val sx = view.xOf(c.start)
            val ex = view.xOf(c.end)
            if (ex < 0f || sx > w) continue
            val x0 = max(sx, -2f)
            val x1 = min(ex, w + 2f)
            val cw = max(x1 - x0, 2f)
            var bg = liveColor(c.color)
            if (c.muted) bg = dim(bg, 0.4f) else if (c.dimmed) bg = dim(bg, 0.6f)
            drawRect(bg, Offset(x0, top), Size(cw, h))
            drawRect(Color.Black.copy(alpha = 0.25f), Offset(x0, top), Size(cw, 12f * dens))
            val fg = onColor(bg)
            if (cw > 14f * dens) {
                clipRect(x0, top, x0 + cw, top + h) {
                    val tw = (cw - 2 * pad - if (c.stars > 0) 3 * 9f * dens else 0f).coerceAtLeast(0f)
                    if (tw > 8f * dens) {
                        val lbl = tm.measure(
                            c.name.ifEmpty { "-" }, ClipText.copy(color = fg), overflow = TextOverflow.Ellipsis, maxLines = 1,
                            constraints = androidx.compose.ui.unit.Constraints(maxWidth = tw.toInt()),
                        )
                        drawText(lbl, topLeft = Offset(max(sx, 0f) + pad, top + 1f * dens))
                    }
                }
            }
            if (c.stars > 0 && cw > 20f * dens) {
                val sz = 8f * dens
                val right = x0 + cw - pad
                val sColor = if (bg.luminance() > 0.5f) Color(0xFF7A4B00) else Color(0xFFFFD35A)
                for (k in 0 until c.stars) {
                    drawStar(Offset(right - sz / 2 - k * (sz + 1f * dens), top + h - sz / 2 - 2f * dens), sz / 2, sColor)
                }
            }
            if (c.selected) {
                drawRect(LiveColors.Accent, Offset(x0, top), Size(cw, h), style = Stroke(2f * dens))
            } else {
                drawRect(Color.Black.copy(alpha = 0.5f), Offset(x0, top), Size(cw, h), style = Stroke(1f))
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Five-point star centered at [c] with outer radius [r]. */
fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val a = Math.PI / 5 * i - Math.PI / 2
        val rr = if (i % 2 == 0) r else r * 0.45f
        val x = c.x + (Math.cos(a) * rr).toFloat()
        val y = c.y + (Math.sin(a) * rr).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
