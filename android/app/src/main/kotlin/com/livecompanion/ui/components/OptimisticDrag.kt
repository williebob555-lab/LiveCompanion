package com.livecompanion.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Shared "instant feel" helper (docs/PREFERENCES.md principle 3). While a drag is active the UI renders ONLY the local
 * value ([display]); after release the local value is kept until the server value matches it ([same]) or [settleMs]
 * passed, so nothing ever snaps back while Live's ~100 ms tick catches up.
 */
@Stable
class Optimistic<T : Any>(
    private val settleMs: Long = SETTLE_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val same: (T, T) -> Boolean = { a, b -> a == b },
) {
    private var local by mutableStateOf<T?>(null)
    private var dragging by mutableStateOf(false)
    private var releasedAt = 0L

    /** Tick counter bumped on release so effects can wait for the settle timeout. */
    var releaseSerial by mutableStateOf(0)
        private set

    val active: Boolean get() = dragging

    fun begin(start: T) { local = start; dragging = true }
    fun move(v: T) { local = v; dragging = true }
    fun release(v: T? = null) {
        if (v != null) local = v
        dragging = false
        releasedAt = clock()
        releaseSerial++
    }

    /** What to draw: the local value while dragging / settling, otherwise the server's [server] value. */
    fun display(server: T): T {
        val l = local ?: return server
        if (dragging) return l
        if (same(server, l)) return server
        return if (clock() - releasedAt < settleMs) l else server
    }

    /** Drops an expired or matched local value (call from an effect, never from composition). */
    fun settle(server: T) {
        val l = local ?: return
        if (dragging) return
        if (same(server, l) || clock() - releasedAt >= settleMs) local = null
    }

    fun cancel() { local = null; dragging = false }

    companion object { const val SETTLE_MS = 600L }
}

/**
 * Latest-value-wins sender for continuous controls: at most one [send] per [intervalMs] (~30 Hz), intermediate values are
 * dropped, [flush]/[sendNow] always deliver the final one.
 */
class DragThrottle<T : Any>(
    private val intervalMs: Long = 33L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val send: (T) -> Unit,
) {
    private var last = Long.MIN_VALUE / 2
    private var pending: T? = null

    fun offer(v: T) {
        val n = clock()
        if (n - last >= intervalMs) { last = n; pending = null; send(v) } else pending = v
    }

    /** Sends the pending value when the interval has elapsed (call from a timer). */
    fun poll() {
        val p = pending ?: return
        val n = clock()
        if (n - last >= intervalMs) { last = n; pending = null; send(p) }
    }

    fun flush() { val p = pending ?: return; pending = null; last = clock(); send(p) }
    fun sendNow(v: T) { pending = null; last = clock(); send(v) }
}

@Composable
fun <T : Any> rememberOptimistic(same: (T, T) -> Boolean = { a, b -> a == b }): Optimistic<T> =
    remember { Optimistic(same = same) }

/** Clears the optimistic value once the server caught up or the settle window passed. */
@Composable
fun <T : Any> Optimistic<T>.SettleEffect(server: T) {
    LaunchedEffect(releaseSerial, server) {
        settle(server)
        if (!active) { delay(Optimistic.SETTLE_MS + 20); settle(server) }
    }
}
