package com.livecompanion.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.net.jsonArgs
import kotlinx.coroutines.launch
import com.livecompanion.state.Loc
import com.livecompanion.state.LiveStore
import com.livecompanion.state.SlotInfo
import com.livecompanion.state.SlotStatus
import com.livecompanion.state.TracksState
import com.livecompanion.ui.components.ColorChip
import com.livecompanion.ui.components.LiveButton
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveIcon
import com.livecompanion.ui.components.LiveIconButton
import com.livecompanion.ui.components.LiveIconKind
import com.livecompanion.ui.components.LiveLabel
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.components.LiveTextField
import com.livecompanion.ui.components.dim
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.components.onColor
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType

/**
 * Session tab: the focused track's slot column. Tap a cell to fire, square button to stop, ⋯ button for the
 * rare-action menu, hold a clip and drag it onto another slot to move it, big TAKE button runs `take.next`,
 * optional scene-launch column.
 */
@Composable
fun SessionScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val slotsState by store.slots.collectAsState()
    val tracks by store.tracks.collectAsState()
    val focus by store.focus.collectAsState()
    val rt = store.rt.collectAsState()
    var showScenes by remember { mutableStateOf(true) }
    var menuSlot by remember { mutableIntStateOf(-1) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var pitchPx by remember { mutableFloatStateOf(0f) }
    var action by remember { mutableStateOf<SlotAction?>(null) }
    var pending by remember { mutableStateOf<PendingMove?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Move/copy; an occupied target never gets silently overwritten: ask (Replace / Swap / Cancel) first.
    fun transfer(fromTr: String, fromI: Int, toTr: String, toI: Int, move: Boolean) {
        val known = if (toTr == slotsState.tr) slotsState.slots.firstOrNull { it.i == toI } else null
        if (known?.hasClip == true) { pending = PendingMove(fromTr, fromI, toTr, toI, move, known.clip?.name ?: ""); return }
        scope.launch {
            try {
                client.request(if (move) "slot.move" else "slot.copy", jsonArgs("tr" to fromTr, "i" to fromI, "to_tr" to toTr, "to_i" to toI))
            } catch (e: com.livecompanion.net.LiveException) {
                if (e.message?.contains("occupied") == true) pending = PendingMove(fromTr, fromI, toTr, toI, move, "")
                else store.notice(e.message ?: "Move failed")
            }
        }
    }
    val tr = slotsState.tr.ifEmpty { focus.ref }
    val goTab = LocalTabNavigator.current

    val recording = slotsState.slots.any { it.status == SlotStatus.RECORDING && it.hasClip }
    val takeCount = slotsState.slots.count { it.hasClip && it.clip?.name?.startsWith("Take ") == true }

    Column(modifier.fillMaxSize().background(LiveColors.Bg)) {
        Row(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(1.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveButton(
                onClick = { client.op("take.next", "tr" to tr) },
                modifier = Modifier.weight(1.4f),
                minHeight = 32.dp,
                onColor = LiveColors.Rec,
                offColor = LiveColors.Rec.copy(alpha = 0.85f),
                enabled = tr.isNotEmpty() && focus.armable, // group / return / master tracks cannot record
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LiveIcon(LiveIconKind.Rec, Color.White, size = 12.dp)
                    LiveText(if (recording) "NEXT" else "TAKE", style = LiveType.Title, color = Color.White)
                    LiveIcon(LiveIconKind.Play, Color.White, size = 11.dp)
                    if (recording) LiveText("REC", style = LiveType.Label, color = Color.White)
                    if (takeCount > 0) LiveText("$takeCount", style = LiveType.Label, color = Color.White.copy(alpha = 0.8f))
                }
            }
            LiveTextButton("Stop", { client.op("track.stop", "tr" to tr) }, Modifier.weight(0.8f), minHeight = 32.dp, enabled = tr.isNotEmpty())
            LiveTextButton("Scenes", { showScenes = !showScenes }, Modifier.weight(0.9f), on = showScenes, minHeight = 32.dp)
        }

        val slots = slotsState.slots
        if (slots.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LiveText(if (tr.isEmpty()) "No track selected" else "No slots", style = LiveType.Body, color = LiveColors.TextDim)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                state = rememberLazyListState(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(1.dp),
            ) {
                items(slots, key = { it.i }) { slot ->
                    SlotRow(
                        slot = slot,
                        sceneLabel = tracks.scenes.getOrNull(slot.i)?.name?.ifBlank { null } ?: "${slot.i + 1}",
                        showScene = showScenes,
                        armed = focus.arm,
                        slotPos = { rt.value.slotPos[slot.i.toString()] },
                        onFire = {
                            client.op("slot.fire", "tr" to tr, "i" to slot.i)
                            store.patchSlot(slot.i) {
                                it.copy(status = if (!it.hasClip && focus.arm) SlotStatus.RECORDING else if (it.hasClip) SlotStatus.TRIGGERED else it.status)
                            }
                        },
                        onStop = {
                            client.op("clip.stop", "loc" to Loc.slot(tr, slot.i))
                            store.patchSlot(slot.i) { it.copy(status = SlotStatus.STOPPED) }
                        },
                        onSceneFire = { client.op("scene.fire", "i" to slot.i) },
                        onMenu = { menuSlot = slot.i },
                        dragOffset = if (dragFrom == slot.i) dragDy else 0f,
                        isDropTarget = dragFrom >= 0 && dragFrom != slot.i && slot.i == dragTarget(dragFrom, dragDy, pitchPx, slots.size),
                        dropReplaces = slot.hasClip,
                        onMeasured = { pitchPx = it },
                        onDragStart = { dragFrom = slot.i; dragDy = 0f },
                        onDrag = { dragDy += it },
                        onDragEnd = {
                            val to = dragTarget(dragFrom, dragDy, pitchPx, slots.size)
                            if (dragFrom >= 0 && to != dragFrom) transfer(tr, dragFrom, tr, to, true)
                            dragFrom = -1; dragDy = 0f
                        },
                    )
                }
                item(key = "add-scene") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiveTextButton("+ Scene", { client.op("scene.create", "i" to slots.size) }, Modifier.weight(1f))
                        LiveText(
                            "Hold a clip and drag to move it",
                            Modifier.weight(1.6f).padding(horizontal = 6.dp), LiveType.Label, LiveColors.TextDim, maxLines = 2,
                        )
                    }
                }
            }
        }
    }

    val menu = slotsState.slots.firstOrNull { it.i == menuSlot }
    if (menu != null) {
        SlotMenuDialog(
            slot = menu,
            onDismiss = { menuSlot = -1 },
            onPick = { a ->
                menuSlot = -1
                when (a) {
                    is SlotAction.Duplicate -> client.op("slot.duplicate", "tr" to tr, "i" to menu.i)
                    is SlotAction.Delete -> action = SlotAction.ConfirmDelete(menu.i)
                    is SlotAction.Edit -> { client.op("clip.open", "loc" to Loc.slot(tr, menu.i)); goTab(Tab.Clip) }
                    is SlotAction.Create -> client.op("slot.create", "tr" to tr, "i" to menu.i, "len" to a.len)
                    else -> action = a
                }
            },
        )
    }
    when (val a = action) {
        is SlotAction.Rename -> {
            val slot = slotsState.slots.firstOrNull { it.i == a.i }
            RenameDialog(slot?.clip?.name ?: "", onDismiss = { action = null }) { name ->
                client.op("clip.set", "loc" to Loc.slot(tr, a.i), "k" to "name", "v" to name)
                store.patchSlot(a.i) { it.copy(clip = it.clip?.copy(name = name)) }
                action = null
            }
        }
        is SlotAction.CopyTo -> TargetDialog("Copy to", tr, a.i, tracks, onDismiss = { action = null }) { toTr, toI ->
            transfer(tr, a.i, toTr, toI, false); action = null
        }
        is SlotAction.MoveTo -> TargetDialog("Move to", tr, a.i, tracks, onDismiss = { action = null }) { toTr, toI ->
            transfer(tr, a.i, toTr, toI, true); action = null
        }
        is SlotAction.ConfirmDelete -> com.livecompanion.ui.components.ConfirmDialog("Delete clip", "Delete this clip from slot ${a.i + 1}? You can undo this in Live.", "Delete",
            onConfirm = { client.op("slot.delete", "tr" to tr, "i" to a.i); action = null }, onDismiss = { action = null })
        else -> Unit
    }
    pending?.let { p ->
        ReplaceMoveDialog(
            p, onCancel = { pending = null },
            onSwap = {
                client.op("slot.move", "tr" to p.fromTr, "i" to p.fromI, "to_tr" to p.toTr, "to_i" to p.toI, "swap" to true); pending = null
            },
            onReplace = {
                client.op(if (p.move) "slot.move" else "slot.copy", "tr" to p.fromTr, "i" to p.fromI, "to_tr" to p.toTr, "to_i" to p.toI, "replace" to true)
                pending = null
            },
        )
    }
}

