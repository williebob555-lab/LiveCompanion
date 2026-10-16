package com.livecompanion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Shared drag gesture for faders/knobs.
 *
 * - Drag along the axis changes the value; [onDelta] gets the change as a fraction of the full
 *   travel (positive = increase), already scaled by 0.1 in FINE mode.
 * - FINE mode: a second finger down, or holding still for the long-press timeout before dragging.
 * - Double-tap (no drag) calls [onReset].
 */
fun Modifier.liveDrag(
    vertical: Boolean,
    enabled: Boolean = true,
    travelPx: Float? = null,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    onFine: (Boolean) -> Unit = {},
    onReset: () -> Unit,
    onDelta: (Float) -> Unit,
): Modifier = composed {
    val deltaS = rememberUpdatedState(onDelta)
    val resetS = rememberUpdatedState(onReset)
    val startS = rememberUpdatedState(onStart)
    val endS = rememberUpdatedState(onEnd)
    val fineS = rememberUpdatedState(onFine)
    pointerInput(vertical, enabled, travelPx) {
        if (!enabled) return@pointerInput
        var lastTap = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val length = (travelPx ?: (if (vertical) size.height else size.width).toFloat()).coerceAtLeast(1f)
            var fine = false
            var dragging = false
            var total = 0f
            startS.value()
            try {
                while (true) {
                    val ev = if (!dragging && !fine) {
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent() }
                    } else awaitPointerEvent()
                    if (ev == null) {
                        fine = true; fineS.value(true); continue
                    }
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed == 0) {
                        if (!dragging && !fine) {
                            val now = ev.changes.first().uptimeMillis
                            if (now - lastTap < 300) { resetS.value(); lastTap = 0 } else lastTap = now
                        }
                        break
                    }
                    if (pressed > 1 && !fine) { fine = true; fineS.value(true) }
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                    val d = c.positionChange()
                    val along = if (vertical) -d.y else d.x
                    if (!dragging) {
                        total += abs(along)
                        if (total > viewConfiguration.touchSlop) dragging = true
                    }
                    if (dragging) {
                        deltaS.value(along / length * (if (fine) 0.1f else 1f))
                        c.consume()
                    }
                }
            } finally {
                endS.value()
                fineS.value(false)
            }
        }
    }
}

/**
 * Live-style fader: flat bar filled up to the value. Vertical by default (drag up = more), or
 * horizontal for mini faders in the track header. Double-tap resets to [defaultValue]; fine mode
 * with a second finger or by long-press before dragging. [bipolar] fills from the center (pan).
 */
