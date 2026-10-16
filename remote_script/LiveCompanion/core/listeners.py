"""Listener manager.

- A cheap GLOBAL group (song summary props, track name/arm/mute/solo/..., scene name/color).
- A detailed group per FOCUSED track (mixer, routing, slots/clips, arrangement, takes, device tree).
- A group per OPENED clip and per OPENED device.
Callbacks only add keys to a dirty set (and optionally request a listener resync). All LOM listener
calls use the `add_X_listener` / `remove_X_listener` / `X_has_listener` convention and tolerate absence."""
from .util import g, seq, resolve_track, resolve_clip, resolve_device, slots_of, lane_clips

MAX_PER_GROUP = 1500

SONG_PROPS = ("tempo", "is_playing", "record_mode", "session_record", "arrangement_overdub", "punch_in",
              "punch_out", "loop", "loop_start", "loop_length", "metronome", "signature_numerator",
              "signature_denominator", "clip_trigger_quantization", "midi_recording_quantization",
              "count_in_duration", "back_to_arranger", "exclusive_arm", "song_length", "can_undo", "can_redo",
              "root_note", "scale_name", "scale_intervals", "cue_points")
STRUCT_PROPS = ("tracks", "return_tracks", "scenes")
TRACK_PROPS = ("name", "color", "arm", "mute", "solo", "current_monitoring_state", "fold_state")
TRACK_SLOT_PROPS = ("playing_slot_index", "fired_slot_index")
CLIP_PROPS = ("name", "color", "looping", "loop_start", "loop_end", "start_marker", "end_marker", "muted",
              "gain", "pitch_coarse", "pitch_fine", "warping", "warp_mode", "launch_mode",
              "launch_quantization", "legato", "signature_numerator", "signature_denominator", "notes",
              "warp_markers", "is_recording")


