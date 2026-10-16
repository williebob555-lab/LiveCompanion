package com.livecompanion.ui.track

import com.livecompanion.net.Peaks
import com.livecompanion.state.ArrClip
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Snap choices of the track view (visible selector). Values are quarter-note beats. */
enum class Snap(val label: String) {
    Off("Off"), S16("1/16"), S8("1/8"), Beat("Beat"), Bar("Bar");

    /** Grid size in beats (0 = free). [unit] = length of one signature beat, [bpb] = beats per bar. */
    fun beats(unit: Double, bpb: Double): Double = when (this) {
        Off -> 0.0; S16 -> 0.25; S8 -> 0.5; Beat -> unit; Bar -> bpb
    }
}

/** Length of one signature beat in quarter notes (6/8 -> 0.5, 4/4 -> 1). */
fun beatUnit(den: Int): Double = 4.0 / den.coerceAtLeast(1)

fun beatsPerBar(num: Int, den: Int): Double = num.coerceAtLeast(1) * beatUnit(den)

fun snapTo(beat: Double, grid: Double): Double = if (grid <= 0.0) beat else Math.rint(beat / grid) * grid

/** Grid used for nudge / loop edits when snap is Off. */
fun effectiveGrid(grid: Double): Double = if (grid <= 0.0) 0.25 else grid

/** "bar.beat.sixteenth" (all 1-based), following the song signature. See [Bbt]. */
fun posString(beat: Double, bpb: Double, unit: Double): String = Bbt.format(beat, bpb, unit)

/** Parse "5", "5.2" or "5.2.3" (bar.beat.sixteenth, 1-based) to beats; null when malformed or out of range. */
fun parsePos(text: String, bpb: Double, unit: Double): Double? = Bbt.parse(text, bpb, unit).beats

/**
 * Playhead extrapolation between ~20 Hz rt frames: [pos] + elapsed * tempo / 60, wrapped inside an enabled loop
 * when the base position was before the loop end. [maxDt] caps the extrapolation when frames stall.
 */
fun extrapolate(pos: Double, playing: Boolean, tempo: Double, dtSec: Double, loopOn: Boolean, loopStart: Double, loopLen: Double, maxDt: Double = 0.35): Double {
    if (!playing) return pos
    val d = min(max(dtSec, 0.0), maxDt)
    var p = pos + d * tempo / 60.0
    if (loopOn && loopLen > 0.0) {
        val end = loopStart + loopLen
        if (pos < end && p >= end) p = loopStart + (p - loopStart) % loopLen
    }
    return p
}

/**
 * Where arrangement-relative offset [rel] (beats from the clip's left edge) lands in the clip's content, taking
 * start marker and loop into account (arrangement clips play from start_marker to loop_end, then wrap to loop_start).
 */
fun contentBeat(c: ArrClip, rel: Double): Double {
    val p = c.startMarker + rel
    if (c.looping && c.loopEnd > c.loopStart + 1e-6 && p >= c.loopEnd) {
        val span = c.loopEnd - c.loopStart
        return c.loopStart + (p - c.loopStart) % span
    }
    return p
}

/** Fraction (0..1) of the audio file under content beat [cb]; approximation from first/last warp markers or tempo. */
fun fileFraction(c: ArrClip, cb: Double, tempo: Double, fileSec: Double): Double {
    if (fileSec <= 0.0) return 0.0
    return if (c.warping && c.warpLast > c.warpFirst + 1e-6) {
        (cb - c.warpFirst) / (c.warpLast - c.warpFirst)
    } else {
        cb * 60.0 / max(tempo, 1.0) / fileSec
    }
}

/** Min/max peak over the [f0,f1] fraction of the file; null when outside the file. */
fun peakSpan(p: Peaks, f0: Double, f1: Double): Pair<Float, Float>? {
    if (p.n <= 0) return null
    val a = floor(min(f0, f1) * p.n).toInt()
    val b = floor(max(f0, f1) * p.n).toInt()
    if (b < 0 || a >= p.n) return null
    var lo = 0f
    var hi = 0f
    val i0 = a.coerceIn(0, p.n - 1)
    val i1 = min(b, p.n - 1).coerceAtMost(i0 + 24)
    for (i in i0..i1) {
        if (p.min[i] < lo) lo = p.min[i]
        if (p.max[i] > hi) hi = p.max[i]
    }
    return lo to hi
}

/** Which arrangement edge a press at [x] grabs on a clip spanning [sx]..[ex] (px): -1 left, 1 right, 0 body. */
fun edgeZone(x: Float, sx: Float, ex: Float, edgePx: Float): Int {
    val w = ex - sx
    val z = min(edgePx, w * 0.3f)
    return when {
        z < 4f -> 0
        x - sx <= z -> -1
        ex - x <= z -> 1
        else -> 0
    }
}

/** Beat time for typed locate rounding to the sample-exact float used by Live. */
fun roundBeat(b: Double): Double = (b * 1_000_000.0).roundToLong() / 1_000_000.0

/**
 * Calls [out] for every mini-preview note placement inside arrangement clip [c] of length [clipLen]:
 * (beats from the clip's left edge, duration, pitch). Handles the start marker and loop tiling.
 */
fun placeNotes(c: ArrClip, clipLen: Double, out: (rel: Double, dur: Double, pitch: Int) -> Unit) {
    val sm = c.startMarker
    val loopOn = c.looping && c.loopEnd > c.loopStart + 1e-6
    val firstEnd = if (loopOn) c.loopEnd else Double.MAX_VALUE
    for (n in c.pn) {
        if (n.size < 3) continue
        val t = n[0]; val d = n[1]; val p = n[2].toInt()
        if (t + d <= sm || t >= firstEnd) continue
        val rel = max(t, sm) - sm
        if (rel >= clipLen) continue
        out(rel, min(d, clipLen - rel), p)
    }
    if (loopOn) {
        val span = c.loopEnd - c.loopStart
        val off0 = c.loopEnd - sm
        var k = 0
        while (off0 + k * span < clipLen && k < 256) {
            for (n in c.pn) {
                if (n.size < 3) continue
                val t = n[0]
                if (t < c.loopStart || t >= c.loopEnd) continue
                val rel = off0 + k * span + (t - c.loopStart)
                if (rel < clipLen) out(rel, min(n[1], clipLen - rel), n[2].toInt())
            }
            k++
        }
    }
}
