package com.livecompanion.ui.clip

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.ClipState
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.ColorPaletteDialog
import com.livecompanion.ui.components.DenseTextField
import com.livecompanion.ui.components.LiveLabel
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val LAUNCH_MODES = listOf("Trigger", "Gate", "Toggle", "Repeat")
private val LAUNCH_QUANTS = listOf("Global", "None", "8 Bars", "4 Bars", "2 Bars", "1 Bar", "1/2", "1/2T", "1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T")
private val Q_GRIDS = listOf("1/4" to 1, "1/8" to 2, "1/8T" to 3, "1/16" to 5, "1/16T" to 6, "1/32" to 8)

/** Sends clip.* ops for the opened clip with optimistic store updates. */
class ClipOps(private val store: LiveStore, private val client: LiveClient) {
    private fun loc() = store.clip.value.loc

    fun set(k: String, v: Any, optimistic: (ClipState) -> ClipState) {
        store.updateClip(optimistic)
        client.opThrottled("clipset.$k", "clip.set", "loc" to loc(), "k" to k, "v" to v)
    }

    fun act(op: String, vararg extra: Pair<String, Any?>) = client.op(op, "loc" to loc(), *extra)
}

/** Numeric cell: dragging accumulates pixels into whole steps; tap types a value. */
@Composable
fun StepField(
    label: String, display: String, modifier: Modifier = Modifier, pxPerStep: Float = 8f, typedInit: String = display,
    onStep: (Int) -> Unit, onTyped: (String) -> Unit,
) {
    var acc by remember { mutableFloatStateOf(0f) }
    NumField(
        label, display, modifier, typedInit,
        onDrag = { px ->
            acc += px
            val steps = (acc / pxPerStep).toInt()
            if (steps != 0) { acc -= steps * pxPerStep; onStep(steps) }
        },
        onDragEnd = { acc = 0f },
        onTyped = onTyped,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Cells(cols: Int, content: @Composable FlowRowScope.() -> Unit) =
    FlowRow(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        maxItemsInEachRow = cols, content = content,
    )

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ClipProps(
    clip: ClipState, ed: NoteEditor?, ops: ClipOps, client: LiveClient, onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var palette by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = (maxWidth / 110.dp).toInt().coerceIn(1, 4)
        val bb = barBeats(clip.signature.getOrElse(0) { 4 }, clip.signature.getOrElse(1) { 4 })
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // name + color (+ close)
            var name by remember(clip.loc) { mutableStateOf(clip.name) }
            var lastSent by remember(clip.loc) { mutableStateOf(clip.name) }
            if (clip.name != name && clip.name != lastSent) { name = clip.name; lastSent = clip.name }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                DenseTextField(
                    name, { name = it; lastSent = it; ops.set("name", it) { c -> c.copy(name = it) } },
                    Modifier.weight(1f).height(DenseH), placeholder = "Clip name",
                )
                androidx.compose.foundation.layout.Box(
                    Modifier.size(DenseH).background(liveColor(clip.color), LiveShapes.Small).clickable { palette = true },
                )
                if (onClose != null) Btn("Close", onClose)
            }
            Cells(cols) {
                Btn("Loop", { ops.set("looping", !clip.looping) { it.copy(looping = !clip.looping) } }, Modifier.weight(1f), on = clip.looping)
                Btn("Muted", { ops.set("muted", !clip.muted) { it.copy(muted = !clip.muted) } }, Modifier.weight(1f), on = clip.muted, onColor = LiveColors.MuteOn)
                Btn("Legato", { ops.set("legato", !clip.legato) { it.copy(legato = !clip.legato) } }, Modifier.weight(1f), on = clip.legato)
                if (clip.audio) Btn("Warp", { ops.set("warping", !clip.warping) { it.copy(warping = !clip.warping) } }, Modifier.weight(1f), on = clip.warping)
            }
            fun pos(label: String, value: Double, key: String, upd: (ClipState, Double) -> ClipState): @Composable FlowRowScope.() -> Unit = {
                // positions in beats; seconds for unwarped audio
                val unwarped = clip.audio && !clip.warping
                val disp = if (unwarped) "%.2fs".format(value) else formatBbt(value, bb)
                StepField(
                    label, disp, Modifier.weight(1f), pxPerStep = 8f, typedInit = if (unwarped) "%.3f".format(value) else disp,
                    onStep = { s -> val nv = max(0.0, value + s * if (unwarped) 0.05 else 0.25); ops.set(key, nv) { upd(it, nv) } },
                    onTyped = { t ->
                        val nv = if (unwarped) t.trim().replace(',', '.').toDoubleOrNull() else parseBbt(t, bb)
                        if (nv != null && nv >= 0) ops.set(key, nv) { upd(it, nv) }
                    },
                )
            }
            Cells(cols) {
                pos("Start", clip.startMarker, "start_marker") { c, v -> c.copy(startMarker = v) }()
                pos("End", clip.endMarker, "end_marker") { c, v -> c.copy(endMarker = v) }()
                pos("Loop st", clip.loopStart, "loop_start") { c, v -> c.copy(loopStart = v) }()
                pos("Loop en", clip.loopEnd, "loop_end") { c, v -> c.copy(loopEnd = v) }()
                FieldBox("Sig", "${clip.signature.getOrElse(0) { 4 }}/${clip.signature.getOrElse(1) { 4 }}", Modifier.weight(1f), enabled = false)
                ChoiceField("Mode", LAUNCH_MODES, clip.launchMode, { i -> ops.set("launch_mode", i) { it.copy(launchMode = i) } }, Modifier.weight(1f))
                ChoiceField("Quant", LAUNCH_QUANTS, clip.launchQuant, { i -> ops.set("launch_quant", i) { it.copy(launchQuant = i) } }, Modifier.weight(1f))
            }
            if (clip.audio) {
                Cells(cols) {
                    StepField(
                        "Gain", clip.gainStr.ifBlank { "%.2f".format(clip.gain) }, Modifier.weight(1f), pxPerStep = 3f, typedInit = "%.3f".format(clip.gain),
                        onStep = { s -> val v = (clip.gain + s * 0.005f).coerceIn(0f, 1f); ops.set("gain", v) { it.copy(gain = v) } },
                        onTyped = { t -> t.trim().replace(',', '.').toFloatOrNull()?.let { v -> ops.set("gain", v.coerceIn(0f, 1f)) { it.copy(gain = v.coerceIn(0f, 1f)) } } },
                    )
                    StepField(
                        "Semi", "${clip.pitchCoarse}", Modifier.weight(1f), pxPerStep = 10f,
                        onStep = { s -> val v = (clip.pitchCoarse + s).coerceIn(-48, 48); ops.set("pitch_coarse", v) { it.copy(pitchCoarse = v) } },
                        onTyped = { t -> t.trim().toIntOrNull()?.let { v -> ops.set("pitch_coarse", v.coerceIn(-48, 48)) { it.copy(pitchCoarse = v.coerceIn(-48, 48)) } } },
                    )
                    StepField(
                        "Cents", "${clip.pitchFine.roundToInt()}", Modifier.weight(1f), pxPerStep = 3f,
                        onStep = { s -> val v = (clip.pitchFine + s).coerceIn(-50f, 50f); ops.set("pitch_fine", v) { it.copy(pitchFine = v) } },
                        onTyped = { t -> t.trim().toFloatOrNull()?.let { v -> ops.set("pitch_fine", v.coerceIn(-50f, 50f)) { it.copy(pitchFine = v.coerceIn(-50f, 50f)) } } },
                    )
                    if (clip.warpModes.isNotEmpty()) {
                        ChoiceField(
                            "Warp", clip.warpModes, clip.warpModes.indexOf(clip.warpMode),
                            { i -> val nm = clip.warpModes[i]; ops.set("warp_mode", nm) { it.copy(warpMode = nm) } },
                            Modifier.weight(1f),
                        )
                    }
                }
            } else {
                var gi by remember { mutableIntStateOf(3) }
                var amount by remember { mutableIntStateOf(100) }
                Cells(cols) {
                    ChoiceField("Q grid", Q_GRIDS.map { it.first }, gi, { gi = it }, Modifier.weight(1f))
                    StepField(
                        "Amount", "$amount%", Modifier.weight(1f), pxPerStep = 5f,
                        onStep = { s -> amount = (amount + s * 5).coerceIn(0, 100) },
                        onTyped = { t -> t.trim().removeSuffix("%").toIntOrNull()?.let { amount = it.coerceIn(0, 100) } },
                    )
                    Btn("Quantize", { ops.act("clip.quantize", "grid" to Q_GRIDS[gi].second, "amount" to amount / 100.0) }, Modifier.weight(1f))
                    Btn("Dup loop", { ops.act("clip.duplicate_loop") }, Modifier.weight(1f))
                    Btn("Crop", { ops.act("clip.crop") }, Modifier.weight(1f))
                }
                if (ed != null) {
                    val has = ed.sel.isNotEmpty()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiveLabel("Transpose sel", Modifier.weight(1f))
                        for ((l, d) in listOf("-12" to -12, "-1" to -1, "+1" to 1, "+12" to 12)) Btn(l, { ed.transposeSelected(d) }, enabled = has, minWidth = 34.dp)
                    }
                }
            }
        }
    }
    if (palette) ColorPaletteDialog(clip.color, { c -> ops.set("color", c) { it.copy(color = c) } }, { palette = false })
}
