"""Fake Live Object Model: mimics the shapes of Live 12's Python API closely enough to run the real
LiveCompanion core against it (properties, add_X_listener/remove_X_listener/X_has_listener, methods).

Simplifications (it is a mock, not an emulator): quantized launches fire on the next beat; arrangement
recording is not simulated; undo/redo only track counters. `FakeSong.advance(dt)` is the simulated clock."""
import math
import os
import random
import struct
import wave

MEDIA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media")
PALETTE = [0xFF94A6, 0xFFA529, 0xCC9927, 0xF7F47C, 0xBFFB00, 0x1AFF2F, 0x25FFA8, 0x5CFFE8, 0x8BC5FF,
           0x5480E4, 0x92A7FF, 0xD86CE4, 0xE553A0, 0xFFFFFF]
_MISSING = object()


# ---------------------------------------------------------------------------------------------- base
class Observable(object):
    _observed = ()

    def __init__(self):
        object.__setattr__(self, "_ls", {})

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        ls = self.__dict__.get("_ls")
        if ls is None:
            raise AttributeError(name)
        obs = type(self)._observed
        if name.startswith("add_") and name.endswith("_listener") and name[4:-9] in obs:
            prop = name[4:-9]
            return lambda cb: ls.setdefault(prop, []).append(cb)
        if name.startswith("remove_") and name.endswith("_listener") and name[7:-9] in obs:
            prop = name[7:-9]

            def rem(cb):
                if cb in ls.get(prop, ()):
                    ls[prop].remove(cb)
                else:
                    raise RuntimeError("listener not registered")
            return rem
        if name.endswith("_has_listener") and name[:-13] in obs:
            prop = name[:-13]
            return lambda cb: cb in ls.get(prop, ())
        raise AttributeError(name)

    def __setattr__(self, name, value):
        if name in type(self)._observed:
            old = self.__dict__.get(name, _MISSING)
            object.__setattr__(self, name, value)
            if old is _MISSING or old != value:
                self._fire(name)
        else:
            object.__setattr__(self, name, value)

    def __dir__(self):
        names = set(object.__dir__(self))
        for p in type(self)._observed:
            names.update(("add_%s_listener" % p, "remove_%s_listener" % p, "%s_has_listener" % p))
        return sorted(names)

    def _fire(self, prop):
        for cb in list(self.__dict__.get("_ls", {}).get(prop, ())):
            cb()

    def listener_count(self):
        return sum(len(v) for v in self.__dict__.get("_ls", {}).values())


class EnumVal(object):
    def __init__(self, name, value):
        self.name, self.value = name, value

    def __str__(self):
        return self.name

    def __int__(self):
        return self.value


DT_INSTRUMENT = EnumVal("instrument", 1)
DT_AUDIO = EnumVal("audio_effect", 2)
DT_MIDI = EnumVal("midi_effect", 3)


# ---------------------------------------------------------------------------------------------- params
class FakeParam(Observable):
    _observed = ("value",)

    def __init__(self, name, value=0.0, lo=0.0, hi=1.0, quantized=False, items=None, fmt=None):
        Observable.__init__(self)
        self.name = self.original_name = name
        self.min, self.max, self.default_value = lo, hi, value
        self.is_quantized = quantized
        self.value_items = tuple(items) if items else ()
        self._fmt = fmt
        self.value = value

    def str_for_value(self, v):
        if self._fmt:
            return self._fmt(v)
        if self.is_quantized and self.value_items:
            i = int(round(v))
            return self.value_items[max(0, min(len(self.value_items) - 1, i))]
        return "%.2f" % v

    def __str__(self):
        return self.str_for_value(self.value)


def _db(v):
    if v <= 0.0001:
        return "-inf dB"
    return "%.1f dB" % (20.0 * math.log10(v / 0.85))


def make_volume():
    return FakeParam("Volume", 0.85, 0.0, 1.0, fmt=_db)


def make_pan():
    return FakeParam("Pan", 0.0, -1.0, 1.0, fmt=lambda v: "C" if abs(v) < 0.005 else ("%dL" % round(-v * 50) if v < 0 else "%dR" % round(v * 50)))


class FakeMixer(object):
    def __init__(self, n_sends):
        self.volume = make_volume()
        self.panning = make_pan()
        self.sends = [FakeParam("Send " + "ABCDEFGH"[i], 0.0, 0.0, 1.0, fmt=_db) for i in range(n_sends)]


# ---------------------------------------------------------------------------------------------- devices
class FakeDeviceView(Observable):
    _observed = ("selected_chain",)

    def __init__(self):
        Observable.__init__(self)
        self.selected_chain = None


class FakeDevice(Observable):
    _observed = ("name", "is_active")
    can_have_chains = False

    def __init__(self, name, cls, typ, param_names=None):
        Observable.__init__(self)
        self.name, self.class_name, self.type = name, cls, typ
        self.parameters = [FakeParam("Device On", 1.0, 0.0, 1.0, quantized=True, items=("Off", "On"))]
        if param_names is None:
            param_names = ["Param %d" % (k + 1) for k in range(6)]
        for i, pn in enumerate(param_names):
            self.parameters.append(FakeParam(pn, 0.5 + 0.05 * (i % 5)))
        self.chains = ()
        self.view = FakeDeviceView()

    @property
    def is_active(self):
        return self.parameters[0].value >= 0.5


class FakeChain(Observable):
    _observed = ("name", "devices")

    def __init__(self, name, devices=()):
        Observable.__init__(self)
        self.name = name
        self.devices = list(devices)
        self.mute = False
        self.solo = False

    def delete_device(self, i):
        del self.devices[i]
        self._fire("devices")


