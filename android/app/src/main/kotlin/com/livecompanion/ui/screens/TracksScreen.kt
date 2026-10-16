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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.state.TrackInfo
import com.livecompanion.ui.components.ConfirmDialog
import com.livecompanion.ui.components.DenseTextField
import com.livecompanion.ui.components.nameError
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
 * name to rename it inline, "Edit" expands the row (color palette, duplicate, delete with confirm). Add audio /
 * MIDI / return tracks from the top bar. Reordering is NOT possible: Live's API has no move_track (docs/LOM_NOTES.md).
 */
@Composable
fun TracksScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val tracks by store.tracks.collectAsState()
    val focus by store.focus.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<TrackInfo?>(null) }

    fun focusOn(ref: String) {
        if (ref != focus.ref) {
            store.focusPending(ref)
            client.op("focus.set", "tr" to ref)
        }
    }

    fun insertIndex(): Int {
        val i = tracks.tracks.indexOfFirst { it.ref == focus.ref }
        return if (i >= 0) i + 1 else -1
    }

    val rowFor: @Composable (TrackInfo) -> Unit = { t ->
        TrackRow(
            t = t,
            focused = t.ref == focus.ref,
            open = expanded == t.ref,
            renaming = renaming == t.ref,
            onFocus = { focusOn(t.ref) },
            onToggleOpen = { renaming = null; expanded = if (expanded == t.ref) null else t.ref },
            // one editor at a time (exclusive with the Edit panel); renaming never changes the focused track
            onStartRename = { expanded = null; renaming = t.ref },
            onRename = { name ->
                renaming = null
                val n = name.trim()
                if (n.isNotEmpty() && n != t.name) {
                    client.op("track.set", "tr" to t.ref, "k" to "name", "v" to n)
                    store.patchTrack(t.ref) { it.copy(name = n) }
                    if (t.ref == focus.ref) store.updateFocus { it.copy(name = n) }
                }
            },
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

    Column(modifier.fillMaxSize().background(LiveColors.Bg), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(1.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveTextButton("+ Audio", { client.op("track.create", "kind" to "audio", "i" to insertIndex()) }, Modifier.weight(1f))
            LiveTextButton("+ MIDI", { client.op("track.create", "kind" to "midi", "i" to insertIndex()) }, Modifier.weight(1f))
            LiveTextButton("+ Return", { client.op("track.create", "kind" to "return") }, Modifier.weight(1f))
        }
        LiveText(
            "Order is fixed here: Live's API has no move-track, so reorder tracks in Live itself.",
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(horizontal = 8.dp, vertical = 3.dp),
            LiveType.Label, LiveColors.TextDim, maxLines = 2,
        )
        val rows = tracks.all
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LiveText("No tracks", style = LiveType.Body, color = LiveColors.TextDim)
            }
        } else {
            val master = rows.firstOrNull { it.kind == "master" }
            val scrolling = if (master != null) rows.filter { it !== master } else rows
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // Wide screens (tablets): rows flow into 2-3 columns instead of ~1800px-wide rows.
                val cols = (maxWidth / 440.dp).toInt().coerceIn(1, 3)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        androidx.compose.foundation.lazy.grid.GridCells.Fixed(cols),
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        contentPadding = PaddingValues(1.dp),
                    ) {
                        items(scrolling, key = { it.ref }) { t -> rowFor(t) }
                    }
                    // Master stays pinned below the list so it is never below the fold.
                    if (master != null) Box(Modifier.fillMaxWidth().padding(1.dp)) { rowFor(master) }
                }
            }
        }
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
    var text by remember(t.ref, renaming) { mutableStateOf(t.name) }
    var showErr by remember(t.ref, renaming) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(LiveShapes.Small)
            .background(if (focused) LiveColors.PanelHi else LiveColors.Panel)
            .then(if (focused) Modifier.border(1.dp, LiveColors.Accent, LiveShapes.Small) else Modifier),
    ) {
        // Error ABOVE the editor row so the soft keyboard never hides it.
        if (renaming && showErr) nameError(text)?.let {
            LiveText(it, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), LiveType.Small, LiveColors.Rec)
        }
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 36.dp).clickable(onClick = onFocus),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.width(if (t.group != null) 14.dp else 0.dp))
            Box(Modifier.width(6.dp).fillMaxHeight().heightIn(min = 36.dp).background(liveColor(t.color)))
            if (renaming) {
                val tryOk = { if (nameError(text) == null) onRename(text) else showErr = true }
                DenseTextField(text, { text = it; showErr = true }, Modifier.weight(1f).padding(vertical = 2.dp), onDone = tryOk, autoFocus = true)
                LiveTextButton("OK", tryOk, on = nameError(text) == null, minWidth = 40.dp)
                LiveTextButton("X", onCancelRename, minWidth = 32.dp)
            } else {
                Box(
                    Modifier.weight(1f).fillMaxHeight().heightIn(min = 36.dp)
                        .then(if (master) Modifier else Modifier.clickable(onClick = onStartRename))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    LiveText(t.name.ifEmpty { "-" }, style = LiveType.Body, color = if (focused) LiveColors.Accent else LiveColors.Text)
                }
                LiveText(
                    when (t.kind) { "midi" -> "MIDI"; "audio" -> "AUDIO"; "group" -> "GROUP"; "return" -> "RET"; else -> "MAIN" },
                    Modifier.width(40.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f)), style = LiveType.Label, color = LiveColors.Text, maxLines = 1,
                )
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
            Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!master) ColorGrid(t.color, onColor)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LiveTextButton("Rename", onStartRename, Modifier.weight(1f), enabled = !master)
                    LiveTextButton("Duplicate", onDuplicate, Modifier.weight(1f), enabled = !master && !ret)
                    LiveTextButton("Delete", onDelete, Modifier.weight(1f), on = !master, onColor = LiveColors.Rec, enabled = !master)
                }
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
