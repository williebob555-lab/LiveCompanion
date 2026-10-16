package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Param
import com.livecompanion.ui.components.DenseButton
import com.livecompanion.ui.components.DenseSelect
import com.livecompanion.ui.components.DenseTextField
import com.livecompanion.ui.components.LiveFader
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.devices.AddDeviceDialog
import com.livecompanion.ui.devices.ChainRow
import com.livecompanion.ui.devices.ChainUi
import com.livecompanion.ui.devices.DevicePending
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Devices tab: the focused track as Live's horizontal SIGNAL CHAIN. Devices run left to right as panels with
 * inline knobs (macros for racks), flow arrows between them, hold-and-drag on a title to reorder, "+" at the
 * end to insert a native device. Optimistic "loading..." panels cover the gap after Load/insert.
 * The full parameter list of one device opens via its Params button (`device.open` / `device.close`).
 */
@Composable
fun DevicesScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val devs by store.devices.collectAsState()
    val focus by store.focus.collectAsState()
    val tr = devs.tr.ifEmpty { focus.ref }
    val ui = remember(tr) { ChainUi() }
    val openNow = rememberUpdatedState(ui.openPath)
    DisposableEffect(Unit) { onDispose { if (openNow.value != null) client.op("device.close") } }
    LaunchedEffect(tr) { client.op("device.close") }
    val goTab = LocalTabNavigator.current

    // Placeholders: drop them once the section has more devices than when they were queued, or after a timeout.
    val pend = DevicePending.of(store)
    LaunchedEffect(devs, pend.items.size) {
        pend.items.toList().forEach { p ->
            if (p.tr == devs.tr && devs.devices.size > p.base) pend.items.remove(p)
        }
    }
    LaunchedEffect(pend.items.size) {
        while (pend.items.isNotEmpty()) {
            delay(500)
            val now = System.currentTimeMillis()
            pend.items.toList().forEach { if (now - it.at > DevicePending.TIMEOUT_MS) pend.items.remove(it) }
        }
    }
    val mine = pend.items.filter { it.tr == tr }

    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg)) {
        val split = maxWidth >= 900.dp
        val opened = ui.openPath
        val viewportPx = with(LocalDensity.current) { (if (split && opened != null) maxWidth / 2 else maxWidth).toPx() }
        val chain: @Composable (Modifier) -> Unit = { m ->
            val scroll = rememberScrollState()
            if (tr.isEmpty()) {
                Box(m, contentAlignment = Alignment.Center) { LiveText("No track selected", color = LiveColors.TextDim) }
            } else {
                // Tablets: scale knobs / cells / rows with the available height so panels fill the screen.
                val scale = if (maxWidth >= 600.dp) (maxHeight / 330.dp).coerceIn(1f, 1.7f) else 1f
                androidx.compose.runtime.CompositionLocalProvider(com.livecompanion.ui.devices.LocalDeviceScale provides scale) {
                    Box(m.horizontalScroll(scroll).padding(2.dp)) {
                        ChainRow(devs.devices, tr, "", ui, store, client, focus.kind, scroll, viewportPx, mine)
                    }
                }
            }
        }
        val paramsPane: @Composable (Modifier) -> Unit = { m ->
            ParamsPanel(store, client, tr, opened ?: "", { ui.openPath = null; client.op("device.close") }, m)
        }
        when {
            opened == null -> chain(Modifier.fillMaxSize())
            split -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                chain(Modifier.weight(1f).fillMaxHeight())
                paramsPane(Modifier.weight(1f).fillMaxHeight())
            }
            else -> paramsPane(Modifier.fillMaxSize())
        }
    }
    ui.addInto?.let { into ->
        AddDeviceDialog(into, tr, focus.kind, store, client, { goTab(Tab.Browser) }, { ui.addInto = null })
    }
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
                valueRange = p.min..p.max.coerceAtLeast(p.min + 1e-6f), defaultValue = p.default ?: p.min,
                label = p.str.ifEmpty { null }, modifier = Modifier.weight(0.58f).height(26.dp),
            )
        }
    }
}
