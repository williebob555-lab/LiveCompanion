package com.livecompanion.ui.clip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.livecompanion.state.ClipState
import com.livecompanion.state.Note
import com.livecompanion.state.RtFrame
import com.livecompanion.state.ScaleInfo
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.theme.LiveColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class RollMode { Draw, Select }
enum class Lane { Vel, Prob }

/** Viewport (horizontal part shared via [v]) + vertical rows + in-flight gesture previews for the roll. */
class RollView {
    val v = ClipView(3f, 1500f)
    var ppk by mutableFloatStateOf(18f)       // pixels per key row
    var topRow by mutableFloatStateOf(40f)    // row index (0 = pitch 127) at the top edge of the note area
    var fold by mutableStateOf(false)
    var foldRows: IntArray = IntArray(0)      // pitches, descending (only used when fold)

    // gesture previews
    var dt by androidx.compose.runtime.mutableDoubleStateOf(0.0)
    var dr by mutableIntStateOf(0)             // rows moved down
    var dd by androidx.compose.runtime.mutableDoubleStateOf(0.0)
    var box by mutableStateOf<Rect?>(null)
    var ghost by mutableStateOf<Note?>(null)

    val rowCount get() = if (fold) foldRows.size else 128
    fun rowOf(p: Int): Int = if (fold) foldRows.indexOf(p) else 127 - p
    fun pitchAt(row: Int): Int? = if (fold) foldRows.getOrNull(row) else (127 - row).takeIf { it in 0..127 }
    fun yTop(row: Int): Float = v.topH + (row - topRow) * ppk
    fun rowAtY(y: Float): Int = floor(topRow + (y - v.topH) / ppk).toInt()
    fun clampTop() {
        val maxTop = max(0f, rowCount - 1f)
        topRow = topRow.coerceIn(-1f, maxTop)
    }
}

private val RowDark = Color(0xFF1F1F1F)
private val RowLight = Color(0xFF282828)
private val RowOutScale = Color(0xFF1A1A1A)
private val RowInScale = Color(0xFF2D2D2D)

