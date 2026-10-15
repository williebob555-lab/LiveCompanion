package com.livecompanion.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.PlaceholderScreen

/**
 * Clip tab. Clip properties + MIDI piano roll or audio waveform with loop/start/warp markers.
 *
 * PLACEHOLDER: replace the body, keep the signature. Read state from `store` (one StateFlow per
 * section), send ops through `client` (`client.op("name", "k" to v)` / suspend `client.request`).
 * Do not edit shared files (MainShell etc.); this file is wired in already.
 */
@Composable
fun ClipScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    PlaceholderScreen("Clip", "Clip properties + MIDI piano roll or audio waveform with loop/start/warp markers.", modifier)
}
