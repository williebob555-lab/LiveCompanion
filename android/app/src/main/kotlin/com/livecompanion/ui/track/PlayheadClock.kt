package com.livecompanion.ui.track

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Smooth playhead: rt frames arrive at ~20 Hz, [tick] (called once per display frame) extrapolates between them with
 * tempo and the frame's arrival time. [shown] is only ever read from draw lambdas, so a 60 fps change redraws the
 * playhead layer without recomposing any screen. Scrubbing overrides the position locally and instantly.
 */
@Stable
class PlayheadClock {
    /** Beat the playhead is drawn at. */
    var shown by mutableDoubleStateOf(0.0)
        private set
    /** True while something needs per-frame ticks (playing or scrubbing). */
    var animating by mutableStateOf(false)
        private set
    var scrubbing = false
        private set

    private var pos = 0.0
    private var nanos = 0L
    private var playing = false
    private var tempo = 120.0
    private var loopOn = false
    private var loopStart = 0.0
    private var loopLen = 0.0
    private var holdUntil = 0L

    val isPlaying get() = playing

    fun setSong(tempo: Double, loopOn: Boolean, loopStart: Double, loopLen: Double) {
        this.tempo = tempo; this.loopOn = loopOn; this.loopStart = loopStart; this.loopLen = loopLen
    }

    /** New rt frame. Ignored (position-wise) briefly after a local scrub so the playhead does not jump back. */
    fun onFrame(rtPos: Double, playing: Boolean, now: Long) {
        this.playing = playing
        if (!scrubbing && now >= holdUntil) {
            pos = rtPos; nanos = now
        }
        animating = playing || scrubbing
        if (!scrubbing && (!playing || abs(pos - shown) > 1.5)) shown = pos
    }

    fun scrubTo(beat: Double, now: Long) {
        scrubbing = true
        pos = beat; nanos = now; shown = beat
        animating = true
    }

    fun endScrub(now: Long) {
        scrubbing = false
        nanos = now
        holdUntil = now + HOLD_NS
        animating = playing
        if (!playing) shown = pos
    }

    /** Jump without scrub semantics (typed locate / nudge). */
    fun jumpTo(beat: Double, now: Long) {
        pos = beat; nanos = now; shown = beat
        holdUntil = now + HOLD_NS
    }

    fun tick(now: Long) {
        if (scrubbing) { shown = pos; return }
        val dt = (now - nanos) / 1e9
        val target = extrapolate(pos, playing, tempo, dt, loopOn, loopStart, loopLen)
        shown = if (!playing || abs(target - shown) > 1.5) target else shown + (target - shown) * 0.4
    }

    companion object {
        const val HOLD_NS = 600_000_000L
    }
}
