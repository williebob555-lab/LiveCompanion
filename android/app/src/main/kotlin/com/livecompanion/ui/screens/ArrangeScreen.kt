package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Loc
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.components.LiveTextField
import com.livecompanion.ui.components.TClip
import com.livecompanion.ui.components.TLane
import com.livecompanion.ui.components.TLoop
import com.livecompanion.ui.components.Timeline
import com.livecompanion.ui.components.adaptiveLaneHeight
import com.livecompanion.ui.components.TimelineView
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.components.timelineHeight
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.floor
import kotlin.math.max

private val DenseH = 28.dp

/** Live's clip palette approximations for the color picker. */
internal val ClipPalette = listOf(
    0xFF3A3A, 0xFF9B3D, 0xF7D12E, 0xBFFB00, 0x1AFF2F, 0x25FFA8, 0x5CFFE8, 0x1ABCF2,
    0x4F87FF, 0x9A6BFF, 0xFF7AF6, 0xFF4FA8, 0xB3B3B3, 0x7A7A7A, 0xA9835F, 0xD3A0D3,
)

/**
 * Arrange tab: the focused track's arrangement lane. Pinch/drag zoom+pan inside the lane only,
 * song loop brace (drag edges in the ruler), playhead, tap clip to select, tap ruler to seek.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArrangeScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val arr by store.arr.collectAsState()
    val session by store.session.collectAsState()
    val focus by store.focus.collectAsState()
    val clipSec by store.clip.collectAsState()
    val goTab = LocalTabNavigator.current
    val tr = arr.tr.ifEmpty { focus.ref }
    val view = remember { TimelineView() }
    var follow by remember { mutableStateOf(false) }
    var sel by remember { mutableIntStateOf(-1) }
    var dialog by remember { mutableStateOf<ArrDialog?>(null) }

    // Selection belongs to a track; drop it when the focus changes.
    LaunchedEffect(tr) { sel = -1 }

    // Sync selection from the opened clip (e.g. chosen elsewhere).
    LaunchedEffect(clipSec.loc) {
        val l = clipSec.loc?.jsonObject
        if (l != null && l["w"]?.jsonPrimitive?.contentOrNull == "arr" && l["tr"]?.jsonPrimitive?.contentOrNull == tr) {
            l["i"]?.jsonPrimitive?.intOrNull?.let { sel = it }
        }
    }

    val bpb = session.sigNum * 4.0 / session.sigDen
    val clips = arr.clips
    val selClip = clips.firstOrNull { it.i == sel }

    // Fit once content (or at least a few bars) is known; refit on track change.
    var fitKey by remember { mutableStateOf("") }
    val contentKey = "$tr:${clips.size}"
    LaunchedEffect(contentKey, view.widthPx) {
        if (view.widthPx > 0f && (!view.fitted || fitKey != tr)) {
            fitArrange(view, clips.map { it.start to it.end }, session.loopStart, session.loopLen, bpb)
            fitKey = tr
        }
    }

    val lanes = remember(clips, sel) {
        listOf(TLane(clips.map { TClip("a${it.i}", it.name.ifEmpty { if (it.audio) "Audio" else "MIDI" }, it.color, it.start, it.end, it.muted, it.i == sel) }))
    }

    Column(modifier.fillMaxSize().background(LiveColors.Bg), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // ---- toggles
        FlowRow(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val m = Modifier
            DBtn("Follow", { follow = !follow }, m, on = follow)
            DBtn("Loop", { client.op("song.set", "k" to "loop", "v" to !session.loop); store.updateSession { it.copy(loop = !it.loop) } }, m, on = session.loop)
            DBtn("Punch In", { client.op("song.set", "k" to "punch_in", "v" to !session.punchIn); store.updateSession { it.copy(punchIn = !it.punchIn) } }, m, on = session.punchIn)
            DBtn("Punch Out", { client.op("song.set", "k" to "punch_out", "v" to !session.punchOut); store.updateSession { it.copy(punchOut = !it.punchOut) } }, m, on = session.punchOut)
            DBtn("Arm", { client.op("track.set", "tr" to tr, "k" to "arm", "v" to !focus.arm); store.updateFocus { it.copy(arm = !it.arm) } }, m, on = focus.arm, onColor = LiveColors.Rec, enabled = focus.armable)
            DBtn("Arr Rec", { client.op("song.set", "k" to "rec", "v" to !session.rec); store.updateSession { it.copy(rec = !it.rec) } }, m, on = session.rec, onColor = LiveColors.Rec)
            DBtn("Overdub", { client.op("song.set", "k" to "overdub", "v" to !session.overdub); store.updateSession { it.copy(overdub = !it.overdub) } }, m, on = session.overdub)
            DBtn("Fit", { fitArrange(view, clips.map { it.start to it.end }, session.loopStart, session.loopLen, bpb) }, m)
            DBtn(
                "+ MIDI clip",
                {
                    val start = if (selClip != null) selClip.end else floor(store.rt.value.pos / bpb) * bpb
                    client.op("arr.create_midi", "tr" to tr, "start" to start, "len" to bpb)
                },
                m, enabled = focus.kind == "midi" && tr.isNotEmpty(),
            )
        }

        // ---- timeline
        Timeline(
            view = view,
            lanes = lanes,
            beatsPerBar = bpb,
            rt = store.rt,
            laneHeight = adaptiveLaneHeight(1, 64.dp, 260.dp, 0.4f),
            loop = TLoop(session.loop, session.loopStart, session.loopLen),
            onLoopChange = { s, l, done ->
                client.opThrottled("loop", "song.set", "k" to "loop_start", "v" to s)
                client.opThrottled("looplen", "song.set", "k" to "loop_len", "v" to l)
                if (done) store.updateSession { it.copy(loopStart = s, loopLen = l) }
            },
            follow = follow,
            onRulerTap = { b ->
                client.op("song.seek", "beats" to b)
            },
            onClipTap = { _, c ->
                val ac = clips.firstOrNull { "a${it.i}" == c.key } ?: return@Timeline
                sel = ac.i
                client.op("clip.open", "loc" to Loc.arr(tr, ac.i))
            },
            onEmptyTap = { _, _ -> sel = -1 },
        )

        // ---- selection info + actions
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).background(LiveColors.Panel).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selClip == null) {
                LiveText(
                    if (clips.isEmpty()) "No arrangement clips on this track" else "Tap a clip to select. Tap the ruler to seek; drag loop edges in the ruler; pinch to zoom.",
                    style = LiveType.Small, color = LiveColors.TextDim, maxLines = 3,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(liveColor(selClip.color), LiveShapes.Small))
                    LiveText(selClip.name.ifEmpty { if (selClip.audio) "Audio" else "MIDI" }, Modifier.weight(1f, fill = false), LiveType.Title)
                    LiveText(
                        "${barPos(selClip.start, bpb)} - ${barPos(selClip.end, bpb)}  (${fmtBars((selClip.end - selClip.start) / bpb)} bars)",
                        style = LiveType.Small, color = LiveColors.TextDim,
                    )
                }
                val loc = Loc.arr(tr, selClip.i)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DBtn("Open in Clip", {
                        client.op("clip.open", "loc" to loc)
                        goTab(Tab.Clip)
                    }, Modifier)
                    DBtn("Fire", {
                        client.op("song.seek", "beats" to selClip.start)
                        if (!session.playing) client.op("song.play")
                    }, Modifier)
                    DBtn("Dup loop", { client.op("clip.duplicate_loop", "loc" to loc) }, Modifier)
                    DBtn("Crop", { client.op("clip.crop", "loc" to loc) }, Modifier)
                    DBtn("Mute", { client.op("clip.set", "loc" to loc, "k" to "muted", "v" to !selClip.muted) }, Modifier, on = selClip.muted, onColor = LiveColors.MuteOn)
                    DBtn("Rename", { dialog = ArrDialog.Rename }, Modifier)
                    DBtn("Color", { dialog = ArrDialog.Color }, Modifier)
                    DBtn("Delete", { client.op("clip.delete", "loc" to loc); sel = -1 }, Modifier, on = true, onColor = LiveColors.Rec)
                }
            }
        }
    }

    val sc = selClip
    when (dialog) {
        ArrDialog.Rename -> if (sc != null) RenameClipDialog(sc.name, { dialog = null }) { n ->
            client.op("clip.set", "loc" to Loc.arr(tr, sc.i), "k" to "name", "v" to n); dialog = null
        } else dialog = null
        ArrDialog.Color -> if (sc != null) ColorDialog({ dialog = null }) { c ->
            client.op("clip.set", "loc" to Loc.arr(tr, sc.i), "k" to "color", "v" to c); dialog = null
        } else dialog = null
        null -> Unit
    }
}

private enum class ArrDialog { Rename, Color }

private fun fitArrange(view: TimelineView, spans: List<Pair<Double, Double>>, loopStart: Double, loopLen: Double, bpb: Double) {
    var from = spans.minOfOrNull { it.first } ?: 0.0
    var to = spans.maxOfOrNull { it.second } ?: 0.0
    if (spans.isEmpty()) { from = 0.0; to = bpb * 8 }
    if (loopLen > 0 && spans.isEmpty()) { from = min0(from, loopStart); to = max(to, loopStart + loopLen) }
    view.fit(from, max(to, from + bpb * 2))
}

private fun min0(a: Double, b: Double) = if (a < b) a else b

internal fun barPos(beat: Double, bpb: Double): String {
    val bar = floor(beat / bpb).toInt() + 1
    val inBar = beat - (bar - 1) * bpb
    return "$bar.${floor(inBar).toInt() + 1}"
}

internal fun fmtBars(v: Double): String = if (v == Math.rint(v)) v.toInt().toString() else "%.2f".format(v)

/** Dense (28dp) text button used across the arrangement/takes screens. */
@Composable
internal fun DBtn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onColor: androidx.compose.ui.graphics.Color = LiveColors.Accent,
    enabled: Boolean = true,
) = LiveTextButton(label, onClick, modifier, on, onColor, enabled, minHeight = DenseH, minWidth = 36.dp)

@Composable
internal fun RenameClipDialog(initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    LiveDialog("Rename", onDismiss) {
        LiveTextField(text, { text = it }, onDone = { onOk(text) })
        LiveDialogButtons {
            LiveTextButton("Cancel", onDismiss, minWidth = 72.dp)
            LiveTextButton("OK", { onOk(text) }, on = true, minWidth = 72.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    LiveDialog("Clip color", onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in ClipPalette) {
                Box(
                    Modifier.size(34.dp).background(liveColor(c), LiveShapes.Small)
                        .border(1.dp, LiveColors.Line, LiveShapes.Small)
                        .clickable { onPick(c) },
                )
            }
        }
        LiveDialogButtons { LiveTextButton("Cancel", onDismiss, minWidth = 72.dp) }
    }
}
