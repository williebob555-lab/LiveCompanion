package com.livecompanion.ui.clip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
    return (if (lo < 0) lo * 1.05 else lo) to hi * 1.06
}

/** Number of peak buckets to request for a view: ~1 per pixel of the visible part, rounded up to a power of two. */
fun peakBuckets(widthPx: Int, fullSpan: Double, visSpan: Double): Int {
    val want = (widthPx * (fullSpan / max(visSpan, 1e-9))).coerceIn(256.0, 8192.0)
    var n = 256
    while (n < want && n < 8192) n *= 2
    return n
}

/** (min,max) of [peaks] over file time [loSec,hiSec], amplitude-scaled by [gain] and clamped to -1..1. Null if outside the file. */
fun peakWindow(peaks: Peaks, fileSec: Double, loSec: Double, hiSec: Double, gain: Float): Pair<Float, Float>? {
    if (fileSec <= 0 || hiSec < 0 || loSec > fileSec) return null
    val n = peaks.n
    val i0 = floor(loSec / fileSec * n).toInt().coerceIn(0, n - 1)
    val i1 = ceil(hiSec / fileSec * n).toInt().coerceIn(i0 + 1, n)
    var mn = 0f
    var mx = 0f
    for (i in i0 until i1) {
        if (peaks.min[i] < mn) mn = peaks.min[i]
        if (peaks.max[i] > mx) mx = peaks.max[i]
    }
    return (mn * gain).coerceIn(-1f, 0f) to (mx * gain).coerceIn(0f, 1f)
}