class FakeRack(FakeDevice):
    _observed = ("name", "is_active", "chains", "variation_count", "selected_variation_index")
    can_have_chains = True

    def __init__(self, name, cls, typ, chains, macros=None):
        FakeDevice.__init__(self, name, cls, typ, param_names=[])
        self.chains = list(chains)
        self.has_macros = macros is not None
        if macros is not None:
            for n in macros:
                self.parameters.append(FakeParam(n, 0.5))
            self.visible_macro_count = len(macros)
            self._vars = []
            self.variation_count = 0
            self.selected_variation_index = -1

    def __getattr__(self, name):
        if not self.__dict__.get("has_macros", False) and name in (
                "variation_count", "selected_variation_index", "visible_macro_count", "store_variation",
                "recall_selected_variation", "delete_selected_variation"):
            raise AttributeError(name)
        return FakeDevice.__getattr__(self, name)

    def store_variation(self):
        self._vars.append([p.value for p in self.parameters[1:1 + self.visible_macro_count]])
        self.variation_count = len(self._vars)
        self.selected_variation_index = self.variation_count - 1

    def recall_selected_variation(self):
        i = self.selected_variation_index
        if 0 <= i < len(self._vars):
            for p, v in zip(self.parameters[1:], self._vars[i]):
                p.value = v

    def delete_selected_variation(self):
        i = self.selected_variation_index
        if 0 <= i < len(self._vars):
            del self._vars[i]
            self.variation_count = len(self._vars)
            self.selected_variation_index = min(i, self.variation_count - 1)

    def delete_chain_device(self, ci, di):
        self.chains[ci].delete_device(di)


# ---------------------------------------------------------------------------------------------- notes
class FakeNote(object):
    _FIELDS = ("note_id", "pitch", "start_time", "duration", "velocity", "mute", "probability",
               "velocity_deviation", "release_velocity")

    def __init__(self, note_id, pitch, start_time, duration, velocity=100.0, mute=False, probability=1.0,
                 velocity_deviation=0.0, release_velocity=64.0):
        self.note_id, self.pitch, self.start_time, self.duration = note_id, pitch, start_time, duration
        self.velocity, self.mute, self.probability = velocity, mute, probability
        self.velocity_deviation, self.release_velocity = velocity_deviation, release_velocity

    def copy(self):
        return FakeNote(*[getattr(self, f) for f in self._FIELDS])

    def __getitem__(self, k):
        return getattr(self, k)

    def __setitem__(self, k, v):
        setattr(self, k, v)

    def get(self, k, d=None):
        return getattr(self, k, d)

    def keys(self):
        return list(self._FIELDS)

    def __repr__(self):
        return "FakeNote(%s)" % ", ".join("%s=%r" % (f, getattr(self, f)) for f in self._FIELDS)


def _spec_get(spec, k, d):
    if isinstance(spec, dict):
        return spec.get(k, d)
    return getattr(spec, k, d)


# ---------------------------------------------------------------------------------------------- clips
class FakeWarpMarker(object):
    def __init__(self, beat_time, sample_time):
        self.beat_time, self.sample_time = beat_time, sample_time


class FakeClip(Observable):
    _observed = ("name", "color", "looping", "loop_start", "loop_end", "start_marker", "end_marker", "muted",
                 "gain", "pitch_coarse", "pitch_fine", "warping", "warp_mode", "launch_mode",
                 "launch_quantization", "legato", "signature_numerator", "signature_denominator", "notes",
                 "warp_markers", "playing_status", "is_recording")

    def __init__(self, name, color, length, audio=False, file_path="", sample_seconds=0.0, sample_rate=44100,
                 arr_start=None):
        Observable.__init__(self)
        self.name, self.color = name, color
        self.is_audio_clip, self.is_midi_clip = bool(audio), not audio
        self.is_arrangement_clip = arr_start is not None
        self._arr_start = arr_start
        self.looping = True
        self.loop_start, self.loop_end = 0.0, float(length)
        self.start_marker, self.end_marker = 0.0, float(length)
        self.muted = False
        self.launch_mode, self.launch_quantization, self.legato = 0, 0, False
        self.signature_numerator, self.signature_denominator = 4, 4
        self.is_playing = self.is_triggered = False
        self.is_recording = False
        self.playing_position = 0.0
        self.playing_status = 0
        self._notes = []
        self._next_id = 1
        self.notes = 0                    # observed counter, bumped on edit
        self._slot = None
        if audio:
            self.file_path = file_path
            self.sample_rate = float(sample_rate)
            self.sample_length = int(sample_seconds * sample_rate)
            self.gain = 0.4
            self.pitch_coarse, self.pitch_fine = 0, 0.0
            self.warping = True
            self.warp_mode = 0
            self.available_warp_modes = (0, 1, 2, 3, 4, 6)
            self.warp_markers = (FakeWarpMarker(0.0, 0.0), FakeWarpMarker(float(length), float(sample_seconds)))

    # ---- derived
    @property
    def length(self):
        if self.is_arrangement_clip:
            return self.end_time - self.start_time
        return (self.loop_end - self.loop_start) if self.looping else (self.end_marker - self.start_marker)

    @property
    def start_time(self):
        return self._arr_start if self.is_arrangement_clip else self.loop_start

    @property
    def end_time(self):
        if self.is_arrangement_clip:
            return self._arr_start + (self.end_marker - self.start_marker)
        return self.loop_end

    @property
    def gain_display_string(self):
        g = getattr(self, "gain", 0.4)
        return "%.1f dB" % (20 * math.log10(max(g, 1e-4) / 0.4)) if self.is_audio_clip else ""

    def __getattr__(self, name):
        if self.__dict__.get("is_audio_clip") is False and name in (
                "file_path", "sample_rate", "sample_length", "gain", "pitch_coarse", "pitch_fine", "warping",
                "warp_mode", "available_warp_modes", "warp_markers", "add_warp_marker", "move_warp_marker",
                "remove_warp_marker"):
            raise AttributeError(name)
        if self.__dict__.get("is_audio_clip") and name in ("get_notes_extended", "add_new_notes"):
            raise AttributeError(name)
        return Observable.__getattr__(self, name)

    def clone(self, arr_start=None):
        c = FakeClip(self.name, self.color, self.end_marker - self.start_marker, self.is_audio_clip,
                     getattr(self, "file_path", ""), 0.0, 44100, arr_start)
        for k in ("looping", "loop_start", "loop_end", "start_marker", "end_marker", "muted", "launch_mode",
                  "launch_quantization", "legato", "signature_numerator", "signature_denominator"):
            c.__dict__[k] = self.__dict__[k]
        if self.is_audio_clip:
            for k in ("file_path", "sample_rate", "sample_length", "gain", "pitch_coarse", "pitch_fine", "warping",
                      "warp_mode", "available_warp_modes"):
                c.__dict__[k] = self.__dict__[k]
            c.__dict__["warp_markers"] = tuple(FakeWarpMarker(m.beat_time, m.sample_time) for m in self.warp_markers)
        c._notes = [n.copy() for n in self._notes]
        c._next_id = self._next_id
        return c

    # ---- transport
    def fire(self):
        self._slot._fire_me()

    def stop(self):
        self._slot.stop()

    # ---- notes (MIDI)
    def get_notes_extended(self, from_pitch=0, pitch_span=128, from_time=0.0, time_span=1e9):
        return [n.copy() for n in sorted(self._notes, key=lambda n: (n.start_time, n.pitch))
                if from_pitch <= n.pitch < from_pitch + pitch_span and from_time <= n.start_time < from_time + time_span]

    def add_new_notes(self, specs):
        ids = []
        for s in specs:
            n = FakeNote(self._next_id, int(_spec_get(s, "pitch", 60)), float(_spec_get(s, "start_time", 0.0)),
                         float(_spec_get(s, "duration", 0.25)), float(_spec_get(s, "velocity", 100.0)),
                         bool(_spec_get(s, "mute", False)), float(_spec_get(s, "probability", 1.0)),
                         float(_spec_get(s, "velocity_deviation", 0.0)), float(_spec_get(s, "release_velocity", 64.0)))
            self._next_id += 1
            self._notes.append(n)
            ids.append(n.note_id)
        self._bump()
        return ids

    def apply_note_modifications(self, notes):
        by_id = dict((n.note_id, n) for n in self._notes)
        for m in notes:
            t = by_id.get(m.note_id)
            if t is not None:
                for f in FakeNote._FIELDS[1:]:
                    setattr(t, f, getattr(m, f))
        self._bump()

    def remove_notes_by_id(self, ids):
        s = set(ids)
        self._notes = [n for n in self._notes if n.note_id not in s]
        self._bump()

    def remove_notes_extended(self, from_pitch, pitch_span, from_time, time_span):
        self._notes = [n for n in self._notes if not (from_pitch <= n.pitch < from_pitch + pitch_span and
                                                       from_time <= n.start_time < from_time + time_span)]
        self._bump()

    def _bump(self):
        self.notes = self.notes + 1

    def quantize(self, grid, amount):
        step = {1: 1.0, 2: 0.5, 3: 1 / 3.0, 4: 0.25, 5: 0.25, 6: 1 / 6.0, 7: 0.125, 8: 0.125}.get(int(grid), 0.25)
        for n in self._notes:
            q = round(n.start_time / step) * step
            n.start_time = n.start_time + (q - n.start_time) * amount
        self._bump()

    def duplicate_loop(self):
        ln = self.loop_end - self.loop_start
        for n in list(self._notes):
            if self.loop_start <= n.start_time < self.loop_end:
                c = n.copy()
                c.note_id = self._next_id
                self._next_id += 1
                c.start_time += ln
                self._notes.append(c)
        self.loop_end = self.loop_end + ln
        self.end_marker = max(self.end_marker, self.loop_end)
        self._bump()

    def crop(self):
        ls = self.loop_start
        self._notes = [n for n in self._notes if ls <= n.start_time < self.loop_end]
        for n in self._notes:
            n.start_time -= ls
        ln = self.loop_end - ls
        self.loop_start, self.loop_end = 0.0, ln
        self.start_marker, self.end_marker = 0.0, ln
        self._bump()

    # ---- warp (audio)
    def add_warp_marker(self, beat_time, sample_time=None):
        if sample_time is None:
            ms = sorted(self.warp_markers, key=lambda m: m.beat_time)
            sample_time = ms[-1].sample_time * beat_time / max(ms[-1].beat_time, 1e-6)
        self.warp_markers = tuple(sorted(list(self.warp_markers) + [FakeWarpMarker(beat_time, sample_time)],
                                         key=lambda m: m.beat_time))
        self._fire("warp_markers")

    def move_warp_marker(self, beat_time, distance):
        for m in self.warp_markers:
            if abs(m.beat_time - beat_time) < 1e-6:
                m.beat_time += distance
        self._fire("warp_markers")

    def remove_warp_marker(self, beat_time):
        self.warp_markers = tuple(m for m in self.warp_markers if abs(m.beat_time - beat_time) > 1e-6)
        self._fire("warp_markers")


