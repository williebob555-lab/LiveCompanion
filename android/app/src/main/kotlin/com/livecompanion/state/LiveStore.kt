package com.livecompanion.state

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * All server state, one StateFlow per protocol section plus the realtime frame on its own flow
 * (so 20 Hz meter ticks never recompose anything that only reads a section).
 *
 * Sections are authoritative: every `sec` message replaces the section wholesale. The `update*`
 * / `patch*` helpers are for optimistic UI; the next `sec` reconciles them.
 */
class LiveStore {
    private val _hello = MutableStateFlow<HelloInfo?>(null)
    private val _session = MutableStateFlow(SessionState())
    private val _tracks = MutableStateFlow(TracksState())
    private val _focus = MutableStateFlow(FocusState())
    private val _slots = MutableStateFlow(SlotsState())
    private val _arr = MutableStateFlow(ArrState())
    private val _takes = MutableStateFlow(TakesState())
    private val _devices = MutableStateFlow(DevicesState())
    private val _params = MutableStateFlow(ParamsState())
    private val _clip = MutableStateFlow(ClipState())
    private val _rt = MutableStateFlow(RtFrame())
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val hello: StateFlow<HelloInfo?> = _hello.asStateFlow()
    val session: StateFlow<SessionState> = _session.asStateFlow()
    val tracks: StateFlow<TracksState> = _tracks.asStateFlow()
    val focus: StateFlow<FocusState> = _focus.asStateFlow()
    val slots: StateFlow<SlotsState> = _slots.asStateFlow()
    val arr: StateFlow<ArrState> = _arr.asStateFlow()
    val takes: StateFlow<TakesState> = _takes.asStateFlow()
    val devices: StateFlow<DevicesState> = _devices.asStateFlow()
    val params: StateFlow<ParamsState> = _params.asStateFlow()
    val clip: StateFlow<ClipState> = _clip.asStateFlow()

    /** Realtime frame (meters, playhead). Collect only in leaf composables / draw lambdas. */
    val rt: StateFlow<RtFrame> = _rt.asStateFlow()

    /** User-visible notices: server `log` messages and failed fire-and-forget requests. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    fun notice(msg: String) {
        _notices.tryEmit(msg)
    }

    // ---------------------------------------------------------------- incoming
    fun setHello(h: HelloInfo?) {
        _hello.value = h
    }

    /** Full replacement of one section. Unknown section names are ignored (forward compatible). */
    fun handleSection(name: String, d: JsonElement) {
        try {
            when (name) {
                "session" -> _session.value = LiveJson.decodeFromJsonElement(SessionState.serializer(), d)
                "tracks" -> _tracks.value = LiveJson.decodeFromJsonElement(TracksState.serializer(), d)
                "focus" -> _focus.value = LiveJson.decodeFromJsonElement(FocusState.serializer(), d)
                "slots" -> _slots.value = LiveJson.decodeFromJsonElement(SlotsState.serializer(), d)
                "arr" -> _arr.value = LiveJson.decodeFromJsonElement(ArrState.serializer(), d)
                "takes" -> _takes.value = LiveJson.decodeFromJsonElement(TakesState.serializer(), d)
                "devices" -> _devices.value = LiveJson.decodeFromJsonElement(DevicesState.serializer(), d)
                "params" -> _params.value = LiveJson.decodeFromJsonElement(ParamsState.serializer(), d)
                "clip" -> _clip.value = LiveJson.decodeFromJsonElement(ClipState.serializer(), d)
            }
        } catch (e: Exception) {
            notice("Bad '$name' section: ${e.message?.take(120)}")
        }
    }

    fun handleRt(d: JsonElement) {
        try {
            _rt.value = LiveJson.decodeFromJsonElement(RtFrame.serializer(), d)
        } catch (_: Exception) {
        }
    }

    /** Forget everything (user disconnected on purpose). */
    fun reset() {
        _hello.value = null
        _session.value = SessionState()
        _tracks.value = TracksState()
        _focus.value = FocusState()
        _slots.value = SlotsState()
        _arr.value = ArrState()
        _takes.value = TakesState()
        _devices.value = DevicesState()
        _params.value = ParamsState()
        _clip.value = ClipState()
        _rt.value = RtFrame()
    }

    // ---------------------------------------------------------------- optimistic helpers
    fun updateSession(f: (SessionState) -> SessionState) = _session.update(f)
    fun updateFocus(f: (FocusState) -> FocusState) = _focus.update(f)
    fun updateSlots(f: (SlotsState) -> SlotsState) = _slots.update(f)
    fun updateClip(f: (ClipState) -> ClipState) = _clip.update(f)
    fun updateDevices(f: (DevicesState) -> DevicesState) = _devices.update(f)
    fun updateParams(f: (ParamsState) -> ParamsState) = _params.update(f)

    /** Patch one track in the tracks section and, if it is the focused one, the focus section. */
    fun patchTrack(ref: String, f: (TrackInfo) -> TrackInfo) {
        _tracks.update { s ->
            s.copy(
                tracks = s.tracks.map { if (it.ref == ref) f(it) else it },
                returns = s.returns.map { if (it.ref == ref) f(it) else it },
                master = s.master?.let { if (it.ref == ref) f(it) else it },
            )
        }
    }

    /** Patch one slot of the slots section. */
    fun patchSlot(i: Int, f: (SlotInfo) -> SlotInfo) {
        _slots.update { s -> s.copy(slots = s.slots.map { if (it.i == i) f(it) else it }) }
    }

    /** Optimistically switch focus to [ref]: header updates instantly, `focus`/`slots` follow from the server. */
    fun focusPending(ref: String) {
        val t = _tracks.value.find(ref) ?: return
        _focus.update {
            it.copy(
                ref = t.ref, name = t.name, color = t.color, kind = t.kind, armable = t.armable,
                arm = t.arm, mute = t.mute, solo = t.solo, monitor = t.monitor,
            )
        }
        _slots.update { SlotsState(tr = ref, slots = emptyList()) }
    }
}
