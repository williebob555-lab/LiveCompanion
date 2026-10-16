package com.livecompanion.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.withTimeoutOrNull

/** One hold time for every hold-and-drag gesture (Arrange clips, Session tiles, device panels). */
const val HOLD_DRAG_MS = 300L

/**
 * Hold-then-drag: the pointer must stay within touch slop for [HOLD_DRAG_MS]; after that every move is reported
 * (even a short one) until release. Moving earlier cancels so parents can scroll. Same semantics as
 * `detectDragGesturesAfterLongPress`, with the shared hold time.
 */
suspend fun PointerInputScope.detectHoldDrag(
    onDragStart: () -> Unit,
    onDrag: (PointerInputChange, Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit = onDragEnd,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var held = true
        var up = false
        val r = withTimeoutOrNull(HOLD_DRAG_MS) {
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: run { held = false; return@withTimeoutOrNull }
                if (!c.pressed) { up = true; held = false; return@withTimeoutOrNull }
                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) { held = false; return@withTimeoutOrNull }
            }
        }
        // r == null -> timed out while still held within slop
        if (r != null || !held || up) return@awaitEachGesture
        onDragStart()
        var ended = false
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull { it.id == down.id }
            if (c == null || !c.pressed) { ended = c != null; break }
            val d = c.positionChange()
            if (d != Offset.Zero) { c.consume(); onDrag(c, d) }
        }
        if (ended) onDragEnd() else onDragCancel()
    }
}
