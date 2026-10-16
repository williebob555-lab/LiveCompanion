package com.livecompanion.ui.devices

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.Device
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Macro
import com.livecompanion.state.Param
import com.livecompanion.ui.components.ConfirmDialog
import com.livecompanion.ui.components.LiveKnob
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import kotlin.math.roundToInt

private val CELL_W = 50.dp
private val KNOB = 34.dp
internal val HEADER_H = 26.dp

fun typeColor(t: String): Color = when (t) {
    "instrument" -> LiveColors.Accent
    "midi_fx" -> LiveColors.MuteOn
    "rack" -> LiveColors.Solo
    else -> LiveColors.TextDim
}

private fun typeBadge(t: String) = when (t) {
    "instrument" -> "INST"; "audio_fx" -> "FX"; "midi_fx" -> "MIDI"; "rack" -> "RACK"; else -> "DEV"
}

private fun controlCount(d: Device) = maxOf(d.macros?.size ?: 0, d.pp.size)

private fun columnsFor(n: Int) = when {
    n >= 5 -> 4
    n >= 2 -> n
    else -> 2
}

/** Panel width from the number of inline controls (4 per row, like Live's compact device view). */
private fun panelWidth(d: Device): Dp {
    val base = columnsFor(controlCount(d)) * CELL_W.value + 10
    return maxOf(base, if (d.chains.isNotEmpty()) 160f else 124f).dp
}

@Composable
private fun Chevron(collapsed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) {
        val p = Path()
        if (collapsed) {
            p.moveTo(size.width * .25f, 0f); p.lineTo(size.width, size.height / 2); p.lineTo(size.width * .25f, size.height)
        } else {
            p.moveTo(0f, size.height * .25f); p.lineTo(size.width, size.height * .25f); p.lineTo(size.width / 2, size.height)
        }
        p.close()
        drawPath(p, LiveColors.TextDim)
    }
}

/** Title-bar power button: lit (accent) when the device is on. */
@Composable
private fun PowerButton(on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(HEADER_H).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(14.dp).clip(LiveShapes.Small)
                .background(if (on) LiveColors.Accent else LiveColors.PanelHi)
                .border(1.dp, if (on) LiveColors.Accent else LiveColors.Line, LiveShapes.Small),
        )
    }
}

/** Connector between two devices: a line with an arrowhead pointing right (signal flow). */
@Composable
fun FlowArrow(modifier: Modifier = Modifier) {
    Canvas(modifier.width(16.dp).fillMaxHeight()) {
        val y = HEADER_H.toPx() / 2 + 3.dp.toPx()
        drawLine(LiveColors.Line, Offset(0f, y), Offset(size.width, y), 1.5f)
        val p = Path()
        p.moveTo(size.width - 5.dp.toPx(), y - 3.5.dp.toPx())
        p.lineTo(size.width, y)
        p.lineTo(size.width - 5.dp.toPx(), y + 3.5.dp.toPx())
        p.close()
        drawPath(p, LiveColors.TextDim)
    }
}

@Composable
private fun SmallBtn(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, color: Color = LiveColors.Accent) =
    LiveTextButton(label, onClick, modifier, on, color, minHeight = 24.dp, minWidth = 24.dp)