class FakeSlot(Observable):
    _observed = ("has_clip", "playing_status", "is_triggered")

    def __init__(self, track):
        Observable.__init__(self)
        self._track = track
        self._clip = None
        self.is_triggered = False
        self.has_stop_button = True

    @property
    def has_clip(self):
        return self._clip is not None

    @property
    def clip(self):
        return self._clip

    @property
    def is_playing(self):
        return self._clip is not None and self._clip.is_playing

    @property
    def is_recording(self):
        return self._clip is not None and self._clip.is_recording

    def _set_clip(self, clip):
        if self._clip is not None:
            self._clip._slot = None
        self._clip = clip
        if clip is not None:
            clip._slot = self
        self._fire("has_clip")

    def create_clip(self, length):
        if self._clip is not None:
            raise RuntimeError("slot already has a clip")
        if not self._track.has_midi_input:
            raise RuntimeError("cannot create clip on audio track")
        self._set_clip(FakeClip("", self._track.color, length))
        self._track._song._edit()

    def delete_clip(self):
        if self._clip is None:
            return
        self._track._song._unplay(self._track, self)
        self._set_clip(None)
        self._track._song._edit()

    def duplicate_clip_to(self, target):
        if self._clip is None:
            raise RuntimeError("no clip")
        target._track._song._unplay(target._track, target)
        target._set_clip(self._clip.clone())
        self._track._song._edit()

    def fire(self):
        self._track._song._fire_slot(self._track, self)

    def _fire_me(self):
        self.fire()

    def stop(self):
        self._track._song._stop_slot(self._track, self)


# ---------------------------------------------------------------------------------------------- tracks
class FakeRouting(object):
    def __init__(self, name):
        self.display_name = name

    def __repr__(self):
        return "RoutingType(%s)" % self.display_name

    def __eq__(self, o):
        return isinstance(o, FakeRouting) and o.display_name == self.display_name

    def __hash__(self):
        return hash(self.display_name)


class FakeLane(Observable):
    _observed = ("name", "arrangement_clips")

    def __init__(self, name):
        Observable.__init__(self)
        self.name = name
        self.arrangement_clips = []


