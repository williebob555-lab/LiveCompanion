package com.livecompanion.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.PlaceholderScreen

/**
 * Mix tab. Focused track sends and routing.
 *
 * PLACEHOLDER: replace the body, keep the signature. Read state from `store` (one StateFlow per
 * section), send ops through `client` (`client.op("name", "k" to v)` / suspend `client.request`).
 * Do not edit shared files (MainShell etc.); this file is wired in already.
 */
@Composable
fun MixScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    PlaceholderScreen("Mix", "Focused track sends and routing.", modifier)
}
