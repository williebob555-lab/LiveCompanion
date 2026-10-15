package com.livecompanion.ui.clip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.livecompanion.net.Peaks
import com.livecompanion.net.PeaksClient
import com.livecompanion.net.PeaksResult
import com.livecompanion.state.ClipState
import com.livecompanion.state.RtFrame
import com.livecompanion.state.WarpMarker
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Callbacks the waveform uses to edit the clip (wired to client ops + optimistic store updates). */
interface AudioOps {
    fun set(key: String, value: Double)
    fun warpAdd(beat: Double)
    fun warpMove(beat: Double, to: Double)
    fun warpRemove(beat: Double)
}

/**
 * Maps the editor's x axis (beats when warped, seconds otherwise) to file time in seconds.
 * Warp markers give a piecewise-linear map (extrapolated past the ends).
 */
class AudioMap(val warping: Boolean, markers: List<WarpMarker>, val fileSec: Double, fallbackAxis: Double) {
    private val m = markers.sortedBy { it.beat }
    private val fb = if (fileSec > 0 && fallbackAxis > 0) fileSec / fallbackAxis else 1.0

    private fun slope(i: Int): Double { // segment i..i+1
        val dB = m[i + 1].beat - m[i].beat
        val dS = m[i + 1].sampleTime - m[i].sampleTime
        return if (dB > 1e-9 && dS > 0) dS / dB else fb
    }

    fun secOf(axis: Double): Double {
        if (!warping) return axis
        if (m.size < 2) return axis * fb
        if (axis <= m.first().beat) return m.first().sampleTime + (axis - m.first().beat) * slope(0)
        if (axis >= m.last().beat) return m.last().sampleTime + (axis - m.last().beat) * slope(m.size - 2)
        var i = 0
        while (i < m.size - 2 && axis > m[i + 1].beat) i++
        return m[i].sampleTime + (axis - m[i].beat) * slope(i)
    }

    fun axisOf(sec: Double): Double {
        if (!warping) return sec
        if (m.size < 2) return if (fb > 0) sec / fb else sec
        val inv = { i: Int -> 1.0 / slope(i) }
        if (sec <= m.first().sampleTime) return m.first().beat + (sec - m.first().sampleTime) * inv(0)
        if (sec >= m.last().sampleTime) return m.last().beat + (sec - m.last().sampleTime) * inv(m.size - 2)
        var i = 0
        while (i < m.size - 2 && sec > m[i + 1].sampleTime) i++
        return m[i].beat + (sec - m[i].sampleTime) * inv(i)
    }
}

/** Visible axis range to show the whole useful extent of the clip. */
fun audioExtent(clip: ClipState, map: AudioMap, fileSec: Double): Pair<Double, Double> {
    val lo = minOf(0.0, clip.startMarker, clip.loopStart)
    var hi = maxOf(clip.endMarker, clip.loopEnd, clip.len, 1.0)
    if (fileSec > 0) {
        val f = map.axisOf(fileSec)
        if (f.isFinite() && f < hi * 4) hi = max(hi, f)
    }
    return lo to hi * 1.02
}

/** Number of peak buckets to request for a view: ~1 per pixel of the visible part, rounded up to a power of two. */
fun peakBuckets(widthPx: Int, fullSpan: Double, visSpan: Double): Int {
    val want = (widthPx * (fullSpan / max(visSpan, 1e-9))).coerceIn(256.0, 8192.0)
    var n = 256
    while (n < want && n < 8192) n *= 2
    return n
}

private class WfDrag(val kind: String, val key: String, val origin: Double) // kind: loop | mark | warp

