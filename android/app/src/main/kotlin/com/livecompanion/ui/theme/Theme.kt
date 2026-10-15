package com.livecompanion.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens from docs/UI.md (Ableton Live 12 dark look). Dark only. */
object LiveColors {
    val Bg = Color(0xFF1A1A1A)
    val Panel = Color(0xFF2A2A2A)
    val PanelHi = Color(0xFF363636)
    val Line = Color(0xFF444444)
    val Text = Color(0xFFC8C8C8)
    val TextDim = Color(0xFF8A8A8A)
    val Accent = Color(0xFFFFA22B)
    val Play = Color(0xFF5FD35F)
    val Rec = Color(0xFFFF3B30)
    val Solo = Color(0xFF3D9FE0)
    val MuteOn = Color(0xFFE8C547)
}

object LiveShapes {
    val Small = RoundedCornerShape(2.dp)
}

/** Small type scale: 11-13sp body, 10sp caps labels. Scales with the system font scale. */
object LiveType {
    private val base = TextStyle(fontFamily = FontFamily.SansSerif, color = LiveColors.Text)
    val Body = base.copy(fontSize = 12.sp, lineHeight = 15.sp)
    val Small = base.copy(fontSize = 11.sp, lineHeight = 14.sp)
    val Title = base.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val Label = base.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.6.sp, color = LiveColors.TextDim)
    val Mono = base.copy(fontSize = 12.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun LiveTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = LiveColors.Accent,
        onPrimary = Color.Black,
        background = LiveColors.Bg,
        onBackground = LiveColors.Text,
        surface = LiveColors.Panel,
        onSurface = LiveColors.Text,
        surfaceVariant = LiveColors.PanelHi,
        onSurfaceVariant = LiveColors.TextDim,
        outline = LiveColors.Line,
        error = LiveColors.Rec,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides LiveColors.Text) {
            Box(Modifier.fillMaxSize().background(LiveColors.Bg)) { content() }
        }
    }
}
