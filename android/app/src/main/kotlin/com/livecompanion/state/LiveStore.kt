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

    // Optimistic edits that must survive a stale server echo for ~600 ms (see SectionGuard).
    private val sessionGuard = SectionGuard<SessionState>()
    private val clipGuard = SectionGuard<ClipState>()
    private val slotsGuard = SectionGuard<SlotsState>()
    private val arrGuard = SectionGuard<ArrState>()
    private val takesGuard = SectionGuard<TakesState>()
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
            if (d is kotlinx.serialization.json.JsonNull) {
                // d:null = "closed / gone" (clip.close, deleted clip, focus change, device.close): clear the editor state.
                when (name) {
                    "clip" -> { clipGuard.clear(); _clip.value = ClipState() }
                    "params" -> _params.value = ParamsState()
                }
                return
            }
            when (name) {
                "session" -> _session.value = sessionGuard.reconcile(LiveJson.decodeFromJsonElement(SessionState.serializer(), d))
                "tracks" -> _tracks.value = LiveJson.decodeFromJsonElement(TracksState.serializer(), d)
                "focus" -> _focus.value = LiveJson.decodeFromJsonElement(FocusState.serializer(), d)
                "slots" -> {
                    val n = LiveJson.decodeFromJsonElement(SlotsState.serializer(), d)
                    if (n.tr != _slots.value.tr) slotsGuard.clear()      // other track: pending edits belong to the old one
                    _slots.value = slotsGuard.reconcile(n)
                }
                "arr" -> {
                    val n = LiveJson.decodeFromJsonElement(ArrState.serializer(), d)
                    if (n.tr != _arr.value.tr) arrGuard.clear()      // other track: pending edits belong to the old one
                    _arr.value = arrGuard.reconcile(n)
                }
                "takes" -> {
                    val n = LiveJson.decodeFromJsonElement(TakesState.serializer(), d)
                    if (n.tr != _takes.value.tr) takesGuard.clear()      // other track: pending edits belong to the old one
                    _takes.value = takesGuard.reconcile(n)
                }
                "devices" -> _devices.value = LiveJson.decodeFromJsonElement(DevicesState.serializer(), d)
                "params" -> _params.value = LiveJson.decodeFromJsonElement(ParamsState.serializer(), d)
                "clip" -> {
                    val c = LiveJson.decodeFromJsonElement(ClipState.serializer(), d)
                    // A different clip (loc changed) drops pending edits of the previous one.
                    if (c.loc != _clip.value.loc) clipGuard.clear()
                    _clip.value = clipGuard.reconcile(c)
                }
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
        sessionGuard.clear(); clipGuard.clear(); slotsGuard.clear(); arrGuard.clear(); takesGuard.clear()
    }

    // ---------------------------------------------------------------- optimistic helpers
    fun updateSession(f: (SessionState) -> SessionState) = _session.update(f)
    fun updateFocus(f: (FocusState) -> FocusState) = _focus.update(f)
    fun updateSlots(f: (SlotsState) -> SlotsState) = _slots.update(f)
    fun updateClip(f: (ClipState) -> ClipState) = _clip.update(f)

    /**
     * Optimistic edits that survive a stale server section for ~600 ms (never snap back). The patch is applied now and
     * re-applied to incoming sections until [done] says the server shows it (default: the patch changes nothing).
     */
    fun editSession(patch: (SessionState) -> SessionState, done: (SessionState) -> Boolean = { it == patch(it) }) {
        sessionGuard.add(patch, done); _session.update(patch)
    }
    fun editClip(patch: (ClipState) -> ClipState, done: (ClipState) -> Boolean = { it == patch(it) }) {
        clipGuard.add(patch, done); _clip.update(patch)
    }
    fun editSlots(patch: (SlotsState) -> SlotsState, done: (SlotsState) -> Boolean = { it == patch(it) }) {
        slotsGuard.add(patch, done); _slots.update(patch)
    }
    fun editArr(patch: (ArrState) -> ArrState, done: (ArrState) -> Boolean = { it == patch(it) }) {
        arrGuard.add(patch, done); _arr.update(patch)
    }
    fun editTakes(patch: (TakesState) -> TakesState, done: (TakesState) -> Boolean = { it == patch(it) }) {
        takesGuard.add(patch, done); _takes.update(patch)
    }

    /** Forget pending optimistic section edits (after a refused op, before re-requesting the truth). */
    fun dropEdits() { sessionGuard.clear(); clipGuard.clear(); slotsGuard.clear(); arrGuard.clear(); takesGuard.clear() }

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
