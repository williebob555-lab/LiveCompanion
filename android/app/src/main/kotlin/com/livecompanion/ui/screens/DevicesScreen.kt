package com.livecompanion.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.PlaceholderScreen

/**
 * Devices tab. Device chain cards, macros as knobs, variations, full parameter list for the opened device.
 *
 * PLACEHOLDER: replace the body, keep the signature. Read state from `store` (one StateFlow per
 * section), send ops through `client` (`client.op("name", "k" to v)` / suspend `client.request`).
 * Do not edit shared files (MainShell etc.); this file is wired in already.
 */
@Composable
fun DevicesScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    PlaceholderScreen("Devices", "Device chain cards, macros as knobs, variations, full parameter list for the opened device.", modifier)
}
