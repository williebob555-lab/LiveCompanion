package com.livecompanion.ui.devices

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.livecompanion.state.Chain
import com.livecompanion.state.Device
import com.livecompanion.state.LiveStore

/** A device the phone has asked Live to add but the `devices` section does not contain yet. */
data class PendingDevice(val tr: String, val name: String, val base: Int, val atStart: Boolean, val at: Long)

/**
 * Optimistic "loading..." placeholders, one list per [LiveStore]. The Browser and the add-device list push
 * here the moment the user taps Load/insert; the Devices screen shows a dim panel until the section catches up.
 */
@Stable
class PendingDevices {
    val items = mutableStateListOf<PendingDevice>()
}

object DevicePending {
    private val models = java.util.WeakHashMap<LiveStore, PendingDevices>()

    fun of(store: LiveStore): PendingDevices = synchronized(models) { models.getOrPut(store) { PendingDevices() } }

    fun add(store: LiveStore, tr: String, name: String, atStart: Boolean = false): PendingDevice {
        val base = store.devices.value.let { if (it.tr == tr) it.devices.size else 0 }
        val p = PendingDevice(tr, name, base, atStart, System.currentTimeMillis())
        of(store).items.add(p)
        return p
    }

    fun remove(store: LiveStore, p: PendingDevice) { of(store).items.remove(p) }

    const val TIMEOUT_MS = 8000L
}

/** UI state of the signal chain that outlives recomposition: collapse flags, selected rack chain, add dialog, opened params. */
@Stable
class ChainUi {
    val collapsed = mutableStateMapOf<String, Boolean>()
    val chainSel = mutableStateMapOf<String, Int>()
    var addInto by mutableStateOf<String?>(null)
    var openPath by mutableStateOf<String?>(null)
    /** Device whose title was tapped: it shows the non-gesture move buttons (the same reorder as hold-drag). */
    var selPath by mutableStateOf<String?>(null)
}

// ------------------------------------------------------------------ optimistic tree edits

/** Recompute every device path after an optimistic move/insert/delete. */
fun reindex(ds: List<Device>, prefix: String = ""): List<Device> = ds.mapIndexed { i, d ->
    val p = "$prefix$i"
    d.copy(path = p, chains = d.chains.map { c: Chain -> c.copy(devices = reindex(c.devices, "$p.c${c.i}.")) })
}

fun patchDevice(ds: List<Device>, path: String, f: (Device) -> Device): List<Device> =
    ds.map { d ->
        val n = if (d.path == path) f(d) else d
        if (n.chains.isEmpty()) n
        else n.copy(chains = n.chains.map { c: Chain -> c.copy(devices = patchDevice(c.devices, path, f)) })
    }

/** Apply [f] to the device list owned by [into] ("" = the track, "2.c1" = chain 1 of device 2), then reindex. */
fun editList(ds: List<Device>, into: String, f: (List<Device>) -> List<Device>): List<Device> {
    if (into.isEmpty()) return reindex(f(ds))
    val head = into.substringBeforeLast('.', "")
    val ci = into.substringAfterLast('.').drop(1).toIntOrNull() ?: return ds
    return reindex(patchDevice(ds, head) { d ->
        d.copy(chains = d.chains.map { if (it.i == ci) it.copy(devices = f(it.devices)) else it })
    })
}

fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val m = toMutableList()
    m.add(to, m.removeAt(from))
    return m
}

// ------------------------------------------------------------------ native devices (Live 12)

enum class NativeCat(val label: String, val devices: List<String>) {
    Instruments("Instruments", listOf(
        "Drift", "Wavetable", "Operator", "Analog", "Simpler", "Sampler", "Meld", "Electric", "Tension", "Collision",
        "Impulse", "Instrument Rack", "Drum Rack",
    )),
    AudioFx("Audio FX", listOf(
        "Amp", "Auto Filter", "Auto Pan-Tremolo", "Auto Shift", "Beat Repeat", "Cabinet", "Channel EQ", "Chorus-Ensemble",
        "Compressor", "Corpus", "Delay", "Drum Buss", "Dynamic Tube", "Echo", "EQ Eight", "EQ Three", "Erosion",
        "Filter Delay", "Flanger", "Gate", "Glue Compressor", "Grain Delay", "Hybrid Reverb", "Limiter", 
        "Multiband Dynamics", "Overdrive", "Pedal", "Phaser-Flanger", "Redux", "Resonators", "Reverb", "Roar",
        "Saturator", "Shifter", "Spectral Resonator", "Spectral Time", "Spectrum", "Tuner", "Utility", "Vinyl Distortion",
        "Vocoder", "Audio Effect Rack",
    )),
    MidiFx("MIDI FX", listOf(
        "Arpeggiator", "CC Control", "Chord", "Envelope MIDI", "Expression Control", "Melodic Steps", "MIDI Monitor",
        "Note Echo", "Note Length", "Pitch", "Random", "Scale", "Velocity", "Midi Effect Rack",
    )),
}

/**
 * Fallback when `device.available` can't be fetched (old script): the list above minus the devices only Suite has.
 * Best effort - Live's own answer (server op `device.available`) always wins.
 */
private val SUITE_ONLY = setOf(
    "Amp", "Cabinet", "Corpus", "Pedal", "Roar", "Vocoder", "Spectral Resonator", "Spectral Time", "Auto Shift",
    "Operator", "Analog", "Electric", "Tension", "Collision",
)

fun standardFallback(): Map<NativeCat, List<String>> = NativeCat.values().associateWith { c -> c.devices.filter { it !in SUITE_ONLY } }

/** Parse a `device.available` reply ({instruments, audio_effects, midi_effects}); null if it has no usable lists. */
fun parseAvailable(d: kotlinx.serialization.json.JsonElement?): Map<NativeCat, List<String>>? {
    val o = d as? kotlinx.serialization.json.JsonObject ?: return null
    fun names(k: String) = (o[k] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    val i = names("instruments") ?: return null
    return mapOf(NativeCat.Instruments to i, NativeCat.AudioFx to (names("audio_effects") ?: emptyList()),
        NativeCat.MidiFx to (names("midi_effects") ?: emptyList()))
}

/** Instruments go first in a chain; effects at the end. */
fun insertIndexFor(cat: NativeCat, into: String): Int? = if (cat == NativeCat.Instruments && into.isEmpty()) 0 else null