@Composable
fun PianoRoll(
    clip: ClipState, ed: NoteEditor, rt: State<RtFrame>, ops: ClipEdits, scale: ScaleInfo?, tempo: Float, rec: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var mode by remember { mutableStateOf(RollMode.Select) }
    var snap by remember { mutableStateOf(true) }
    var sub by remember { mutableStateOf(Subdiv()) }
    var lane by remember { mutableStateOf(Lane.Vel) }
    var showScale by remember { mutableStateOf(scale != null) }
    var sigDialog by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    val view = remember(clip.loc) { RollView() }
    val v = view.v
    v.leftW = with(density) { 44.dp.toPx() }
    v.braceH = with(density) { 14.dp.toPx() }
    v.rulerH = with(density) { 17.dp.toPx() }
    val num = clip.signature.getOrElse(0) { 4 }
    val den = clip.signature.getOrElse(1) { 4 }
    val axis = remember(num, den) { AxisSpec(false, num, den) }
    // fold rows follow the notes in the editor
    val used = remember(ed.notes) { ed.notes.map { it.p }.distinct().sortedDescending().toIntArray() }
    view.foldRows = used
    if (view.fold && used.isEmpty()) view.fold = false
    val stepNow = gridStepFor(sub, v.ppu, axis.beatLen, axis.barLen)
    val tm = rememberTextMeasurer()
    val head = rememberPlayhead(rt, clip, tempo, false, rec) { h ->
        if (rec && v.width > 0f) {
            val x = v.xOf(h)
            if (x > v.width * 0.9f) v.x0 = h - (v.width * 0.4f - v.leftW) / v.ppu.toDouble()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoRows = maxWidth < 640.dp
        val toolbarGrid: @Composable () -> Unit = {
            GridControls(sub, { sub = it }, snap, { snap = it }, "$num/$den", { sigDialog = true }, stepLabel(stepNow))
        }
        val toolbarTools: @Composable () -> Unit = {
            ToolBtn("Draw", { mode = RollMode.Draw }, on = mode == RollMode.Draw, minWidth = 42.dp)
            ToolBtn("Select", { mode = RollMode.Select }, on = mode == RollMode.Select, minWidth = 46.dp)
            ToolBtn("Fold", { view.fold = !view.fold; view.topRow = 0f }, on = view.fold, enabled = used.isNotEmpty(), minWidth = 36.dp)
            if (scale != null) ToolBtn(scaleLabel(scale), { showScale = !showScale }, on = showScale, minWidth = 40.dp)
            ToolBtn("Vel", { lane = Lane.Vel }, on = lane == Lane.Vel)
            ToolBtn("Prob", { lane = Lane.Prob }, on = lane == Lane.Prob, minWidth = 34.dp)
            ToolBtn("All", { ed.selectAll() })
            ToolBtn("Dup", { ed.duplicateSelected() }, enabled = ed.sel.isNotEmpty())
            ToolBtn("Del", { if (ed.sel.size > BULK_DELETE_CONFIRM) confirmDel = true else ed.deleteSelected() }, enabled = ed.sel.isNotEmpty())
        }
        Column(Modifier.fillMaxSize()) {
            if (twoRows) {
                ToolRow { toolbarGrid() }
                ToolRow { toolbarTools() }
            } else {
                ToolRow { toolbarGrid(); toolbarTools() }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val laneH = (maxHeight * 0.2f).coerceIn(34.dp, 90.dp)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(LiveColors.Bg).onSizeChanged {
                        v.width = it.width.toFloat(); v.height = it.height.toFloat()
                    }) {
                        NotesCanvas(clip, ed, view, tm, axis, sub, snap, mode, ops, scale?.takeIf { showScale }, rec, num, den)
                        ZoomPad(Modifier.align(androidx.compose.ui.Alignment.BottomEnd), { v.zoomX(it) },
                            { f -> view.ppk = (view.ppk * f).coerceIn(with(density) { 5.dp.toPx() }, with(density) { 40.dp.toPx() }) }, { v.requestFit() })
                        // Playhead / recording overlay: only this canvas redraws per frame.
                        Canvas(Modifier.fillMaxSize()) {
                            val h = head() ?: if (rec) clip.loopEnd else null
                            if (rec) drawRecRegion(v, 0.0, h ?: 0.0, v.topH, size.height)
                            if (h != null) drawPlayhead(v, h, if (rec) LiveColors.Rec else LiveColors.Play, v.braceH, size.height)
                        }
                    }
                    LaneCanvas(clip, ed, view, axis, sub, lane, Modifier.height(laneH).fillMaxWidth())
                }
            }
        }
    }
    if (sigDialog) SigDialog(num, den, { sigDialog = false }) { n, d ->
        ops.set("sig_num", n.toDouble()); ops.set("sig_den", d.toDouble())
    }
    if (confirmDel) {
        com.livecompanion.ui.components.ConfirmDialog(
            "Delete notes", "Delete ${ed.sel.size} notes? (Undo restores them.)", "Delete",
            { ed.deleteSelected() }, { confirmDel = false },
        )
    }
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
internal fun scaleLabel(s: ScaleInfo): String = "Scale"
internal fun scaleTitle(s: ScaleInfo): String = NOTE_NAMES[((s.root % 12) + 12) % 12] + " " + s.name