class FakeTrack(Observable):
    _observed = ("name", "color", "arm", "mute", "solo", "current_monitoring_state", "fold_state",
                 "playing_slot_index", "fired_slot_index", "devices", "arrangement_clips", "take_lanes",
                 "input_routing_type", "input_routing_channel", "output_routing_type")

    def __setattr__(self, name, value):
        Observable.__setattr__(self, name, value)
        # Exclusive arm, like Live: arming one track disarms the others.
        if name == "arm" and value is True:
            song = self.__dict__.get("_song")
            if song is not None and getattr(song, "exclusive_arm", False):
                for t in list(song.tracks):
                    if t is not self and t.__dict__.get("arm") is True:
                        t.arm = False

    def __init__(self, song, name, kind, color, n_scenes=0, n_sends=2):
        Observable.__init__(self)
        self._song = song
        self.kind = kind
        self.name, self.color = name, color
        self.mute = False
        self.solo = False
        self.devices = []
        self.mixer_device = FakeMixer(n_sends if kind != "master" else 0)
        self.output_meter_left = self.output_meter_right = 0.0
        self.has_midi_input = kind == "midi"
        self.has_audio_input = kind == "audio"
        self.can_be_armed = kind in ("audio", "midi")
        self.is_foldable = kind == "group"
        self.group_track = None
        self.is_grouped = False
        if self.can_be_armed:
            self.arm = False
            self.current_monitoring_state = 1
        if self.is_foldable:
            self.fold_state = False
        self.playing_slot_index = -1
        self.fired_slot_index = -1
        self.clip_slots = [FakeSlot(self) for _ in range(n_scenes)] if kind in ("audio", "midi", "group") else []
        self.arrangement_clips = []
        if kind in ("audio", "midi"):
            self.take_lanes = []
        self.input_routing_type = FakeRouting("Ext. In" if kind == "audio" else "All Ins")
        self.available_input_routing_types = [FakeRouting(n) for n in ("Ext. In", "All Ins", "No Input")]
        self.input_routing_channel = FakeRouting("1/2" if kind == "audio" else "All Channels")
        self.available_input_routing_channels = [FakeRouting(n) for n in ("1/2", "1", "2", "All Channels")]
        self.output_routing_type = FakeRouting("Master")
        self.available_output_routing_types = [FakeRouting(n) for n in ("Master", "Sends Only", "Ext. Out")]

    def __getattr__(self, name):
        if self.__dict__.get("kind") in ("return", "master", "group") and name in ("take_lanes", "create_take_lane"):
            raise AttributeError(name)
        if self.__dict__.get("kind") in ("return", "master") and name in (
                "arm", "current_monitoring_state", "clip_slots", "arrangement_clips"):
            raise AttributeError(name)
        return Observable.__getattr__(self, name)

    def stop_all_clips(self):
        self._song._stop_track(self)

    def delete_device(self, i):
        del self.devices[i]
        self._fire("devices")
        self._song._edit()

    def create_midi_clip(self, start_time, length):
        if not self.has_midi_input:
            raise RuntimeError("not a MIDI track")
        c = FakeClip("", self.color, length, arr_start=float(start_time))
        self.arrangement_clips = sorted(self.arrangement_clips + [c], key=lambda x: x.start_time)
        self._song._edit()
        return c

    def duplicate_clip_to_arrangement(self, clip, time):
        c = clip.clone(arr_start=float(time))
        self.arrangement_clips = sorted(self.arrangement_clips + [c], key=lambda x: x.start_time)
        self._song._edit()
        return c

    def delete_clip(self, clip):
        if clip in self.arrangement_clips:
            self.arrangement_clips = [c for c in self.arrangement_clips if c is not clip]
        else:
            for lane in getattr(self, "take_lanes", []):
                if clip in lane.arrangement_clips:
                    lane.arrangement_clips = [c for c in lane.arrangement_clips if c is not clip]
                    break
            else:
                raise RuntimeError("clip not on track")
        self._song._edit()

    def create_take_lane(self):
        lane = FakeLane("Take Lane %d" % (len(self.take_lanes) + 1))
        self.take_lanes = list(self.take_lanes) + [lane]
        return lane


# ---------------------------------------------------------------------------------------------- song
class FakeScene(Observable):
    _observed = ("name", "color")

    def __init__(self, song, name, color):
        Observable.__init__(self)
        self._song = song
        self.name, self.color = name, color

    def fire(self):
        i = self._song.scenes.index(self)
        for t in self._song.tracks:
            if t.clip_slots and i < len(t.clip_slots):
                self._song._fire_slot(t, t.clip_slots[i], from_scene=True)


class FakeSongView(Observable):
    _observed = ("selected_track", "selected_scene", "detail_clip")

    def __init__(self):
        Observable.__init__(self)
        self.selected_track = None
        self.selected_scene = None
        self.detail_clip = None
        self.selected_device = None

    def select_device(self, d):
        self.selected_device = d


class FakeCue(object):
    def __init__(self, time):
        self.time = time


