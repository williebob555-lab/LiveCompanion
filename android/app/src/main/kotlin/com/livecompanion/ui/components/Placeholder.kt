package com.livecompanion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType

/** Stand-in body for tabs that are not implemented yet. */
@Composable
fun PlaceholderScreen(title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(LiveColors.Bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LiveText(title, style = LiveType.Title)
        LiveText(description, style = LiveType.Small, color = LiveColors.TextDim, maxLines = 4)
        LiveText("Not implemented yet", style = LiveType.Label)
    }
}
