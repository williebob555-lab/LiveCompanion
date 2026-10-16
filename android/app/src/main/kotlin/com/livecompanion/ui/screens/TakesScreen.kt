package com.livecompanion.ui.screens

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.livecompanion.ui.components.TimelineVScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Loc
import com.livecompanion.state.SlotInfo
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.components.TClip
import com.livecompanion.ui.components.TLane
import com.livecompanion.ui.components.Timeline
import com.livecompanion.ui.components.adaptiveLaneHeight
import com.livecompanion.ui.components.TimelineView
import com.livecompanion.ui.components.dim
import com.livecompanion.ui.components.drawStar
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.components.onColor
import com.livecompanion.ui.components.timelineRulerHeight
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import kotlin.math.floor
import kotlin.math.max

private val GutterW = 72.dp

/** Phone-local take ratings (1..3 stars) in SharedPreferences, keyed by track name + lane + clip start. */
class TakeRatings(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("take_ratings", Context.MODE_PRIVATE)
    private var version by mutableIntStateOf(0)

    fun key(track: String, lane: Int, start: Double) = "$track|$lane|${"%.3f".format(java.util.Locale.ROOT, start)}"

    fun get(key: String): Int { version; return sp.getInt(key, 0) }

    fun set(key: String, stars: Int) {
        sp.edit().apply { if (stars <= 0) remove(key) else putInt(key, stars.coerceIn(1, 3)) }.apply()
        version++
    }
}

private sealed interface Pick {
    data class Take(val lane: Int, val i: Int) : Pick
}

