package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.theme.LiveColors

/** Height of every control in the compact bar. */
private val BarH = 30.dp

/**
 * Phone-landscape / tablet top chrome. ONE row when it fits (>= ~700dp of width): transport (play/stop/rec/
 * session-rec/overdub/loop/metronome, tempo, position, undo/redo) + the focused track (prev/name/next,
 * arm/M/S/monitor), plus vol/pan/meter when there is room (>= ~900dp). Narrower widths wrap onto two 30dp rows
 * (transport / track+faders), never clipping a partial control. Tabs live in the left rail, not here.
 */
@Composable
fun CompactTopBar(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val fs = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth().background(LiveColors.Panel)) {
        val gap = Arrangement.spacedBy(1.dp)
        val w = maxWidth
        if (w >= 700.dp * fs) {
            Row(Modifier.padding(1.dp), gap, Alignment.CenterVertically) {
                TransportButtons(store, client, BarH, 26.dp)
                TempoPos(store, client, 1.3f, 1.3f, BarH)
                UndoRedo(store, client, BarH, 26.dp)
                Row(Modifier.width(6.dp)) {}
                TrackNav(store, client, Modifier.weight(2.4f), BarH, 26.dp)
                TrackToggles(store, client, BarH, 28.dp)
                if (w >= 900.dp * fs) TrackFaders(store, client, 26.dp)
            }
        } else {
            Column(Modifier.padding(1.dp), Arrangement.spacedBy(1.dp)) {
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                    TransportButtons(store, client, BarH, 26.dp)
                    TempoPos(store, client, 1.8f, 1.3f, BarH)
                    UndoRedo(store, client, BarH, 26.dp)
                }
                Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                    TrackNav(store, client, Modifier.weight(1.6f), BarH, 26.dp)
                    TrackToggles(store, client, BarH, 28.dp)
                    TrackFaders(store, client, 26.dp)
                }
            }
        }
    }
}
