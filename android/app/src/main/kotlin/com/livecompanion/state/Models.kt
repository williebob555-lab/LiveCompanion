package com.livecompanion.state

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lenient JSON for the wire: unknown keys ignored, every field has a default, nulls -> defaults. */
val LiveJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** Live reports velocities as floats (112.0); accept ints or floats, hold an Int. */
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int {
        val e = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive
        return e.intOrNull ?: e.doubleOrNull?.roundToInt() ?: 0
    }
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

// ------------------------------------------------------------------ shared
/** Parameter value: raw float + display string (PROTOCOL "P"). */
@Serializable
data class P(val v: Float = 0f, val min: Float = 0f, val max: Float = 1f, val str: String = "")

@Serializable
data class SendParam(
    val name: String = "", val v: Float = 0f, val min: Float = 0f, val max: Float = 1f, val str: String = "",
)

@Serializable
data class HelloInfo(val proto: Int = 1, val live: String = "", val host: String = "")

// ------------------------------------------------------------------ session (global)
@Serializable
data class SessionState(
    val tempo: Float = 120f,
    val sig: List<Int> = listOf(4, 4),
    val playing: Boolean = false,
    val pos: Double = 0.0,
    val rec: Boolean = false,
    @SerialName("session_rec") val sessionRec: Boolean = false,
    val overdub: Boolean = false,
    @SerialName("punch_in") val punchIn: Boolean = false,
    @SerialName("punch_out") val punchOut: Boolean = false,
    val loop: Boolean = false,
    @SerialName("loop_start") val loopStart: Double = 0.0,
    @SerialName("loop_len") val loopLen: Double = 4.0,
    val metronome: Boolean = false,
    @SerialName("count_in") val countIn: Int = 0,
    val quant: Int = 0,
    @SerialName("rec_quant") val recQuant: Int = 0,
    @SerialName("can_undo") val canUndo: Boolean = false,
    @SerialName("can_redo") val canRedo: Boolean = false,
    @SerialName("back_to_arr") val backToArr: Boolean = false,
    @SerialName("auto_arm") val autoArm: Boolean = false,
    val view: String = "session",
    @SerialName("sel_track") val selTrack: String = "",
    @SerialName("sel_scene") val selScene: Int = 0,
    @SerialName("song_len") val songLen: Double = 0.0,
    val follow: Boolean = false,
) {
    val sigNum get() = sig.getOrElse(0) { 4 }.coerceAtLeast(1)
    val sigDen get() = sig.getOrElse(1) { 4 }.coerceAtLeast(1)
}

// ------------------------------------------------------------------ tracks (global)
@Serializable
data class TrackInfo(
    val ref: String = "",
    val name: String = "",
    val color: Int = 0x808080,
    /** audio | midi | group | return | master */
    val kind: String = "audio",
    val armable: Boolean = false,
    val arm: Boolean = false,
    val mute: Boolean = false,
    val solo: Boolean = false,
    /** 0 = In, 1 = Auto, 2 = Off */
    val monitor: Int = 1,
    val group: String? = null,
    val folded: Boolean = false,
    val foldable: Boolean = false,
    @SerialName("playing_slot") val playingSlot: Int = -1,
    @SerialName("fired_slot") val firedSlot: Int = -1,
    /** Returns and master only. */
    val vol: P? = null,
)

@Serializable
data class SceneInfo(val i: Int = 0, val name: String = "", val color: Int = 0)

@Serializable
data class TracksState(
    val tracks: List<TrackInfo> = emptyList(),
    val returns: List<TrackInfo> = emptyList(),
    val master: TrackInfo? = null,
    val scenes: List<SceneInfo> = emptyList(),
) {
    /** Tracks, returns, master in navigation order. */
    val all: List<TrackInfo> get() = tracks + returns + listOfNotNull(master)
    fun find(ref: String): TrackInfo? = all.firstOrNull { it.ref == ref }
}

// ------------------------------------------------------------------ focus
@Serializable
data class FocusState(
    val ref: String = "",
    val name: String = "",
    val color: Int = 0x808080,
    val kind: String = "audio",
    val armable: Boolean = false,
    val arm: Boolean = false,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val monitor: Int = 1,
    val vol: P = P(0.85f, 0f, 1f, ""),
    val pan: P = P(0f, -1f, 1f, ""),
    val sends: List<SendParam> = emptyList(),
    @SerialName("in_type") val inType: String = "",
    @SerialName("in_types") val inTypes: List<String> = emptyList(),
    @SerialName("in_ch") val inCh: String = "",
    @SerialName("in_chs") val inChs: List<String> = emptyList(),
    @SerialName("out_type") val outType: String = "",
    @SerialName("out_types") val outTypes: List<String> = emptyList(),
    @SerialName("playing_slot") val playingSlot: Int = -1,
    @SerialName("fired_slot") val firedSlot: Int = -1,
)

