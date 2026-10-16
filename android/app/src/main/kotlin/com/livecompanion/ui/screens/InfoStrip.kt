package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livecompanion.net.ConnStatus
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.MeterBar
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType

private val StripText = LiveType.Mono.copy(fontSize = 10.sp, lineHeight = 12.sp, color = LiveColors.Text)
private val StripLabel = LiveType.Label.copy(fontSize = 8.sp, lineHeight = 9.sp, letterSpacing = 0.3.sp)

/**
 * READ-ONLY info strip drawn in the display-cutout inset (landscape). Nothing here is interactive.
 * Top group: connection dot, song position, tempo, time signature. Bottom group: CPU and the focused track's meter.
 * The middle is left empty on purpose: that is where a punch-hole camera usually sits.
 */
@Composable
fun InfoStrip(store: LiveStore, statusOverride: ConnStatus?, client: LiveClient, modifier: Modifier = Modifier) {
    val conn by client.state.collectAsState()
    val status = statusOverride ?: conn.status
    val s by store.session.collectAsState()
    val rt by store.rt.collectAsState()
    val focus by store.focus.collectAsState()
    val pos = if (rt.playing || rt.pos != 0.0) rt.pos else s.pos
    val cpu = ((rt.cpu ?: s.cpu) * 100f).toInt().coerceIn(0, 100)
    val dot = when (status) {
        ConnStatus.CONNECTED -> LiveColors.Play
        ConnStatus.IDLE -> LiveColors.Rec
        else -> LiveColors.Warn
    }
    Column(
        modifier.background(LiveColors.Panel).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterHorizontally) {
            if (client is com.livecompanion.net.DemoClient) LiveText("DEMO", style = StripLabel.copy(color = LiveColors.Accent), textAlign = TextAlign.Center)
            else Box(Modifier.size(7.dp).background(dot, CircleShape))
            Cell("POS", formatPosition(pos, s.sigNum, s.sigDen))
            Cell("BPM", formatTempo(s.tempo))
            Cell("SIG", "${s.sigNum}/${s.sigDen}")
        }
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterHorizontally) {
            Cell("CPU", "$cpu%")
            LiveText(focus.name.take(6).ifEmpty { "-" }, style = StripLabel, textAlign = TextAlign.Center)
            MeterBar(
                left = { store.rt.value.meters.focusL },
                right = { store.rt.value.meters.focusR },
                modifier = Modifier.width(8.dp).height(56.dp),
                vertical = true,
            )
        }
    }
}

@Composable
private fun Cell(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LiveText(label, style = StripLabel, textAlign = TextAlign.Center)
        // The strip has a fixed physical width (the cutout inset): keep its text at 10dp whatever the font scale so "10.2.3" never truncates.
        val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        LiveText(value, style = StripText.copy(fontSize = (10f / fs).sp), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
    }
}
