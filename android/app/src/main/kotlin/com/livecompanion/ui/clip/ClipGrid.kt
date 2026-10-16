package com.livecompanion.ui.clip

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Grid / ruler math for the clip editor (pure, unit tested). Time unit = beats (quarter notes);
 * for un-warped audio the editor works in seconds and uses [secondsLines] instead.
 */

/** Grid subdivision choice: adaptive (follows zoom, like Live's narrow/wide) or fixed 1/[denom] note, optional triplet. */
data class Subdiv(val adaptive: Boolean = true, val denom: Int = 16, val triplet: Boolean = false) {
    /** Fixed step in beats (quarter notes). */
    val fixedBeats: Double get() = (4.0 / denom) * (if (triplet) 2.0 / 3.0 else 1.0)

    fun label(adaptiveStep: Double? = null): String =
        if (adaptive) (if (adaptiveStep != null) "Auto " + stepLabel(adaptiveStep) else "Auto") else "1/$denom" + (if (triplet) "T" else "")

    companion object {
        val DENOMS = listOf(1, 2, 4, 8, 16, 32)
    }
}

/** Name of a step given in beats: 0.25 -> "1/16", 1/6 -> "1/16T", 8.0 -> "2 bars"... (bars assumed 4 beats here). */
fun stepLabel(beats: Double): String {
    if (beats <= 0) return "-"
    val whole = beats / 4.0 // in whole notes
    for (d in Subdiv.DENOMS) {
        if (abs(whole - 1.0 / d) < 1e-6) return "1/$d"
        if (abs(whole - (2.0 / 3.0) / d) < 1e-6) return "1/${d}T"
    }
    if (beats >= 4 && abs(beats % 4) < 1e-6) { val b = (beats / 4).toInt(); return if (b == 1) "1 bar" else "$b bars" }
    return "%.3g".format(beats)
}

enum class GridLevel { Bar, Beat, Sub }

class GridLine(val t: Double, val level: GridLevel, val bar: Long, val beatInBar: Int, val subIdx: Int)

/** Beat length in quarter notes for a signature denominator (6/8 -> 0.5). */
fun beatLenOf(den: Int): Double = 4.0 / den.coerceAtLeast(1)

/**
 * The step (in beats) the adaptive grid uses at [ppb] pixels per beat: power-of-two subdivisions below the
 * signature beat, then beat, bar and power-of-two multiples of bars when zoomed far out. At least [minPx] apart.
 */
fun adaptiveStep(ppb: Float, beatLen: Double, barLen: Double, minPx: Float = 12f): Double {
    var s = 0.125 // 1/32 note
    while (s < beatLen - 1e-9) { if (s * ppb >= minPx) return s; s *= 2 }
    if (beatLen * ppb >= minPx) return beatLen
    if (barLen > beatLen + 1e-9) { if (barLen * ppb >= minPx) return barLen }
    var k = barLen.coerceAtLeast(beatLen) * 2
    while (k * ppb < minPx && k < 1e7) k *= 2
    return k
}

/** The step the grid draws / snaps with for this subdivision choice. */
fun gridStepFor(sub: Subdiv, ppb: Float, beatLen: Double, barLen: Double): Double =
    if (sub.adaptive) adaptiveStep(ppb, beatLen, barLen) else sub.fixedBeats

/**
 * Grid lines in [from,to] (beats) for [step]. Level: Bar on bar starts, Beat on signature beats, Sub otherwise.
 * Works for any step (triplets too): lines sit at multiples of [step]; Bar/Beat levels are detected by alignment.
 */
fun gridLines(from: Double, to: Double, step: Double, beatLen: Double, barLen: Double, out: MutableList<GridLine> = ArrayList()): MutableList<GridLine> {
    if (step <= 0 || to < from || (to - from) / step > 20000) return out
    var k = max(0L, floor(from / step - 1e-9).toLong())
    val kEnd = floor(to / step + 1e-9).toLong()
    while (k <= kEnd) {
        val t = k * step
        val barIdx = floor(t / barLen + 1e-6).toLong()
        val inBar = t - barIdx * barLen
        val level = when {
            inBar < 1e-6 || barLen - inBar < 1e-6 -> GridLevel.Bar
            abs(inBar / beatLen - Math.rint(inBar / beatLen)) < 1e-6 -> GridLevel.Beat
            else -> GridLevel.Sub
        }
        val bar = if (barLen - inBar < 1e-6) barIdx + 1 else barIdx
        val rel = if (barLen - inBar < 1e-6) 0.0 else inBar
        val beat = floor(rel / beatLen + 1e-6).toInt()
        val sub = floor((rel - beat * beatLen) / step + 1e-6).toInt()
        out.add(GridLine(t, level, bar, beat, sub))
        k++
    }
    return out
}

