package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.livecompanion.net.LiveException
import com.livecompanion.net.jsonArgs
import com.livecompanion.ui.components.detectHoldDrag
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.state.TrackInfo
import com.livecompanion.ui.components.ConfirmDialog
import com.livecompanion.ui.components.DenseTextField
import com.livecompanion.ui.components.nameError
import com.livecompanion.ui.components.LiveButton
import com.livecompanion.ui.components.LivePalette
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.components.LiveIconButton
import com.livecompanion.ui.components.LiveIconKind
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType

/**
 * Tracks tab: every track / return / master with color, name, arm / M / S. Tap the row to focus the track, tap the
 * name to switch to it, the pencil to rename it; "Edit" expands the row (color palette, duplicate, delete with confirm). Tapping anywhere
 * else on a row (name included) switches to that track.
 *
 * REORDER: hold-drag the grip at the row start (or the up/down buttons on the focused row). The order changes locally
 * at once; on drop `track.move_check` runs, a ONE-dialog loss warning follows if the rebuild-move would drop
 * something, then `track.move` (force when confirmed). The row says "Moving..." until the tracks section arrives.
 * Groups, returns and master have no grip (tapping the empty grip slot explains why).
 */
@Composable
fun TracksScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null) {
    val tracks by store.tracks.collectAsState()
    val focus by store.focus.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<TrackInfo?>(null) }
    val scope = rememberCoroutineScope()
    // Reorder state: `order` = optimistic order of the plain tracks (drag + until the server's section arrives).
    var order by remember { mutableStateOf<List<TrackInfo>?>(null) }
    var dragRef by remember { mutableStateOf<String?>(null) }
    var movingRef by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<MoveConfirm?>(null) }
    var dragFrom by remember { mutableStateOf(-1) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragAcc by remember { mutableStateOf(Offset.Zero) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val shownTracks = order ?: tracks.tracks

    fun settle() { order = null; movingRef = null; dragRef = null; confirm = null }

    fun sig(l: List<TrackInfo>) = l.map { "${it.name}|${it.kind}|${it.color}" }

    suspend fun runMove(t: TrackInfo, to: Int, force: Boolean) {
        movingRef = t.ref
        try {
            client.request("track.move", jsonArgs("tr" to t.ref, "to" to to, "force" to force), 20000)
            val want = order?.let { sig(it) }
            if (want != null) withTimeoutOrNull(2500) { store.tracks.first { sig(it.tracks) == want } }
            settle()
        } catch (e: LiveException) {
            settle(); store.notice(e.message ?: "Move failed")
        }
    }

    fun drop(t: TrackInfo, from: Int, to: Int) {
        if (to == from) { settle(); return }
        movingRef = t.ref
        scope.launch {
            try {
                val chk = client.request("track.move_check", jsonArgs("tr" to t.ref)) as? JsonObject
                val losses = (chk?.get("losses") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                if (losses.isEmpty()) runMove(t, to, false) else confirm = MoveConfirm(t, to, losses)
            } catch (e: LiveException) {
                settle(); store.notice(e.message ?: "Move failed")
            }
        }
    }

    /** Button alternative to the drag: move the track one place up (-1) or down (+1). */
    fun nudge(t: TrackInfo, dir: Int) {
        if (movingRef != null) return
        val cur = tracks.tracks
        val from = cur.indexOfFirst { it.ref == t.ref }
        val to = from + dir
        if (from < 0 || to !in cur.indices) return
        order = cur.toMutableList().also { val x = it.removeAt(from); it.add(to, x) }
        drop(t, from, to)
    }

    fun retarget() {
        val cur = order ?: return
        val dr = dragRef ?: return
        val p = dragStart + dragAcc
        val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { i ->
            i.key != dr && p.x >= i.offset.x && p.x <= i.offset.x + i.size.width && p.y >= i.offset.y && p.y <= i.offset.y + i.size.height
        } ?: return
        val from = cur.indexOfFirst { it.ref == dr }
        if (from >= 0 && hit.index != from && hit.index < cur.size) order = cur.toMutableList().also { val x = it.removeAt(from); it.add(hit.index, x) }
    }

    fun dragBegin(t: TrackInfo) {
        if (movingRef != null) return
        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == t.ref } ?: return
        dragFrom = tracks.tracks.indexOfFirst { it.ref == t.ref }
        dragStart = Offset(info.offset.x + info.size.width / 2f, info.offset.y + info.size.height / 2f)
        dragAcc = Offset.Zero
        expanded = null; renaming = null
        order = tracks.tracks
        dragRef = t.ref
    }

    fun dragMove(d: Offset) { if (dragRef != null) { dragAcc += d; retarget() } }

    fun dragEnd() {
        val dr = dragRef ?: return
        val cur = order ?: tracks.tracks
        val to = cur.indexOfFirst { it.ref == dr }
        val t = cur.firstOrNull { it.ref == dr }
        dragRef = null
        if (t == null || to < 0) settle() else drop(t, dragFrom, to)
    }

    // Edge auto-scroll while dragging (keeps retargeting under a still finger).
    LaunchedEffect(dragRef) {
        while (dragRef != null) {
            val p = dragStart + dragAcc
            val h = gridState.layoutInfo.viewportSize.height
            val dy = if (p.y < 40f) -14f else if (h > 0 && p.y > h - 40f) 14f else 0f
            if (dy != 0f) { gridState.scrollBy(dy); retarget() }
            delay(16)
        }
    }
    // In the picker dialog the rename happens in its own small dialog (inline rename collapsed under the soft keyboard).
    var renameDlg by remember { mutableStateOf<TrackInfo?>(null) }

    fun applyRename(t: TrackInfo, name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && n != t.name) {
            client.op("track.set", "tr" to t.ref, "k" to "name", "v" to n)
            store.patchTrack(t.ref) { it.copy(name = n) }
            if (t.ref == focus.ref) store.updateFocus { it.copy(name = n) }
        }
    }

    fun focusOn(ref: String) {
        if (ref != focus.ref) {
            store.focusPending(ref)
            client.op("focus.set", "tr" to ref)
        }
        onFocused?.invoke()
    }

    fun insertIndex(): Int {
        val i = tracks.tracks.indexOfFirst { it.ref == focus.ref }
        return if (i >= 0) i + 1 else -1
    }

    var gripNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gripNote) { if (gripNote != null) { delay(4000); gripNote = null } }

    val rowFor: @Composable (TrackInfo) -> Unit = { t ->
        val movable = t.kind == "audio" || t.kind == "midi"
        val idx = shownTracks.indexOfFirst { it.ref == t.ref }
        TrackRow(
            t = t,
            movable = movable && idx >= 0,
            dragging = dragRef == t.ref,
            moving = movingRef == t.ref,
            canUp = movingRef == null && idx > 0,
            canDown = movingRef == null && idx >= 0 && idx < shownTracks.size - 1,
            onUp = { nudge(t, -1) },
            onDown = { nudge(t, 1) },
            onGripBegin = { dragBegin(t) },
            onGripDrag = { dragMove(it) },
            onGripEnd = { dragEnd() },
            onGripBlocked = {
                val why = when (t.kind) {
                    "group" -> "Group tracks can't be moved here - move them in Live"
                    "return" -> "Return tracks can't be reordered"
                    "master" -> "The master track can't be moved"
                    else -> "This track can't be moved"
                }
                gripNote = why   // shown inline: the picker popup covers the shell's notice bar
                store.notice(why)
            },
            focused = t.ref == focus.ref,
            open = expanded == t.ref,
            renaming = renaming == t.ref,
            onFocus = { focusOn(t.ref) },
            onToggleOpen = { renaming = null; expanded = if (expanded == t.ref) null else t.ref },
            // one editor at a time (exclusive with the Edit panel); renaming never changes the focused track
            onStartRename = { expanded = null; if (onFocused != null) renameDlg = t else renaming = t.ref },
            onRename = { name -> renaming = null; applyRename(t, name) },
            onCancelRename = { renaming = null },
            onToggle = { k, v ->
                client.op("track.set", "tr" to t.ref, "k" to k, "v" to v)
                store.patchTrack(t.ref) {
                    when (k) {
                        "arm" -> it.copy(arm = v); "mute" -> it.copy(mute = v); else -> it.copy(solo = v)
                    }
                }
                if (t.ref == focus.ref) store.updateFocus {
                    when (k) {
                        "arm" -> it.copy(arm = v); "mute" -> it.copy(mute = v); else -> it.copy(solo = v)
                    }
                }
            },
            onColor = { c ->
                expanded = null   // the panel closes after the action; colour pick never moves focus
                client.op("track.set", "tr" to t.ref, "k" to "color", "v" to c)
                store.patchTrack(t.ref) { it.copy(color = c) }
                if (t.ref == focus.ref) store.updateFocus { it.copy(color = c) }
            },
            onDuplicate = { expanded = null; client.op("track.duplicate", "tr" to t.ref) },
            onDelete = { deleting = t },
        )
    }

    Column(modifier.then(if (onFocused == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).background(LiveColors.Bg), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(1.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveTextButton("+ Audio", { client.op("track.create", "kind" to "audio", "i" to insertIndex()) }, Modifier.weight(1f))
            LiveTextButton("+ MIDI", { client.op("track.create", "kind" to "midi", "i" to insertIndex()) }, Modifier.weight(1f))
            LiveTextButton("+ Return", { client.op("track.create", "kind" to "return") }, Modifier.weight(1f))
        }
        gripNote?.let { n -> LiveText(n, Modifier.fillMaxWidth().background(LiveColors.Panel).padding(horizontal = 8.dp, vertical = 4.dp), LiveType.Small, LiveColors.Warn) }
        val rows = shownTracks + tracks.returns + listOfNotNull(tracks.master)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LiveText("No tracks", style = LiveType.Body, color = LiveColors.TextDim)
            }
        } else {
            val master = rows.firstOrNull { it.kind == "master" }
            val scrolling = if (master != null) rows.filter { it !== master } else rows
            BoxWithConstraints(Modifier.fillMaxWidth().then(if (onFocused == null) Modifier.weight(1f) else Modifier.heightIn(max = 420.dp))) {
                // Wide screens (tablets): rows flow into 2-3 columns instead of ~1800px-wide rows.
                val cols = (maxWidth / 440.dp).toInt().coerceIn(1, 3)
                // Pin Master only if >= 5 rows still fit above it; on short screens it scrolls with the list (rows keep their height).
                val pinMaster = master != null && maxHeight >= 38.dp * 5 + 44.dp
                val listRows = if (pinMaster) scrolling else rows
                // Opening Edit brings that row to the top of the list so its action row is visible without scrolling.
                androidx.compose.runtime.LaunchedEffect(expanded) {
                    val i = listRows.indexOfFirst { it.ref == expanded }
                    if (i >= 0) gridState.scrollToItem(i)
                }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        androidx.compose.foundation.lazy.grid.GridCells.Fixed(cols),
                        Modifier.fillMaxWidth().weight(1f),
                        state = gridState,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        contentPadding = PaddingValues(1.dp),
                    ) {
                        items(listRows, key = { it.ref }) { t -> rowFor(t) }
                    }
                    // Master stays pinned below the list so it is never below the fold.
                    if (pinMaster && master != null) Box(Modifier.fillMaxWidth().padding(1.dp)) { rowFor(master) }
                }
                // While a move is in flight the optimistic rows carry stale refs: nothing may be tapped.
                if (movingRef != null) Box(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
                })
            }
        }
    }

    renameDlg?.let { t ->
        com.livecompanion.ui.components.NameDialog("Rename track", t.name, { renameDlg = null }) { n -> renameDlg = null; applyRename(t, n) }
    }

    confirm?.let { c ->
        val done = remember(c) { booleanArrayOf(false) }
        ConfirmDialog(
            "Move track", "Moving \"${c.t.name}\" will lose: ${c.losses.joinToString(", ")}.", "Move anyway",
            onConfirm = { done[0] = true; scope.launch { runMove(c.t, c.to, true) } },
            onDismiss = { if (!done[0]) settle() else confirm = null },
        )
    }

    deleting?.let { t ->
        ConfirmDialog(
            "Delete track", "Delete \"${t.name}\" and everything on it? You can undo this in Live.", "Delete",
            onConfirm = {
                if (expanded == t.ref) expanded = null
                client.op("track.delete", "tr" to t.ref)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun TrackRow(
    t: TrackInfo,
    movable: Boolean,
    dragging: Boolean,
    moving: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onGripBegin: () -> Unit,
    onGripDrag: (Offset) -> Unit,
    onGripEnd: () -> Unit,
    onGripBlocked: () -> Unit,
    focused: Boolean,
    open: Boolean,
    renaming: Boolean,
    onFocus: () -> Unit,
    onToggleOpen: () -> Unit,
    onStartRename: () -> Unit,
    onRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onColor: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val master = t.kind == "master"
    val ret = t.kind == "return"
    Column(
        Modifier.fillMaxWidth().clip(LiveShapes.Small)
            .background(if (focused || dragging) LiveColors.PanelHi else LiveColors.Panel)
            .then(if (dragging) Modifier.border(2.dp, LiveColors.Accent, LiveShapes.Small)
                  else if (focused) Modifier.border(1.dp, LiveColors.Accent, LiveShapes.Small) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 36.dp).clickable(onClick = onFocus),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.width(if (t.group != null) 14.dp else 0.dp))
            // Reorder grip: hold ~0.3 s, then drag. Rows that cannot move have an empty slot that explains why when tapped.
            val begin by rememberUpdatedState(onGripBegin)
            val drag by rememberUpdatedState(onGripDrag)
            val end by rememberUpdatedState(onGripEnd)
            Box(
                Modifier.width(24.dp).fillMaxHeight().heightIn(min = 36.dp).testTag("grip_${t.ref}")
                    .then(if (movable) Modifier.pointerInput(t.ref) {
                        detectHoldDrag({ begin() }, { _, d -> drag(Offset(d.x, d.y)) }, { end() })
                    } else Modifier.clickable(onClick = onGripBlocked)),
                contentAlignment = Alignment.Center,
            ) { if (movable) LiveText(if (dragging) "≡" else "⋮⋮", style = LiveType.Body, color = if (dragging) LiveColors.Accent else LiveColors.TextDim, softWrap = false) else LiveText("·", style = LiveType.Body, color = LiveColors.TextDim.copy(alpha = 0.4f), softWrap = false) }
            Box(Modifier.width(6.dp).fillMaxHeight().heightIn(min = 36.dp).background(liveColor(t.color)))
            if (renaming) {
                RenameEditor(t.name, onRename, onCancelRename, key = t.ref)
            } else {
                Box(
                    Modifier.weight(1f).fillMaxHeight().heightIn(min = 36.dp)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    LiveText(t.name.ifEmpty { "-" }, style = LiveType.Body, color = if (focused) LiveColors.Accent else LiveColors.Text)
                }
                if (!master) LiveTextButton("✎", onStartRename, minHeight = 32.dp, minWidth = 30.dp)
                LiveText(
                    if (moving) "Moving..." else when (t.kind) { "midi" -> "MIDI"; "audio" -> "AUDIO"; "group" -> "GROUP"; "return" -> "RET"; else -> "MAIN" },
                    Modifier.width((if (moving) 64.dp else 40.dp) * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f)), style = LiveType.Label,
                    color = if (moving) LiveColors.Accent else LiveColors.Text, maxLines = 1, softWrap = false,
                )
                if (focused && movable) {
                    LiveTextButton("▲", onUp, enabled = canUp, minHeight = 32.dp, minWidth = 30.dp)
                    LiveTextButton("▼", onDown, enabled = canDown, minHeight = 32.dp, minWidth = 30.dp)
                }
                if (t.armable) {
                    LiveIconButton(LiveIconKind.Rec, { onToggle("arm", !t.arm) }, Modifier, t.arm, LiveColors.Rec, minHeight = 32.dp, minWidth = 32.dp)
                }
                if (!master) {
                    LiveTextButton("M", { onToggle("mute", !t.mute) }, on = t.mute, onColor = LiveColors.MuteOn, minHeight = 32.dp, minWidth = 32.dp)
                    LiveTextButton("S", { onToggle("solo", !t.solo) }, on = t.solo, onColor = LiveColors.Solo, minHeight = 32.dp, minWidth = 32.dp)
                }
                LiveTextButton(if (open) "Edit ▴" else "Edit ▾", onToggleOpen, on = open, minHeight = 32.dp, minWidth = 56.dp)
            }
            Box(Modifier.width(2.dp))
        }
        if (open && !renaming) {
            // Actions first (always visible, no scrolling); the palette sits behind a "Color" chip.
            var showColor by remember(t.ref) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LiveTextButton("Rename", onStartRename, Modifier.weight(1f), enabled = !master)
                    LiveTextButton("Duplicate", onDuplicate, Modifier.weight(1f), enabled = !master && !ret)
                    LiveTextButton("Delete", onDelete, Modifier.weight(1f), on = !master, onColor = LiveColors.Rec, enabled = !master)
                    if (!master) LiveButton(
                        { showColor = !showColor }, Modifier.weight(1f), on = showColor, minHeight = 32.dp,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(10.dp).clip(LiveShapes.Small).background(liveColor(t.color)))
                            LiveText(if (showColor) "Color ▴" else "Color ▾", style = LiveType.Small, color = c, softWrap = false)
                        }
                    }
                }
                if (!master && showColor) ColorGrid(t.color, onColor)
            }
        }
    }
}