@Composable
fun LiveFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    defaultValue: Float = valueRange.start,
    vertical: Boolean = true,
    bipolar: Boolean = false,
    label: String? = null,
    color: Color = LiveColors.Accent,
    enabled: Boolean = true,
    onFinished: () -> Unit = {},
) {
    var local by remember { mutableStateOf<Float?>(null) }
    var fine by remember { mutableStateOf(false) }
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val shown = (local ?: value).coerceIn(valueRange.start, valueRange.endInclusive)
    val frac = (shown - valueRange.start) / span
    val bg = LiveColors.PanelHi
    val fill = if (enabled) color else LiveColors.TextDim
    Box(
        modifier
            .clip(LiveShapes.Small)
            .liveDrag(
                vertical = vertical,
                enabled = enabled,
                onStart = { local = value },
                onEnd = { local = null; onFinished() },
                onFine = { fine = it },
                onReset = { local = null; onValueChange(defaultValue) },
            ) { d ->
                val cur = (local ?: value) + d * span
                val next = cur.coerceIn(valueRange.start, valueRange.endInclusive)
                local = next
                onValueChange(next)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(bg)
            val w = size.width
            val h = size.height
            val zero = if (bipolar) 0.5f else 0f
            val a = minOf(zero, frac)
            val b = maxOf(zero, frac)
            if (vertical) {
                drawRect(fill.copy(alpha = if (fine) 1f else 0.85f), Offset(0f, h * (1 - b)), Size(w, h * (b - a)))
                val hy = h * (1 - frac)
                drawRect(LiveColors.Text, Offset(0f, (hy - 1.5f).coerceIn(0f, h - 3f)), Size(w, 3f))
            } else {
                drawRect(fill.copy(alpha = if (fine) 1f else 0.85f), Offset(w * a, 0f), Size(w * (b - a), h))
                val hx = w * frac
                drawRect(LiveColors.Text, Offset((hx - 1.5f).coerceIn(0f, w - 3f), 0f), Size(3f, h))
            }
            if (bipolar) {
                if (vertical) drawLine(LiveColors.Line, Offset(0f, h / 2), Offset(w, h / 2), 1f)
                else drawLine(LiveColors.Line, Offset(w / 2, 0f), Offset(w / 2, h), 1f)
            }
            if (fine) drawRect(LiveColors.Text, style = Stroke(2f))
        }
        if (label != null) {
            LiveText(
                label,
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.55f), LiveShapes.Small).padding(horizontal = 3.dp),
                LiveType.Small,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Rotary control: drag vertically; double-tap resets; fine mode as for [LiveFader]. */
@Composable
fun LiveKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    defaultValue: Float = valueRange.start,
    label: String? = null,
    valueText: String? = null,
    knobSize: Dp = 44.dp,
    color: Color = LiveColors.Accent,
    enabled: Boolean = true,
    onFinished: () -> Unit = {},
) {
    var local by remember { mutableStateOf<Float?>(null) }
    var fine by remember { mutableStateOf(false) }
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val frac = (((local ?: value) - valueRange.start) / span).coerceIn(0f, 1f)
    Column(modifier.widthIn(min = knobSize), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(knobSize)
                .liveDrag(
                    vertical = true,
                    enabled = enabled,
                    travelPx = 160f * 2.5f,
                    onStart = { local = value },
                    onEnd = { local = null; onFinished() },
                    onFine = { fine = it },
                    onReset = { local = null; onValueChange(defaultValue) },
                ) { d ->
                    val next = ((local ?: value) + d * span).coerceIn(valueRange.start, valueRange.endInclusive)
                    local = next
                    onValueChange(next)
                },
        ) {
            val stroke = size.minDimension * 0.12f
            val inset = stroke / 2 + 1f
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(LiveColors.PanelHi, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(if (enabled) color else LiveColors.TextDim, 135f, 270f * frac, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            val ang = Math.toRadians((135f + 270f * frac).toDouble())
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 - stroke * 1.6f
            drawLine(if (fine) color else LiveColors.Text, c, Offset(c.x + (r * Math.cos(ang)).toFloat(), c.y + (r * Math.sin(ang)).toFloat()), 2.5f, StrokeCap.Round)
        }
        if (label != null) {
            // Large font scale: tighter tracking + a second line so "Parameter 1..4" stay distinguishable (P2-2-5).
            val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
            LiveText(
                label, Modifier.widthIn(max = knobSize + 10.dp),
                if (big) LiveType.Label.copy(letterSpacing = 0.sp) else LiveType.Label,
                maxLines = if (big) 2 else 1, textAlign = if (big) androidx.compose.ui.text.style.TextAlign.Center else null,
            )
        }
        if (valueText != null) LiveText(valueText, Modifier.widthIn(max = knobSize + 10.dp), LiveType.Small)
    }
}

/**
 * Stereo level meter. [left]/[right] are read inside the draw phase, so feeding them from the
 * realtime StateFlow (`{ rt.value.meters.focusL }`) redraws only this canvas, never recomposes.
 * Levels are 0..1. Zones: green, yellow above 0.75, red above 0.92.
 */
@Composable
fun MeterBar(
    left: () -> Float,
    right: () -> Float,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    Canvas(modifier) {
        drawRect(LiveColors.Bg)
        val gap = 1f
        val thick = (if (vertical) size.width else size.height)
        val bar = ((thick - gap) / 2f).coerceAtLeast(1f)
        val len = if (vertical) size.height else size.width
        fun channel(level: Float, idx: Int) {
            val l = level.coerceIn(0f, 1f)
            val off = idx * (bar + gap)
            fun seg(from: Float, to: Float, c: Color) {
                val a = from.coerceAtMost(l)
                val b = to.coerceAtMost(l)
                if (b <= a) return
                if (vertical) drawRect(c, Offset(off, len * (1 - b)), Size(bar, len * (b - a)))
                else drawRect(c, Offset(len * a, off), Size(len * (b - a), bar))
            }
            seg(0f, 0.75f, LiveColors.Play)
            seg(0.75f, 0.92f, LiveColors.MuteOn)
            seg(0.92f, 1f, LiveColors.Rec)
        }
        channel(left(), 0)
        channel(right(), 1)
    }
}