/** Asked once when a move/copy lands on an occupied slot (never overwrite silently). */
@Composable
internal fun ReplaceMoveDialog(p: PendingMove, onCancel: () -> Unit, onSwap: () -> Unit, onReplace: () -> Unit) {
    LiveDialog(
        "Replace?", onDismiss = onCancel,
        buttons = {
            LiveTextButton("Cancel", onCancel, minWidth = 64.dp)
            if (p.move) LiveTextButton("Swap", onSwap, minWidth = 64.dp)
            LiveTextButton("Replace", onReplace, on = true, onColor = LiveColors.Rec, minWidth = 64.dp)
        },
    ) {
        LiveText(
            "Slot ${p.toI + 1}" + (if (p.toName.isNotEmpty()) " (${p.toName})" else "") + " already has a clip." +
                if (p.move) " Replace it (the old clip is lost), swap the two clips, or cancel?" else " Replace it (the old clip is lost) or cancel?",
            style = LiveType.Body, maxLines = 4,
        )
    }
}

internal data class PendingMove(val fromTr: String, val fromI: Int, val toTr: String, val toI: Int, val move: Boolean, val toName: String)

sealed interface SlotAction {
    data object Duplicate : SlotAction
    data object Delete : SlotAction
    data object Edit : SlotAction
    data class ConfirmDelete(val i: Int) : SlotAction
    data class Create(val len: Double) : SlotAction
    data class Rename(val i: Int) : SlotAction
    data class CopyTo(val i: Int) : SlotAction
    data class MoveTo(val i: Int) : SlotAction
}

