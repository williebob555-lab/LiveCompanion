package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.net.LiveException
import com.livecompanion.net.jsonArgs
import com.livecompanion.state.LiveJson
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.devices.DevicePending
import com.livecompanion.ui.components.DenseButton
import com.livecompanion.ui.components.DenseTextField
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class BrowserItem(
    val name: String = "",
    val folder: Boolean = false,
    val loadable: Boolean = false,
    val uri: String? = null,
    val key: String? = null,
)

/** Browser state that survives tab switches: current path, per-path listing cache, preview. One per [LiveStore]. */
private class BrowserModel {
    var path by mutableStateOf(listOf<String>())
    var roots by mutableStateOf<List<BrowserItem>?>(null)
    val cache = HashMap<List<String>, List<BrowserItem>>()
    var version by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var previewing by mutableStateOf<List<String>?>(null)
    var status by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
}

private val models = java.util.WeakHashMap<LiveStore, BrowserModel>()

private suspend fun fetch(client: LiveClient, path: List<String>): List<BrowserItem> {
    val d = client.request("browser.list", jsonArgs("path" to path))
    return LiveJson.decodeFromJsonElement(ListSerializer(BrowserItem.serializer()), d)
}

/**
 * Browser tab: root categories (fixed row), breadcrumb, filtered listing. Folders navigate; loadable
 * items get Load (onto the focused track) and Preview. Listings are cached per path in memory.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val m = remember(store) { models.getOrPut(store) { BrowserModel() } }
    val focus by store.focus.collectAsState()
    val hello by store.hello.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(hello) {
        if (hello != null && m.roots == null) {
            try { m.roots = fetch(client, emptyList()).also { m.cache[emptyList()] = it } }
            catch (e: LiveException) { m.error = e.message }
        }
    }
    // Opens on a useful default: the last category, else Instruments (else the first one).
    LaunchedEffect(m.roots) {
        val r = m.roots
        if (r != null && m.path.isEmpty() && r.isNotEmpty()) m.path = listOf((r.firstOrNull { it.name.equals("Instruments", true) } ?: r.first()).name)
    }
    LaunchedEffect(m.path, m.version) {
        if (m.path.isEmpty() || m.cache.containsKey(m.path)) { m.loading = false; return@LaunchedEffect }
        m.loading = true; m.error = null
        try { m.cache[m.path] = fetch(client, m.path) }
        catch (e: LiveException) { m.error = e.message ?: "Listing failed" }
        m.loading = false
        m.version++
    }

    @Suppress("UNUSED_VARIABLE") val ver = m.version
    val listing = m.cache[m.path].orEmpty()
    val shown = remember(listing, m.query, ver) {
        if (m.query.isBlank()) listing else listing.filter { it.name.contains(m.query.trim(), ignoreCase = true) }
    }
    val tr = focus.ref
    // the status bar clears itself (was stuck on screen forever)
    LaunchedEffect(m.status) { if (m.status != null) { kotlinx.coroutines.delay(3500); m.status = null } }

    Column(modifier.fillMaxSize().background(LiveColors.Bg).padding(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // ONE scrolling category row; a fade + chevron at the right edge shows there are more chips (no clipped chip)
        val chipScroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().background(LiveColors.Panel)) {
            Row(
                Modifier.fillMaxWidth().padding(2.dp).horizontalScroll(chipScroll),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val roots = m.roots
                if (roots == null) LiveText(m.error ?: "Loading categories...", color = LiveColors.TextDim, modifier = Modifier.padding(4.dp))
                else {
                    for (r in roots) DenseButton(r.name, { m.path = listOf(r.name); m.query = "" }, on = m.path.firstOrNull() == r.name)
                    Box(Modifier.width(22.dp))
                }
            }
            if (chipScroll.canScrollForward) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(28.dp).height(34.dp).background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, LiveColors.Panel)),
                    ),
                    contentAlignment = Alignment.CenterEnd,
                ) { LiveText(">", Modifier.padding(end = 4.dp), LiveType.Body, LiveColors.TextDim) }
            }
        }
        // breadcrumb (every segment taps back to that level) + compact search on the same row
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DenseButton("Up", { m.path = m.path.dropLast(1); m.query = "" }, enabled = m.path.isNotEmpty())
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (m.path.isEmpty()) LiveText("Pick a category", style = LiveType.Small, color = LiveColors.TextDim)
                m.path.forEachIndexed { idx, seg ->
                    if (idx > 0) LiveText("/", Modifier.padding(horizontal = 2.dp), LiveType.Small, color = LiveColors.TextDim)
                    val last = idx == m.path.lastIndex
                    LiveText(
                        seg,
                        Modifier.clickable(enabled = !last) { m.path = m.path.take(idx + 1); m.query = "" }.padding(horizontal = 3.dp, vertical = 6.dp),
                        LiveType.Small, color = if (last) LiveColors.Text else LiveColors.Accent, maxLines = 1,
                    )
                }
            }
            DenseTextField(m.query, { m.query = it }, Modifier.widthIn(min = 90.dp, max = 180.dp), placeholder = "Search")
            DenseButton("Refresh", { m.cache.remove(m.path); m.version++ }, enabled = m.path.isNotEmpty())
            if (m.previewing != null) DenseButton("Stop", { m.previewing = null; client.op("browser.preview_stop") }, on = true, onColor = LiveColors.Play)
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val paneW = maxWidth
            val twoPane = paneW >= 840.dp
            val itemList: @Composable (List<BrowserItem>, List<String>, Modifier, List<String>?) -> Unit = { items0, base, mod, highlight ->
                LazyColumn(mod, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(items0, key = { it.name + it.uri }) { item ->
                        val full = base + item.name
                        Row(
                            Modifier.fillMaxWidth().height(32.dp)
                                .background(if (highlight != null && full == highlight) LiveColors.PanelHi else LiveColors.Panel, LiveShapes.Small)
                                .then(if (item.folder) Modifier.clickable { m.path = full; m.query = "" } else Modifier)
                                .padding(start = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(Modifier.size(8.dp).background(if (item.folder) LiveColors.Accent else LiveColors.TextDim, LiveShapes.Small))
                            LiveText(item.name, Modifier.weight(1f), LiveType.Body, color = if (highlight != null && full == highlight) LiveColors.Accent else LiveColors.Text)
                            if (item.loadable) {
                                val prev = m.previewing == full
                                DenseButton("Preview", {
                                    if (prev) { m.previewing = null; client.op("browser.preview_stop") }
                                    else { m.previewing = full; client.op("browser.preview", "path" to full) }
                                }, on = prev, onColor = LiveColors.Play)
                                DenseButton("Load", {
                                    // Instant feedback: a "loading..." device panel appears in the chain right away.
                                    val isInst = m.path.firstOrNull()?.lowercase() in setOf("instruments", "drums", "sounds")
                                    val ph = DevicePending.add(store, tr, item.name.substringBeforeLast('.'), atStart = isInst)
                                    m.status = "Loading ${item.name}..."
                                    scope.launch {
                                        try {
                                            client.request("browser.load", jsonArgs("tr" to tr, "path" to full))
                                            m.status = "Loaded ${item.name}"
                                        } catch (e: LiveException) {
                                            DevicePending.remove(store, ph)
                                            m.status = "Load failed: ${e.message}"
                                        }
                                        m.status?.let { store.notice(it) }
                                    }
                                }, on = true, enabled = tr.isNotEmpty())
                            }
                        }
                    }
                }
            }
            when {
                m.path.isEmpty() -> Center("Pick a category above")
                m.loading && listing.isEmpty() -> Center("Loading...")
                m.error != null && listing.isEmpty() -> Center(m.error ?: "")
                twoPane -> {
                    // Tablet: folders | items. The folder pane shows this level's folders (or, inside a leaf folder, its siblings).
                    val hasFolders = listing.any { it.folder }
                    val folderBase = if (hasFolders || m.path.size <= 1) m.path else m.path.dropLast(1)
                    val folders = (m.cache[folderBase] ?: listing).filter { it.folder }
                    val leaves = shown.filter { !it.folder }
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.width(paneW * 0.36f).fillMaxHeight()) {
                            if (folders.isEmpty()) Center("No folders") else itemList(folders, folderBase, Modifier.fillMaxSize(), m.path)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (leaves.isEmpty()) Center(if (hasFolders) "Pick a folder on the left" else if (listing.isEmpty()) "Empty" else "No matches")
                            else itemList(leaves, m.path, Modifier.fillMaxSize(), null)
                        }
                    }
                }
                shown.isEmpty() -> Center(if (listing.isEmpty()) "Empty" else "No matches")
                else -> itemList(shown, m.path, Modifier.fillMaxSize(), null)
            }
        }
        m.status?.let { LiveText(it, Modifier.fillMaxWidth().background(LiveColors.Panel).padding(4.dp), LiveType.Small) }
    }
}

@Composable
private fun Center(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LiveText(text, color = LiveColors.TextDim) }
}

/** Test hook: preload the browser cache/roots/path for [store]. */
internal fun seedBrowserForTest(store: LiveStore, roots: List<BrowserItem>, path: List<String>, listing: List<BrowserItem>) {
    val m = models.getOrPut(store) { BrowserModel() }
    m.roots = roots
    m.cache[emptyList()] = roots
    m.cache[path] = listing
    m.path = path
}