@Composable
private fun NotesCanvas(
    clip: ClipState, ed: NoteEditor, view: RollView, tm: TextMeasurer, axis: AxisSpec, sub: Subdiv, snap: Boolean,
    mode: RollMode, ops: ClipEdits, scale: ScaleInfo?, rec: Boolean, num: Int, den: Int,
) {
    val density = LocalDensity.current
    val v = view.v
    val labelSp = with(density) { 9.dp.toSp() }
    val noteColor = liveColor(clip.color)
    val subS = rememberUpdatedState(sub)
    val snapS = rememberUpdatedState(snap)
    val modeS = rememberUpdatedState(mode)
    val clipS = rememberUpdatedState(clip)
    val opsS = rememberUpdatedState(ops)
    val axisS = rememberUpdatedState(axis)
    val touchMin = with(density) { 10.dp.toPx() }
    val edgePx = with(density) { 14.dp.toPx() }
    val hitPx = with(density) { 18.dp.toPx() }
    val minPpk = with(density) { 5.dp.toPx() }
    val maxPpk = with(density) { 40.dp.toPx() }

    // initial fit once the size is known
    v.fitReq // re-run the fit when requested
    if (v.width > 0f && v.height > 0f && !v.inited) {
        v.inited = true
        val (a, b) = if (clip.looping || clip.endMarker <= clip.startMarker) clip.loopStart to clip.loopEnd else clip.startMarker to clip.endMarker
        val hi = if (b > a) b else if (clip.len > 0) clip.len else 4.0
        v.fit(if (b > a) a else 0.0, hi)
        val ps = ed.notes.map { it.p }
        val lo = ps.minOrNull() ?: 55
        val hp = ps.maxOrNull() ?: 72
        val rows = (hp - lo + 5).coerceAtLeast(12)
        val gh = v.height - v.topH
        view.ppk = (gh / rows).coerceIn(with(density) { 9.dp.toPx() }, with(density) { 22.dp.toPx() })
        val vis = gh / view.ppk
        view.topRow = (127 - ((lo + hp) / 2f) - vis / 2f).coerceIn(0f, 127f)
        if (view.fold) view.topRow = 0f
    }

    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position
                val c0 = clipS.value
                val ax = axisS.value
                val stepNow = snapStepFor(subS.value, snapS.value, v.ppu, ax.beatLen, ax.barLen)
                if (start.y < v.topH && start.x >= v.leftW) {
                    clipTopGesture(down, v, c0, opsS.value, stepNow, hitPx)
                    return@awaitEachGesture
                }
                val region = if (start.x < v.leftW) 1 else 0
                val beat0 = v.unitOf(start.x)
                val row0 = view.rowAtY(start.y)
                val pitch0 = view.pitchAt(row0)
                val minBeats = touchMin / v.ppu.toDouble()
                val hit = if (region == 0 && pitch0 != null) hitNote(ed.notes, beat0, pitch0, minBeats) else -1
                val anchor = if (hit >= 0) ed.notes[hit] else null
                var moved = false
                var transforming = false
                var kind = 0 // 1 move, 2 resize, 3 box, 4 draw
                var selMinT = 0.0
                var selMinD = 0.0
                var selMinRow = 0
                var selMaxRow = 0
                val mode0 = modeS.value
                val step0 = stepNow
                val snap0 = step0 > 0
                val minLen = if (snap0) step0 else 0.0625
                val defLen = if (snap0) min(step0, ax.barLen) else 0.25

                fun beginDrag() {
                    if (region != 0) return
                    if (anchor != null) {
                        if (anchor.id !in ed.sel) ed.sel = setOf(anchor.id)
                        val s = ed.selectedNotes()
                        selMinT = s.minOf { it.t }
                        selMinD = s.minOf { it.d }
                        selMinRow = s.minOf { view.rowOf(it.p) }
                        selMaxRow = s.maxOf { view.rowOf(it.p) }
                        kind = if (inResizeZone(anchor, beat0, edgePx / v.ppu.toDouble())) 2 else 1
                        ed.busy = true
                    } else if (mode0 == RollMode.Draw) {
                        if (pitch0 != null) {
                            kind = 4
                            val t0 = max(0.0, snapFloorStep(beat0, step0))
                            view.ghost = Note(id = -999, p = pitch0, t = t0, d = defLen, v = 100)
                        }
                    } else {
                        kind = 3
                    }
                }

                fun drag(pos: Offset) {
                    when (kind) {
                        1 -> {
                            val raw = (pos.x - start.x) / v.ppu.toDouble()
                            view.dt = moveDelta(anchor!!.t, raw, selMinT, step0, snap0)
                            val dr = ((pos.y - start.y) / view.ppk).roundToInt()
                            view.dr = dr.coerceIn(-selMinRow, view.rowCount - 1 - selMaxRow)
                        }
                        2 -> {
                            val raw = (pos.x - start.x) / v.ppu.toDouble()
                            view.dd = resizeDelta(anchor!!.t + anchor.d, raw, selMinD, step0, snap0, minLen)
                        }
                        3 -> {
                            view.box = Rect(min(start.x, pos.x), min(start.y, pos.y), max(start.x, pos.x), max(start.y, pos.y))
                            val t0 = min(beat0, v.unitOf(pos.x)); val t1 = max(beat0, v.unitOf(pos.x))
                            val ra = min(row0, view.rowAtY(pos.y)); val rb = max(row0, view.rowAtY(pos.y))
                            val out = HashSet<Int>()
                            for (n in ed.notes) {
                                if (n.t > t1 || n.t + n.d < t0) continue
                                val r = view.rowOf(n.p)
                                if (r in ra..rb) out.add(n.id)
                            }
                            ed.sel = out
                        }
                        4 -> {
                            val g = view.ghost ?: return
                            val end = if (snap0) snapValue(v.unitOf(pos.x), step0) else v.unitOf(pos.x)
                            view.ghost = g.copy(d = max(minLen, end - g.t))
                        }
                    }
                }

                fun clearPreview() {
                    view.dt = 0.0; view.dr = 0; view.dd = 0.0; view.box = null; view.ghost = null
                }

                fun finish() {
                    when (kind) {
                        1, 2 -> {
                            val dt = view.dt; val dr = view.dr; val dd = view.dd
                            if (dt != 0.0 || dr != 0 || dd != 0.0) {
                                ed.modify(
                                    ed.selectedNotes().map {
                                        NoteEdit(
                                            it.id, p = if (dr != 0) view.pitchAt(view.rowOf(it.p) + dr) else null,
                                            t = if (dt != 0.0) it.t + dt else null, d = if (dd != 0.0) it.d + dd else null,
                                        )
                                    },
                                )
                            }
                        }
                        4 -> view.ghost?.let { ed.add(listOf(it.copy(id = 0))) }
                    }
                    clearPreview()
                    if (kind == 1 || kind == 2) ed.endGesture()
                    kind = 0
                }

                fun tap() {
                    if (region != 0) return
                    if (mode0 == RollMode.Draw) {
                        if (anchor != null) ed.remove(setOf(anchor.id))
                        else if (pitch0 != null && beat0 >= 0) {
                            ed.add(listOf(Note(p = pitch0, t = max(0.0, snapFloorStep(beat0, step0)), d = defLen, v = 100)))
                        }
                    } else {
                        ed.sel = if (anchor != null) {
                            if (anchor.id in ed.sel) ed.sel - anchor.id else ed.sel + anchor.id
                        } else emptySet()
                    }
                }

                fun transform(a: PointerInputChange, b: PointerInputChange) {
                    v.transformX(a, b)
                    val pcy = (a.previousPosition.y + b.previousPosition.y) / 2f
                    val ccy = (a.position.y + b.position.y) / 2f
                    val psy = abs(a.previousPosition.y - b.previousPosition.y)
                    val csy = abs(a.position.y - b.position.y)
                    val zy = if (psy > 24f) csy / psy else 1f
                    val rowA = view.topRow + (pcy - v.topH) / view.ppk
                    view.ppk = (view.ppk * zy).coerceIn(minPpk, maxPpk)
                    view.topRow = rowA - (ccy - v.topH) / view.ppk
                    view.clampTop()
                }

                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (!moved && !transforming) tap() else finish()
                            break
                        }
                        if (pressed.size >= 2) {
                            if (!transforming) {
                                transforming = true
                                if (kind != 0) { clearPreview(); if (kind == 1 || kind == 2) ed.endGesture(); kind = 0 }
                            }
                            transform(pressed[0], pressed[1])
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (transforming) { ev.changes.forEach { it.consume() }; continue }
                        val c = pressed[0]
                        if (!moved) {
                            if ((c.position - start).getDistance() <= viewConfiguration.touchSlop) continue
                            moved = true
                            beginDrag()
                        }
                        if (region == 1) {
                            view.topRow -= c.positionChange().y / view.ppk
                            view.clampTop()
                        } else if (kind != 0) drag(c.position)
                        c.consume()
                    }
                } finally {
                    if (kind != 0) {
                        clearPreview()
                        if (kind == 1 || kind == 2) ed.endGesture()
                    }
                }
            }
        },
    ) {
        val stepDraw = gridStepFor(sub, v.ppu, axis.beatLen, axis.barLen)
        drawRoll(clip, ed, view, tm, labelSp, noteColor, axis, stepDraw, scale, sub.label(stepDraw.takeIf { sub.adaptive }), num, den, rec)
    }
}