@Composable
fun AudioEditor(
    clip: ClipState, host: String, rt: State<RtFrame>, ops: ClipEdits, tempo: Float, rec: Boolean,
    modifier: Modifier = Modifier, peaksClient: PeaksClient = PeaksClient.shared,
) {
    val density = LocalDensity.current
    val tm = rememberTextMeasurer()
    val lblSp = with(density) { 9.dp.toSp() }
    val hitPx = with(density) { 18.dp.toPx() }
    val bandPx = with(density) { 40.dp.toPx() }
    val fileSecClip = if (clip.sampleRate > 0) clip.sampleLen / clip.sampleRate else 0.0
    val num = clip.signature.getOrElse(0) { 4 }
    val den = clip.signature.getOrElse(1) { 4 }
    val seconds = !clip.warping
    val axis = remember(seconds, num, den) { AxisSpec(seconds, num, den) }

    var peaks by remember(clip.filePath) { mutableStateOf<Peaks?>(null) }
    var err by remember(clip.filePath) { mutableStateOf<String?>(null) }
    val fileSec = peaks?.seconds?.takeIf { it > 0 } ?: fileSecClip
    val fallbackAxis = max(max(clip.endMarker, clip.loopEnd), 1.0)
    val map = remember(clip.warping, clip.warpMarkers, fileSec, fallbackAxis) { AudioMap(clip.warping, clip.warpMarkers, fileSec, fallbackAxis) }
    val extent = remember(clip.loopStart, clip.loopEnd, clip.startMarker, clip.endMarker, clip.len, map, fileSec) { audioExtent(clip, map, fileSec) }
    val gain = remember(clip.gainStr, clip.gain) { gainLinear(clip.gainStr, clip.gain) }

    val v = remember(clip.loc, clip.filePath, seconds) { ClipView(if (seconds) 2f else 3f, if (seconds) 60000f else 1500f) }
    v.leftW = with(density) { 44.dp.toPx() }
    v.braceH = with(density) { 14.dp.toPx() }
    v.rulerH = with(density) { 17.dp.toPx() }
    var sub by remember { mutableStateOf(Subdiv()) }
    var snap by remember { mutableStateOf(true) }
    var warpAddMode by remember { mutableStateOf(false) }
    var selWarp by remember(clip.loc) { mutableStateOf<Double?>(null) }
    var dragWarp by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var sigDialog by remember { mutableStateOf(false) }
    val stepNow = if (seconds) 0.0 else gridStepFor(sub, v.ppu, axis.beatLen, axis.barLen)
    val head = rememberPlayhead(rt, clip, tempo, seconds, rec) { h ->
        if (rec && v.width > 0f && v.xOf(h) > v.width * 0.9f) v.x0 = h - (v.width * 0.4f - v.leftW) / v.ppu.toDouble()
    }

    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg)) {
        val twoRows = maxWidth < 520.dp
        val gridTools: @Composable () -> Unit = {
            GridControls(sub, { sub = it }, snap, { snap = it }, "$num/$den", { sigDialog = true }, stepLabel(stepNow), showSub = !seconds)
        }
        val waveTools: @Composable () -> Unit = {
            if (clip.warping) {
                ToolBtn("Warp +", { warpAddMode = !warpAddMode }, on = warpAddMode, minWidth = 48.dp)
                ToolBtn("Del warp", { selWarp?.let { ops.warpRemove(it); selWarp = null } }, enabled = selWarp != null, minWidth = 54.dp)
            }
            ToolCaption("GAIN " + clip.gainStr.ifBlank { "%.2f".format(clip.gain) })
        }
        Column(Modifier.fillMaxSize()) {
            if (twoRows) { ToolRow { gridTools() }; ToolRow { waveTools() } } else ToolRow { gridTools(); waveTools() }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { v.width = it.width.toFloat(); v.height = it.height.toFloat() }) {
                v.fitReq
                run {
                    val span = max(extent.second - extent.first, 1e-3)
                    v.setLimits(span, if (seconds) 0.0625 * 60.0 / max(tempo.toDouble(), 20.0) else 0.0625, density.density)
                }
                if (v.width > 0f && !v.inited && (fileSec > 0 || rec)) {
                    v.inited = true
                    v.fit(extent.first, extent.second)
                }
                val widthPx = v.width.toInt().coerceAtLeast(256)
                val fullSpan = max(extent.second - extent.first, 1e-6)
                val n = peakBuckets(widthPx, fullSpan, max((v.width - v.leftW) / v.ppu.toDouble(), 1e-6))
                LaunchedEffect(clip.filePath, n, host, rec) {
                    if (rec) return@LaunchedEffect
                    if (clip.filePath.isBlank()) { err = "No file"; return@LaunchedEffect }
                    while (true) {
                        when (val r = peaksClient.get(host, clip.filePath, n, clip.loc)) {
                            is PeaksResult.Ok -> { peaks = r.peaks; err = null; break }
                            is PeaksResult.Err -> { err = r.msg; delay(4000) }
                        }
                    }
                }
                val clipS = rememberUpdatedState(clip)
                val opsS = rememberUpdatedState(ops)
                val axisS = rememberUpdatedState(axis)
                val subS = rememberUpdatedState(sub)
                val snapS = rememberUpdatedState(snap)
                val addS = rememberUpdatedState(warpAddMode)
                val color = liveColor(clip.color)

                Canvas(
                    Modifier.fillMaxSize().systemGestureExclusion().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val c0 = clipS.value
                            val ax = axisS.value
                            val step = if (ax.seconds) 0.0 else snapStepFor(subS.value, snapS.value, v.ppu, ax.beatLen, ax.barLen)
                            if (inTopGestureZone(v, c0, down.position, hitPx, bandPx)) {
                                clipTopGesture(down, v, c0, opsS.value, step, hitPx, ax.seconds)
                                return@awaitEachGesture
                            }
                            // nearest warp marker
                            var target: Double? = null
                            if (c0.warping && down.position.x >= v.leftW) {
                                var bestD = max(hitPx, with(density) { 24.dp.toPx() })   // generous finger target for warp markers
                                for (wm in c0.warpMarkers) {
                                    val d = abs(v.xOf(wm.beat) - down.position.x)
                                    if (d <= bestD) { bestD = d; target = wm.beat }
                                }
                            }
                            val tgt = target
                            if (tgt != null) selWarp = tgt
                            var moved = false
                            var transforming = false
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) {
                                        if (!moved && !transforming) {
                                            if (tgt == null) {
                                                if (addS.value && c0.warping) opsS.value.warpAdd(snapValue(v.unitOf(down.position.x), step).coerceAtLeast(0.0))
                                                else selWarp = null
                                            }
                                        } else if (tgt != null && !transforming) {
                                            val d = dragWarp
                                            if (d != null && abs(d.second - d.first) > 1e-6) { opsS.value.warpMove(d.first, d.second); selWarp = d.second }
                                        }
                                        break
                                    }
                                    if (pressed.size >= 2) {
                                        transforming = true; dragWarp = null
                                        v.transformX(pressed[0], pressed[1])
                                        ev.changes.forEach { it.consume() }
                                        continue
                                    }
                                    if (transforming) { ev.changes.forEach { it.consume() }; continue }
                                    val p = pressed[0]
                                    if (!moved) {
                                        if ((p.position - down.position).getDistance() <= viewConfiguration.touchSlop) continue
                                        moved = true
                                    }
                                    if (tgt != null) {
                                        dragWarp = tgt to max(0.0, snapValue(v.unitOf(p.position.x), step))
                                    } else {
                                        v.x0 = max(v.minX, v.x0 - p.positionChange().x / v.ppu.toDouble())
                                    }
                                    p.consume()
                                }
                            } finally { dragWarp = null }
                        }
                    },
                ) {
                    drawWave(clip, peaks, map, v, axis, if (seconds) 0.0 else gridStepFor(sub, v.ppu, axis.beatLen, axis.barLen), fileSec, color, gain, selWarp, dragWarp, tm, lblSp, "", "")
                }
                ZoomPad(Modifier.align(Alignment.BottomEnd), { v.zoomX(it) }, null, { v.requestFit() })
                Canvas(Modifier.fillMaxSize()) {
                    val h = v.headFor(head()) ?: if (rec) clip.loopEnd else null
                    if (rec) {
                        drawRecRegion(v, 0.0, h ?: 0.0, v.topH, size.height)
                        drawText(tm, "RECORDING", Offset(v.leftW + 8f, v.topH + 6f), TextStyle(fontSize = lblSp, color = LiveColors.Rec))
                    }
                    if (h != null) drawPlayhead(v, h, if (rec) LiveColors.Rec else LiveColors.Playhead, v.braceH, size.height)
                }
                if (peaks == null && !rec) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        val msg = if (clip.filePath.isBlank()) "No audio file yet" else if (err != null) "Waveform unavailable" + (if (err!!.contains("format", true)) " (format)" else "") else "Loading waveform..."
                        Box(Modifier.background(LiveColors.PanelHi).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            LiveText(msg, style = LiveType.Small, color = LiveColors.TextDim)
                        }
                    }
                }
            }
        }
    }
    if (sigDialog) SigDialog(num, den, { sigDialog = false }) { n, d ->
        ops.set("sig_num", n.toDouble()); ops.set("sig_den", d.toDouble())
    }
}