/** The 70-color Live palette as small squares, adapting the column count to the available width. */
@Composable
private fun ColorGrid(current: Int, onPick: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth >= 420.dp) 35 else 14
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LivePalette.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { c ->
                        val sel = (c and 0xFFFFFF) == (current and 0xFFFFFF)
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).heightIn(max = 28.dp)
                                .clip(LiveShapes.Small)
                                .background(liveColor(c))
                                .then(if (sel) Modifier.border(2.dp, LiveColors.Accent, LiveShapes.Small) else Modifier)
                                .clickable { onPick(c) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Inline rename editor (field + OK + X) for use inside a Row. The empty-name error is shown IN the row: the field gets a
 * red outline and the red text "Name can't be empty" as its placeholder/hint, plus a red OK, so neither the soft
 * keyboard nor list clipping can hide it. The field keeps focus. [forceError] is for tests.
 */
@Composable
internal fun RowScope.RenameEditor(initial: String, onRename: (String) -> Unit, onCancel: () -> Unit, key: Any = Unit, forceError: Boolean = false) {
    var text by remember(key) { mutableStateOf(initial) }
    var showErr by remember(key) { mutableStateOf(forceError) }
    val err = showErr && nameError(text) != null
    val tryOk = { if (nameError(text) == null) onRename(text) else showErr = true }
    Box(Modifier.weight(1f).padding(vertical = 2.dp)) {
        DenseTextField(text, { text = it }, Modifier.fillMaxWidth(), onDone = tryOk, autoFocus = true, isError = err)
        if (err && text.isEmpty()) {
            LiveText("Name can't be empty", Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp), LiveType.Small, LiveColors.Rec)
        }
    }
    LiveTextButton("OK", tryOk, on = nameError(text) == null, minWidth = 40.dp)
    LiveTextButton("X", onCancel, minWidth = 32.dp)
}

/** A pending move that needs the user's OK because the rebuild would drop [losses]. */
private data class MoveConfirm(val t: TrackInfo, val to: Int, val losses: List<String>)