/**
 * One device in the signal chain: title bar (power, name = drag handle, collapse chevron), inline parameter
 * knobs (macros for racks), variations, rack chain selector and a small action row.
 * [handle] is applied to the name area and carries the hold-and-drag reorder gesture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevicePanel(
    d: Device, tr: String, ui: ChainUi, into: String, store: LiveStore, client: LiveClient,
    handle: Modifier, dragging: Boolean, modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val collapsed = ui.collapsed[d.path] == true
    val isOpen = ui.openPath == d.path
    val border = if (isOpen || dragging) LiveColors.Accent else LiveColors.Line
    val toggle = {
        store.updateDevices { s -> s.copy(devices = patchDevice(s.devices, d.path) { it.copy(on = !it.on) }) }
        client.op("device.toggle", "tr" to tr, "path" to d.path)
    }
    val flip: () -> Unit = { if (collapsed) { ui.collapsed.remove(d.path) } else { ui.collapsed[d.path] = true } }

    if (collapsed) {
        Column(
            modifier.width(30.dp).fillMaxHeight()
                .background(LiveColors.Panel, LiveShapes.Small).border(1.dp, border, LiveShapes.Small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(typeColor(d.type)))
            PowerButton(d.on, toggle)
            Box(Modifier.size(HEADER_H).clickable(onClick = flip), contentAlignment = Alignment.Center) { Chevron(true) }
            // Name written bottom-to-top; this is also the drag handle of a collapsed device.
            Box(
                Modifier.weight(1f).fillMaxWidth().then(handle).clip(LiveShapes.Small),
                contentAlignment = Alignment.TopCenter,
            ) {
                LiveText(
                    d.name.ifEmpty { d.cls },
                    Modifier.layout { m, c ->
                        val p = m.measure(c.copy(minWidth = 0, maxWidth = c.maxHeight.coerceAtLeast(1), minHeight = 0, maxHeight = Int.MAX_VALUE))
                        layout(p.height, p.width) { p.place(-(p.width - p.height) / 2, (p.width - p.height) / 2) }
                    }.rotate(-90f).padding(top = 2.dp),
                    LiveType.Small, color = if (d.on) LiveColors.Text else LiveColors.TextDim,
                )
            }
        }
        return
    }

    Column(
        modifier.width(panelWidth(d)).fillMaxHeight()
            .background(LiveColors.Panel, LiveShapes.Small).border(1.dp, border, LiveShapes.Small),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(typeColor(d.type)))
        Row(Modifier.fillMaxWidth().height(HEADER_H), verticalAlignment = Alignment.CenterVertically) {
            PowerButton(d.on, toggle)
            Box(Modifier.weight(1f).fillMaxHeight().then(handle), contentAlignment = Alignment.CenterStart) {
                LiveText(d.name.ifEmpty { d.cls }, style = LiveType.Title, color = if (d.on) LiveColors.Text else LiveColors.TextDim)
            }
            Box(Modifier.size(HEADER_H).clickable(onClick = flip), contentAlignment = Alignment.Center) { Chevron(false) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)
                .alpha(if (d.on) 1f else 0.5f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val macros = d.macros.orEmpty()
            if (macros.isNotEmpty() || d.pp.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth(), maxItemsInEachRow = columnsFor(controlCount(d)),
                    horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (m in macros) MacroKnob(m, d, tr, store, client)
                    for (p in d.pp) ParamCell(p, d, tr, store, client)
                }
            } else if (d.chains.isEmpty()) {
                LiveText(typeBadge(d.type), style = LiveType.Label)
            }
            d.variations?.let { vr ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    fun v(act: String, i: Int? = null) = client.op("device.var", "tr" to tr, "path" to d.path, "act" to act, "i" to i)
                    SmallBtn("<", { if (vr.sel > 0) v("select", vr.sel - 1) })
                    LiveText(if (vr.count == 0) "-" else "${vr.sel + 1}/${vr.count}", Modifier.widthIn(min = 24.dp), LiveType.Small, textAlign = TextAlign.Center)
                    SmallBtn(">", { if (vr.sel < vr.count - 1) v("select", vr.sel + 1) })
                    SmallBtn("Store", { v("store") })
                }
            }
            if (d.chains.isNotEmpty()) RackChains(d, tr, ui, client)
        }
        Row(Modifier.fillMaxWidth().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallBtn("Params", {
                if (isOpen) { ui.openPath = null; client.op("device.close") }
                else { ui.openPath = d.path; client.op("device.open", "tr" to tr, "path" to d.path) }
            }, Modifier.weight(1f), on = isOpen)
            SmallBtn("Dup", {
                store.updateDevices { s ->
                    s.copy(devices = editList(s.devices, into) { l ->
                        val i = l.indexOfFirst { it.path == d.path }
                        if (i < 0) l else l.toMutableList().also { it.add(i + 1, l[i]) }
                    })
                }
                client.op("device.duplicate", "tr" to tr, "path" to d.path)
            })
            SmallBtn("Del", { confirmDelete = true }, color = LiveColors.Rec)
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete device", "Delete \"${d.name}\" from the track?", "Delete", {
            store.updateDevices { s -> s.copy(devices = editList(s.devices, into) { l -> l.filter { it.path != d.path } }) }
            client.op("device.delete", "tr" to tr, "path" to d.path)
        }, { confirmDelete = false })
    }
}

@Composable
private fun RackChains(d: Device, tr: String, ui: ChainUi, client: LiveClient) {
    val sel = ui.chainSel[d.path]
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LiveText("CHAINS", style = LiveType.Label)
        for (c in d.chains) {
            val on = sel == c.i
            Row(
                Modifier.fillMaxWidth().height(24.dp).clip(LiveShapes.Small)
                    .background(if (on) LiveColors.Accent else LiveColors.PanelHi)
                    .clickable {
                        if (on) ui.chainSel.remove(d.path)
                        else { ui.chainSel[d.path] = c.i; client.op("chain.select", "tr" to tr, "path" to d.path, "i" to c.i) }
                    }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LiveText(c.name.ifEmpty { "Chain ${c.i + 1}" }, Modifier.weight(1f), LiveType.Small, color = if (on) Color.Black else LiveColors.Text)
                LiveText("${c.devices.size}", style = LiveType.Label, color = if (on) Color.Black else LiveColors.TextDim)
            }
        }
    }
}

@Composable
private fun MacroKnob(m: Macro, d: Device, tr: String, store: LiveStore, client: LiveClient) {
    Box(Modifier.width(CELL_W), contentAlignment = Alignment.TopCenter) {
        LiveKnob(
            value = m.v, valueRange = m.min..m.max.coerceAtLeast(m.min + 1e-3f), defaultValue = m.min,
            knobSize = KNOB, label = m.name.ifEmpty { "M${m.i + 1}" }, valueText = m.str,
            onValueChange = { v ->
                store.updateDevices { st ->
                    st.copy(devices = patchDevice(st.devices, d.path) { dv ->
                        dv.copy(macros = dv.macros?.map { if (it.i == m.i) it.copy(v = v) else it })
                    })
                }
                client.opThrottled("dm:$tr:${d.path}:${m.i}", "device.param", "tr" to tr, "path" to d.path, "i" to m.i, "v" to v)
            },
        )
    }
}

/** One inline parameter: knob with value string, or a stepped selector for quantized parameters. */
@Composable
private fun ParamCell(p: Param, d: Device, tr: String, store: LiveStore, client: LiveClient) {
    fun send(v: Float) {
        store.updateDevices { st ->
            st.copy(devices = patchDevice(st.devices, d.path) { dv -> dv.copy(pp = dv.pp.map { if (it.i == p.i) it.copy(v = v) else it }) })
        }
        client.opThrottled("dp:$tr:${d.path}:${p.i}", "device.param", "tr" to tr, "path" to d.path, "i" to p.i, "v" to v)
    }
    val items = p.items
    Box(Modifier.width(CELL_W), contentAlignment = Alignment.TopCenter) {
        if (p.quant && !items.isNullOrEmpty()) {
            val cur = (p.v - p.min).roundToInt().coerceIn(0, items.lastIndex)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Stepped selector: each tap advances to the next item (wraps around).
                Box(
                    Modifier.padding(top = 4.dp).width(CELL_W - 4.dp).height(KNOB - 6.dp).clip(LiveShapes.Small)
                        .background(LiveColors.PanelHi)
                        .clickable { send(p.min + (cur + 1) % items.size) },
                    contentAlignment = Alignment.Center,
                ) { LiveText(items[cur], Modifier.padding(horizontal = 2.dp), LiveType.Small, textAlign = TextAlign.Center) }
                Row(Modifier.padding(top = 2.dp).height(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (items.size <= 6) for (k in items.indices) Box(Modifier.size(4.dp).background(if (k == cur) LiveColors.Accent else LiveColors.Line))
                }
                LiveText(p.name, Modifier.width(CELL_W - 2.dp), LiveType.Label, textAlign = TextAlign.Center)
            }
        } else {
            LiveKnob(
                value = p.v, valueRange = p.min..p.max.coerceAtLeast(p.min + 1e-6f), defaultValue = p.default ?: p.min,
                knobSize = KNOB, label = p.name, valueText = p.str.ifEmpty { null },
                onValueChange = ::send,
            )
        }
    }
}

/** Dim placeholder shown right after Load/insert until Live reports the new device. */
@Composable
fun PendingPanel(name: String, modifier: Modifier = Modifier) {
    Column(
        modifier.width(124.dp).fillMaxHeight().background(LiveColors.Panel.copy(alpha = 0.6f), LiveShapes.Small)
            .border(1.dp, LiveColors.Accent.copy(alpha = 0.6f), LiveShapes.Small),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(LiveColors.Accent.copy(alpha = 0.6f)))
        Box(Modifier.fillMaxWidth().height(HEADER_H).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            LiveText(name, style = LiveType.Title, color = LiveColors.TextDim)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LiveText("loading...", style = LiveType.Small, color = LiveColors.Accent)
        }
    }
}
