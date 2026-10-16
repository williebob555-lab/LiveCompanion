package com.livecompanion.ui.track

import kotlin.math.floor
import kotlin.math.max

/**
 * The ONE bar.beat.sixteenth formatter / parser used everywhere (transport, info strip, arrange locate, clip
 * properties, clip ruler). All parts are 1-based and follow the signature: [unit] = quarter notes per signature
 * beat (4/den), [bpb] = quarter notes per bar. A "sixteenth" is always 0.25 quarter notes, so a /4 beat has 4
 * sixteenths, a /8 beat (0.5) has 2, and a /16 beat (0.25) has 1.
 */
object Bbt {
    const val SIXTEENTH = 0.25
    private const val EPS = 1e-6

    fun sixteenthsPerBeat(unit: Double): Int = max(1, floor(unit / SIXTEENTH + EPS).toInt())

    fun format(pos: Double, bpb: Double, unit: Double): String {
        val p = max(0.0, pos) + 1e-9
        val bar = floor(p / bpb + EPS * 0.001).toInt()
        val inBar = p - bar * bpb
        val nBeats = max(1, Math.rint(bpb / unit).toInt())
        val bt = floor(inBar / unit + EPS * 0.001).toInt().coerceIn(0, nBeats - 1)
        val sx = floor((inBar - bt * unit) / SIXTEENTH + EPS * 0.001).toInt().coerceIn(0, sixteenthsPerBeat(unit) - 1)
        return "${bar + 1}.${bt + 1}.${sx + 1}"
    }

    /** Result of [parse]: exactly one of [beats] / [error] is non-null. */
    data class Parsed(val beats: Double?, val error: String?)

    /**
     * "5", "5.2" or "5.2.3" -> quarter-note beats. Missing parts default to 1. Rejects (never wraps) a beat above
     * the signature's beat count, a sixteenth above the beat's sixteenths, zero/negative parts, and bars past
     * [maxBar] (1-based).
     */
    fun parse(text: String, bpb: Double, unit: Double, maxBar: Int = 100_000): Parsed {
        val parts = text.trim().split('.', ' ', ':', ',').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Parsed(null, "Enter bar.beat.sixteenth, e.g. 5.2.3")
        if (parts.size > 3) return Parsed(null, "Use at most three parts: bar.beat.sixteenth")
        val n = parts.map { it.toIntOrNull() ?: return Parsed(null, "Numbers only, e.g. 5.2.3") }
        if (n.any { it < 1 }) return Parsed(null, "Parts start at 1 (earliest is 1.1.1)")
        val nBeats = max(1, Math.rint(bpb / unit).toInt())
        val spb = sixteenthsPerBeat(unit)
        val bar = n[0]; val bt = n.getOrElse(1) { 1 }; val sx = n.getOrElse(2) { 1 }
        if (bar > maxBar) return Parsed(null, "Bar must be at most $maxBar")
        if (bt > nBeats) return Parsed(null, "Beat must be 1 to $nBeats in this signature")
        if (sx > spb) return Parsed(null, "Sixteenth must be 1 to $spb in this signature")
        return Parsed((bar - 1) * bpb + (bt - 1) * unit + (sx - 1) * SIXTEENTH, null)
    }
}