// ------------------------------------------------------------------ slots
@Serializable
data class SlotClip(
    val name: String = "",
    val color: Int = 0x808080,
    val len: Double = 0.0,
    val looping: Boolean = false,
    val audio: Boolean = false,
    val muted: Boolean = false,
)

object SlotStatus {
    const val EMPTY = "empty"
    const val STOPPED = "stopped"
    const val PLAYING = "playing"
    const val TRIGGERED = "triggered"
    const val RECORDING = "recording"
}

@Serializable
data class SlotInfo(
    val i: Int = 0,
    @SerialName("has_clip") val hasClip: Boolean = false,
    @SerialName("has_stop") val hasStop: Boolean = true,
    val status: String = SlotStatus.EMPTY,
    val clip: SlotClip? = null,
)

@Serializable
data class SlotsState(val tr: String = "", val slots: List<SlotInfo> = emptyList())

// ------------------------------------------------------------------ arrangement / takes
@Serializable
data class ArrClip(
    val i: Int = 0, val name: String = "", val color: Int = 0x808080,
    val start: Double = 0.0, val end: Double = 0.0,
    val audio: Boolean = false, val muted: Boolean = false, val looping: Boolean = false,
)

@Serializable
data class ArrState(val tr: String = "", val clips: List<ArrClip> = emptyList())

@Serializable
data class TakeClip(
    val i: Int = 0, val name: String = "", val color: Int = 0x808080,
    val start: Double = 0.0, val end: Double = 0.0,
)

@Serializable
data class TakeLane(val i: Int = 0, val name: String = "", val clips: List<TakeClip> = emptyList())

@Serializable
data class TakesState(val tr: String = "", val supported: Boolean = false, val lanes: List<TakeLane> = emptyList())

// ------------------------------------------------------------------ devices / params
@Serializable
data class Macro(
    val i: Int = 0, val name: String = "", val v: Float = 0f,
    val min: Float = 0f, val max: Float = 127f, val str: String = "",
)

@Serializable
data class Variations(val count: Int = 0, val sel: Int = -1)

@Serializable
data class Chain(val i: Int = 0, val name: String = "", val devices: List<Device> = emptyList())

@Serializable
data class Device(
    val path: String = "",
    val name: String = "",
    val cls: String = "",
    val on: Boolean = true,
    /** instrument | audio_fx | midi_fx | rack | other */
    val type: String = "other",
    val chains: List<Chain> = emptyList(),
    val macros: List<Macro>? = null,
    @SerialName("var") val variations: Variations? = null,
)

@Serializable
data class DevicesState(val tr: String = "", val devices: List<Device> = emptyList())

@Serializable
data class Param(
    val i: Int = 0, val name: String = "", val v: Float = 0f,
    val min: Float = 0f, val max: Float = 1f, val str: String = "",
    val quant: Boolean = false, val items: List<String>? = null,
)

@Serializable
data class ParamsState(
    val tr: String = "", val path: String = "", val name: String = "", val params: List<Param> = emptyList(),
)

// ------------------------------------------------------------------ clip (opened)
@Serializable
data class Note(
    val id: Int = 0, val p: Int = 60, val t: Double = 0.0, val d: Double = 0.25, @Serializable(with = LenientIntSerializer::class) val v: Int = 100,
    val m: Boolean = false, val prob: Float = 1f, val vd: Float = 0f, @Serializable(with = LenientIntSerializer::class) val rv: Int = 64,
)

@Serializable
data class WarpMarker(val beat: Double = 0.0, @SerialName("sample_time") val sampleTime: Double = 0.0)