private fun snapFloorStep(t: Double, step: Double): Double = if (step <= 0) t else floor(t / step + 1e-9) * step

private fun DrawScope.drawRoll(
    clip: ClipState, ed: NoteEditor, view: RollView, tm: TextMeasurer, labelSp: TextUnit, noteColor: Color,
    axis: AxisSpec, step: Double, scale: ScaleInfo?, gridLabel: String, num: Int, den: Int, rec: Boolean,
) {
    val v = view.v
    val kbW = v.leftW
    val topH = v.topH
    val ppk = view.ppk
    val w = size.width
    val h = size.height
    val r0 = max(0, floor(view.topRow).toInt())
    val r1 = min(view.rowCount - 1, ceil(view.topRow + (h - topH) / ppk).toInt())
    val keyLbl = TextStyle(fontSize = labelSp, color = Color(0xFF202020))

    clipRect(kbW, topH, w, h) {
        for (r in r0..r1) {
            val p = view.pitchAt(r) ?: continue
            val y = view.yTop(r)
            val base = when {
                scale != null -> if (inScale(p, scale.root, scale.intervals)) RowInScale else RowOutScale
                isBlackKey(p) -> RowDark
                else -> RowLight
            }
            drawRect(base, Offset(kbW, y), Size(w - kbW, ppk))
            if (scale != null && ((p - scale.root) % 12 + 12) % 12 == 0) drawRect(LiveColors.Accent.copy(alpha = 0.10f), Offset(kbW, y), Size(w - kbW, ppk))
            if (p % 12 == 0) drawLine(Color(0xFF3F3F3F), Offset(kbW, y + ppk), Offset(w, y + ppk), 1f)
        }
        drawTimeGrid(v, axis, step, topH, h)
        val (ra, rb) = if (clip.looping) (v.pvLs ?: clip.loopStart) to (v.pvLe ?: clip.loopEnd) else (v.pvSm ?: clip.startMarker) to (v.pvEm ?: clip.endMarker)
        drawOutsideDim(v, ra, rb, topH, h)

        // notes (single pass, culled)
        val sel = ed.sel
        val dt = view.dt
        val dr = view.dr
        val dd = view.dd
        val muted = Color(0xFF777777)
        val ppu = v.ppu
        val xMin = v.unitOf(kbW) - 64.0
        val xMax = v.unitOf(w)
        for (n in ed.notes) {
            val isSel = n.id in sel
            val t = if (isSel) n.t + dt else n.t
            if (t > xMax) continue
            val d = if (isSel) n.d + dd else n.d
            if (t + d < xMin) continue
            var row = view.rowOf(n.p)
            if (isSel && dr != 0) row += dr
            if (row < r0 - 1 || row > r1 + 1) continue
            val x = v.xOf(t)
            val nw = (d * ppu).toFloat()
            val y = view.yTop(row)
            val base = if (n.m) muted else noteColor
            val a = 0.40f + 0.60f * (n.v / 127f)
            val sz = Size(max(nw - 1f, 2f), max(ppk - 1f, 1f))
            drawRect(base.copy(alpha = a), Offset(x, y + 0.5f), sz)
            if (isSel) drawRect(Color.White, Offset(x, y + 0.5f), sz, style = Stroke(1.5f))
        }
        view.ghost?.let { g ->
            val row = view.rowOf(g.p)
            drawRect(noteColor.copy(alpha = 0.7f), Offset(v.xOf(g.t), view.yTop(row) + 0.5f), Size(max((g.d * ppu).toFloat() - 1f, 2f), ppk - 1f))
        }
        view.box?.let { r ->
            drawRect(LiveColors.Accent.copy(alpha = 0.15f), r.topLeft, r.size)
            drawRect(LiveColors.Accent, r.topLeft, r.size, style = Stroke(1f))
        }
    }

    // top chrome (brace, ruler, flags, corner)
    drawClipTop(v, clip, axis, step, tm, labelSp, "$num/$den", gridLabel)

    // key column
    clipRect(0f, topH, kbW, h) {
        drawRect(Color(0xFF1E1E1E), Offset(0f, topH), Size(kbW, h - topH))
        val labelAll = ppk >= labelSp.toPx() * 1.15f || view.fold
        for (r in r0..r1) {
            val p = view.pitchAt(r) ?: continue
            val y = view.yTop(r)
            val black = isBlackKey(p) && !view.fold
            drawRect(
                if (black) Color(0xFF3A3A3A) else Color(0xFFC4C4C4), Offset(0f, y + 0.5f),
                Size(if (black) kbW * 0.6f else kbW, max(ppk - 1f, 1f)),
            )
            if (!black && (p % 12 == 0 || labelAll) && ppk >= labelSp.toPx() * 0.7f) {
                val inScaleRoot = scale != null && ((p - scale.root) % 12 + 12) % 12 == 0
                drawText(tm, pitchName(p), Offset(kbW * 0.62f + 1f, y + ppk / 2f - labelSp.value * 0.62f), if (inScaleRoot) keyLbl.copy(color = Color(0xFF0B6D99)) else keyLbl)
            }
            if (p % 12 == 0) drawLine(Color(0xFF808080), Offset(0f, y + ppk), Offset(kbW, y + ppk), 1f)
        }
    }
    drawLine(LiveColors.Line, Offset(kbW, topH), Offset(kbW, h), 1f)
}

