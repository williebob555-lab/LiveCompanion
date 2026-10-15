package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.Chain
import com.livecompanion.state.Device
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Param
import com.livecompanion.ui.components.ConfirmDialog
import com.livecompanion.ui.components.DenseButton
import com.livecompanion.ui.components.DenseSelect
import com.livecompanion.ui.components.DenseTextField
import com.livecompanion.ui.components.LiveFader
import com.livecompanion.ui.components.LiveKnob
import com.livecompanion.ui.components.LiveLabel
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import kotlin.math.roundToInt

private fun patchDevice(ds: List<Device>, path: String, f: (Device) -> Device): List<Device> =
    ds.map { d ->
        val n = if (d.path == path) f(d) else d
        if (n.chains.isEmpty()) n
        else n.copy(chains = n.chains.map { c: Chain -> c.copy(devices = patchDevice(c.devices, path, f)) })
    }

/**
 * Devices tab: the focused track's device chain (horizontal cards on wide screens, vertical list on
 * compact), macros as knobs, variations, rack chains inline, and the full parameter list of the
 * opened device (`device.open` / `device.close`).
 */
@Composable
fun DevicesScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val devs by store.devices.collectAsState()
    val focus by store.focus.collectAsState()
    val tr = devs.tr.ifEmpty { focus.ref }
    var openPath by remember { mutableStateOf<String?>(null) }
    val openNow = rememberUpdatedState(openPath)
    DisposableEffect(Unit) { onDispose { if (openNow.value != null) client.op("device.close") } }
    LaunchedEffect(tr) { if (openNow.value != null) { openPath = null; client.op("device.close") } }

    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg)) {
        val wide = maxWidth >= 600.dp
        val opened = openPath
        val chain: @Composable (Modifier) -> Unit = { m ->
            ChainView(store, client, tr, devs.devices, wide, opened, { p -> openPath = p; client.op("device.open", "tr" to tr, "path" to p) }, m)
        }
        val paramsPane: @Composable (Modifier) -> Unit = { m ->
            ParamsPanel(store, client, tr, opened ?: "", { openPath = null; client.op("device.close") }, m)
        }
        when {
            opened == null -> chain(Modifier.fillMaxSize())
            wide -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                chain(Modifier.weight(1f).fillMaxHeight())
                paramsPane(Modifier.weight(1f).fillMaxHeight())
            }
            else -> paramsPane(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ChainView(
    store: LiveStore, client: LiveClient, tr: String, devices: List<Device>, wide: Boolean,
    openPath: String?, onOpen: (String) -> Unit, modifier: Modifier,
) {
    if (devices.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            LiveText(if (tr.isEmpty()) "No track selected" else "No devices on this track", color = LiveColors.TextDim)
        }
        return
    }
    val pad = PaddingValues(2.dp)
    if (wide) {
        LazyRow(modifier, contentPadding = pad, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(devices, key = { it.path }) { d ->
                Box(Modifier.width(216.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    DeviceCard(d, tr, store, client, 0, openPath, onOpen)
                }
            }
        }
    } else {
        LazyColumn(modifier, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(devices, key = { it.path }) { d -> DeviceCard(d, tr, store, client, 0, openPath, onOpen) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(
    d: Device, tr: String, store: LiveStore, client: LiveClient, depth: Int,
    openPath: String?, onOpen: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by remember(d.path) { mutableStateOf(setOf<Int>()) }
    val isOpen = openPath == d.path
    Column(
        Modifier.fillMaxWidth()
            .background(LiveColors.Panel, LiveShapes.Small)
            .border(1.dp, if (isOpen) LiveColors.Accent else LiveColors.Line, LiveShapes.Small)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DenseButton("ON", {
                store.updateDevices { s -> s.copy(devices = patchDevice(s.devices, d.path) { it.copy(on = !it.on) }) }
                client.op("device.toggle", "tr" to tr, "path" to d.path)
            }, on = d.on, modifier = Modifier.width(36.dp))
            LiveText(d.name.ifEmpty { d.cls }, Modifier.weight(1f), LiveType.Title)
            Box(Modifier.border(1.dp, LiveColors.Line, LiveShapes.Small).padding(horizontal = 4.dp, vertical = 1.dp)) {
                LiveText(typeBadge(d.type), style = LiveType.Label)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DenseButton("Params", { onOpen(d.path) }, Modifier.weight(1f), on = isOpen)
            DenseButton("Select", { client.op("device.select", "tr" to tr, "path" to d.path) }, Modifier.weight(1f))
            DenseButton("Del", { confirmDelete = true }, Modifier.weight(0.7f))
        }
        val macros = d.macros.orEmpty()
        if (macros.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (m in macros) {
                    Box(Modifier.width(52.dp)) {
                        LiveKnob(
                            value = m.v, valueRange = m.min..m.max.coerceAtLeast(m.min + 1e-3f), defaultValue = m.min,
                            knobSize = 34.dp, label = m.name.ifEmpty { "M${m.i + 1}" }, valueText = m.str,
                            onValueChange = { v ->
                                store.updateDevices { s ->
                                    s.copy(devices = patchDevice(s.devices, d.path) { dv ->
                                        dv.copy(macros = dv.macros?.map { if (it.i == m.i) it.copy(v = v) else it })
                                    })
                                }
                                client.opThrottled("dm:$tr:${d.path}:${m.i}", "device.param", "tr" to tr, "path" to d.path, "i" to m.i, "v" to v)
                            },
                        )
                    }
                }
            }
        }
        d.variations?.let { vr ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LiveLabel("Variations")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    fun v(act: String, i: Int? = null) =
                        client.op("device.var", "tr" to tr, "path" to d.path, "act" to act, "i" to i)
                    DenseButton("<", { if (vr.sel > 0) v("select", vr.sel - 1) }, enabled = vr.sel > 0)
                    Box(Modifier.height(28.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        LiveText(if (vr.count == 0) "none" else "${vr.sel + 1}/${vr.count}", style = LiveType.Small)
                    }
                    DenseButton(">", { if (vr.sel < vr.count - 1) v("select", vr.sel + 1) }, enabled = vr.sel < vr.count - 1)
                    DenseButton("Store", { v("store") })
                    DenseButton("Recall", { v("recall", vr.sel) }, enabled = vr.sel >= 0)
                    DenseButton("Del", { v("delete", vr.sel) }, enabled = vr.sel >= 0)
                }
            }
        }
        if (d.chains.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LiveLabel("Chains")
                for (c in d.chains) {
                    val open = c.i in expanded
                    DenseButton(
                        (if (open) "v  " else ">  ") + c.name.ifEmpty { "Chain ${c.i + 1}" },
                        {
                            expanded = if (open) expanded - c.i else expanded + c.i
                            client.op("chain.select", "tr" to tr, "path" to d.path, "i" to c.i)
                        },
                        Modifier.fillMaxWidth(), on = open,
                    )
                    if (open) {
                        Column(Modifier.padding(start = if (depth < 3) 8.dp else 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (c.devices.isEmpty()) LiveText("empty", color = LiveColors.TextDim, style = LiveType.Small)
                            for (nd in c.devices) DeviceCard(nd, tr, store, client, depth + 1, openPath, onOpen)
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete device", "Delete \"${d.name}\" from the track?", "Delete",
            { client.op("device.delete", "tr" to tr, "path" to d.path) }, { confirmDelete = false })
    }
}

private fun typeBadge(t: String) = when (t) {
    "instrument" -> "INST"; "audio_fx" -> "AUDIO FX"; "midi_fx" -> "MIDI FX"; "rack" -> "RACK"; else -> "DEV"
}

@Composable
private fun ParamsPanel(
    store: LiveStore, client: LiveClient, tr: String, path: String, onBack: () -> Unit, modifier: Modifier,
) {
    val ps by store.params.collectAsState()
    var filter by remember { mutableStateOf("") }
    Column(modifier.background(LiveColors.Bg).padding(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DenseButton("Back", onBack)
            LiveText(ps.name.ifEmpty { "Parameters" }, Modifier.weight(1f), LiveType.Title)
        }
        DenseTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = "Filter parameters")
        if (ps.path != path) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LiveText("Loading parameters...", color = LiveColors.TextDim) }
            return
        }
        val shown = remember(ps.params, filter) {
            if (filter.isBlank()) ps.params else ps.params.filter { it.name.contains(filter.trim(), ignoreCase = true) }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LiveText("No parameters", color = LiveColors.TextDim) }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(280.dp), Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(shown, key = { it.i }) { p -> ParamRow(p, tr, path, store, client) }
            }
        }
    }
}

@Composable
private fun ParamRow(p: Param, tr: String, path: String, store: LiveStore, client: LiveClient) {
    fun send(v: Float) {
        store.updateParams { s -> s.copy(params = s.params.map { if (it.i == p.i) it.copy(v = v) else it }) }
        client.opThrottled("dp:$tr:$path:${p.i}", "device.param", "tr" to tr, "path" to path, "i" to p.i, "v" to v)
    }
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LiveText(p.name, Modifier.weight(0.42f), LiveType.Small)
        val items = p.items
        if (p.quant && !items.isNullOrEmpty()) {
            val cur = (p.v - p.min).roundToInt().coerceIn(0, items.lastIndex)
            if (items.size <= 3) {
                Row(Modifier.weight(0.58f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.forEachIndexed { idx, s -> DenseButton(s, { send(p.min + idx) }, Modifier.weight(1f), on = idx == cur) }
                }
            } else {
                DenseSelect(p.name, items[cur], items, { idx, _ -> send(p.min + idx) }, Modifier.weight(0.58f))
            }
        } else {
            LiveFader(
                value = p.v, onValueChange = ::send, vertical = false,
                valueRange = p.min..p.max.coerceAtLeast(p.min + 1e-6f), defaultValue = p.min,
                label = p.str.ifEmpty { null }, modifier = Modifier.weight(0.58f).height(26.dp),
            )
        }
    }
}