@Composable
fun AudioEditor(clip: ClipState, host: String, rt: State<RtFrame>, ops: AudioOps, modifier: Modifier = Modifier, peaksClient: PeaksClient = PeaksClient.shared) {
    val density = LocalDensity.current
    val tm = rememberTextMeasurer()
    val stripH = with(density) { 20.dp.toPx() }
    val hit = with(density) { 18.dp.toPx() }
    val lblSp = with(density) { 9.dp.toSp() }
    val fileSecClip = if (clip.sampleRate > 0) clip.sampleLen / clip.sampleRate else 0.0

    var peaks by remember(clip.filePath) { mutableStateOf<Peaks?>(null) }
    var err by remember(clip.filePath) { mutableStateOf<String?>(null) }
    val fileSec = peaks?.seconds?.takeIf { it > 0 } ?: fileSecClip
    val fallbackAxis = max(max(clip.endMarker, clip.loopEnd), 1.0)
    val map = remember(clip.warping, clip.warpMarkers, fileSec, fallbackAxis) { AudioMap(clip.warping, clip.warpMarkers, fileSec, fallbackAxis) }
    val extent = remember(clip.loopStart, clip.loopEnd, clip.startMarker, clip.endMarker, clip.len, map, fileSec) { audioExtent(clip, map, fileSec) }

    var v0 by remember(clip.loc, clip.filePath) { mutableDoubleStateOf(extent.first) }
    var v1 by remember(clip.loc, clip.filePath) { mutableDoubleStateOf(extent.second) }
    var inited by remember(clip.loc, clip.filePath) { mutableStateOf(false) }
    if (!inited && fileSec > 0) { v0 = extent.first; v1 = extent.second; inited = true }
    var drag by remember { mutableStateOf<WfDrag?>(null) }
    var dragVal by remember { mutableDoubleStateOf(0.0) }

    BoxWithConstraints(modifier.background(LiveColors.Bg).clipToBounds()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val fullSpan = max(extent.second - extent.first, 1e-6)
        val n = peakBuckets(widthPx, fullSpan, max(v1 - v0, 1e-6))
        LaunchedEffect(clip.filePath, n, host) {
            if (clip.filePath.isBlank()) { err = "No file"; return@LaunchedEffect }
            while (true) {
                when (val r = peaksClient.get(host, clip.filePath, n)) {
                    is PeaksResult.Ok -> { peaks = r.peaks; err = null; break }
                    is PeaksResult.Err -> { err = r.msg; delay(4000) }
                }
            }
        }

        val clipS = rememberUpdatedState(clip)
        val opsS = rememberUpdatedState(ops)
        val mapS = rememberUpdatedState(map)
        val color = liveColor(clip.color)

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun ax(x: Float) = v0 + (x / w) * (v1 - v0)
                    fun xs(a: Double) = ((a - v0) / (v1 - v0) * w).toFloat()
                    val c0 = clipS.value
                    val zone = if (down.position.y < stripH) 0 else if (down.position.y > h - stripH) 2 else 1
                    // pick a target
                    var target: WfDrag? = null
                    var bestD = hit
                    fun consider(kind: String, key: String, value: Double) {
                        val d = abs(xs(value) - down.position.x)
                        if (d <= bestD) { bestD = d; target = WfDrag(kind, key, value) }
                    }
                    when (zone) {
                        0 -> { consider("loop", "loop_start", c0.loopStart); consider("loop", "loop_end", c0.loopEnd) }
                        2 -> { consider("mark", "start_marker", c0.startMarker); consider("mark", "end_marker", c0.endMarker) }
                        else -> if (c0.warping) for (wm in c0.warpMarkers) consider("warp", "w", wm.beat)
                    }
                    val tgt = target
                    val slop = viewConfiguration.touchSlop
                    val outcome = withTimeoutOrNull<String>(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) return@withTimeoutOrNull "up"
                            if (pressed.size >= 2) return@withTimeoutOrNull "multi"
                            if ((pressed[0].position - down.position).getDistance() > slop) return@withTimeoutOrNull "move"
                        }
                        @Suppress("UNREACHABLE_CODE") "up"
                    }
                    val snapStep = if (c0.warping) gridStep(((w) / (v1 - v0)).toFloat(), 8f) / 2 else 0.0
                    fun snapV(a: Double) = if (snapStep > 0) Math.rint(a / snapStep) * snapStep else a

                    if (outcome == null) { // long press
                        if (c0.warping) {
                            if (tgt != null && tgt.kind == "warp") opsS.value.warpRemove(tgt.origin)
                            else if (tgt == null && zone == 1) opsS.value.warpAdd(snapV(ax(down.position.x)))
                        }
                        // swallow the rest of the gesture
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }
                    if (outcome == "up") return@awaitEachGesture

                    var lastPos = down.position
                    var transforming = outcome == "multi"
                    if (!transforming && tgt != null) {
                        drag = tgt; dragVal = tgt.origin
                    }
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (!transforming) { transforming = true; drag = null }
                                val a = pressed[0]; val b = pressed[1]
                                val pcx = (a.previousPosition.x + b.previousPosition.x) / 2
                                val ccx = (a.position.x + b.position.x) / 2
                                val ps = abs(a.previousPosition.x - b.previousPosition.x)
                                val cs = abs(a.position.x - b.position.x)
                                val z = if (ps > 24f) ps / cs else 1f // span ratio inverted: >1 means zoom in
                                val anchor = ax(pcx)
                                val newSpan = ((v1 - v0) * z).coerceIn(fullSpan / 400, fullSpan * 3)
                                v0 = anchor - (ccx / w) * newSpan
                                v1 = v0 + newSpan
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            if (transforming) { ev.changes.forEach { it.consume() }; continue }
                            val c = pressed[0]
                            lastPos = c.position
                            if (tgt != null) {
                                var nv = snapV(ax(c.position.x))
                                if (tgt.key == "loop_start") nv = min(nv, c0.loopEnd - 0.01)
                                if (tgt.key == "loop_end") nv = max(nv, c0.loopStart + 0.01)
                                if (tgt.kind != "warp") nv = max(0.0, nv).let { if (tgt.key == "loop_start" || tgt.key == "loop_end") nv else it }
                                dragVal = nv
                            } else {
                                val dx = c.positionChange().x
                                val span = v1 - v0
                                v0 -= dx / w * span; v1 -= dx / w * span
                            }
                            c.consume()
                        }
                    } finally {
                        val d = drag
                        if (d != null && !transforming) {
                            if (d.kind == "warp") { if (abs(dragVal - d.origin) > 1e-6) opsS.value.warpMove(d.origin, dragVal) }
                            else if (abs(dragVal - d.origin) > 1e-6) opsS.value.set(d.key, dragVal)
                        }
                        drag = null
                    }
                }
            },
        ) {
            drawWave(clip, peaks, map, v0, v1, fileSec, color, stripH, drag, dragVal, tm, lblSp)
            val pos = rt.value.clipPos
            if (pos != null) {
                val x = ((pos - v0) / (v1 - v0) * size.width).toFloat()
                if (x in 0f..size.width) drawLine(LiveColors.Play, Offset(x, 0f), Offset(x, size.height), 1.5f)
            }
        }
        if (peaks == null) {
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                val msg = if (err != null) "Helper not running" else "Loading waveform..."
                Box(Modifier.background(LiveColors.PanelHi).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    LiveText(msg, style = LiveType.Small, color = LiveColors.TextDim)
                }
            }
        }
    }
}

