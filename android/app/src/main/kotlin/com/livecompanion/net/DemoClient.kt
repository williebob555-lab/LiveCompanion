package com.livecompanion.net

import com.livecompanion.state.ArrClip
import com.livecompanion.state.ArrState
import com.livecompanion.state.ClipState
import com.livecompanion.state.DevicesState
import com.livecompanion.state.FocusState
import com.livecompanion.state.HelloInfo
import com.livecompanion.state.LiveJson
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Meters
import com.livecompanion.state.Note
import com.livecompanion.state.P
import com.livecompanion.state.RtFrame
import com.livecompanion.state.SessionState
import com.livecompanion.state.SlotClip
import com.livecompanion.state.SlotInfo
import com.livecompanion.state.SlotStatus
import com.livecompanion.state.SlotsState
import com.livecompanion.state.TakesState
import com.livecompanion.state.TrackInfo
import com.livecompanion.state.TracksState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * In-app fake Live ("Try demo"). Same API as [LiveClient]; requests are answered locally against the JSON fixtures in
 * assets/demo (recorded from the mock server). Common ops are applied to the [LiveStore]; everything else answers ok
 * silently (logcat only, no user notice). [loader] returns the text of `<name>.json` or null.
 * [tickMs] <= 0 disables the playback ticker (tests drive [tick] by hand).
 */
