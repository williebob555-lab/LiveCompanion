package com.livecompanion.ui.clip

import com.livecompanion.state.Note
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Pure geometry/snapping helpers for the piano roll (unit tested). Times are beats (quarter notes). */

data class GridOpt(val label: String, val beats: Double)

/** `beats <= 0` means "one bar". */
val GRID_OPTS = listOf(
    GridOpt("1/32", 0.125), GridOpt("1/16", 0.25), GridOpt("1/16T", 1.0 / 6), GridOpt("1/8", 0.5),
    GridOpt("1/8T", 1.0 / 3), GridOpt("1/4", 1.0), GridOpt("1/2", 2.0), GridOpt("Bar", 0.0),
)

fun GridOpt.len(barBeats: Double): Double = if (beats > 0) beats else barBeats

/** Length of one bar in beats for a time signature. */
fun barBeats(num: Int, den: Int): Double = num.coerceAtLeast(1) * 4.0 / den.coerceAtLeast(1)

fun snapNearest(t: Double, grid: Double, on: Boolean): Double =
    if (!on || grid <= 0) t else (t / grid).roundToLong() * grid

fun snapFloor(t: Double, grid: Double, on: Boolean): Double =
    if (!on || grid <= 0) t else floor(t / grid + 1e-9) * grid

/** Smallest power-of-two beat fraction/multiple whose on-screen spacing is at least [minPx]. */
fun gridStep(pxPerBeat: Float, minPx: Float = 9f): Double {
    var s = 1.0 / 16
    while (s * pxPerBeat < minPx && s < 4096) s *= 2
    return s
}

/** Bar-label stride (in bars) so labels are at least [minPx] apart. */
fun barLabelStride(pxPerBeat: Float, barBeats: Double, minPx: Float = 40f): Int {
    var k = 1
    while (k * barBeats * pxPerBeat < minPx && k < 1 shl 20) k *= 2
    return k
}

/** Index of the topmost note under (beat, pitch), or -1. [minBeats] widens tiny notes to a touchable size. */
fun hitNote(notes: List<Note>, beat: Double, pitch: Int, minBeats: Double): Int {
    for (i in notes.indices.reversed()) {
        val n = notes[i]
        if (n.p == pitch && beat >= n.t - 1e-9 && beat <= n.t + max(n.d, minBeats)) return i
    }
    return -1
}

/** True if [beat] is within the right-edge resize zone of [n] (never more than the right half). */
fun inResizeZone(n: Note, beat: Double, edgeBeats: Double): Boolean {
    val zone = min(edgeBeats, max(n.d, edgeBeats) * 0.5)
    return beat >= n.t + max(n.d, edgeBeats) - zone
}

/** Ids of notes overlapping the time range [t0,t1] with pitch in [p0,p1] (either order). */
fun notesInBox(notes: List<Note>, t0: Double, t1: Double, p0: Int, p1: Int): Set<Int> {
    val a = min(t0, t1); val b = max(t0, t1)
    val lo = min(p0, p1); val hi = max(p0, p1)
    val out = HashSet<Int>()
    for (n in notes) if (n.p in lo..hi && n.t <= b && n.t + n.d >= a) out.add(n.id)
    return out
}

/** Time delta for moving a selection; the anchor note's start snaps, selection never goes below 0. */
fun moveDelta(anchorT: Double, rawDelta: Double, minSelT: Double, grid: Double, snap: Boolean): Double {
    var d = if (snap) snapNearest(anchorT + rawDelta, grid, true) - anchorT else rawDelta
    if (minSelT + d < 0) d = -minSelT
    return d
}

/** Duration delta for resizing: the anchor note's END snaps; no note shorter than [minLen]. */
fun resizeDelta(anchorEnd: Double, rawDelta: Double, minSelDur: Double, grid: Double, snap: Boolean, minLen: Double): Double {
    var d = if (snap) snapNearest(anchorEnd + rawDelta, grid, true) - anchorEnd else rawDelta
    val lim = minLen - minSelDur
    if (d < lim) d = lim
    return d
}

fun clampPitchDelta(d: Int, minP: Int, maxP: Int): Int = d.coerceIn(-minP, 127 - maxP)

fun velFromFrac(frac: Float): Int = (1 + (frac.coerceIn(0f, 1f)) * 126f).toInt().coerceIn(1, 127)

private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
fun pitchName(p: Int): String = NAMES[((p % 12) + 12) % 12] + (p / 12 - 2) // Live: C3 = 60
fun isBlackKey(p: Int): Boolean = when (((p % 12) + 12) % 12) { 1, 3, 6, 8, 10 -> true; else -> false }

/** "bar.beat.sixteenth" (1-based) like Live's clip view. */
fun formatBbt(pos: Double, barBeats: Double): String {
    val bb = if (barBeats > 0) barBeats else 4.0
    val bar = floor(pos / bb + 1e-9)
    val rem = pos - bar * bb
    val beat = floor(rem + 1e-9)
    val sub = floor((rem - beat) * 4 + 1e-9)
    return "${bar.toInt() + 1}.${beat.toInt() + 1}.${sub.toInt() + 1}"
}

/** Accepts "b.b.s" (bar.beat.sixteenth, 1-based) or a decimal beat count. Null if invalid. */
fun parseBbt(text: String, barBeats: Double): Double? {
    val s = text.trim().replace(',', '.')
    if (s.isEmpty()) return null
    val parts = s.split('.')
    if (parts.size == 3) {
        val b = parts[0].toIntOrNull() ?: return null
        val q = parts[1].toIntOrNull() ?: return null
        val sx = parts[2].toIntOrNull() ?: return null
        return (b - 1) * barBeats + (q - 1) + (sx - 1) / 4.0
    }
    return s.toDoubleOrNull()?.takeIf { abs(it) < 1e7 }
}
