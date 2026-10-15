package com.livecompanion.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.PlaceholderScreen

/**
 * Arrange tab. Arrangement lane for the focused track (zoom/pan, loop brace, playhead, clip select -> Clip tab).
 *
 * PLACEHOLDER: replace the body, keep the signature. Read state from `store` (one StateFlow per
 * section), send ops through `client` (`client.op("name", "k" to v)` / suspend `client.request`).
 * Do not edit shared files (MainShell etc.); this file is wired in already.
 */
@Composable
fun ArrangeScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    PlaceholderScreen("Arrange", "Arrangement lane for the focused track (zoom/pan, loop brace, playhead, clip select -> Clip tab).", modifier)
}