private fun DrawScope.drawWave(
    clip: ClipState, peaks: Peaks?, map: AudioMap, v: ClipView, axis: AxisSpec, step: Double, fileSec: Double, color: Color, gain: Float,
    selWarp: Double?, dragWarp: Pair<Double, Double>?, tm: TextMeasurer, lblSp: TextUnit, sigLabel: String, gridLabel: String,
) {
    val w = size.width
    val h = size.height
    val top = v.topH
    val mid = (top + h) / 2f
    val half = (h - top) / 2f - 2f
    val lbl = TextStyle(fontSize = lblSp, color = LiveColors.TextDim)

    drawRect(Color(0xFF202020), Offset(v.leftW, top), Size(w - v.leftW, h - top))
    clipRect(v.leftW, top, w, h) {
        drawTimeGrid(v, axis, step, top, h)
        drawLine(Color(0xFF3A3A3A), Offset(v.leftW, mid), Offset(w, mid), 1f)
        // peaks, gain-scaled
        if (peaks != null && peaks.n > 0 && fileSec > 0) {
            val c0 = v.leftW.toInt()
            val cols = (w - v.leftW).toInt().coerceAtLeast(1)
            val span = cols / v.ppu.toDouble()
            val a0 = v.unitOf(v.leftW)
            val clipCol = Color(0xFFFF6A5F)
            for (cx in 0 until cols) {
                val s0 = map.secOf(a0 + cx.toDouble() / cols * span)
                val s1 = map.secOf(a0 + (cx + 1.0) / cols * span)
                val win = peakWindow(peaks, fileSec, min(s0, s1), max(s0, s1), gain) ?: continue
                val y0 = mid - win.second * half
                val y1 = mid - win.first * half
                val hot = win.second >= 0.999f || win.first <= -0.999f
                drawRect(if (hot) clipCol else color, Offset(c0 + cx.toFloat(), y0), Size(1f, max(1f, y1 - y0)))
            }
        }
        val ls = v.pvLs ?: clip.loopStart
        val le = v.pvLe ?: clip.loopEnd
        val sm = v.pvSm ?: clip.startMarker
        val em = v.pvEm ?: clip.endMarker
        val (ra, rb) = if (clip.looping) ls to le else sm to em
        drawOutsideDim(v, ra, rb, top, h)
        // start / end lines through the waveform
        for (u in listOf(sm, em)) {
            val x = v.xOf(u)
            drawLine(Color(0xAAE2E2E2), Offset(x, top), Offset(x, h), 1f)
        }
        // warp markers
        if (clip.warping) {
            val wc = Color(0xFFE8C547)
            for (wm in clip.warpMarkers) {
                val b = if (dragWarp != null && abs(dragWarp.first - wm.beat) < 1e-9) dragWarp.second else wm.beat
                val x = v.xOf(b)
                if (x < v.leftW - 10f || x > w + 10f) continue
                val sel = selWarp != null && (abs(selWarp - wm.beat) < 1e-9 || (dragWarp != null && abs(dragWarp.second - b) < 1e-9))
                val c = if (sel) Color.White else wc
                val d = density
                drawLine(c, Offset(x, top), Offset(x, h), (if (sel) 2f else 1.5f) * d)
                // visible grip handle: a pill with two grip lines at the top and a smaller one at the bottom (hit zone is wider than the pill)
                val gw = (if (sel) 16f else 13f) * d
                val gh = (if (sel) 22f else 18f) * d
                for (yy in listOf(top, h - gh * 0.7f)) {
                    val hh = if (yy == top) gh else gh * 0.7f
                    drawRoundRect(c, Offset(x - gw / 2f, yy), Size(gw, hh), androidx.compose.ui.geometry.CornerRadius(3f * d))
                    drawLine(Color.Black.copy(alpha = 0.6f), Offset(x - 2f * d, yy + hh * 0.25f), Offset(x - 2f * d, yy + hh * 0.75f), 1f * d)
                    drawLine(Color.Black.copy(alpha = 0.6f), Offset(x + 2f * d, yy + hh * 0.25f), Offset(x + 2f * d, yy + hh * 0.75f), 1f * d)
                }
            }
        }
    }
    drawClipTop(v, clip, axis, step, tm, lblSp, sigLabel, if (axis.seconds) "seconds" else gridLabel)
    // amplitude gutter
    drawRect(Color(0xFF1E1E1E), Offset(0f, top), Size(v.leftW, h - top))
    drawText(tm, "+1", Offset(3f, top + 2f), lbl)
    drawText(tm, "0", Offset(3f, mid - lblSp.value * 0.6f), lbl)
    drawText(tm, "-1", Offset(3f, h - lblSp.value * 1.6f), lbl)
    if (clip.gainStr.isNotBlank()) drawText(tm, clip.gainStr, Offset(3f, top + (h - top) / 4f), TextStyle(fontSize = lblSp, color = LiveColors.Text))
    drawLine(LiveColors.Line, Offset(v.leftW, top), Offset(v.leftW, h), 1f)
}
