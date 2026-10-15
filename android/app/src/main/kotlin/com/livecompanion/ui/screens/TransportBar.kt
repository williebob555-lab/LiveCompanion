package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.state.SessionState
import com.livecompanion.ui.components.LiveButton
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveIconButton
import com.livecompanion.ui.components.LiveIconKind
import com.livecompanion.ui.components.LiveLabel
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.components.LiveTextField
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import kotlin.math.floor

/**
 * Fixed top transport bar: play/stop/rec/session-rec/overdub/loop/metronome, tempo, position, undo/redo.
 * Two 32dp rows below 440dp wide, one row above.
 */
@Composable
fun TransportBar(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val s by store.session.collectAsState()
    var tempoDialog by remember { mutableStateOf(false) }

    fun toggle(k: String, cur: Boolean, patch: (SessionState, Boolean) -> SessionState) {
        client.op("song.set", "k" to k, "v" to !cur)
        store.updateSession { patch(it, !cur) }
    }

    @Composable
    fun RowScope.Buttons() {
        W(LiveIconKind.Play, s.playing, LiveColors.Play) {
            if (s.playing) client.op("song.stop") else client.op("song.play")
            store.updateSession { it.copy(playing = !it.playing) }
        }
        W(LiveIconKind.Stop, false, LiveColors.Accent) {
            client.op("song.stop"); store.updateSession { it.copy(playing = false) }
        }
        W(LiveIconKind.Rec, s.rec, LiveColors.Rec) { toggle("rec", s.rec) { x, v -> x.copy(rec = v) } }
        W(LiveIconKind.SessionRec, s.sessionRec, LiveColors.Rec) { toggle("session_rec", s.sessionRec) { x, v -> x.copy(sessionRec = v) } }
        W(LiveIconKind.Overdub, s.overdub, LiveColors.Accent) { toggle("overdub", s.overdub) { x, v -> x.copy(overdub = v) } }
        W(LiveIconKind.Loop, s.loop, LiveColors.Accent) { toggle("loop", s.loop) { x, v -> x.copy(loop = v) } }
        W(LiveIconKind.Metronome, s.metronome, LiveColors.Accent) { toggle("metronome", s.metronome) { x, v -> x.copy(metronome = v) } }
    }

    @Composable
    fun RowScope.TempoPos(tempoWeight: Float, posWeight: Float) {
        LiveButton(
            onClick = { tempoDialog = true },
            modifier = Modifier.weight(tempoWeight),
            minWidth = 52.dp,
        ) { c -> LiveText(formatTempo(s.tempo), style = LiveType.Mono, color = c) }
        PositionReadout(store, Modifier.weight(posWeight))
    }

    @Composable
    fun RowScope.UndoRedo() {
        W(LiveIconKind.Undo, false, LiveColors.Accent, enabled = s.canUndo) { client.op("song.undo") }
        W(LiveIconKind.Redo, false, LiveColors.Accent, enabled = s.canRedo) { client.op("song.redo") }
    }

    BoxWithConstraints(modifier.fillMaxWidth().background(LiveColors.Panel)) {
        if (maxWidth >= 440.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
            Row(Modifier.padding(1.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
                Buttons()
                TempoPos(1.4f, 1.8f)
                UndoRedo()
            }
        } else {
            Column(Modifier.padding(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) { Buttons() }
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
                    TempoPos(1.3f, 1.7f)
                    UndoRedo()
                }
            }
        }
    }

    if (tempoDialog) {
        TempoDialog(
            current = s.tempo,
            onDismiss = { tempoDialog = false },
            onTap = { client.op("song.tap_tempo") },
            onSet = { bpm ->
                client.op("song.set", "k" to "tempo", "v" to bpm)
                store.updateSession { it.copy(tempo = bpm) }
                tempoDialog = false
            },
        )
    }
}

@Composable
private fun RowScope.W(
    icon: LiveIconKind,
    on: Boolean,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = LiveIconButton(icon, onClick, Modifier.weight(1f), on, color, enabled, minWidth = 30.dp)

internal fun formatTempo(t: Float): String = String.format(java.util.Locale.US, "%.2f", t)

/** Bar.beat.sixteenth readout. Reads the realtime flow itself so only this leaf recomposes at 20 Hz. */
@Composable
fun PositionReadout(store: LiveStore, modifier: Modifier = Modifier) {
    val rt by store.rt.collectAsState()
    val s by store.session.collectAsState()
    val pos = if (rt.playing || rt.pos != 0.0) rt.pos else s.pos
    Box(
        modifier
            .heightIn(min = 32.dp)
            .background(LiveColors.Bg, LiveShapes.Small)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { LiveText(formatPosition(pos, s.sigNum, s.sigDen), style = LiveType.Mono) }
}

internal fun formatPosition(pos: Double, num: Int, den: Int): String {
    val beatLen = 4.0 / den
    val barLen = beatLen * num
    val p = pos.coerceAtLeast(0.0)
    val bar = floor(p / barLen).toInt() + 1
    val inBar = p - (bar - 1) * barLen
    val beat = floor(inBar / beatLen).toInt() + 1
    val sixteenth = floor((inBar - (beat - 1) * beatLen) / beatLen * 4).toInt().coerceIn(0, 3) + 1
    return "$bar.$beat.$sixteenth"
}

@Composable
private fun TempoDialog(current: Float, onDismiss: () -> Unit, onTap: () -> Unit, onSet: (Float) -> Unit) {
    var text by remember { mutableStateOf(formatTempo(current)) }
    val parsed = text.replace(',', '.').toFloatOrNull()?.takeIf { it in 20f..999f }
    LiveDialog("Tempo", onDismiss) {
        LiveLabel("BPM (20-999)")
        LiveTextField(
            text, { text = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            onDone = { parsed?.let(onSet) },
        )
        LiveDialogButtons {
            LiveTextButton("Tap", onTap, minWidth = 64.dp)
            LiveTextButton("Cancel", onDismiss, minWidth = 64.dp)
            LiveTextButton("Set", { parsed?.let(onSet) }, on = parsed != null, enabled = parsed != null, minWidth = 64.dp)
        }
    }
}