@Serializable
data class ClipState(
    /** Locator exactly as the server sent it; send back verbatim in clip ops. Null = nothing open. */
    val loc: JsonObject? = null,
    val name: String = "",
    val color: Int = 0x808080,
    val audio: Boolean = false,
    val len: Double = 0.0,
    val looping: Boolean = false,
    @SerialName("loop_start") val loopStart: Double = 0.0,
    @SerialName("loop_end") val loopEnd: Double = 0.0,
    @SerialName("start_marker") val startMarker: Double = 0.0,
    @SerialName("end_marker") val endMarker: Double = 0.0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val signature: List<Int> = listOf(4, 4),
    @SerialName("launch_mode") val launchMode: Int = 0,
    @SerialName("launch_quant") val launchQuant: Int = 0,
    val legato: Boolean = false,
    val muted: Boolean = false,
    // audio only
    val gain: Float = 0f,
    @SerialName("gain_str") val gainStr: String = "",
    @SerialName("pitch_coarse") val pitchCoarse: Int = 0,
    @SerialName("pitch_fine") val pitchFine: Float = 0f,
    val warping: Boolean = false,
    @SerialName("warp_mode") val warpMode: String = "",
    @SerialName("warp_modes") val warpModes: List<String> = emptyList(),
    @SerialName("file_path") val filePath: String = "",
    @SerialName("sample_rate") val sampleRate: Double = 0.0,
    @SerialName("sample_len") val sampleLen: Double = 0.0,
    @SerialName("warp_markers") val warpMarkers: List<WarpMarker> = emptyList(),
    // midi only
    val notes: List<Note> = emptyList(),
    @SerialName("notes_total") val notesTotal: Int = 0,
)

// ------------------------------------------------------------------ rt frame
@Serializable
data class Meters(val focus: List<Float> = emptyList(), val master: List<Float> = emptyList()) {
    val focusL get() = focus.getOrElse(0) { 0f }
    val focusR get() = focus.getOrElse(1) { focusL }
    val masterL get() = master.getOrElse(0) { 0f }
    val masterR get() = master.getOrElse(1) { masterL }
}

@Serializable
data class RtFrame(
    val pos: Double = 0.0,
    val playing: Boolean = false,
    @SerialName("beat_in_bar") val beatInBar: Float = 0f,
    val meters: Meters = Meters(),
    @SerialName("clip_pos") val clipPos: Double? = null,
    /** Keyed by slot index (as string). */
    @SerialName("slot_pos") val slotPos: Map<String, Double> = emptyMap(),
)

// ------------------------------------------------------------------ locators
object Loc {
    fun slot(tr: String, i: Int): JsonObject = buildJsonObject { put("w", "slot"); put("tr", tr); put("i", i) }
    fun arr(tr: String, i: Int): JsonObject = buildJsonObject { put("w", "arr"); put("tr", tr); put("i", i) }
    fun take(tr: String, lane: Int, i: Int): JsonObject =
        buildJsonObject { put("w", "take"); put("tr", tr); put("lane", lane); put("i", i) }
}

// ------------------------------------------------------------------ hardening
private const val MAX_STR = 300
private const val MAX_NUM = 1.0e9

/**
 * Defends the decoders against hostile/buggy servers: NaN/Infinity and absurd numbers become 0 / are clamped to
 * +-1e9 (so Int fields can't overflow and drop the whole section) and strings are cut to 300 chars (a 100 KB name
 * would otherwise be laid out in full). Structure is untouched.
 */
fun sanitizeJson(e: kotlinx.serialization.json.JsonElement, depth: Int = 0): kotlinx.serialization.json.JsonElement = when {
    depth > 24 -> kotlinx.serialization.json.JsonNull
    e is kotlinx.serialization.json.JsonObject ->
        kotlinx.serialization.json.JsonObject(e.mapValues { sanitizeJson(it.value, depth + 1) })
    e is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { sanitizeJson(it, depth + 1) })
    e is kotlinx.serialization.json.JsonPrimitive && e.isString ->
        if (e.content.length > MAX_STR) kotlinx.serialization.json.JsonPrimitive(e.content.take(MAX_STR)) else e
    e is kotlinx.serialization.json.JsonPrimitive && e !is kotlinx.serialization.json.JsonNull -> {
        val c = e.content
        val d = c.toDoubleOrNull()
        when {
            c == "true" || c == "false" -> e
            d == null -> kotlinx.serialization.json.JsonPrimitive(0) // NaN / Infinity / garbage literal
            d.isNaN() || d.isInfinite() -> kotlinx.serialization.json.JsonPrimitive(0)
            d > MAX_NUM -> kotlinx.serialization.json.JsonPrimitive(MAX_NUM.toLong())
            d < -MAX_NUM -> kotlinx.serialization.json.JsonPrimitive(-MAX_NUM.toLong())
            else -> e
        }
    }
    else -> e
}