/**
 * Takes tab. Supported: take lanes stacked under a shared-style timeline (audition / promote / rename lane /
 * new lane) plus local star ratings. Unsupported: explanation and the session-slot TAKE workflow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TakesScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val takes by store.takes.collectAsState()
    val slotsState by store.slots.collectAsState()
    val session by store.session.collectAsState()
    val focus by store.focus.collectAsState()
    val ctx = LocalContext.current
    val goTab = LocalTabNavigator.current
    val ratings = remember { TakeRatings(ctx) }
    val tr = takes.tr.ifEmpty { focus.ref }
    var bestOnly by remember { mutableStateOf(false) }
    var showSlots by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<Pick.Take?>(null) }
    var renameLane by remember { mutableIntStateOf(-1) }
    val view = remember { TimelineView() }
    val bpb = session.sigNum * 4.0 / session.sigDen
    val trackName = focus.name

    LaunchedEffect(tr) { pick = null }

    Column(modifier.fillMaxSize().background(LiveColors.Bg), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LiveTextButton(
                "TAKE", { client.op("take.next", "tr" to tr) }, Modifier,
                on = true, onColor = LiveColors.Rec, enabled = tr.isNotEmpty() && focus.armable, minHeight = 32.dp, minWidth = 56.dp,
            )
            DBtn("Stop", { client.op("track.stop", "tr" to tr) }, enabled = tr.isNotEmpty())
            if (takes.supported) {
                DBtn("Lanes", { showSlots = false }, on = !showSlots)
                DBtn("Session takes", { showSlots = true }, on = showSlots)
            }
            if (takes.supported && !showSlots) DBtn("+ Lane", { client.op("takes.create_lane", "tr" to tr) })
            DBtn("Best only", { bestOnly = !bestOnly }, on = bestOnly)
            if (takes.supported && !showSlots) DBtn("Fit", { fitTakes(view, takes.lanes.flatMap { l -> l.clips.map { it.start to it.end } }, bpb) })
        }

        if (!takes.supported || showSlots) {
            UnsupportedTakes(
                slotsState.slots, trackName, bestOnly, ratings, lanesSupported = takes.supported,
                onFire = { i -> client.op("slot.fire", "tr" to tr, "i" to i) },
                onToArr = { i ->
                    val t = floor(session.pos / bpb).coerceAtLeast(0.0) * bpb
                    client.op("clip.to_arr", "loc" to Loc.slot(tr, i), "time" to t)
                },
            )
        } else {
            val spans = takes.lanes.flatMap { l -> l.clips.map { it.start to it.end } }
            LaunchedEffect(tr, spans.size, view.widthPx) {
                if (view.widthPx > 0f && !view.fitted) fitTakes(view, spans, bpb)
            }
            var shown = takes.lanes
            if (bestOnly) {
                val best = takes.lanes.maxOfOrNull { l -> l.clips.maxOfOrNull { ratings.get(ratings.key(trackName, l.i, it.start)) } ?: 0 } ?: 0
                if (best > 0) shown = takes.lanes.map { l -> l.copy(clips = l.clips.filter { ratings.get(ratings.key(trackName, l.i, it.start)) == best }) }
            }
            if (takes.lanes.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                    LiveText("No take lanes yet. Use + Lane to create one.", style = LiveType.Small, color = LiveColors.TextDim, maxLines = 3)
                }
            } else {
                val tlLanes = shown.map { l ->
                    TLane(l.clips.map { c ->
                        TClip("t${l.i}:${c.i}", c.name.ifEmpty { "Take" }, c.color, c.start, c.end,
                            selected = pick?.lane == l.i && pick?.i == c.i,
                            stars = ratings.get(ratings.key(trackName, l.i, c.start)))
                    })
                }
                val vs = remember { TimelineVScroll() }
                val LaneH = adaptiveLaneHeight(takes.lanes.size, 36.dp, 120.dp, 0.5f)
                Row(Modifier.fillMaxWidth().weight(1f, fill = false).height(IntrinsicSize.Min)) {
                    Column(Modifier.width(GutterW).fillMaxHeight()) {
                        Spacer(Modifier.height(timelineRulerHeight()))
                        // Lane labels scroll with the lanes (the ruler stays fixed).
                        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                            Column(
                                Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Top, unbounded = true)
                                    .offset { IntOffset(0, -vs.clamped.roundToInt()) },
                            ) {
                                for (l in takes.lanes) {
                                    Box(
                                        Modifier.fillMaxWidth().height(LaneH).background(LiveColors.PanelHi)
                                            .clickable { renameLane = l.i }.padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) { LiveText(l.name.ifEmpty { "Lane ${l.i + 1}" }, style = LiveType.Small) }
                                }
                            }
                        }
                    }
                    Timeline(
                        view = view, lanes = tlLanes, beatsPerBar = bpb, rt = store.rt,
                        modifier = Modifier.weight(1f), laneHeight = LaneH, vScroll = vs,
                        onRulerTap = { client.op("song.seek", "beats" to it) },
                        onClipTap = { li, c ->
                            val lane = shown.getOrNull(li) ?: return@Timeline
                            val ci = c.key.substringAfter(':').toIntOrNull() ?: return@Timeline
                            pick = Pick.Take(lane.i, ci)
                        },
                        onEmptyTap = { _, _ -> pick = null },
                    )
                }
                val p = pick
                val lane = takes.lanes.firstOrNull { it.i == p?.lane }
                val clip = lane?.clips?.firstOrNull { it.i == p?.i }
                Column(
                    Modifier.fillMaxWidth().background(LiveColors.Panel).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (lane == null || clip == null) {
                        LiveText("Tap a take to select it. Tap the ruler to seek; pinch to zoom.", style = LiveType.Small, color = LiveColors.TextDim, maxLines = 3)
                    } else {
                        val rk = ratings.key(trackName, lane.i, clip.start)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(10.dp).background(liveColor(clip.color), LiveShapes.Small))
                            LiveText(clip.name.ifEmpty { "Take" }, Modifier.weight(1f, fill = false), LiveType.Title)
                            LiveText("${barPos(clip.start, bpb)} - ${barPos(clip.end, bpb)}", style = LiveType.Small, color = LiveColors.TextDim)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            DBtn("Audition", {
                                client.op("clip.open", "loc" to Loc.take(tr, lane.i, clip.i))
                                client.op("song.seek", "beats" to clip.start)
                                client.op("song.play")
                            })
                            DBtn("Open in Clip", {
                                client.op("clip.open", "loc" to Loc.take(tr, lane.i, clip.i))
                                goTab(Tab.Clip)
                            })
                            DBtn("Promote", { client.op("takes.promote", "loc" to Loc.take(tr, lane.i, clip.i)) })
                            DBtn("Rename lane", { renameLane = lane.i })
                            StarRow(ratings.get(rk)) { ratings.set(rk, it) }
                        }
                    }
                }
            }
        }
    }

    if (renameLane >= 0) {
        val l = takes.lanes.firstOrNull { it.i == renameLane }
        RenameClipDialog(l?.name ?: "", { renameLane = -1 }) { n ->
            client.op("takes.rename", "tr" to tr, "lane" to renameLane, "name" to n); renameLane = -1
        }
    }
}

private fun fitTakes(view: TimelineView, spans: List<Pair<Double, Double>>, bpb: Double) {
    val from = spans.minOfOrNull { it.first } ?: 0.0
    val to = spans.maxOfOrNull { it.second } ?: (bpb * 8)
    view.fit(from, max(to, from + bpb * 2))
}

@Composable
private fun UnsupportedTakes(
    slots: List<SlotInfo>,
    trackName: String,
    bestOnly: Boolean,
    ratings: TakeRatings,
    lanesSupported: Boolean,
    onFire: (Int) -> Unit,
    onToArr: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().background(LiveColors.Panel).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiveText("Two ways to take", style = LiveType.Title, maxLines = 2)
            LiveText(
                "Session takes: TAKE arms this track and records into the next empty slot as Take 1, Take 2, ... " +
                    "Tap a take to audition it, rate it with the stars (stored on this phone), and use To arr to copy it to the arrangement at the playhead bar.",
                style = LiveType.Small, color = LiveColors.TextDim, maxLines = 6,
            )
            LiveText(
                if (lanesSupported) "Take lanes: Live's arrangement comping lanes. Recording in the arrangement (Rec button + play) fills them; switch to Lanes above to audition and promote."
                else "Take lanes (arrangement comping) are not exposed by this Live version, so only session takes are available here.",
                style = LiveType.Small, color = LiveColors.TextDim, maxLines = 5,
            )
        }
        var list = slots.filter { it.hasClip && it.clip?.name?.startsWith("Take", ignoreCase = true) == true }
        if (bestOnly) {
            val best = list.maxOfOrNull { ratings.get(ratings.key(trackName, -1, it.i.toDouble())) } ?: 0
            if (best > 0) list = list.filter { ratings.get(ratings.key(trackName, -1, it.i.toDouble())) == best }
        }
        if (list.isEmpty()) {
            LiveText("No slot clips named Take* on this track yet.", style = LiveType.Small, color = LiveColors.TextDim, maxLines = 2)
        }
        for (s in list) {
            val c = s.clip ?: continue
            val k = ratings.key(trackName, -1, s.i.toDouble())
            val bg = dim(liveColor(c.color), 0.7f)
            Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    Modifier.weight(1f).fillMaxSize().background(bg, LiveShapes.Small).clickable { onFire(s.i) }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiveText("${s.i + 1}", style = LiveType.Small, color = onColor(bg).copy(alpha = 0.7f))
                    LiveText(c.name, Modifier.weight(1f), LiveType.Body, onColor(bg))
                    if (c.len > 0) LiveText("${fmtBars(c.len)}b", style = LiveType.Small, color = onColor(bg).copy(alpha = 0.7f))
                }
                DBtn("To arr", { onToArr(s.i) })
                StarRow(ratings.get(k)) { ratings.set(k, it) }
            }
        }
    }
}

/** Three tappable stars (1..3); tapping the current rating clears it. */
@Composable
private fun StarRow(stars: Int, onSet: (Int) -> Unit) {
    Row(Modifier.height(28.dp).background(LiveColors.PanelHi, LiveShapes.Small)) {
        for (n in 1..3) {
            Canvas(Modifier.size(28.dp).clickable { onSet(if (stars == n) 0 else n) }) {
                drawStar(center, size.minDimension * 0.34f, if (n <= stars) LiveColors.Accent else Color(0xFF666666))
            }
        }
    }
}