private fun DrawScope.drawWave(
    clip: ClipState, peaks: Peaks?, map: AudioMap, v0: Double, v1: Double, fileSec: Double, color: Color,
    stripH: Float, drag: WfDrag?, dragVal: Double, tm: TextMeasurer, lblSp: androidx.compose.ui.unit.TextUnit,
) {
    val w = size.width
    val h = size.height
    val span = max(v1 - v0, 1e-9)
    fun xs(a: Double) = ((a - v0) / span * w).toFloat()
    val top = stripH
    val bot = h - stripH
    val mid = (top + bot) / 2f
    val half = (bot - top) / 2f
    val lbl = TextStyle(fontSize = lblSp, color = LiveColors.TextDim)

    // strips
    drawRect(LiveColors.Panel, Offset(0f, 0f), Size(w, top))
    drawRect(LiveColors.Panel, Offset(0f, bot), Size(w, stripH))
    drawRect(Color(0xFF202020), Offset(0f, top), Size(w, bot - top))

    // grid + labels (beats when warped, seconds otherwise)
    val ppu = (w / span).toFloat()
    val step = if (clip.warping) gridStep(ppu, 12f).coerceAtLeast(1.0) else niceSecStep(ppu)
    var k = floor(v0 / step).toLong()
    val kEnd = ceil(v1 / step).toLong()
    var lastLabelX = -1000f
    while (k <= kEnd) {
        val t = k * step
        val x = xs(t)
        drawLine(Color(0xFF333333), Offset(x, top), Offset(x, bot), 1f)
        if (x - lastLabelX > 36f && x >= 0f) {
            val label = if (clip.warping) "${(t + 1).toLong()}" else fmtSec(t)
            drawText(tm, label, Offset(x + 2f, 3f), lbl)
            lastLabelX = x
        }
        k++
    }
    drawLine(Color(0xFF3A3A3A), Offset(0f, mid), Offset(w, mid), 1f)

    // peaks
    if (peaks != null && peaks.n > 0 && fileSec > 0) {
        val n = peaks.n
        val cols = w.toInt().coerceAtLeast(1)
        for (cx in 0 until cols) {
            val s0 = map.secOf(v0 + cx.toDouble() / cols * span)
            val s1 = map.secOf(v0 + (cx + 1.0) / cols * span)
            val lo = min(s0, s1)
            val hi = max(s0, s1)
            if (hi < 0 || lo > fileSec) continue
            val i0 = floor(lo / fileSec * n).toInt().coerceIn(0, n - 1)
            val i1 = ceil(hi / fileSec * n).toInt().coerceIn(i0 + 1, n)
            var mn = 0f
            var mx = 0f
            for (i in i0 until i1) {
                if (peaks.min[i] < mn) mn = peaks.min[i]
                if (peaks.max[i] > mx) mx = peaks.max[i]
            }
            val y0 = mid - mx.coerceIn(0f, 1f) * half
            val y1 = mid - mn.coerceIn(-1f, 0f) * half
            drawRect(color, Offset(cx.toFloat(), y0), Size(1f, max(1f, y1 - y0)))
        }
    }

    fun val_(key: String, base: Double) = if (drag != null && drag.key == key && drag.kind != "warp") dragVal else base
    val ls = val_("loop_start", clip.loopStart)
    val le = val_("loop_end", clip.loopEnd)
    val sm = val_("start_marker", clip.startMarker)
    val em = val_("end_marker", clip.endMarker)

    // outside-of-region dim (loop when looping else start..end)
    val (ra, rb) = if (clip.looping) ls to le else sm to em
    if (rb > ra) {
        val xa = xs(ra).coerceIn(0f, w)
        val xb = xs(rb).coerceIn(0f, w)
        drawRect(Color(0x88000000), Offset(0f, top), Size(xa, bot - top))
        drawRect(Color(0x88000000), Offset(xb, top), Size(w - xb, bot - top))
    }
    // start/end markers (bottom strip)
    for ((x, txt) in listOf(xs(sm) to "S", xs(em) to "E")) {
        drawLine(Color(0xFFDDDDDD), Offset(x, top), Offset(x, h), 1f)
        val flagW = 14f * (stripH / 40f) + 8f
        val left = if (txt == "S") x else x - flagW
        drawRect(Color(0xFFDDDDDD), Offset(left, bot), Size(flagW, stripH))
        drawText(tm, txt, Offset(left + flagW / 2 - 3f, bot + 3f), TextStyle(fontSize = lblSp, color = Color.Black))
    }
    // loop brace (top strip)
    run {
        val xa = xs(ls)
        val xb = xs(le)
        val flagW = 14f * (stripH / 40f) + 8f
        val c = if (clip.looping) LiveColors.Accent else LiveColors.TextDim
        drawLine(c, Offset(xa, 0f), Offset(xa, bot), 1.5f)
        drawLine(c, Offset(xb, 0f), Offset(xb, bot), 1.5f)
        drawRect(c, Offset(xa, 0f), Size(flagW, stripH))
        drawRect(c, Offset(xb - flagW, 0f), Size(flagW, stripH))
        drawLine(c, Offset(xa, 1f), Offset(xb, 1f), 2f)
        drawText(tm, "L", Offset(xa + flagW / 2 - 3f, 3f), TextStyle(fontSize = lblSp, color = Color.Black))
    }
    // warp markers
    if (clip.warping) {
        val wc = Color(0xFFE8C547)
        for (wm in clip.warpMarkers) {
            val b = if (drag != null && drag.kind == "warp" && abs(drag.origin - wm.beat) < 1e-9) dragVal else wm.beat
            val x = xs(b)
            if (x < -10f || x > w + 10f) continue
            drawLine(wc, Offset(x, top), Offset(x, bot), 1f)
            val p = Path().apply { moveTo(x - 5f, top); lineTo(x + 5f, top); lineTo(x, top + 8f); close() }
            drawPath(p, wc)
        }
    }
}

private fun niceSecStep(ppu: Float): Double {
    val steps = doubleArrayOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0)
    for (s in steps) if (s * ppu >= 40f) return s
    return 600.0
}

private fun fmtSec(t: Double): String {
    if (t < 0) return ""
    val m = (t / 60).toInt()
    val s = t - m * 60
    return if (m > 0) "$m:${"%02d".format(s.toInt())}" else if (s == floor(s)) "${s.toInt()}s" else "%.2fs".format(s)
}