@Composable
private fun LaneCanvas(clip: ClipState, ed: NoteEditor, view: RollView, axis: AxisSpec, sub: Subdiv, lane: Lane, modifier: Modifier) {
    val density = LocalDensity.current
    val v = view.v
    val tm = rememberTextMeasurer()
    val labelSp = with(density) { 9.dp.toSp() }
    val laneS = rememberUpdatedState(lane)
    val barW = with(density) { 3.dp.toPx() }
    val reach = with(density) { 16.dp.toPx() }
    val noteColor = liveColor(clip.color)
    Canvas(
        modifier.background(LiveColors.Bg).clipToBounds().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.x < v.leftW) return@awaitEachGesture
                val lane0 = laneS.value
                val hh = size.height.toFloat()
                val fracAt = { y: Float -> (1f - (y - 2f) / (hh - 4f)).coerceIn(0f, 1f) }
                // nearest bar by x; among ties prefer the one whose value is closest to the touch
                var best: Note? = null
                var bestDx = Float.MAX_VALUE
                for (n in ed.notes) {
                    val dx = abs(v.xOf(n.t) + barW / 2 - down.position.x)
                    if (dx > reach) continue
                    val b = best
                    if (b == null || dx < bestDx - 3f) {
                        best = n; bestDx = dx
                    } else if (abs(dx - bestDx) <= 3f) {
                        val f = fracAt(down.position.y)
                        val vn = if (lane0 == Lane.Vel) n.v / 127f else n.prob
                        val vb = if (lane0 == Lane.Vel) b.v / 127f else b.prob
                        if (abs(vn - f) < abs(vb - f)) best = n
                        bestDx = min(dx, bestDx)
                    }
                }
                val target = best ?: return@awaitEachGesture
                val ids = if (target.id in ed.sel && ed.sel.size > 1) ed.sel else setOf(target.id)
                ed.busy = true
                var last: List<NoteEdit> = emptyList()
                fun apply(y: Float) {
                    val f = fracAt(y)
                    last = ids.map { id -> if (lane0 == Lane.Vel) NoteEdit(id, v = velFromFrac(f)) else NoteEdit(id, prob = (f * 20).roundToInt() / 20f) }
                    ed.modify(last, send = false)
                }
                try {
                    apply(down.position.y)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        apply(c.position.y)
                        c.consume()
                    }
                } finally {
                    ed.modify(last)
                    ed.endGesture()
                }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        drawRect(LiveColors.Panel, Offset(0f, 0f), Size(v.leftW, h))
        drawText(tm, if (lane == Lane.Vel) "VELOCITY" else "PROBABILITY", Offset(3f, 3f), TextStyle(fontSize = labelSp, color = LiveColors.TextDim))
        drawText(tm, if (lane == Lane.Vel) "127" else "100%", Offset(3f, h - labelSp.value * 1.6f), TextStyle(fontSize = labelSp, color = LiveColors.TextDim))
        drawLine(LiveColors.Line, Offset(v.leftW, 0f), Offset(v.leftW, h), 1f)
        clipRect(v.leftW, 0f, w, h) {
            drawTimeGrid(v, axis, gridStepFor(sub, v.ppu, axis.beatLen, axis.barLen), 0f, h)
            for (f in listOf(0.25f, 0.5f, 0.75f)) drawLine(Color(0xFF2A2A2A), Offset(v.leftW, h * (1 - f)), Offset(w, h * (1 - f)), 1f)
            val sel = ed.sel
            val xMax = v.unitOf(w)
            val xMin = v.unitOf(v.leftW) - 2.0
            for (n in ed.notes) {
                if (n.t > xMax || n.t < xMin) continue
                val x = v.xOf(n.t + (if (n.id in sel) view.dt else 0.0))
                val f = if (lane == Lane.Vel) n.v / 127f else n.prob
                val bh = f * (h - 4f)
                val c = if (n.id in sel) LiveColors.Accent else noteColor
                drawRect(c.copy(alpha = 0.85f), Offset(x, h - bh - 1f), Size(barW, bh))
                drawCircle(c, barW * 0.9f, Offset(x + barW / 2, h - bh - 1f))
            }
        }
    }
}

/** Deleting more than this many notes at once asks first. */
internal const val BULK_DELETE_CONFIRM = 50