/** Slot index a dragged row currently hovers (row pitch = measured row height + 1dp gap). */
private fun dragTarget(from: Int, dy: Float, pitch: Float, count: Int): Int =
    if (from < 0 || pitch <= 0f) from else (from + (dy / pitch).roundToInt()).coerceIn(0, (count - 1).coerceAtLeast(0))

@Composable
private fun SlotRow(
    slot: SlotInfo,
    sceneLabel: String,
    showScene: Boolean,
    armed: Boolean,
    slotPos: () -> Double?,
    onFire: () -> Unit,
    onStop: () -> Unit,
    onSceneFire: () -> Unit,
    onMenu: () -> Unit,
    dragOffset: Float,
    isDropTarget: Boolean,
    dropReplaces: Boolean,
    onMeasured: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val clip = slot.clip
    val status = if (!slot.hasClip) SlotStatus.EMPTY else slot.status
    val base = if (clip != null && slot.hasClip) liveColor(clip.color) else LiveColors.Panel
    val active = status == SlotStatus.PLAYING || status == SlotStatus.RECORDING || status == SlotStatus.TRIGGERED
    val cellBg = when {
        !slot.hasClip -> LiveColors.Panel
        active -> base
        else -> dim(base, 0.7f)
    }
    val fg = if (!slot.hasClip) LiveColors.TextDim else onColor(cellBg)
    val density = androidx.compose.ui.platform.LocalDensity.current
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 32.dp)
            .onSizeChanged { onMeasured(it.height + with(density) { 1.dp.toPx() }) }
            .graphicsLayer { translationY = dragOffset; alpha = if (dragOffset != 0f) 0.85f else 1f }
            .then(if (isDropTarget) Modifier.border(2.dp, if (dropReplaces) LiveColors.Rec else LiveColors.Accent, LiveShapes.Small) else Modifier)
            .then(if (isDropTarget && dropReplaces) Modifier.background(LiveColors.Rec.copy(alpha = 0.25f)) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (showScene) {
            val sceneW = 58.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            LiveButton(onSceneFire, Modifier.width(sceneW).fillMaxHeight(), minHeight = 32.dp, minWidth = sceneW, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)) { c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    LiveIcon(LiveIconKind.Play, c, size = 9.dp)
                    LiveText(sceneLabel, style = LiveType.Small, color = c)
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .heightIn(min = 32.dp)
                .clip(LiveShapes.Small)
                .background(cellBg)
                .then(if (status == SlotStatus.TRIGGERED) Modifier.border(2.dp, LiveColors.Accent, LiveShapes.Small) else Modifier)
                .clickable(onClick = onFire)
                .then(
                    if (slot.hasClip) Modifier.pointerInput(slot.i) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, amount -> change.consume(); onDrag(amount.y) },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd,
                        )
                    } else Modifier
                )
                .then(
                    if (status == SlotStatus.PLAYING && clip != null && clip.len > 0) {
                        Modifier.drawBehind {
                            val p = slotPos()
                            if (p != null) {
                                val frac = ((p % clip.len) / clip.len).toFloat().coerceIn(0f, 1f)
                                drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, size.height - 3f), Size(size.width * frac, 3f))
                            }
                        }
                    } else Modifier
                )
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    when (status) {
                        SlotStatus.PLAYING -> Box(Modifier.size(18.dp).background(LiveColors.Play, LiveShapes.Small), contentAlignment = Alignment.Center) {
                            LiveIcon(LiveIconKind.Play, Color.Black, size = 11.dp)
                        }
                        SlotStatus.TRIGGERED -> LiveIcon(LiveIconKind.Play, LiveColors.Accent, size = 12.dp)
                        SlotStatus.RECORDING -> LiveIcon(LiveIconKind.Rec, LiveColors.Rec, size = 14.dp)
                        SlotStatus.STOPPED -> LiveIcon(LiveIconKind.Play, fg, size = 11.dp)
                        else -> if (armed) LiveIcon(LiveIconKind.Rec, LiveColors.Rec.copy(alpha = 0.6f), size = 11.dp)
                    }
                }
                LiveText(
                    clip?.name?.ifEmpty { if (clip.audio) "Audio" else "MIDI" } ?: "",
                    Modifier.weight(1f, fill = false),
                    LiveType.Small,
                    fg,
                )
                if (isDropTarget && dropReplaces) LiveText("Replace?", style = LiveType.Title, color = Color.White, modifier = Modifier.background(LiveColors.Rec, LiveShapes.Small).padding(horizontal = 4.dp))
                if (status == SlotStatus.RECORDING) {
                    // Elapsed recording length in bars (4/4 assumed for the label), from rt or the clip length.
                    val beats = slotPos() ?: clip?.len ?: 0.0
                    LiveText("%.1f bars".format(java.util.Locale.US, beats / 4.0), style = LiveType.Label, color = fg)
                }
            }
        }
        LiveIconButton(
            LiveIconKind.Stop, onStop, Modifier.fillMaxHeight(),
            enabled = active, minHeight = 32.dp,
        )
        LiveTextButton("\u22EF", onMenu, Modifier.fillMaxHeight(), minHeight = 32.dp)
    }
}

