package com.livecompanion.state

import kotlin.math.floor

/**
 * Meter envelope of the clip being recorded (server `rt.rec_env`): coarse peaks (0..1) on a song-time grid of [step]
 * beats, absolute index = floor(songBeat / step). Frames carry only the newest points; this merges them.
 * Used to draw the growing RED region with a waveform shape.
 */
class RecEnvBuf {
    private var key: String? = null
    private var first = 0L
    private var vals = FloatArray(256)
    private var count = 0
    @Volatile var step = 0.25
        private set

    @Synchronized fun accept(e: RecEnvFrame?) {
        if (e == null || e.v.isEmpty()) return
        if (e.k != key || e.f != first) {
            key = e.k; first = e.f; count = 0
        }
        step = if (e.step > 0.0) e.step else 0.25
        val off = (e.i - first).toInt()
        if (off < 0 || off > MAX) return
        val need = off + e.v.size
        if (need > vals.size) vals = vals.copyOf(maxOf(need, vals.size * 2).coerceAtMost(MAX + 256))
        if (off > count) java.util.Arrays.fill(vals, count, off, e.v[0] / 100f)
        for (k in e.v.indices) if (off + k < vals.size) vals[off + k] = e.v[k] / 100f
        count = maxOf(count, minOf(need, vals.size))
    }

    /** Peak (0..1) at song beat [beat], or -1 when that beat is outside the recorded envelope. */
    @Synchronized fun at(beat: Double): Float {
        if (key == null) return -1f
        val k = (floor(beat / step) - first).toInt()
        return if (k in 0 until count) vals[k] else -1f
    }

    /** Like [at] but linearly interpolated between grid points (no stair steps); -1 outside the recorded envelope. */
    @Synchronized fun smoothAt(beat: Double): Float {
        if (key == null || count == 0) return -1f
        val pos = beat / step - first
        val k0 = floor(pos).toInt()
        if (k0 < 0 || k0 >= count) return -1f
        val a = vals[k0]
        val b = vals[minOf(k0 + 1, count - 1)]
        return a + (b - a) * (pos - k0).toFloat()
    }

    @Synchronized fun clear() { key = null; count = 0 }

    companion object {
        const val MAX = 64000
        val shared = RecEnvBuf()
    }
}