/** Bar-label stride (in bars) so numbers are at least [minPx] apart. */
fun labelStride(ppb: Float, barLen: Double, minPx: Float = 34f): Int {
    var k = 1
    while (k * barLen * ppb < minPx && k < 1 shl 20) k *= 2
    return k
}

/** "Live-like" snapping step for editing: the grid step when snap is on, else 0 (free). */
fun snapStepFor(sub: Subdiv, snap: Boolean, ppb: Float, beatLen: Double, barLen: Double): Double =
    if (!snap) 0.0 else gridStepFor(sub, ppb, beatLen, barLen)

fun snapValue(t: Double, step: Double): Double = if (step <= 0) t else (t / step).roundToLong() * step

/** Nice second step (and its minor subdivision count) for the seconds ruler. */
fun niceSecStep(ppu: Float, minPx: Float = 48f): Double {
    val steps = doubleArrayOf(0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0)
    for (s in steps) if (s * ppu >= minPx) return s
    return 1200.0
}

/** Lines for the seconds axis: Bar level = major (labelled), Sub = minor (5 per major). */
fun secondsLines(from: Double, to: Double, ppu: Float): List<GridLine> {
    val major = niceSecStep(ppu)
    val minor = major / 5.0
    val out = ArrayList<GridLine>()
    if ((to - from) / minor > 20000) return out
    var k = max(0L, floor(from / minor).toLong())
    val kEnd = ceil(to / minor).toLong()
    while (k <= kEnd) {
        val isMajor = k % 5L == 0L
        if (isMajor || minor * ppu >= 6f) out.add(GridLine(k * minor, if (isMajor) GridLevel.Bar else GridLevel.Sub, k / 5, 0, (k % 5).toInt()))
        k++
    }
    return out
}

fun fmtSeconds(t: Double): String {
    if (t < 0) return ""
    val m = (t / 60).toInt()
    val s = t - m * 60
    return when {
        m > 0 -> "$m:${"%02d".format(floor(s).toInt())}"
        abs(s - Math.rint(s)) < 1e-6 -> "${s.toInt()}s"
        s < 1 -> "%.0fms".format(s * 1000)
        else -> "%.1fs".format(s)
    }
}

/** Ruler label for a grid line: bar number, bar.beat, or bar.beat.sub, picking the shortest that fits [spacingPx]. */
fun rulerLabel(l: GridLine, stridePx: Float): String? = when (l.level) {
    GridLevel.Bar -> "${l.bar + 1}"
    GridLevel.Beat -> if (stridePx >= 30f) "${l.bar + 1}.${l.beatInBar + 1}" else null
    GridLevel.Sub -> if (stridePx >= 34f) "${l.bar + 1}.${l.beatInBar + 1}.${l.subIdx + 1}" else null
}

/** True when bar [bar] (0-based) should get the darker band (Live alternates bar shading). */
fun isDarkBar(bar: Long): Boolean = bar % 2L == 0L

/** Pitch classes in scale -> membership bitmask helper. */
fun inScale(p: Int, root: Int, intervals: List<Int>): Boolean {
    if (intervals.isEmpty()) return false
    val pc = (((p - root) % 12) + 12) % 12
    return pc in intervals
}

/** Clip signature as validated (num 1..99, den one of 1,2,4,8,16). */
fun validSignature(num: Int, den: Int): Boolean = num in 1..99 && den in listOf(1, 2, 4, 8, 16)

/** Linear gain factor from Live's dB readout ("-6.0 dB"), falling back to raw (0.4 == 0 dB). */
fun gainLinear(gainStr: String, raw: Float): Float {
    parseDb(gainStr)?.let { return Math.pow(10.0, it / 20.0).toFloat() }
    return if (raw > 0f) raw / 0.4f else 1f
}

/** Visible-range helper used by editors: clamp helper. */
fun clampD(v: Double, lo: Double, hi: Double): Double = min(max(v, lo), hi)