@Composable
private fun SlotMenuDialog(slot: SlotInfo, onDismiss: () -> Unit, onPick: (SlotAction) -> Unit) {
    LiveDialog("Slot ${slot.i + 1}" + (slot.clip?.name?.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""), onDismiss) {
        val full = Modifier.fillMaxWidth()
        if (slot.hasClip) {
            LiveTextButton("Edit clip", { onPick(SlotAction.Edit) }, full)
            LiveTextButton("Rename", { onPick(SlotAction.Rename(slot.i)) }, full)
            LiveTextButton("Duplicate", { onPick(SlotAction.Duplicate) }, full)
            LiveTextButton("Copy to...", { onPick(SlotAction.CopyTo(slot.i)) }, full)
            LiveTextButton("Move to...", { onPick(SlotAction.MoveTo(slot.i)) }, full)
            LiveTextButton("Delete", { onPick(SlotAction.Delete) }, full, on = true, onColor = LiveColors.Rec)
        } else {
            LiveLabel("Create clip (beats)")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (len in listOf(1.0, 2.0, 4.0, 8.0, 16.0)) {
                    LiveTextButton(len.toInt().toString(), { onPick(SlotAction.Create(len)) }, Modifier.weight(1f), minWidth = 0.dp)
                }
            }
        }
        LiveDialogButtons { LiveTextButton("Cancel", onDismiss, minWidth = 72.dp) }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    LiveDialog("Rename clip", onDismiss) {
        LiveTextField(text, { text = it }, onDone = { onOk(text) })
        LiveDialogButtons {
            LiveTextButton("Cancel", onDismiss, minWidth = 72.dp)
            LiveTextButton("OK", { onOk(text) }, on = true, minWidth = 72.dp)
        }
    }
}

/** Pick a destination track and slot number for copy/move. */
@Composable
internal fun TargetDialog(
    title: String,
    fromTr: String,
    fromI: Int,
    tracks: TracksState,
    onDismiss: () -> Unit,
    onOk: (String, Int) -> Unit,
) {
    var toTr by remember { mutableStateOf(fromTr) }
    var toI by remember { mutableIntStateOf(fromI + 1) }
    LiveDialog(
        title, onDismiss,
        buttons = {
            LiveTextButton("Cancel", onDismiss, minWidth = 72.dp)
            LiveTextButton("OK", { onOk(toTr, toI) }, on = true, minWidth = 72.dp)
        },
    ) {
        LiveLabel("Track")
        LazyColumn(Modifier.heightIn(max = if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500) 120.dp else 220.dp)) {
            items(tracks.tracks, key = { it.ref }) { t ->
                val sel = t.ref == toTr
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp)
                        .background(if (sel) LiveColors.PanelHi else LiveColors.Panel)
                        .clickable { toTr = t.ref }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ColorChip(t.color)
                    LiveText(t.name, Modifier.weight(1f), LiveType.Body, if (sel) LiveColors.Accent else LiveColors.Text)
                }
            }
        }
        LiveLabel("Slot")
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveTextButton("-", { toI = (toI - 1).coerceAtLeast(0) })
            Box(Modifier.width(56.dp).heightIn(min = 40.dp).background(LiveColors.Bg, LiveShapes.Small), contentAlignment = Alignment.Center) {
                LiveText("${toI + 1}", style = LiveType.Mono)
            }
            LiveTextButton("+", { toI += 1 })
        }
    }
}
