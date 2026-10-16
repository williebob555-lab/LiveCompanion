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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.theme.LiveColors

/** Height of every control in the compact bar. */
private val BarH = 40.dp

/**
 * Phone-landscape / tablet top chrome: ALWAYS one 40dp row. Everything is fixed-width except the focused-track name,
 * which takes all the remaining room (so names are not truncated while buttons keep their size). When the full set does
 * not fit (small phones), the row keeps transport play/stop/rec, tempo, position, track prev/name/next and arm/M/S;
 * the rest (session-rec, overdub, loop, metronome, undo/redo, monitor, vol/pan/meter) moves to [CompactSecondaryBar]
 * (the shell shows it as a bottom strip; [onSecondary] tells it when). vol/pan/meter join the row when >= ~260dp spare.
 */
@Composable
fun CompactTopBar(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier, onSecondary: (Boolean) -> Unit = {}) {
    val tempoW = maxOf(58.dp * LocalDensity.current.fontScale.coerceAtLeast(1f), monoWidth("999.99", 8.dp))
    val posW = monoWidth("999.4.4", 10.dp)
    val bw = 36.dp
    // fixed widths (+ 1dp gaps) of the full row without the name / faders
    val fullFixed = bw * 9 + tempoW + posW + 6.dp + 64.dp + 34.dp * 3 + 70.dp + 22.dp
    val nameMin = 120.dp
    BoxWithConstraints(modifier.fillMaxWidth().background(LiveColors.Panel)) {
        val gap = Arrangement.spacedBy(1.dp)
        val w = maxWidth
        val full = w >= fullFixed + nameMin
        SideEffect { onSecondary(!full) }
        Row(Modifier.padding(horizontal = 1.dp), gap, Alignment.CenterVertically) {
            if (full) {
                TransportButtons(store, client, BarH, bw, fixed = true)
                TempoPos(store, client, 0f, 0f, BarH)
                UndoRedo(store, client, BarH, bw, fixed = true)
                Row(Modifier.width(6.dp)) {}
                val faders = w >= fullFixed + nameMin + 260.dp
                TrackNav(store, client, Modifier.weight(if (faders) 2.6f else 1f), BarH, 32.dp)
                TrackToggles(store, client, BarH, 34.dp)
                if (faders) TrackFaders(store, client, 32.dp)
            } else {
                TransportButtons(store, client, BarH, bw, fixed = true, extras = false)
                TempoPos(store, client, 0f, 0f, BarH)
                TrackNav(store, client, Modifier.weight(1f), BarH, 32.dp)
                TrackToggles(store, client, BarH, 34.dp, monitor = false)
            }
        }
    }
}

/** Secondary transport + track controls for small phones (see [CompactTopBar]); shown as a 40dp strip at the bottom. */
@Composable
fun CompactSecondaryBar(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(LiveColors.Panel).padding(horizontal = 1.dp), Arrangement.spacedBy(1.dp), Alignment.CenterVertically) {
        TransportExtras(store, client, BarH, 36.dp, fixed = true)
        UndoRedo(store, client, BarH, 36.dp, fixed = true)
        Row(Modifier.width(4.dp)) {}
        MonitorToggle(store, client, BarH)
        TrackFaders(store, client, 32.dp)
    }
}

/**
 * Portrait phone chrome in THREE 28dp rows (~12% of a 915dp screen): transport + undo/redo / tempo + position + track
 * prev-name-next / arm M S monitor + vol/pan/meter. Used by the shell when the width is >= 340dp * fontScale;
 * narrower or larger-font screens keep the roomier [TransportBar] + [TrackHeader].
 */
@Composable
fun PortraitTopBar(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val h = 40.dp
    Column(modifier.fillMaxWidth().background(LiveColors.Panel).padding(1.dp), Arrangement.spacedBy(1.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
            TransportButtons(store, client, h, 30.dp)
            UndoRedo(store, client, h, 30.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
            TempoPos(store, client, 1.3f, 1.7f, h)
            TrackNav(store, client, Modifier.weight(2.4f), h, 34.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackToggles(store, client, h, 36.dp)
            TrackFaders(store, client, 30.dp)
        }
    }
}