class FakeSong(Observable):
    _observed = ("tempo", "is_playing", "record_mode", "session_record", "arrangement_overdub", "punch_in",
                 "punch_out", "loop", "loop_start", "loop_length", "metronome", "signature_numerator",
                 "signature_denominator", "clip_trigger_quantization", "midi_recording_quantization",
                 "count_in_duration", "back_to_arranger", "exclusive_arm", "song_length", "can_undo", "can_redo",
                 "tracks", "return_tracks", "scenes")

    def __init__(self):
        Observable.__init__(self)
        self.name = "Demo Set"
        self.tempo = 120.0
        self.signature_numerator, self.signature_denominator = 4, 4
        self.is_playing = False
        self.current_song_time = 0.0
        self.record_mode = self.session_record = self.arrangement_overdub = False
        self.punch_in = self.punch_out = self.loop = self.metronome = False
        self.loop_start, self.loop_length = 0.0, 8.0
        self.count_in_duration = 0
        self.clip_trigger_quantization = 7
        self.midi_recording_quantization = 5
        self.back_to_arranger = False
        self.exclusive_arm = True
        self.tracks, self.return_tracks, self.scenes = [], [], []
        self.master_track = None
        self.view = FakeSongView()
        self.cue_points = [FakeCue(0.0), FakeCue(16.0), FakeCue(32.0)]
        self._edits = self._redos = 0
        self._tap_times = []
        self.can_undo = self.can_redo = False

    # ---- structure
    @property
    def song_length(self):
        ends = [c.end_time for t in self.tracks for c in getattr(t, "arrangement_clips", [])]
        return max(ends) if ends else 0.0

    def _edit(self):
        self._edits += 1
        self._redos = 0
        self.can_undo, self.can_redo = True, False

    def undo(self):
        if self._edits:
            self._edits -= 1
            self._redos += 1
        self.can_undo, self.can_redo = self._edits > 0, self._redos > 0

    def redo(self):
        if self._redos:
            self._redos -= 1
            self._edits += 1
        self.can_undo, self.can_redo = self._edits > 0, self._redos > 0

    def _new_track(self, name, kind, color=None):
        return FakeTrack(self, name, kind, PALETTE[len(self.tracks) % len(PALETTE)] if color is None else color,
                         len(self.scenes), n_sends=len(self.return_tracks))

    def _insert_track(self, t, index):
        lst = list(self.tracks)
        lst.insert(len(lst) if index < 0 else index, t)
        self.tracks = lst
        self._edit()
        return t

    def create_audio_track(self, index=-1):
        return self._insert_track(self._new_track("%d Audio" % (len(self.tracks) + 1), "audio"), index)

    def create_midi_track(self, index=-1):
        return self._insert_track(self._new_track("%d MIDI" % (len(self.tracks) + 1), "midi"), index)

    def create_return_track(self):
        t = FakeTrack(self, "%s Return" % "ABCDEFGH"[len(self.return_tracks) % 8], "return",
                      PALETTE[(len(self.return_tracks) + 9) % 14], 0, 0)
        self.return_tracks = list(self.return_tracks) + [t]
        for tr in self.tracks:
            tr.mixer_device.sends.append(FakeParam("Send " + "ABCDEFGH"[len(tr.mixer_device.sends) % 8], 0.0, fmt=_db))
        self._edit()

    def delete_track(self, i):
        t = self.tracks[i]
        self._stop_track(t)
        for o in self.tracks:
            if o.group_track is t:
                o.group_track, o.is_grouped = None, False
        self.tracks = [x for x in self.tracks if x is not t]
        if self.view.selected_track is t:
            self.view.selected_track = self.tracks[min(i, len(self.tracks) - 1)] if self.tracks else None
        self._edit()

    def delete_return_track(self, i):
        self.return_tracks = [x for k, x in enumerate(self.return_tracks) if k != i]
        for t in self.tracks:
            if i < len(t.mixer_device.sends):
                del t.mixer_device.sends[i]
        self._edit()

    def duplicate_track(self, i):
        src = self.tracks[i]
        t = FakeTrack(self, src.name + " copy", src.kind, src.color, len(self.scenes), len(self.return_tracks))
        for s, d in zip(src.clip_slots, t.clip_slots):
            if s.clip is not None:
                d._set_clip(s.clip.clone())
        t.arrangement_clips = [c.clone(arr_start=c.start_time) for c in src.arrangement_clips]
        t.devices = list(src.devices)
        self._insert_track(t, i + 1)
        return t

    def create_scene(self, index=-1):
        sc = FakeScene(self, "", 0)
        lst = list(self.scenes)
        idx = len(lst) if index < 0 or index > len(lst) else index
        lst.insert(idx, sc)
        for t in self.tracks:
            if t.kind in ("audio", "midi", "group"):
                t.clip_slots.insert(idx, FakeSlot(t))
        self.scenes = lst
        self._edit()
        return sc

    # ---- transport
    def start_playing(self):
        self.is_playing = True

    def stop_playing(self):
        if not self.is_playing:
            self.current_song_time = 0.0
        self.is_playing = False
        self.stop_all_clips()

    def continue_playing(self):
        self.is_playing = True

    def tap_tempo(self):
        import time
        now = time.monotonic()
        self._tap_times = [t for t in self._tap_times if now - t < 2.5] + [now]
        if len(self._tap_times) >= 2:
            iv = (self._tap_times[-1] - self._tap_times[0]) / (len(self._tap_times) - 1)
            self.tempo = max(20.0, min(999.0, 60.0 / iv))

    def capture_midi(self):
        t = self.view.selected_track
        if t is not None and t.has_midi_input:
            for s in t.clip_slots:
                if s.clip is None:
                    c = FakeClip("Captured", t.color, 4.0)
                    c.add_new_notes([{"pitch": 60, "start_time": 0.0, "duration": 1.0}])
                    s._set_clip(c)
                    return
        raise RuntimeError("nothing to capture")

    def stop_all_clips(self):
        for t in self.tracks:
            self._stop_track(t)

    def re_enable_automation(self):
        pass

    def jump_by(self, beats):
        self.current_song_time = max(0.0, self.current_song_time + beats)

    def jump_to_next_cue(self):
        for c in self.cue_points:
            if c.time > self.current_song_time + 1e-6:
                self.current_song_time = c.time
                return

    def jump_to_prev_cue(self):
        for c in reversed(self.cue_points):
            if c.time < self.current_song_time - 1e-6:
                self.current_song_time = c.time
                return

    # ---- clip engine
    def _unplay(self, track, slot):
        c = slot.clip
        if c is not None and (c.is_playing or c.is_recording):
            self._end_clip(track, slot)
        slot.is_triggered = False
        self._refresh_indices(track)

    def _end_clip(self, track, slot):
        c = slot.clip
        if c is None:
            return
        if c.is_recording:
            ln = max(1.0, math.ceil(c.loop_end - 1e-6))
            c.loop_end = c.end_marker = ln
            c.is_recording = False
        c.is_playing = False
        c.playing_position = 0.0
        c.playing_status = 0

    def _refresh_indices(self, track):
        ps, fs = -1, -1
        for i, s in enumerate(track.clip_slots):
            if s.is_playing or s.is_recording:
                ps = i
            if s.is_triggered:
                fs = i
        track.playing_slot_index = ps
        track.fired_slot_index = fs

    def _stop_track(self, track):
        for s in track.clip_slots:
            if s.clip is not None and (s.clip.is_playing or s.clip.is_recording):
                self._end_clip(track, s)
            s.is_triggered = False
        self._refresh_indices(track)

    def _stop_slot(self, track, slot):
        self._unplay(track, slot)

    def _fire_slot(self, track, slot, from_scene=False):
        if track.kind in ("return", "master"):
            return
        if slot.clip is None and not (track.can_be_armed and getattr(track, "arm", False)):
            self._stop_track(track)
            return
        for s in track.clip_slots:
            s.is_triggered = False
        slot.is_triggered = True
        if not self.is_playing:
            self.is_playing = True
        if self.clip_trigger_quantization == 0:
            self._launch(track, slot)
        else:
            self._refresh_indices(track)

    def _launch(self, track, slot):
        for s in track.clip_slots:
            if s is not slot and s.clip is not None and (s.clip.is_playing or s.clip.is_recording):
                self._end_clip(track, s)
        slot.is_triggered = False
        if slot.clip is None:
            c = FakeClip("", track.color, 0.0, audio=False)
            c.loop_end = c.end_marker = 0.0
            if track.kind == "audio":
                c = FakeClip("", track.color, 0.0, audio=True, file_path="", sample_seconds=0.0)
                c.loop_end = c.end_marker = 0.0
            c.is_recording = True
            slot._set_clip(c)
        c = slot.clip
        c.is_playing = True
        c.playing_position = c.loop_start
        c.playing_status = 1
        self._refresh_indices(track)

    def advance(self, dt):
        """Advance simulated time by dt seconds."""
        if self.is_playing and dt > 0:
            beats = dt * self.tempo / 60.0
            old = self.current_song_time
            new = old + beats
            if self.loop and old < self.loop_start + self.loop_length <= new:
                new = self.loop_start + (new - (self.loop_start + self.loop_length))
            crossed = int(new) != int(old) or new < old
            self.current_song_time = new
            for t in self.tracks:
                for s in t.clip_slots:
                    c = s.clip
                    if c is None:
                        continue
                    if c.is_recording:
                        c.loop_end = c.end_marker = c.loop_end + beats
                        c.playing_position = c.loop_end
                    elif c.is_playing:
                        p = c.playing_position + beats
                        span = c.loop_end - c.loop_start
                        if c.looping and span > 0:
                            p = c.loop_start + (p - c.loop_start) % span
                        elif p >= c.end_marker:
                            self._end_clip(t, s)
                            self._refresh_indices(t)
                            continue
                        c.playing_position = p
            if crossed:
                for t in self.tracks:
                    for s in t.clip_slots:
                        if s.is_triggered:
                            self._launch(t, s)
        ph = self.current_song_time
        for i, t in enumerate(self.tracks + self.return_tracks + [self.master_track]):
            active = self.is_playing and (t is self.master_track or t.kind == "return" or t.playing_slot_index >= 0)
            lvl = (0.25 + 0.2 * abs(math.sin(ph * 2.0 + i))) if active else 0.0
            t.output_meter_left = lvl * t.mixer_device.volume.value
            t.output_meter_right = lvl * 0.9 * t.mixer_device.volume.value