class DemoClient(
    store: LiveStore,
    scope: CoroutineScope,
    private val loader: (String) -> String?,
    private val tickMs: Long = 100L,
) : LiveClient(store, scope) {

    private var ticker: Job? = null
    private val slotsBy = HashMap<String, SlotsState>()
    private val arrBy = HashMap<String, ArrState>()
    private val devicesBy = HashMap<String, DevicesState>()
    private val takesBy = HashMap<String, TakesState>()
    private val focusBy = HashMap<String, FocusState>()
    private var focusRef = ""
    private var openLoc: JsonObject? = null
    private var t = 0.0

    // ------------------------------------------------------------ lifecycle
    override fun connect(host: String, port: Int) {
        ticker?.cancel()
        store.reset()
        slotsBy.clear(); arrBy.clear(); devicesBy.clear(); takesBy.clear(); focusBy.clear()
        openLoc = null; focusRef = ""; t = 0.0
        store.setHello(HelloInfo(1, "12.4.1 (demo)", "demo"))
        fixture("sec_session")?.let { store.handleSection("session", it) }
        store.updateSession { it.copy(follow = false, playing = false, pos = 0.0, loop = true, loopStart = 0.0, loopLen = 16.0) }
        fixture("sec_tracks")?.let { store.handleSection("tracks", it) }
        val first = store.tracks.value.tracks.firstOrNull()?.ref
        _state.value = ConnState(ConnStatus.CONNECTED, DEMO_TARGET, 0, null)
        if (first != null) focus(first)
        if (tickMs > 0) ticker = scope2.launch {
            while (isActive) { delay(tickMs); tick(tickMs / 1000.0) }
        }
    }

    // LiveClient keeps its scope private; keep our own handle for the ticker.
    private val scope2: CoroutineScope = scope

    override fun disconnect() {
        ticker?.cancel(); ticker = null
        store.reset()
        _state.value = ConnState()
    }

    // ------------------------------------------------------------ fixtures
    private fun fixture(name: String): JsonElement? = try {
        loader(name)?.let { LiveJson.parseToJsonElement(it).jsonObject["d"] }
    } catch (_: Exception) { null }

    private fun <T> decode(name: String, ser: KSerializer<T>): T? =
        fixture(name)?.takeIf { it !is JsonNull }?.let { runCatching { LiveJson.decodeFromJsonElement(ser, it) }.getOrNull() }

    private fun <T> push(section: String, ser: KSerializer<T>, v: T) =
        store.handleSection(section, LiveJson.encodeToJsonElement(ser, v))

    /** Fixture family for a track: its own recorded set if it has one, else a generic one by kind. */
    private fun family(ref: String): String? {
        val k = store.tracks.value.find(ref)?.kind
        return when (ref) {
            "t0" -> "midi"; "t2" -> "midi_rack"; "t3" -> "audio"; "t4" -> "audio2"; "t5" -> "group"
            else -> when (k) { "midi" -> "midi"; "audio" -> "audio2"; "group" -> "group"; "return" -> "return"; "master" -> "master"; else -> null }
        }
    }

    private fun emptySlots(ref: String): SlotsState {
        val n = store.tracks.value.scenes.size
        return SlotsState(ref, List(n) { SlotInfo(it, false, true, SlotStatus.EMPTY, null) })
    }

    private fun slotsFor(ref: String): SlotsState = slotsBy.getOrPut(ref) {
        val tr = store.tracks.value.find(ref)
        val f = family(ref)
        if (tr == null || tr.kind == "return" || tr.kind == "master" || f == null) emptySlots(ref)
        else (decode("sec_slots_$f", SlotsState.serializer()) ?: emptySlots(ref)).copy(tr = ref)
    }

    private fun arrFor(ref: String): ArrState = arrBy.getOrPut(ref) {
        val f = family(ref)
        (f?.let { decode("sec_arr_$f", ArrState.serializer()) } ?: ArrState()).copy(tr = ref)
    }

    private fun devicesFor(ref: String): DevicesState = devicesBy.getOrPut(ref) {
        val f = family(ref)
        (f?.let { decode("sec_devices_$f", DevicesState.serializer()) } ?: DevicesState()).copy(tr = ref)
    }

    private fun takesFor(ref: String): TakesState = takesBy.getOrPut(ref) {
        val f = family(ref)
        val base = (f?.let { decode("sec_takes_$f", TakesState.serializer()) } ?: TakesState()).copy(tr = ref)
        // Demo ships a take lane so promote / demote / move can be tried: one alternate take of the first arrangement clip.
        val first = if (base.supported && base.lanes.isEmpty()) arrFor(ref).clips.firstOrNull() else null
        if (first == null) base
        else base.copy(lanes = listOf(com.livecompanion.state.TakeLane(0, "Take Lane 1",
            listOf(com.livecompanion.state.TakeClip(0, first.name.ifBlank { "Take" } + " (alt)", first.color, first.start, first.end)))))
    }

    private fun focusFor(ref: String): FocusState = focusBy.getOrPut(ref) {
        val tr = store.tracks.value.find(ref)
        val f = family(ref)
        val base = f?.let { decode("sec_focus_$f", FocusState.serializer()) } ?: FocusState()
        base.copy(ref = ref, name = tr?.name ?: base.name, color = tr?.color ?: base.color, kind = tr?.kind ?: base.kind,
            vol = tr?.vol ?: base.vol)
    }

    private fun pushFocus() { focusBy[focusRef]?.let { push("focus", FocusState.serializer(), it) } }
    private fun pushSlots(ref: String) { if (ref == focusRef) push("slots", SlotsState.serializer(), slotsFor(ref)) }
    private fun pushArr(ref: String) { if (ref == focusRef) push("arr", ArrState.serializer(), arrFor(ref)) }
    private fun pushTakes(ref: String) { if (ref == focusRef) push("takes", TakesState.serializer(), takesFor(ref)) }
    private fun pushDevices(ref: String) { if (ref == focusRef) push("devices", DevicesState.serializer(), devicesFor(ref)) }

    private fun focus(ref: String) {
        if (store.tracks.value.find(ref) == null) return
        focusRef = ref
        val tr = store.tracks.value.find(ref)!!
        focusBy[ref] = focusFor(ref).copy(arm = tr.arm, mute = tr.mute, solo = tr.solo, monitor = tr.monitor,
            name = tr.name, color = tr.color, playingSlot = tr.playingSlot)
        pushFocus()
        push("slots", SlotsState.serializer(), slotsFor(ref))
        push("arr", ArrState.serializer(), arrFor(ref))
        push("takes", TakesState.serializer(), takesFor(ref))
        push("devices", DevicesState.serializer(), devicesFor(ref))
        store.updateSession { it.copy(selTrack = ref) }
        store.handleSection("params", JsonNull)
    }

    // ------------------------------------------------------------ requests
    override suspend fun request(op: String, args: JsonObject, timeoutMs: Long): JsonElement {
        if (SimDelay.ms > 0) delay(SimDelay.ms)
        try {
            return handle(op, args)
        } catch (e: LiveException) {
            throw e
        } catch (e: Exception) {
            store.notice("Demo: $op failed (${e.message?.take(60)})")
            return JsonNull
        }
    }

    private fun S(a: JsonObject, k: String) = a[k]?.jsonPrimitive?.contentOrNull
    private fun I(a: JsonObject, k: String) = a[k]?.jsonPrimitive?.intOrNull
    private fun D(a: JsonObject, k: String) = a[k]?.jsonPrimitive?.doubleOrNull
    private fun B(a: JsonObject, k: String) = a[k]?.jsonPrimitive?.let { it.booleanOrNull ?: (it.doubleOrNull?.let { d -> d != 0.0 }) }

    /** Unsupported demo ops are silent for the user (no notice); only written to logcat. */
    private fun unsupported(op: String): JsonElement {
        try { android.util.Log.d("Demo", "not simulated: $op") } catch (_: Throwable) {}  // logcat only, never a user notice
        return JsonNull
    }

    private fun handle(op: String, a: JsonObject): JsonElement {
        when (op) {
            "sys.info" -> return fixture("res_sys_info") ?: JsonNull
            "sys.probe" -> return JsonNull
            "focus.set" -> S(a, "tr")?.let { focus(it) }
            "focus.follow" -> B(a, "on")?.let { v -> store.updateSession { it.copy(follow = v) } }
            "view.select" -> S(a, "tr")?.let { r -> store.updateSession { it.copy(selTrack = r) } }
            "song.play", "song.continue" -> store.updateSession { it.copy(playing = true) }
            "song.stop" -> {
                val was = store.session.value.playing
                store.updateSession { it.copy(playing = false, pos = if (was) it.pos else 0.0) }
                if (!was) t = 0.0
                if (store.session.value.playing.not()) emitRt()
            }
            "song.seek" -> D(a, "beats")?.let { v -> setPos(v.coerceAtLeast(0.0)) }
            "song.jump", "song.scrub" -> D(a, "beats")?.let { v -> setPos((store.session.value.pos + v).coerceAtLeast(0.0)) }
            "song.set" -> songSet(S(a, "k") ?: "", a["v"])
            "song.tap_tempo", "song.undo", "song.redo", "song.capture_midi", "song.re_enable_automation", "song.cue" -> return unsupported(op)
            "cue.move" -> {
                val i = I(a, "i"); val tt = D(a, "time")
                if (i != null && tt != null && tt >= 0.0) store.updateSession { ss ->
                    ss.copy(cues = ss.cues.mapIndexed { ci, c -> if (ci == i) c.copy(t = tt) else c }.sortedBy { it.t })
                }
            }
            "song.stop_all_clips" -> stopAll()
            "track.set" -> trackSet(a)
            "track.stop" -> S(a, "tr")?.let { stopTrack(it) }
            "track.create" -> trackCreate(a)
            "track.delete" -> S(a, "tr")?.let { trackDelete(it) }
            "track.duplicate" -> S(a, "tr")?.let { trackDuplicate(it) }
            "track.move_check" -> return trackMoveCheck(a)
            "track.move" -> return trackMove(a)
            "slot.fire" -> { val tr = S(a, "tr"); val i = I(a, "i"); if (tr != null && i != null) fireSlot(tr, i) }
            "scene.fire" -> I(a, "i")?.let { i -> store.tracks.value.tracks.forEach { fireSlot(it.ref, i) } }
            "slot.delete" -> {
                val tr = S(a, "tr"); val i = I(a, "i")
                if (tr != null && i != null) { slotsBy[tr] = slotsFor(tr).let { s -> s.copy(slots = s.slots.map { if (it.i == i) SlotInfo(i, false, true, SlotStatus.EMPTY, null) else it }) }; pushSlots(tr) }
            }
            "slot.create" -> {
                val tr = S(a, "tr"); val i = I(a, "i")
                if (tr != null && i != null) {
                    val len = D(a, "len") ?: 4.0
                    slotsBy[tr] = slotsFor(tr).let { s -> s.copy(slots = s.slots.map { if (it.i == i) SlotInfo(i, true, true, SlotStatus.STOPPED, SlotClip("New clip", 0x3FC0FF, len, true, false)) else it }) }
                    pushSlots(tr)
                }
            }
            "clip.fire" -> { val l = a["loc"] as? JsonObject; if (l != null && S(l, "w") == "slot") fireSlot(S(l, "tr") ?: "", I(l, "i") ?: 0) }
            "clip.stop" -> { val l = a["loc"] as? JsonObject; if (l != null && S(l, "w") == "slot") stopSlot(S(l, "tr") ?: "", I(l, "i") ?: 0) }
            "clip.open" -> (a["loc"] as? JsonObject)?.let { openClip(it) }
            "clip.close" -> { openLoc = null; store.handleSection("clip", JsonNull) }
            "clip.set" -> clipSet(a)
            "notes.add", "notes.modify", "notes.remove", "notes.clear", "notes.transpose", "notes.duplicate" -> notes(op, a)
            "device.toggle" -> deviceEdit(a) { d -> d.copy(on = !d.on) }
            "device.param" -> {
                val i = I(a, "i"); val v = D(a, "v")?.toFloat()
                if (i != null && v != null) deviceEdit(a) { d -> d.copy(pp = d.pp.map { p -> if (p.i == i) p.copy(v = v, str = fmt(v, p.min, p.max)) else p }) }
            }
            "clip.peaks" -> return demoPeaks(a)
            "arr.move" -> return arrMove(a)
            "arr.trim" -> arrTrim(a)
            "arr.create_midi" -> arrCreate(a)
            "clip.delete" -> clipDelete(a)
            "clip.to_arr" -> toArr(a)
            "slot.move", "slot.copy" -> slotTransfer(a, op == "slot.move")
            "slot.duplicate" -> slotDuplicate(a)
            "takes.promote" -> takesPromote(a)
            "takes.demote" -> return takesDemote(a)
            "takes.move" -> return takesMove(a)
            "takes.create_lane" -> takesCreateLane(a)
            "takes.rename" -> takesRename(a)
            "browser.list" -> {
                val path = (a["path"] as? JsonArray)?.size ?: 0
                return fixture(when (path) { 0 -> "res_browser_roots"; 1 -> "res_browser_sub"; else -> "res_browser_deep" }) ?: JsonArray(emptyList())
            }
            "browser.preview", "browser.preview_stop" -> Unit
            "device.available" -> {
                val m = com.livecompanion.ui.devices.standardFallback()
                fun arr(c: com.livecompanion.ui.devices.NativeCat) = JsonArray((m[c] ?: emptyList()).map { JsonPrimitive(it) })
                return JsonObject(mapOf("instruments" to arr(com.livecompanion.ui.devices.NativeCat.Instruments),
                    "audio_effects" to arr(com.livecompanion.ui.devices.NativeCat.AudioFx),
                    "midi_effects" to arr(com.livecompanion.ui.devices.NativeCat.MidiFx)))
            }
            else -> return unsupported(op)
        }
        return JsonNull
    }

    private fun fmt(v: Float, min: Float, max: Float) = if (max - min > 10) "%.0f".format(v) else "%.2f".format(v)

    // ------------------------------------------------------------ transport
    private fun setPos(p: Double) { store.updateSession { it.copy(pos = p) }; t = p; emitRt() }

    private fun songSet(k: String, v: JsonElement?) {
        val d = (v as? JsonPrimitive)?.doubleOrNull
        val b = (v as? JsonPrimitive)?.let { it.booleanOrNull ?: it.doubleOrNull?.let { x -> x != 0.0 } }
        store.updateSession { s ->
            when (k) {
                "tempo" -> if (d != null) s.copy(tempo = d.toFloat().coerceIn(20f, 999f)) else s
                "sig" -> (v as? JsonArray)?.let { arr -> s.copy(sig = arr.map { it.jsonPrimitive.intOrNull ?: 4 }) } ?: s
                "metronome" -> s.copy(metronome = b ?: s.metronome)
                "loop" -> s.copy(loop = b ?: s.loop)
                "loop_start" -> s.copy(loopStart = d ?: s.loopStart)
                "loop_len" -> s.copy(loopLen = (d ?: s.loopLen).coerceAtLeast(0.25))
                "rec" -> s.copy(rec = b ?: s.rec)
                "session_rec" -> s.copy(sessionRec = b ?: s.sessionRec)
                "overdub" -> s.copy(overdub = b ?: s.overdub)
                "punch_in" -> s.copy(punchIn = b ?: s.punchIn)
                "punch_out" -> s.copy(punchOut = b ?: s.punchOut)
                "count_in" -> s.copy(countIn = d?.toInt() ?: s.countIn)
                "quant" -> s.copy(quant = d?.toInt() ?: s.quant)
                "rec_quant" -> s.copy(recQuant = d?.toInt() ?: s.recQuant)
                "auto_arm" -> s.copy(autoArm = b ?: s.autoArm)
                "back_to_arr" -> s.copy(backToArr = b ?: s.backToArr)
                "view" -> s.copy(view = (v as? JsonPrimitive)?.contentOrNull ?: s.view)
                else -> s
            }
        }
    }

    /** Advance playback by [dtSec] seconds and emit an rt frame. Public for tests. */
    fun tick(dtSec: Double) {
        val s = store.session.value
        if (s.playing) {
            var p = s.pos + dtSec * s.tempo / 60.0
            if (s.loop && s.loopLen > 0 && p >= s.loopStart + s.loopLen && s.pos < s.loopStart + s.loopLen) {
                p = s.loopStart + (p - (s.loopStart + s.loopLen))
            }
            t = p
            store.updateSession { it.copy(pos = p) }
        }
        emitRt()
    }

    private fun emitRt() {
        val s = store.session.value
        val ph = t * 2 * PI
        val lvl = if (s.playing) (0.45 + 0.3 * abs(sin(ph * 0.9)) + 0.1 * sin(ph * 3.1)).toFloat().coerceIn(0f, 1f) else 0f
        val slotPos = HashMap<String, Double>()
        slotsBy[focusRef]?.slots?.forEach { sl ->
            if (sl.status == SlotStatus.PLAYING) {
                val len = sl.clip?.len?.takeIf { it > 0 } ?: 4.0
                slotPos[sl.i.toString()] = s.pos % len
            }
        }
        val clipLen = store.clip.value.takeIf { it.loc != null }
        val clipPos = clipLen?.let { c ->
            val l = (c.loopEnd - c.loopStart).coerceAtLeast(0.25)
            if (s.playing) c.loopStart + (s.pos % l) else null
        }
        store.handleRt(LiveJson.encodeToJsonElement(RtFrame.serializer(), RtFrame(
            pos = s.pos, playing = s.playing, beatInBar = ((s.pos % s.sigNum)).toFloat(),
            meters = Meters(listOf(lvl, lvl * 0.9f), listOf(lvl * 0.8f, lvl * 0.75f)),
            clipPos = clipPos, slotPos = slotPos, cpu = if (s.playing) 0.12f + 0.03f * lvl else 0.04f,
        )))
    }

    // ------------------------------------------------------------ tracks
    private fun trackSet(a: JsonObject) {
        val tr = S(a, "tr") ?: return
        val k = S(a, "k") ?: return
        val v = a["v"] as? JsonPrimitive ?: return
        val b = v.booleanOrNull ?: v.doubleOrNull?.let { it != 0.0 }
        val d = v.doubleOrNull
        val info = store.tracks.value.find(tr) ?: return
        when (k) {
            "arm" -> if (b != null) {
                if (b && store.session.value.autoArm) store.tracks.value.tracks.filter { it.arm && it.ref != tr }.forEach { o -> store.patchTrack(o.ref) { it.copy(arm = false) } }
                store.patchTrack(tr) { it.copy(arm = b) }
            }
            "mute" -> if (b != null) store.patchTrack(tr) { it.copy(mute = b) }
            "solo" -> if (b != null) store.patchTrack(tr) { it.copy(solo = b) }
            "monitor" -> if (d != null) store.patchTrack(tr) { it.copy(monitor = d.toInt().coerceIn(0, 2)) }
            "folded" -> if (b != null) store.patchTrack(tr) { it.copy(folded = b) }
            "name" -> v.contentOrNull?.takeIf { it.isNotBlank() }?.let { n -> store.patchTrack(tr) { it.copy(name = n) } }
            "color" -> if (d != null) store.patchTrack(tr) { it.copy(color = d.toInt()) }
            "vol" -> if (d != null) {
                val p = P(d.toFloat(), 0f, 1f, volStr(d.toFloat()))
                focusBy[tr] = focusFor(tr).copy(vol = p)
                if (info.kind == "return" || info.kind == "master") store.patchTrack(tr) { it.copy(vol = p) }
            }
            "pan" -> if (d != null) focusBy[tr] = focusFor(tr).copy(pan = P(d.toFloat(), -1f, 1f, panStr(d.toFloat())))
            "send" -> Unit
            else -> { unsupported("track.set $k"); return }
        }
        val upd = store.tracks.value.find(tr)
        if (upd != null) {
            focusBy[tr] = focusFor(tr).copy(arm = upd.arm, mute = upd.mute, solo = upd.solo, monitor = upd.monitor, name = upd.name, color = upd.color)
        }
        if (tr == focusRef) pushFocus()
    }

    private fun volStr(v: Float): String {
        if (v <= 0.0001f) return "-inf dB"
        val db = 20 * kotlin.math.log10(v.toDouble() / 0.85)
        return "%.1f dB".format(db)
    }

    private fun panStr(v: Float) = if (abs(v) < 0.005f) "C" else if (v < 0) "${(-v * 50).toInt()}L" else "${(v * 50).toInt()}R"

    private fun trackCreate(a: JsonObject) {
        val kind = S(a, "kind") ?: "midi"
        val st = store.tracks.value
        val n = st.tracks.size + st.returns.size + 1
        val ref = "t${n + 100 + st.all.size}"
        val color = listOf(0xFF7F2A, 0x3FC0FF, 0x7ED321, 0xFFD60A, 0xB05CF5)[n % 5]
        if (kind == "return") {
            val r = TrackInfo(ref, "${('A' + st.returns.size)} Return", color, "return", vol = P(0.85f, 0f, 1f, "0.0 dB"))
            push("tracks", TracksState.serializer(), st.copy(returns = st.returns + r))
        } else {
            val t = TrackInfo(ref, (if (kind == "audio") "$n Audio" else "$n MIDI"), color, if (kind == "audio") "audio" else "midi", armable = true)
            val idx = (I(a, "i") ?: -1).let { if (it in 0..st.tracks.size) it else st.tracks.size }
            push("tracks", TracksState.serializer(), st.copy(tracks = st.tracks.toMutableList().also { it.add(idx, t) }))
        }
        focus(ref)
    }

    private fun trackDelete(ref: String) {
        val st = store.tracks.value
        if (st.find(ref) == null || ref == st.master?.ref) return
        push("tracks", TracksState.serializer(), st.copy(tracks = st.tracks.filter { it.ref != ref }, returns = st.returns.filter { it.ref != ref }))
        slotsBy.remove(ref); arrBy.remove(ref); devicesBy.remove(ref); takesBy.remove(ref); focusBy.remove(ref)
        if (ref == focusRef) store.tracks.value.all.firstOrNull()?.let { focus(it.ref) }
    }

    /** Demo of the server's rebuild-move: only plain audio/MIDI tracks; group members need a confirm (they leave the group). */
    private fun movableTrack(a: JsonObject): TrackInfo {
        val ref = S(a, "tr") ?: throw LiveException("missing arg: tr")
        val st = store.tracks.value
        val t = st.tracks.firstOrNull { it.ref == ref }
            ?: throw LiveException(if (st.returns.any { it.ref == ref }) "return tracks can't be moved" else if (st.master?.ref == ref) "the master track can't be moved" else "no such track: $ref")
        if (t.kind == "group" || t.foldable) throw LiveException("group tracks can't be moved")
        return t
    }

    private fun moveLosses(t: TrackInfo): List<String> = if (t.group != null) listOf("group membership") else emptyList()

    private fun trackMoveCheck(a: JsonObject): JsonElement {
        val l = moveLosses(movableTrack(a))
        return buildJsonObject { put("ok_to_move", l.isEmpty()); put("losses", JsonArray(l.map { JsonPrimitive(it) })) }
    }

    private fun trackMove(a: JsonObject): JsonElement {
        val t = movableTrack(a)
        val st = store.tracks.value
        val to = I(a, "to") ?: throw LiveException("missing arg: to")
        if (to < 0 || to >= st.tracks.size) throw LiveException("index out of range")
        val losses = moveLosses(t)
        if (losses.isNotEmpty() && B(a, "force") != true) {
            throw LiveException("needs_confirm", buildJsonObject { put("losses", JsonArray(losses.map { JsonPrimitive(it) })) })
        }
        val list = st.tracks.toMutableList()
        list.remove(t)
        list.add(to, if (losses.isNotEmpty()) t.copy(group = null) else t)
        push("tracks", TracksState.serializer(), st.copy(tracks = list))
        focus(t.ref)
        return buildJsonObject { put("tr", t.ref) }
    }

    private fun trackDuplicate(ref: String) {
        val st = store.tracks.value
        val src = st.tracks.firstOrNull { it.ref == ref } ?: return
        val nr = "t${200 + st.all.size + st.tracks.indexOf(src)}${st.tracks.size}"
        val copy = src.copy(ref = nr, name = src.name + " copy", arm = false, playingSlot = -1, firedSlot = -1)
        slotsBy[nr] = slotsFor(ref).copy(tr = nr)
        arrBy[nr] = arrFor(ref).copy(tr = nr)
        devicesBy[nr] = devicesFor(ref).copy(tr = nr)
        takesBy[nr] = takesFor(ref).copy(tr = nr)
        val idx = st.tracks.indexOf(src) + 1
        push("tracks", TracksState.serializer(), st.copy(tracks = st.tracks.toMutableList().also { it.add(idx, copy) }))
    }

    // ------------------------------------------------------------ clips / slots
    private fun fireSlot(tr: String, i: Int) {
        val s = slotsFor(tr)
        if (s.slots.none { it.i == i && it.hasClip }) { stopTrack(tr); return }
        slotsBy[tr] = s.copy(slots = s.slots.map {
            when { it.i == i -> it.copy(status = SlotStatus.PLAYING); it.status == SlotStatus.PLAYING -> it.copy(status = SlotStatus.STOPPED); else -> it }
        })
        store.patchTrack(tr) { it.copy(playingSlot = i, firedSlot = -1) }
        if (tr == focusRef) { focusBy[tr] = focusFor(tr).copy(playingSlot = i); pushFocus() }
        pushSlots(tr)
        store.updateSession { it.copy(playing = true) }
    }

    private fun stopSlot(tr: String, i: Int) {
        slotsBy[tr] = slotsFor(tr).let { s -> s.copy(slots = s.slots.map { if (it.i == i && it.hasClip) it.copy(status = SlotStatus.STOPPED) else it }) }
        store.patchTrack(tr) { if (it.playingSlot == i) it.copy(playingSlot = -1) else it }
        if (tr == focusRef) { focusBy[tr] = focusFor(tr).copy(playingSlot = -1); pushFocus() }
        pushSlots(tr)
    }

    private fun stopTrack(tr: String) {
        slotsBy[tr] = slotsFor(tr).let { s -> s.copy(slots = s.slots.map { if (it.status == SlotStatus.PLAYING) it.copy(status = SlotStatus.STOPPED) else it }) }
        store.patchTrack(tr) { it.copy(playingSlot = -1, firedSlot = -1) }
        if (tr == focusRef) { focusBy[tr] = focusFor(tr).copy(playingSlot = -1); pushFocus() }
        pushSlots(tr)
    }

    private fun stopAll() { store.tracks.value.tracks.forEach { stopTrack(it.ref) } }

    private fun openClip(loc: JsonObject) {
        val w = S(loc, "w") ?: "slot"
        val tr = S(loc, "tr") ?: return
        val audio = store.tracks.value.find(tr)?.kind == "audio" || family(tr)?.startsWith("audio") == true
        val name = (if (audio) "sec_clip_audio_" else "sec_clip_midi_") + (if (w == "take") "take" else if (w == "arr") "arr" else "slot")
        val fx = (if (audio || w != "take") fixture(name) else fixture("sec_clip_midi_arr")) ?: return
        val base = runCatching { LiveJson.decodeFromJsonElement(ClipState.serializer(), fx) }.getOrNull() ?: return
        var c = base.copy(loc = loc)
        // Take the real name/colour from the slot / arrangement entry so it matches what was tapped.
        val i = I(loc, "i") ?: 0
        when (w) {
            "slot" -> slotsFor(tr).slots.firstOrNull { it.i == i }?.clip?.let { sc ->
                c = c.copy(name = sc.name, color = sc.color, len = sc.len.takeIf { it > 0 } ?: c.len, looping = sc.looping)
                if (sc.len > 0) c = c.copy(loopEnd = sc.len.coerceAtMost(maxOf(c.loopEnd, sc.len)), endMarker = sc.len)
            }
            "arr" -> arrFor(tr).clips.firstOrNull { it.i == i }?.let { ac -> c = c.copy(name = ac.name, color = ac.color, start = ac.start, end = ac.end) }
        }
        openLoc = loc
        push("clip", ClipState.serializer(), c.copy(notesTotal = c.notes.size))
    }

    private fun clipSet(a: JsonObject) {
        val loc = a["loc"] as? JsonObject ?: return
        val k = S(a, "k") ?: return
        val v = a["v"] as? JsonPrimitive ?: return
        val d = v.doubleOrNull
        val b = v.booleanOrNull ?: d?.let { it != 0.0 }
        val tr = S(loc, "tr") ?: ""; val w = S(loc, "w"); val i = I(loc, "i") ?: 0
        val isOpen = store.clip.value.loc == loc || (store.clip.value.loc != null && openLoc == loc)
        if (isOpen) {
            store.updateClip { c ->
                when (k) {
                    "name" -> c.copy(name = v.contentOrNull ?: c.name)
                    "color" -> c.copy(color = d?.toInt() ?: c.color)
                    "looping" -> c.copy(looping = b ?: c.looping)
                    "loop_start" -> c.copy(loopStart = d ?: c.loopStart)
                    "loop_end" -> c.copy(loopEnd = d ?: c.loopEnd)
                    "start_marker" -> c.copy(startMarker = d ?: c.startMarker)
                    "end_marker" -> c.copy(endMarker = d ?: c.endMarker)
                    "sig_num" -> c.copy(signature = listOf(d?.toInt() ?: 4, c.signature.getOrElse(1) { 4 }))
                    "sig_den" -> c.copy(signature = listOf(c.signature.getOrElse(0) { 4 }, d?.toInt() ?: 4))
                    "muted" -> c.copy(muted = b ?: c.muted)
                    "gain" -> c.copy(gain = d?.toFloat() ?: c.gain)
                    "pitch_coarse" -> c.copy(pitchCoarse = d?.toInt() ?: c.pitchCoarse)
                    "pitch_fine" -> c.copy(pitchFine = d?.toFloat() ?: c.pitchFine)
                    "warping" -> c.copy(warping = b ?: c.warping)
                    "warp_mode" -> c.copy(warpMode = v.contentOrNull ?: c.warpMode)
                    "launch_mode" -> c.copy(launchMode = d?.toInt() ?: c.launchMode)
                    "launch_quant" -> c.copy(launchQuant = d?.toInt() ?: c.launchQuant)
                    "legato" -> c.copy(legato = b ?: c.legato)
                    else -> c
                }
            }
        }
        if (w == "slot" && (k == "name" || k == "color" || k == "looping")) {
            slotsBy[tr] = slotsFor(tr).let { s ->
                s.copy(slots = s.slots.map { sl ->
                    if (sl.i != i || sl.clip == null) sl else sl.copy(clip = when (k) {
                        "name" -> sl.clip.copy(name = v.contentOrNull ?: sl.clip.name)
                        "color" -> sl.clip.copy(color = d?.toInt() ?: sl.clip.color)
                        else -> sl.clip.copy(looping = b ?: sl.clip.looping)
                    })
                })
            }
            pushSlots(tr)
        }
        if (w == "arr" && (k == "name" || k == "color")) {
            arrBy[tr] = arrFor(tr).let { s ->
                s.copy(clips = s.clips.map { c ->
                    if (c.i != i) c else if (k == "name") c.copy(name = v.contentOrNull ?: c.name) else c.copy(color = d?.toInt() ?: c.color)
                })
            }
            pushArr(tr)
        }
    }

    // ------------------------------------------------------------ waveforms
    /** Bundled peaks (assets/demo/peaks_*.json, recorded from the mock media) resampled to the requested bucket count. */
    private fun demoPeaks(a: JsonObject): JsonElement {
        val loc = locOf(a)
        val n = (I(a, "n") ?: 1024).coerceIn(16, 8192)
        val path = when {
            store.clip.value.loc == loc && store.clip.value.filePath.isNotBlank() -> store.clip.value.filePath
            S(loc, "w") == "arr" -> arrFor(S(loc, "tr") ?: "").clips.firstOrNull { it.i == I(loc, "i") }?.filePath.orEmpty()
            else -> ""
        }
        val key = if (path.contains("pad_chord", true)) "peaks_pad_chord" else "peaks_sine_a"
        val src = fixture(key) as? JsonObject ?: throw LiveException("no audio file")
        val sn = src["n"]?.jsonPrimitive?.intOrNull ?: return src
        fun resample(k: String): JsonArray = JsonArray((src[k] as JsonArray).map { chan ->
            val v = (chan as JsonArray).map { it.jsonPrimitive.doubleOrNull ?: 0.0 }
            JsonArray(List(n) { b ->
                val lo = (b.toLong() * sn / n).toInt().coerceIn(0, sn - 1)
                val hi = ((b + 1).toLong() * sn / n).toInt().coerceIn(lo + 1, sn)
                var m = v[lo]
                for (j in lo until hi) m = if (k == "min") minOf(m, v[j]) else maxOf(m, v[j])
                JsonPrimitive(m)
            })
        })
        return buildJsonObject {
            put("sr", src["sr"] ?: JsonPrimitive(44100)); put("ch", src["ch"] ?: JsonPrimitive(1))
            put("frames", src["frames"] ?: JsonPrimitive(0)); put("n", n)
            put("min", resample("min")); put("max", resample("max"))
        }
    }

    // ------------------------------------------------------------ arrangement / takes / slots (demo "server")
    private fun reindex(clips: List<ArrClip>): List<ArrClip> = clips.sortedBy { it.start }.mapIndexed { k, c -> c.copy(i = k) }
    private fun locOf(a: JsonObject, key: String = "loc") = a[key] as? JsonObject ?: throw LiveException("bad loc")

    private fun arrMove(a: JsonObject): JsonElement {
        val loc = locOf(a); val tr = S(loc, "tr") ?: ""; val i = I(loc, "i") ?: -1
        val start = D(a, "start") ?: throw LiveException("start required")
        val c = arrFor(tr).clips.firstOrNull { it.i == i } ?: throw LiveException("no such arrangement clip")
        val len = c.end - c.start
        val moved = c.copy(start = start.coerceAtLeast(0.0), end = start.coerceAtLeast(0.0) + len)
        // Never delete what the moved clip lands on: clips just overlap (the moved clip is drawn on top).
        val rest = arrFor(tr).clips.filter { it.i != i }
        val all = reindex(rest + moved)
        arrBy[tr] = arrFor(tr).copy(clips = all)
        pushArr(tr)
        return buildJsonObject { put("i", all.indexOfFirst { it.start == moved.start && it.name == moved.name }) }
    }

    private fun arrTrim(a: JsonObject) {
        val loc = locOf(a); val tr = S(loc, "tr") ?: ""; val i = I(loc, "i") ?: -1
        val c = arrFor(tr).clips.firstOrNull { it.i == i } ?: throw LiveException("no such arrangement clip")
        val ns = D(a, "start") ?: c.start; val ne = D(a, "end") ?: c.end
        if (ne - ns < 0.0625) throw LiveException("clip would be shorter than 1/16 beat")
        arrBy[tr] = arrFor(tr).copy(clips = reindex(arrFor(tr).clips.map { if (it.i == i) it.copy(start = ns, end = ne) else it }))
        pushArr(tr)
    }

    private fun arrCreate(a: JsonObject) {
        val tr = S(a, "tr") ?: return
        val start = D(a, "start") ?: 0.0; val len = D(a, "len") ?: 4.0
        val color = store.tracks.value.find(tr)?.color ?: 0x3FC0FF
        arrBy[tr] = arrFor(tr).copy(clips = reindex(arrFor(tr).clips + ArrClip(name = "", color = color, start = start, end = start + len)))
        pushArr(tr)
    }

    private fun clipDelete(a: JsonObject) {
        val loc = locOf(a); val tr = S(loc, "tr") ?: ""; val i = I(loc, "i") ?: -1
        when (S(loc, "w")) {
            "slot" -> { slotsBy[tr] = slotsFor(tr).let { s -> s.copy(slots = s.slots.map { if (it.i == i) SlotInfo(i, false, true, SlotStatus.EMPTY, null) else it }) }; pushSlots(tr) }
            "arr" -> { arrBy[tr] = arrFor(tr).let { s -> s.copy(clips = reindex(s.clips.filter { it.i != i })) }; pushArr(tr) }
            "take" -> {
                val lane = I(loc, "lane") ?: -1
                takesBy[tr] = takesFor(tr).let { s -> s.copy(lanes = s.lanes.map { l -> if (l.i != lane) l else l.copy(clips = l.clips.filter { it.i != i }.mapIndexed { k, c -> c.copy(i = k) }) }) }
                pushTakes(tr)
            }
        }
    }

    private fun toArr(a: JsonObject) {
        val loc = locOf(a); val tr = S(loc, "tr") ?: ""
        val time = D(a, "time") ?: 0.0
        val c = when (S(loc, "w")) {
            "slot" -> slotsFor(tr).slots.firstOrNull { it.i == I(loc, "i") }?.clip?.let { ArrClip(name = it.name, color = it.color, start = time, end = time + (it.len.takeIf { l -> l > 0 } ?: 4.0), audio = it.audio) }
            "arr" -> arrFor(tr).clips.firstOrNull { it.i == I(loc, "i") }?.let { it.copy(start = time, end = time + (it.end - it.start)) }
            else -> null
        } ?: return
        arrBy[tr] = arrFor(tr).copy(clips = reindex(arrFor(tr).clips + c))
        pushArr(tr)
    }

    private fun slotTransfer(a: JsonObject, move: Boolean) {
        val fromTr = S(a, "tr") ?: return; val toTr = S(a, "to_tr") ?: fromTr
        val fromI = I(a, "i") ?: return; val toI = I(a, "to_i") ?: return
        val src = slotsFor(fromTr).slots.firstOrNull { it.i == fromI }?.takeIf { it.hasClip } ?: throw LiveException("no clip in slot")
        val dst = slotsFor(toTr).slots.firstOrNull { it.i == toI } ?: throw LiveException("no such slot")
        val replace = B(a, "replace") == true; val swap = B(a, "swap") == true
        if (dst.hasClip && !replace && !(move && swap)) throw LiveException("target slot is occupied")
        if (fromTr == toTr && fromI == toI) return
        val landed = src.copy(i = toI, status = SlotStatus.STOPPED)
        val back = if (swap && dst.hasClip) dst.copy(i = fromI, status = SlotStatus.STOPPED) else SlotInfo(fromI, false, true, SlotStatus.EMPTY, null)
        slotsBy[toTr] = slotsFor(toTr).let { s -> s.copy(slots = s.slots.map { if (it.i == toI) landed else it }) }
        if (move) slotsBy[fromTr] = slotsFor(fromTr).let { s -> s.copy(slots = s.slots.map { if (it.i == fromI) back else it }) }
        pushSlots(toTr); if (fromTr != toTr) pushSlots(fromTr)
    }

    private fun slotDuplicate(a: JsonObject) {
        val tr = S(a, "tr") ?: return; val i = I(a, "i") ?: return
        val s = slotsFor(tr)
        val src = s.slots.firstOrNull { it.i == i }?.takeIf { it.hasClip } ?: throw LiveException("no clip in slot")
        val free = s.slots.firstOrNull { it.i > i && !it.hasClip } ?: throw LiveException("no free slot below")
        slotsBy[tr] = s.copy(slots = s.slots.map { if (it.i == free.i) src.copy(i = free.i, status = SlotStatus.STOPPED) else it })
        pushSlots(tr)
    }

    private fun takesPromote(a: JsonObject) {
        val loc = locOf(a); val tr = S(loc, "tr") ?: ""
        val lane = takesFor(tr).lanes.firstOrNull { it.i == I(loc, "lane") } ?: throw LiveException("no such take lane")
        val tc = lane.clips.firstOrNull { it.i == I(loc, "i") } ?: throw LiveException("no such take")
        val audio = store.tracks.value.find(tr)?.kind != "midi"
        arrBy[tr] = arrFor(tr).copy(clips = reindex(arrFor(tr).clips + ArrClip(name = tc.name, color = tc.color, start = tc.start, end = tc.end, audio = audio)))
        pushArr(tr)
    }

    private fun takesAdd(tr: String, laneI: Int, c: com.livecompanion.state.TakeClip): Int {
        var idx = -1
        takesBy[tr] = takesFor(tr).let { s ->
            s.copy(lanes = s.lanes.map { l ->
                if (l.i != laneI) l else {
                    val all = (l.clips + c).sortedBy { it.start }.mapIndexed { k, x -> x.copy(i = k) }
                    idx = all.indexOfFirst { it.start == c.start && it.name == c.name }
                    l.copy(clips = all)
                }
            })
        }
        return idx
    }

    private fun takesDemote(a: JsonObject): JsonElement {
        val loc = locOf(a); val tr = S(loc, "tr") ?: ""; val i = I(loc, "i") ?: -1
        val laneI = I(a, "lane") ?: throw LiveException("lane required")
        if (S(loc, "w") != "arr") throw LiveException("loc must be an arrangement clip")
        if (takesFor(tr).lanes.none { it.i == laneI }) throw LiveException("no such lane")
        val c = arrFor(tr).clips.firstOrNull { it.i == i } ?: throw LiveException("no such arrangement clip")
        val idx = takesAdd(tr, laneI, com.livecompanion.state.TakeClip(0, c.name, c.color, c.start, c.end))
        arrBy[tr] = arrFor(tr).copy(clips = reindex(arrFor(tr).clips.filter { it.i != i }))
        pushArr(tr); pushTakes(tr)
        return buildJsonObject { put("lane", laneI); put("i", idx) }
    }

    private fun takesMove(a: JsonObject): JsonElement {
        val loc = locOf(a); val tr = S(loc, "tr") ?: ""; val i = I(loc, "i") ?: -1
        val from = I(loc, "lane") ?: -1; val to = I(a, "lane") ?: throw LiveException("lane required")
        if (S(loc, "w") != "take") throw LiveException("loc must be a take")
        if (takesFor(tr).lanes.none { it.i == to }) throw LiveException("no such lane")
        val c = takesFor(tr).lanes.firstOrNull { it.i == from }?.clips?.firstOrNull { it.i == i } ?: throw LiveException("no such take")
        if (from == to) return buildJsonObject { put("lane", to); put("i", i) }
        val idx = takesAdd(tr, to, c)
        takesBy[tr] = takesFor(tr).let { s -> s.copy(lanes = s.lanes.map { l -> if (l.i != from) l else l.copy(clips = l.clips.filter { it.i != i }.mapIndexed { k, x -> x.copy(i = k) }) }) }
        pushTakes(tr)
        return buildJsonObject { put("lane", to); put("i", idx) }
    }

    private fun takesCreateLane(a: JsonObject) {
        val tr = S(a, "tr") ?: return
        val s = takesFor(tr)
        takesBy[tr] = s.copy(lanes = s.lanes + com.livecompanion.state.TakeLane(s.lanes.size, "Take Lane ${s.lanes.size + 1}", emptyList()))
        pushTakes(tr)
    }

    private fun takesRename(a: JsonObject) {
        val tr = S(a, "tr") ?: return; val lane = I(a, "lane") ?: return; val name = S(a, "name") ?: return
        takesBy[tr] = takesFor(tr).let { s -> s.copy(lanes = s.lanes.map { if (it.i == lane) it.copy(name = name) else it }) }
        pushTakes(tr)
    }

    private fun notes(op: String, a: JsonObject) {
        if (store.clip.value.loc == null) return
        fun parse(e: JsonElement?): List<Note> = (e as? JsonArray)?.mapNotNull { runCatching { LiveJson.decodeFromJsonElement(Note.serializer(), it) }.getOrNull() } ?: emptyList()
        val ids = (a["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet() ?: emptySet()
        store.updateClip { c ->
            var next = c.nextId()
            val n = when (op) {
                "notes.add" -> c.notes + parse(a["notes"]).map { it.copy(id = next++) }
                "notes.modify" -> { val m = parse(a["notes"]).associateBy { it.id }; c.notes.map { m[it.id] ?: it } }
                "notes.remove" -> c.notes.filter { it.id !in ids }
                "notes.clear" -> emptyList()
                "notes.transpose" -> { val s = I(a, "semis") ?: 0; c.notes.map { if (it.id in ids) it.copy(p = (it.p + s).coerceIn(0, 127)) else it } }
                else -> { val off = D(a, "offset") ?: 1.0; c.notes + c.notes.filter { it.id in ids }.map { it.copy(id = next++, t = it.t + off) } }
            }
            c.copy(notes = n, notesTotal = n.size)
        }
    }

    private fun ClipState.nextId() = (notes.maxOfOrNull { it.id } ?: 0) + 1

    private fun deviceEdit(a: JsonObject, f: (com.livecompanion.state.Device) -> com.livecompanion.state.Device) {
        val tr = S(a, "tr") ?: return
        val path = S(a, "path") ?: return
        devicesBy[tr] = devicesFor(tr).let { s -> s.copy(devices = s.devices.map { if (it.path == path) f(it) else it }) }
        pushDevices(tr)
    }

    companion object {
        val DEMO_TARGET = HostTarget("demo", 0)
    }
}