class Listeners(object):
    def __init__(self, song, app, mark, resync, log=None):
        self.song, self.app = song, app
        self.mark, self.resync = mark, resync
        self.log = log or (lambda m: None)
        self.groups = {}          # key -> list[(obj, prop, cb)]
        self._want = {}

    # ------------------------------------------------------------ plumbing
    def _cb(self, keys, resync=False):
        mark, rs = self.mark, self.resync

        def cb(*_a):
            for k in keys:
                mark(k)
            if resync:
                rs()
        cb._keys = list(keys)
        return cb

    @staticmethod
    def _attach(bag, obj, prop, cb):
        if obj is None or len(bag) >= MAX_PER_GROUP:
            return
        add = getattr(obj, "add_%s_listener" % prop, None)
        if add is None:
            return
        try:
            has = getattr(obj, "%s_has_listener" % prop, None)
            if has is not None and has(cb):
                return
            add(cb)
            bag.append((obj, prop, cb))
        except Exception:
            pass

    @staticmethod
    def _detach(bag):
        for obj, prop, cb in bag:
            try:
                rem = getattr(obj, "remove_%s_listener" % prop, None)
                if rem is None:
                    continue
                has = getattr(obj, "%s_has_listener" % prop, None)
                if has is not None and not has(cb):
                    continue
                rem(cb)
            except Exception:
                pass
        del bag[:]

    def _many(self, bag, obj, props, cb):
        for p in props:
            self._attach(bag, obj, p, cb)

    # ------------------------------------------------------------ public
    def sync(self, active, focus_refs, clip_locs, devs, rebuild=False):
        """Make attached groups match the wanted set. clip_locs: {key: loc}; devs: set of (tr, path)."""
        want = {}
        if active:
            want[("g",)] = None
            for r in focus_refs:
                want[("f", r)] = r
            for k, loc in clip_locs.items():
                want[("c", k)] = loc
            for d in devs:
                want[("d",) + tuple(d)] = d
        if rebuild:
            for k in list(self.groups):
                self._detach(self.groups.pop(k))
        for k in list(self.groups):
            if k not in want:
                self._detach(self.groups.pop(k))
        for k, arg in want.items():
            if k in self.groups:
                continue
            bag = []
            self.groups[k] = bag
            try:
                if k[0] == "g":
                    self._build_global(bag)
                elif k[0] == "f":
                    self._build_focus(bag, arg)
                elif k[0] == "c":
                    self._build_clip(bag, arg)
                elif k[0] == "d":
                    self._build_device(bag, arg[0], arg[1])
            except Exception as e:
                self.log("listener build %r failed: %r" % (k, e))

    def detach_all(self):
        self.sync(False, (), {}, ())

    # ------------------------------------------------------------ builders
    def _build_global(self, bag):
        song = self.song
        cb = self._cb([("session",)])
        self._many(bag, song, SONG_PROPS, cb)
        self._many(bag, g(song, "view", None), ("selected_track", "selected_scene"), cb)
        self._many(bag, g(self.app, "view", None), ("focused_document_view",), cb)
        self._many(bag, song, STRUCT_PROPS, self._cb([("struct",)], True))
        alltr = [(("t%d" % i), t) for i, t in enumerate(seq(g(song, "tracks", ())))]
        alltr += [(("r%d" % i), t) for i, t in enumerate(seq(g(song, "return_tracks", ())))]
        alltr.append(("m", g(song, "master_track", None)))
        for ref, t in alltr:
            if t is None:
                continue
            self._many(bag, t, TRACK_PROPS, self._cb([("tracks",), ("focus", ref)]))
            self._many(bag, t, TRACK_SLOT_PROPS, self._cb([("tracks",), ("focus", ref), ("slots", ref)]))
        scb = self._cb([("tracks",)])
        for s in seq(g(song, "scenes", ())):
            self._many(bag, s, ("name", "color"), scb)

    def _build_focus(self, bag, ref):
        song = self.song
        t = resolve_track(song, ref)
        fcb = self._cb([("focus", ref)])
        scb = self._cb([("slots", ref)])
        mixer = g(t, "mixer_device", None)
        for p in [g(mixer, "volume", None), g(mixer, "panning", None)] + seq(g(mixer, "sends", ())):
            self._attach(bag, p, "value", fcb)
        for p in ("input_routing_type", "input_routing_channel", "output_routing_type",
                  "available_input_routing_types", "available_input_routing_channels",
                  "available_output_routing_types"):
            self._attach(bag, t, p, fcb)
        # session slots
        hcb = self._cb([("slots", ref)], True)
        for s in slots_of(t):
            self._attach(bag, s, "has_clip", hcb)
            self._attach(bag, s, "playing_status", scb)
            self._attach(bag, s, "is_triggered", scb)
            if g(s, "has_clip", False):
                c = g(s, "clip", None)
                self._many(bag, c, ("name", "color", "muted", "looping", "playing_status", "is_recording"), scb)
        # arrangement
        acb = self._cb([("arr", ref)])
        self._attach(bag, t, "arrangement_clips", self._cb([("arr", ref)]))
        for c in seq(g(t, "arrangement_clips", ()))[:300]:
            self._many(bag, c, ("name", "color", "muted", "looping", "start_time", "end_time",
                                "start_marker", "end_marker", "loop_start", "loop_end", "notes"), acb)
        # takes
        tcb = self._cb([("takes", ref)])
        self._attach(bag, t, "take_lanes", tcb)
        for lane in seq(g(t, "take_lanes", ()))[:32]:
            self._attach(bag, lane, "name", tcb)
            self._attach(bag, lane, "arrangement_clips", tcb)
            for c in lane_clips(lane)[:100]:
                self._many(bag, c, ("name", "color", "muted"), tcb)
        # devices
        dcb = self._cb([("devices", ref)])
        self._attach(bag, t, "devices", self._cb([("devices", ref)], True))
        self._walk_devices(bag, seq(g(t, "devices", ())), dcb, 0)

    def _walk_devices(self, bag, devs, cb, depth):
        for d in devs:
            if len(bag) >= MAX_PER_GROUP or depth > 6:
                return
            self._attach(bag, d, "name", cb)
            ps = seq(g(d, "parameters", ()))
            for p in ps[:9]:
                self._attach(bag, p, "value", cb)
            if g(d, "can_have_chains", False):
                if g(d, "variation_count", None) is not None:
                    for p in ps[1:9]:
                        self._attach(bag, p, "value", cb)
                    self._many(bag, d, ("variation_count", "selected_variation_index"), cb)
                rcb = self._cb(cb._keys, True)
                self._attach(bag, d, "chains", rcb)
                for ch in seq(g(d, "chains", ())):
                    self._attach(bag, ch, "name", cb)
                    self._attach(bag, ch, "devices", rcb)
                    self._walk_devices(bag, seq(g(ch, "devices", ())), cb, depth + 1)

    def _build_clip(self, bag, loc):
        cr = resolve_clip(self.song, loc)
        tr = loc.get("tr")
        cb = self._cb([("clip",), ("slots", tr), ("arr", tr), ("takes", tr)])
        self._many(bag, cr.clip, CLIP_PROPS, cb)

    def _build_device(self, bag, tr, path):
        dev, _, _ = resolve_device(self.song, tr, path)
        cb = self._cb([("params",)])
        for p in seq(g(dev, "parameters", ())):
            self._attach(bag, p, "value", cb)
        self._attach(bag, dev, "name", cb)