# ---------------------------------------------------------------------------------------------- browser / app
class FakeBrowserItem(object):
    def __init__(self, name, children=None, loadable=False, device=False, uri=None, kind="device"):
        self.name = name
        self.children = list(children) if children is not None else []
        self.is_folder = children is not None
        self.is_loadable = loadable
        self.is_device = device
        self.uri = uri or ("query:%s" % name.replace(" ", ""))
        self.kind = kind


class FakeBrowser(object):
    def __init__(self):
        D = lambda n, k: FakeBrowserItem(n, None, True, True, kind=k)
        self.instruments = FakeBrowserItem("Instruments", [
            FakeBrowserItem("Drum Rack", None, True, True, kind="instrument"),
            FakeBrowserItem("Instrument Rack", None, True, True, kind="instrument"),
            FakeBrowserItem("Synths", [D("Operator", "instrument"), D("Wavetable", "instrument"),
                                       D("Analog", "instrument"), D("Drift", "instrument")]),
            FakeBrowserItem("Samplers", [D("Simpler", "instrument"), D("Sampler", "instrument")]),
        ])
        self.audio_effects = FakeBrowserItem("Audio Effects", [
            FakeBrowserItem("Dynamics", [D("Compressor", "audio"), D("Glue Compressor", "audio"), D("Limiter", "audio")]),
            FakeBrowserItem("EQ and Filters", [D("EQ Eight", "audio"), D("Auto Filter", "audio")]),
            FakeBrowserItem("Reverb and Resonance", [D("Reverb", "audio"), D("Hybrid Reverb", "audio")]),
            D("Saturator", "audio"), D("Delay", "audio"),
        ])
        self.midi_effects = FakeBrowserItem("MIDI Effects", [D("Arpeggiator", "midi"), D("Chord", "midi"), D("Scale", "midi")])
        self.drums = FakeBrowserItem("Drums", [FakeBrowserItem("Kits", [D("808 Core Kit", "instrument"), D("909 Core Kit", "instrument")]),
                                               D("Kick Hit", "instrument")])
        self.sounds = FakeBrowserItem("Sounds", [FakeBrowserItem("Bass", [D("Sub Bass", "instrument"), D("Reese", "instrument")]),
                                                 FakeBrowserItem("Pad", [D("Warm Pad", "instrument")])])
        self.samples = FakeBrowserItem("Samples", [
            FakeBrowserItem("Loops", [FakeBrowserItem(n, None, True, False, kind="sample") for n in
                                      ("sine_a.wav", "pad_chord.wav", "noise_hat.wav")]),
            FakeBrowserItem("One Shots", [FakeBrowserItem("kick.wav", None, True, False, kind="sample")]),
        ])
        self.packs = FakeBrowserItem("Packs", [FakeBrowserItem("Core Library", [])])
        self.user_library = FakeBrowserItem("User Library", [FakeBrowserItem("Presets", [])])
        self.plugins = FakeBrowserItem("Plug-ins", [])
        self.clips = FakeBrowserItem("Clips", [])
        self.previewing = None
        self._song = None

    def load_item(self, item):
        if not item.is_loadable:
            raise RuntimeError("item not loadable")
        song = self._song
        t = song.view.selected_track
        if t is None:
            raise RuntimeError("no selected track")
        if item.kind == "sample":
            if not t.has_audio_input:
                raise RuntimeError("samples need an audio track")
            path = os.path.join(MEDIA_DIR, item.name)
            for s in t.clip_slots:
                if s.clip is None:
                    s._set_clip(audio_clip_from_wav(song, item.name, t.color, path))
                    return
            raise RuntimeError("no free slot")
        typ = {"instrument": DT_INSTRUMENT, "audio": DT_AUDIO, "midi": DT_MIDI}[item.kind]
        if item.name == "Instrument Rack" or item.name == "Drum Rack":
            d = FakeRack(item.name, "InstrumentGroupDevice" if item.name == "Instrument Rack" else "DrumGroupDevice",
                         typ, [FakeChain("Chain")], macros=_macro_names() if item.name == "Instrument Rack" else None)
        else:
            d = FakeDevice(item.name, item.name.replace(" ", ""), typ)
        if typ is DT_INSTRUMENT:
            t.devices = [d] + list(t.devices)
        else:
            t.devices = list(t.devices) + [d]
        song._edit()

    def preview_item(self, item):
        self.previewing = item.name

    def stop_preview(self):
        self.previewing = None


