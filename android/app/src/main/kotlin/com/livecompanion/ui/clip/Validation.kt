package com.livecompanion.ui.clip

import com.livecompanion.state.ClipState

/** Largest clip position (beats or seconds) we will send; far beyond any real clip, keeps typos out. */
const val MAX_POS = 1.0e6

/** Smallest distance kept between start<end style pairs when clamping drags. */
const val MIN_GAP = 0.05

/** Parses a typed number (comma or dot decimals). Null for blank, NaN/Infinity or junk. */
fun parseNumber(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/** Inline message for a typed number, or null when [text] is a number in [min]..[max] (whole numbers only if [int]). */
fun numberError(text: String, min: Double, max: Double, int: Boolean = false, unit: String = ""): String? {
    val v = parseNumber(text) ?: return if (text.isBlank()) "Enter a number" else "Not a number"
    if (int && v != Math.rint(v)) return "Whole numbers only"
    val u = if (unit.isEmpty()) "" else " $unit"
    if (v < min || v > max) return "Must be between ${fmtLimit(min)} and ${fmtLimit(max)}$u"
    return null
}

private fun fmtLimit(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

/** start_marker/loop_start are the "low" side of their pair. */
private fun lowKey(key: String) = key == "start_marker" || key == "loop_start"

private fun partner(key: String, c: ClipState): Pair<Double, String> = when (key) {
    "start_marker" -> c.endMarker to "End"
    "end_marker" -> c.startMarker to "Start"
    "loop_start" -> c.loopEnd to "Loop end"
    else -> c.loopStart to "Loop start"
}

private fun title(key: String) = when (key) {
    "start_marker" -> "Start"
    "end_marker" -> "End"
    "loop_start" -> "Loop start"
    else -> "Loop end"
}

/** Error text if setting marker [key] to [v] would be invalid (negative, absurd, or crossing its partner); else null. */
fun markerError(key: String, v: Double, c: ClipState): String? {
    if (!v.isFinite()) return "Not a number"
    if (v < 0) return "Must not be negative"
    if (v > MAX_POS) return "Too large"
    val (other, name) = partner(key, c)
    if (lowKey(key) && v >= other) return "${title(key)} must be before $name"
    if (!lowKey(key) && v <= other) return "${title(key)} must be after $name"
    return null
}

/** Clamps a dragged marker value so it stays valid; null when no valid value exists (e.g. zero-length clip). */
fun clampMarker(key: String, v: Double, c: ClipState): Double? {
    val (other, _) = partner(key, c)
    val r = if (lowKey(key)) v.coerceIn(0.0, maxOf(0.0, other - MIN_GAP)) else v.coerceIn(other + MIN_GAP, MAX_POS)
    return r.takeIf { it.isFinite() && markerError(key, it, c) == null }
}
