package com.livecompanion.ui.devices

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.livecompanion.net.LiveClient
import com.livecompanion.state.Device
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.DenseButton
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveIcon
import com.livecompanion.ui.components.LiveIconKind
import com.livecompanion.ui.components.LiveScrollColumn
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import kotlinx.coroutines.launch

/**
 * A left-to-right row of device panels joined by flow arrows. Hold a panel's title and drag to reorder
 * (`device.move`). [scroll] is the outer horizontal scroll (top-level chain only) and is nudged while a
 * dragged panel nears the viewport edge. Rack chains render recursively inside the rack's slot.
 */
@Composable
fun ChainRow(
    devices: List<Device>, tr: String, into: String, ui: ChainUi, store: LiveStore, client: LiveClient,
    focusKind: String, scroll: ScrollState?, viewportPx: Float, pending: List<PendingDevice>,
    modifier: Modifier = Modifier,
) {
    var from by remember { mutableIntStateOf(-1) }
    var dx by remember { mutableFloatStateOf(0f) }
    val lefts = remember { HashMap<Int, Float>() }
    val widths = remember { HashMap<Int, Float>() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val gapPx = with(density) { 16.dp.toPx() }
    val devsNow = rememberUpdatedState(devices)

    fun targetFor(f: Int): Int {
        if (f < 0) return -1
        val center = (lefts[f] ?: 0f) + dx + (widths[f] ?: 0f) / 2
        var t = 0
        for (j in devices.indices) if (j != f && (lefts[j] ?: 0f) + (widths[j] ?: 0f) / 2 < center) t++
        return t
    }
    val target = targetFor(from)

    // One reorder path for hold-drag and for the move buttons of the selected device.
    fun commitMove(f: Int, t: Int) {
        if (f !in devices.indices || t !in devices.indices || t == f) return
        store.updateDevices { st -> st.copy(devices = editList(st.devices, into) { l -> l.moved(f, t) }) }
        client.op("device.move", "tr" to tr, "path" to devsNow.value[f].path, "to" to t)
    }

    val start = pending.filter { it.atStart }
    val end = pending.filter { !it.atStart }
    Row(modifier.fillMaxHeight()) {
        for (p in start) { FlowArrow(); PendingPanel(p.name) }
        devices.forEachIndexed { i, d ->
            key(d.path) {
                val isDrag = from == i
                val shift = when {
                    from < 0 || isDrag -> 0f
                    from < target && i in (from + 1)..target -> -((widths[from] ?: 0f))
                    from > target && i in target until from -> (widths[from] ?: 0f)
                    else -> 0f
                }
                val handle = Modifier.pointerInput(d.path, devices.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { from = i; dx = 0f },
                        onDrag = { ch, delta ->
                            ch.consume()
                            dx += delta.x
                            if (scroll != null) {
                                val screenX = (lefts[i] ?: 0f) + dx - scroll.value
                                val w = widths[i] ?: 0f
                                val edge = 48f
                                val step = when {
                                    screenX < edge -> -22f
                                    screenX + w > viewportPx - edge -> 22f
                                    else -> 0f
                                }
                                if (step != 0f) scope.launch {
                                    val before = scroll.value
                                    scroll.scrollBy(step)
                                    dx += scroll.value - before
                                }
                            }
                        },
                        onDragEnd = {
                            val f = from
                            val t = targetFor(f)
                            from = -1; dx = 0f
                            if (f >= 0 && t >= 0) commitMove(f, t)
                        },
                        onDragCancel = { from = -1; dx = 0f },
                    )
                }
                Row(
                    Modifier
                        .onGloballyPositioned { lefts[i] = it.positionInParent().x; widths[i] = it.size.width.toFloat() }
                        .zIndex(if (isDrag) 2f else 0f)
                        .graphicsLayer { translationX = if (isDrag) dx else shift }
                        .alpha(if (isDrag) 0.92f else 1f)
                        .fillMaxHeight(),
                ) {
                    FlowArrow()
                    DevicePanel(d, tr, ui, into, store, client, handle, isDrag, index = i, count = devices.size, onShift = { dir -> commitMove(i, i + dir) })
                    val sel = ui.chainSel[d.path]
                    val chain = d.chains.firstOrNull { it.i == sel }
                    if (chain != null && ui.collapsed[d.path] != true) {
                        ChainGroup(chain.name.ifEmpty { "Chain ${chain.i + 1}" }) {
                            ChainRow(
                                chain.devices, tr, "${d.path}.c${chain.i}", ui, store, client, focusKind, null, 0f, emptyList(),
                            )
                        }
                    }
                }
            }
        }
        for (p in end) { FlowArrow(); PendingPanel(p.name) }
        FlowArrow()
        AddPanel(devices.isEmpty() && pending.isEmpty(), onClick = { ui.addInto = into })
        Box(Modifier.width(8.dp))
    }
}

/** Bordered group that holds a rack chain's devices, joined to the rack by a flow arrow. */
@Composable
private fun ChainGroup(title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxHeight()) {
        FlowArrow()
        Column(
            Modifier.fillMaxHeight().background(LiveColors.Bg, LiveShapes.Small).border(1.dp, LiveColors.Accent.copy(alpha = 0.5f), LiveShapes.Small).padding(2.dp),
        ) {
            LiveText(title, Modifier.padding(horizontal = 4.dp).height(14.dp), LiveType.Label)
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun AddPanel(empty: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(if (empty) 120.dp else 44.dp).height(if (empty) 84.dp else 70.dp).clip(LiveShapes.Small)
            .background(LiveColors.Panel.copy(alpha = 0.5f)).border(1.dp, LiveColors.Line, LiveShapes.Small)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        LiveIcon(LiveIconKind.Plus, LiveColors.Accent, size = 20.dp)
        LiveText(if (empty) "Add a device" else "Add", style = LiveType.Label, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Quick list of Live 12's native devices to insert at the end of the chain, plus a link to the Browser. */
@Composable
fun AddDeviceDialog(
    into: String, tr: String, focusKind: String, store: LiveStore, client: LiveClient,
    onBrowser: () -> Unit, onDismiss: () -> Unit,
) {
    val cats = if (focusKind == "midi") NativeCat.values().toList() else listOf(NativeCat.AudioFx)
    var cat by remember { mutableStateOf(cats.first()) }
    LiveDialog("Add device", onDismiss) {
        if (cats.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (c in cats) DenseButton(c.label, { cat = c }, Modifier.weight(1f), on = c == cat)
            }
        }
        LiveScrollColumn(Modifier.weight(1f, fill = false).height(200.dp)) {
            for (n in cat.devices) {
                Row(
                    Modifier.fillMaxWidth().height(32.dp).clip(LiveShapes.Small).background(LiveColors.PanelHi)
                        .clickable {
                            val idx = insertIndexFor(cat, into)
                            if (into.isEmpty()) DevicePending.add(store, tr, n, atStart = idx == 0)
                            client.op("device.insert", "tr" to tr, "name" to n, "i" to idx, "into" to into.ifEmpty { null })
                            onDismiss()
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { LiveText(n) }
            }
        }
        LiveDialogButtons {
            DenseButton("Browser...", { onDismiss(); onBrowser() })
            DenseButton("Cancel", onDismiss)
        }
    }
}
