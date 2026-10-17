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

    private var lastTick = 0L

    /**
     * Per display frame. While playing the playhead advances at exactly the tempo (constant speed, never backwards)
     * and drift against Live's reported position is corrected gently (~2 beats/s of correction at most), because rt
     * frames arrive in uneven ~100 ms bursts from Live's display timer - chasing each one made the playhead stutter.
     * Large errors (seek, loop wrap, tempo jump) snap.
     */
    fun tick(now: Long) {
        if (scrubbing) { shown = pos; lastTick = now; return }
        val dtFrame = if (lastTick == 0L) 0.0 else ((now - lastTick) / 1e9).coerceIn(0.0, 0.1)
        lastTick = now
        val target = extrapolate(pos, playing, tempo, (now - nanos) / 1e9, loopOn, loopStart, loopLen)
        if (!playing) { shown = target; return }
        val err = target - shown
        if (abs(err) > 0.5) { shown = target; return }
        val bps = tempo / 60.0
        val corr = (err * 2.0 * dtFrame).coerceIn(-bps * dtFrame * 0.5, bps * dtFrame * 0.5)
        var next = shown + bps * dtFrame + corr
        if (next < shown) next = shown                                   // never step backwards while playing
        if (loopOn && loopLen > 0.0 && shown < loopStart + loopLen && next >= loopStart + loopLen) next -= loopLen
        shown = next
    }

    companion object {
        const val HOLD_NS = 600_000_000L
    }
}
