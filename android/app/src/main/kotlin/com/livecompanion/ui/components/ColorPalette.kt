package com.livecompanion.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes

/** Approximation of Live's 70 clip/track colors, 14 per row (RGB ints). */
val LivePalette: List<Int> = listOf(
    0xFF94A6, 0xFFA529, 0xCC9927, 0xF7F47C, 0xBFFB00, 0x1AFF2F, 0x25FFA8, 0x5CFFE8, 0x8BC5FF, 0x5480E4, 0x92A7FF, 0xD86CE4, 0xE553A0, 0xFFFFFF,
    0xFF3636, 0xF66C03, 0x99724B, 0xFFF034, 0x87FF67, 0x3DC300, 0x00BFAF, 0x19E9FF, 0x10A4EE, 0x007DC0, 0x886CE4, 0xB677C6, 0xFF39D4, 0xD0D0D0,
    0xE2675A, 0xFFA374, 0xD3AD71, 0xEDFFAE, 0xD2E498, 0xBAD074, 0x9BC48D, 0xD4FDE1, 0xCDF1F8, 0xB9C1E3, 0xCDBBE4, 0xAE98E5, 0xE5DCE1, 0xA9A9A9,
    0xC6928B, 0xB78256, 0x99836A, 0xBFBA69, 0xA6BE00, 0x7DB04D, 0x88C2BA, 0x9BB3C4, 0x85A5C2, 0x8393CA, 0xA595B5, 0xBF9FBE, 0xBC7196, 0x7B7B7B,
    0xAF3333, 0xA95131, 0x724F41, 0xDBC300, 0x85961F, 0x539F31, 0x0A9C8E, 0x236384, 0x1A2F96, 0x2F52A2, 0x624BAD, 0xA34BAD, 0xCC2E6E, 0x3C3C3C,
)

/** Plain dialog with the 70-color grid; [current] is outlined. */
@Composable
fun ColorPaletteDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    LiveDialog("Color", onDismiss) {
        LiveScrollColumn(Modifier.weight(1f, fill = false)) {
            LivePalette.chunked(14).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { c ->
                        val sel = (c and 0xFFFFFF) == (current and 0xFFFFFF)
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .background(liveColor(c), LiveShapes.Small)
                                .then(if (sel) Modifier.border(2.dp, LiveColors.Accent, LiveShapes.Small) else Modifier)
                                .clickable { onPick(c); onDismiss() },
                        )
                    }
                }
            }
        }
        LiveDialogButtons { DenseButton("Cancel", onDismiss) }
    }
}