class FakeAppView(Observable):
    _observed = ("focused_document_view",)

    def __init__(self):
        Observable.__init__(self)
        self.focused_document_view = "Session"

    def is_view_visible(self, name):
        return self.focused_document_view == name

    def show_view(self, name):
        self.focused_document_view = name

    def focus_view(self, name):
        self.focused_document_view = name


class FakeApp(object):
    def __init__(self, browser):
        self.browser = browser
        self.view = FakeAppView()

    def get_major_version(self):
        return 12

    def get_minor_version(self):
        return 4

    def get_bugfix_version(self):
        return 1


# ---------------------------------------------------------------------------------------------- media
def ensure_media(directory=MEDIA_DIR):
    """Generate small WAVs (22.05 kHz mono 16-bit) if missing. Each < 200 KB."""
    os.makedirs(directory, exist_ok=True)
    sr = 22050
    rng = random.Random(1)

    def write(name, seconds, fn):
        p = os.path.join(directory, name)
        if os.path.exists(p):
            return
        n = int(sr * seconds)
        frames = b"".join(struct.pack("<h", max(-32767, min(32767, int(fn(i / sr, i) * 32767)))) for i in range(n))
        with wave.open(p, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes(frames)

    write("sine_a.wav", 3.0, lambda t, i: 0.6 * math.sin(2 * math.pi * 220 * t) * (0.5 + 0.5 * math.sin(2 * math.pi * 0.5 * t)))
    write("pad_chord.wav", 3.0, lambda t, i: 0.25 * (math.sin(2 * math.pi * 261.6 * t) + math.sin(2 * math.pi * 329.6 * t)
                                                     + math.sin(2 * math.pi * 392.0 * t)) * min(1.0, t * 4))
    write("noise_hat.wav", 2.0, lambda t, i: (rng.uniform(-1, 1) * 0.5 * math.exp(-((t * 4) % 1.0) * 12)))
    return directory


def audio_clip_from_wav(song, name, color, path, arr_start=None):
    secs, sr = 2.0, 22050
    try:
        with wave.open(path, "rb") as w:
            sr = w.getframerate()
            secs = w.getnframes() / float(sr)
    except Exception:
        pass
    beats = max(1.0, round(secs * song.tempo / 60.0 * 4) / 4.0)
    return FakeClip(name, color, beats, True, path, secs, sr, arr_start)


# ---------------------------------------------------------------------------------------------- demo set
def _macro_names():
    return ["Cutoff", "Resonance", "Attack", "Release", "Drive", "Chorus", "Level", "Space"]


def _dev(name, cls, typ, params=None):
    return FakeDevice(name, cls, typ, params)


def _notes_pattern(kind):
    n = []
    if kind == "drums":
        for bar in range(2):
            b = bar * 4
            n += [(36, b + 0, 0.25, 112), (36, b + 2.5, 0.25, 100), (38, b + 1, 0.25, 104), (38, b + 3, 0.25, 108)]
            n += [(42, b + k * 0.5, 0.2, 70 + 15 * (k % 2)) for k in range(8)]
    elif kind == "bass":
        for k, p in enumerate((36, 36, 39, 41, 36, 36, 43, 41)):
            n.append((p, k * 1.0, 0.75, 100))
    elif kind == "keys":
        for k, ch in enumerate(((60, 64, 67), (57, 60, 64), (65, 69, 72), (67, 71, 74))):
            for p in ch:
                n.append((p, k * 2.0, 1.9, 84))
    else:
        for k in range(8):
            n.append((72 + (k * 3) % 7, k * 0.5, 0.4, 90))
    return n


def _midi_clip(name, color, length, kind, track=None):
    c = FakeClip(name, color, length)
    c.add_new_notes([{"pitch": p, "start_time": t, "duration": d, "velocity": v} for p, t, d, v in _notes_pattern(kind)
                     if t < length])
    return c


def build_demo(media_dir=None):
    """Returns (song, app) with a rich demo set. Deterministic."""
    media = ensure_media(media_dir or MEDIA_DIR)
    song = FakeSong()
    browser = FakeBrowser()
    app = FakeApp(browser)
    browser._song = song
    # scenes
    names = ["Intro", "Verse", "Pre", "Chorus", "Verse 2", "Bridge", "Chorus 2", "Outro"]
    song.scenes = [FakeScene(song, n, PALETTE[(i * 2) % 14]) for i, n in enumerate(names)]
    # returns first so tracks get sends
    song.master_track = FakeTrack(song, "Master", "master", 0x9A9A9A, 0, 0)
    song.return_tracks = [FakeTrack(song, "A Reverb", "return", PALETTE[8], 0, 0),
                          FakeTrack(song, "B Delay", "return", PALETTE[11], 0, 0)]
    spec = [("Drums", "midi", 1), ("Bass", "midi", 5), ("Keys", "midi", 9), ("Vocals", "audio", 12),
            ("Guitar", "audio", 4), ("FX Group", "group", 7), ("FX Audio", "audio", 10), ("FX MIDI", "midi", 0)]
    song.tracks = [FakeTrack(song, n, k, PALETTE[c], len(song.scenes), 2) for n, k, c in spec]
    T = dict((t.name, t) for t in song.tracks)
    for child in (T["FX Audio"], T["FX MIDI"]):
        child.group_track, child.is_grouped = T["FX Group"], True
    song.master_track.mixer_device.sends = []

    # devices
    kick = FakeChain("Kick", [_dev("Simpler", "OriginalSimpler", DT_INSTRUMENT)])
    snare = FakeChain("Snare", [_dev("Simpler", "OriginalSimpler", DT_INSTRUMENT), _dev("Saturator", "Saturator", DT_AUDIO)])
    hat = FakeChain("Hat", [_dev("Simpler", "OriginalSimpler", DT_INSTRUMENT)])
    T["Drums"].devices = [FakeRack("Drum Rack", "DrumGroupDevice", DT_INSTRUMENT, [kick, snare, hat]),
                          _dev("Compressor", "Compressor2", DT_AUDIO)]
    T["Bass"].devices = [_dev("Operator", "Operator", DT_INSTRUMENT, ["Osc A Level", "Filter Freq", "Filter Res", "Attack", "Release"]),
                         _dev("Saturator", "Saturator", DT_AUDIO, ["Drive", "Output"])]
    pad = FakeRack("Layer Pad", "InstrumentGroupDevice", DT_INSTRUMENT, [
        FakeChain("Soft Pad", [_dev("Wavetable", "InstrumentVector", DT_INSTRUMENT), _dev("Auto Filter", "AutoFilter2", DT_AUDIO)]),
        FakeChain("Bright Saw", [_dev("Analog", "UltraAnalog", DT_INSTRUMENT), _dev("Chorus-Ensemble", "ChorusEnsemble", DT_AUDIO)]),
        FakeChain("Sub", [_dev("Operator", "Operator", DT_INSTRUMENT)])], macros=_macro_names())
    pad.parameters[1].value = 0.3
    pad.store_variation()
    pad.parameters[1].value = 0.8
    pad.store_variation()
    pad.parameters[5].value = 0.9
    pad.store_variation()
    pad.selected_variation_index = 1
    arp = _dev("Arpeggiator", "MidiArpeggiator", DT_MIDI, ["Rate", "Gate", "Steps"])
    T["Keys"].devices = [arp, pad, _dev("EQ Eight", "Eq8", DT_AUDIO)]
    T["Vocals"].devices = [_dev("EQ Eight", "Eq8", DT_AUDIO), _dev("Compressor", "Compressor2", DT_AUDIO),
                           _dev("Reverb", "Reverb", DT_AUDIO)]
    T["Guitar"].devices = [_dev("Amp", "Amp", DT_AUDIO), _dev("Cabinet", "Cabinet", DT_AUDIO)]
    T["FX Group"].devices = [_dev("Glue Compressor", "GlueCompressor", DT_AUDIO)]
    song.return_tracks[0].devices = [_dev("Reverb", "Reverb", DT_AUDIO)]
    song.return_tracks[1].devices = [_dev("Delay", "Delay", DT_AUDIO)]
    song.master_track.devices = [_dev("Limiter", "Limiter", DT_AUDIO)]

    # session clips
    def put(tr, i, clip):
        T[tr].clip_slots[i]._set_clip(clip)

    for i in range(6):
        put("Drums", i, _midi_clip("Beat %d" % (i + 1), PALETTE[1], 8.0, "drums"))
        put("Bass", i, _midi_clip("Bass %d" % (i + 1), PALETTE[5], 8.0, "bass"))
    for i in (0, 1, 2, 4):
        put("Keys", i, _midi_clip("Chords %d" % (i + 1), PALETTE[9], 8.0, "keys"))
    put("FX MIDI", 5, _midi_clip("Lead", PALETTE[0], 4.0, "lead"))
    wav = lambda n: os.path.join(media, n)
    put("Vocals", 0, audio_clip_from_wav(song, "Vox A", PALETTE[12], wav("sine_a.wav")))
    put("Vocals", 3, audio_clip_from_wav(song, "Vox B", PALETTE[12], wav("sine_a.wav")))
    put("Guitar", 1, audio_clip_from_wav(song, "Pad Loop", PALETTE[4], wav("pad_chord.wav")))
    put("Guitar", 2, audio_clip_from_wav(song, "Pad Loop 2", PALETTE[4], wav("pad_chord.wav")))
    put("FX Audio", 3, audio_clip_from_wav(song, "Hats", PALETTE[10], wav("noise_hat.wav")))

    # arrangement
    def arr(tr, spans, mk):
        t = T[tr]
        t.arrangement_clips = [mk(n, s, e) for n, s, e in spans]

    def midi_arr(kind, color):
        def mk(n, s, e):
            c = _midi_clip(n, color, e - s, kind)
            c = c.clone(arr_start=float(s))
            return c
        return mk

    arr("Drums", [("Beat A", 0, 8), ("Beat B", 8, 16), ("Beat C", 16, 32)], midi_arr("drums", PALETTE[1]))
    arr("Bass", [("Bass A", 0, 16), ("Bass B", 16, 32)], midi_arr("bass", PALETTE[5]))
    arr("Keys", [("Chords", 8, 32)], midi_arr("keys", PALETTE[9]))
    arr("Vocals", [("Vox Main", 8, 14), ("Vox Main 2", 16, 22)],
        lambda n, s, e: audio_clip_from_wav(song, n, PALETTE[12], wav("sine_a.wav"), arr_start=float(s)))
    arr("Guitar", [("Guitar Main", 0, 6)],
        lambda n, s, e: audio_clip_from_wav(song, n, PALETTE[4], wav("pad_chord.wav"), arr_start=float(s)))

    # take lanes
    def lane(track, name, clips, color, wavname):
        ln = FakeLane(name)
        ln.arrangement_clips = [audio_clip_from_wav(song, cn, color, wav(wavname), arr_start=float(s)) for cn, s in clips]
        track.take_lanes = list(track.take_lanes) + [ln]

    lane(T["Vocals"], "Take Lane 1", [("Vox take 1", 8.0), ("Vox take 1b", 16.0)], PALETTE[12], "sine_a.wav")
    lane(T["Vocals"], "Take Lane 2", [("Vox take 2", 8.0)], PALETTE[11], "sine_a.wav")
    lane(T["Vocals"], "Take Lane 3", [("Vox take 3", 8.0), ("Vox take 3b", 16.0)], PALETTE[10], "sine_a.wav")
    lane(T["Guitar"], "Take Lane 1", [("Gtr take 1", 0.0)], PALETTE[4], "pad_chord.wav")
    lane(T["Guitar"], "Take Lane 2", [("Gtr take 2", 0.0), ("Gtr take 2b", 8.0)], PALETTE[3], "pad_chord.wav")

    # mixer defaults
    T["Drums"].mixer_device.volume.value = 0.8
    T["Bass"].mixer_device.volume.value = 0.7
    T["Keys"].mixer_device.sends[0].value = 0.3
    T["Keys"].arm = False
    song.view.selected_track = T["Drums"]
    song.view.selected_scene = song.scenes[0]
    song._edits = 0
    song.can_undo = song.can_redo = False
    return song, app
