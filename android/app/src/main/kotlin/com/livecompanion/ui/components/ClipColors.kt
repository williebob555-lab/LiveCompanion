package com.livecompanion.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Live sends colors as 0xRRGGBB ints. */
fun liveColor(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/** Text color for use ON [bg]: black if luminance > 0.5 else white (docs/UI.md). */
fun onColor(bg: Color): Color = if (bg.luminance() > 0.5f) Color.Black else Color.White

/** Convenience: contrast text color for a Live RGB int. */
fun onClipColor(rgb: Int): Color = onColor(liveColor(rgb))

/** Darkened/dimmed variant for stopped/inactive clip cells so playing ones stand out. */
fun dim(c: Color, amount: Float = 0.55f): Color =
    Color(c.red * amount + 0.16f * (1 - amount), c.green * amount + 0.16f * (1 - amount), c.blue * amount + 0.16f * (1 - amount))
