"""All protocol ops. Each op: fn(client, args) -> JSON-able result; raises OpError subclasses.

Missing LOM API -> Unsupported("..."). Core wraps every call so a bad op can never break the tick."""
from . import sections as S
from .util import (OpError, NeedsConfirm, BadArgs, Unsupported, g, num, intv, seq, need, need_num, need_int, is_int,
                   resolve_track, resolve_slot, resolve_clip, resolve_device, slots_of, lane_clips,
                   track_ref, parse_ref)

BROWSER_ROOTS = ("instruments", "audio_effects", "midi_effects", "drums", "sounds", "samples", "packs",
                 "user_library", "plugins", "clips")
MAX_OP_NOTES = 4000
MIN_ARR_LEN = 0.0625   # 1/16 beat: arr.trim refuses shorter clips
MAX_BROWSER_ITEMS = 2000

_SPEC = [None, False]


def _note_spec(d):
    """Build a note specification. Real Live wants Live.Clip.MidiNoteSpecification; fakes take dicts."""
    if not _SPEC[1]:
        _SPEC[1] = True
        try:
            from Live.Clip import MidiNoteSpecification  # only importable inside Live
            _SPEC[0] = MidiNoteSpecification
        except Exception:
            _SPEC[0] = None
    if _SPEC[0] is not None:
        return _SPEC[0](**d)
    return d


def _b(v, name="value"):
    if isinstance(v, bool):
        return v
    if isinstance(v, int) and v in (0, 1):
        return bool(v)
    raise BadArgs("expected boolean: " + name)


def _n(v, name="value", lo=None, hi=None):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise BadArgs("expected number: " + name)
    if v != v or v in (float("inf"), float("-inf")):
        raise BadArgs("bad number: " + name)
    if lo is not None and v < lo:
        raise BadArgs("%s below %s" % (name, lo))
    if hi is not None and v > hi:
        raise BadArgs("%s above %s" % (name, hi))
    return v


def _i(v, name="value", lo=None, hi=None):
    _n(v, name, lo, hi)
    if int(v) != v:
        raise BadArgs("expected integer: " + name)
    return int(v)


def _s(v, name="value"):
    if not isinstance(v, str):
        raise BadArgs("expected string: " + name)
    return v


def _parse_db(s):
    """'-6.0 dB' -> -6.0; None if the string is not a dB value."""
    try:
        s = str(s).strip()
        if not s.lower().endswith("db"):
            return None
        return float(s[:-2].strip().replace(",", "."))
    except (TypeError, ValueError):
        return None


def _put(obj, attr, val):
    if obj is None or not hasattr(obj, attr):
        raise Unsupported(attr)
    setattr(obj, attr, val)


def _call(obj, meth, *args):
    f = getattr(obj, meth, None) if obj is not None else None
    if f is None:
        raise Unsupported(meth)
    return f(*args)


def clean_loc(loc):
    if not isinstance(loc, dict):
        raise BadArgs("bad loc")
    out = {"w": loc.get("w"), "tr": loc.get("tr"), "i": loc.get("i")}
    if loc.get("w") == "take":
        out["lane"] = loc.get("lane")
    return out


class Ops(object):
    def __init__(self, core):
        self.core = core
        self.last_take = {}
        t = {}
        t["song.play"] = lambda c, a: _call(self.song, "start_playing")
        t["song.stop"] = lambda c, a: _call(self.song, "stop_playing")
        t["song.continue"] = lambda c, a: _call(self.song, "continue_playing")
        t["song.tap_tempo"] = lambda c, a: _call(self.song, "tap_tempo")
        t["song.undo"] = self.song_undo
        t["song.redo"] = self.song_redo
        t["song.capture_midi"] = lambda c, a: _call(self.song, "capture_midi")
        t["song.stop_all_clips"] = lambda c, a: _call(self.song, "stop_all_clips")
        t["song.re_enable_automation"] = lambda c, a: _call(self.song, "re_enable_automation")
        t["song.jump"] = self.song_jump
        t["song.seek"] = self.song_seek
        t["song.scrub"] = self.song_scrub
        t["song.cue"] = self.song_cue
        t["cue.move"] = self.cue_move
        t["song.set"] = self.song_set
        t["scene.fire"] = self.scene_fire
        t["scene.create"] = self.scene_create
        t["track.create"] = self.track_create
        t["track.delete"] = self.track_delete
        t["track.duplicate"] = self.track_duplicate
        t["track.move_check"] = self.track_move_check
        t["track.move"] = self.track_move
        t["focus.set"] = self.focus_set
        t["focus.follow"] = self.focus_follow
        t["view.select"] = self.view_select
        t["track.set"] = self.track_set
        t["track.stop"] = self.track_stop
        t["slot.fire"] = self.slot_fire
        t["slot.create"] = self.slot_create
        t["slot.delete"] = self.slot_delete
        t["slot.duplicate"] = self.slot_duplicate
        t["slot.copy"] = lambda c, a: self.slot_copy(c, a, False)
        t["slot.move"] = lambda c, a: self.slot_copy(c, a, True)
        t["take.next"] = self.take_next
        t["clip.fire"] = self.clip_fire
        t["clip.stop"] = self.clip_stop
        t["clip.scrub"] = self.clip_scrub
        t["clip.stop_scrub"] = self.clip_stop_scrub
        t["clip.open"] = self.clip_open
        t["clip.close"] = self.clip_close
        t["clip.set"] = self.clip_set
        t["clip.delete"] = self.clip_delete
        t["clip.duplicate_loop"] = lambda c, a: _call(self._clip(a).clip, "duplicate_loop")
        t["clip.crop"] = lambda c, a: _call(self._clip(a).clip, "crop")
        t["clip.quantize"] = self.clip_quantize
        t["clip.to_arr"] = self.clip_to_arr
        t["clip.peaks"] = self.clip_peaks
        t["arr.create_midi"] = self.arr_create_midi
        t["arr.move"] = self.arr_move
        t["arr.trim"] = self.arr_trim
        t["notes.add"] = self.notes_add
        t["notes.modify"] = self.notes_modify
        t["notes.remove"] = self.notes_remove
        t["notes.clear"] = self.notes_clear
        t["notes.transpose"] = self.notes_transpose
        t["notes.duplicate"] = self.notes_duplicate
        t["warp.add"] = self.warp_add
        t["warp.move"] = self.warp_move
        t["warp.remove"] = self.warp_remove
        t["takes.create_lane"] = self.takes_create_lane
        t["takes.rename"] = self.takes_rename
        t["takes.promote"] = self.takes_promote
        t["takes.demote"] = self.takes_demote
        t["takes.move"] = self.takes_move
        t["device.open"] = self.device_open
        t["device.close"] = self.device_close
        t["device.toggle"] = self.device_toggle
        t["device.param"] = self.device_param
        t["device.delete"] = self.device_delete
        t["device.select"] = self.device_select
        t["device.move"] = self.device_move
        t["device.insert"] = self.device_insert
        t["device.available"] = self.device_available
        t["device.duplicate"] = self.device_duplicate
        t["device.var"] = self.device_var
        t["chain.select"] = self.chain_select
        t["browser.list"] = self.browser_list
        t["browser.load"] = self.browser_load
        t["browser.preview"] = self.browser_preview
        t["browser.preview_stop"] = lambda c, a: _call(self._browser(), "stop_preview")
        t["sys.info"] = self.sys_info
        t["sys.probe"] = self.sys_probe
        self.table = t

    @property
    def song(self):
        return self.core.song

    @property
    def app(self):
        return self.core.app

    def run(self, client, op, a):
        f = self.table.get(op)
        if f is None:
            raise OpError("unknown op: %s" % (op,))
        return f(client, a)

    # ------------------------------------------------------------ helpers
    def _clip(self, a, key="loc"):
        return resolve_clip(self.song, need(a, key, dict))

    def _open_clip(self, c, midi=None):
        if not c.clip:
            raise OpError("no clip open")
        cr = resolve_clip(self.song, c.clip)
        is_audio = bool(g(cr.clip, "is_audio_clip", False))
        if midi is True and is_audio:
            raise BadArgs("opened clip is not a MIDI clip")
        if midi is False and not is_audio:
            raise BadArgs("opened clip is not an audio clip")
        return cr

    def _browser(self):
        b = g(self.app, "browser", None)
        if b is None:
            raise Unsupported("browser")
        return b

    # ------------------------------------------------------------ song
    def song_undo(self, c, a):
        if g(self.song, "can_undo", True):
            _call(self.song, "undo")

    def song_redo(self, c, a):
        if g(self.song, "can_redo", True):
            _call(self.song, "redo")

    def song_jump(self, c, a):
        b = _n(need(a, "beats"), "beats")
        if hasattr(self.song, "jump_by"):
            self.song.jump_by(b)
        else:
            _put(self.song, "current_song_time", max(0.0, num(g(self.song, "current_song_time", 0.0)) + b))

    def song_seek(self, c, a):
        _put(self.song, "current_song_time", float(max(0.0, _n(need(a, "beats"), "beats"))))

    def song_scrub(self, c, a):
        """Relative scrub (LOM scrub_by); falls back to a clamped relative seek."""
        b = _n(need(a, "beats"), "beats")
        if hasattr(self.song, "scrub_by"):
            self.song.scrub_by(b)
        else:
            _put(self.song, "current_song_time", float(max(0.0, num(g(self.song, "current_song_time", 0.0)) + b)))

    def song_cue(self, c, a):
        d = need(a, "dir")
        if d == 1:
            _call(self.song, "jump_to_next_cue")
        elif d == -1:
            _call(self.song, "jump_to_prev_cue")
        else:
            raise BadArgs("dir must be 1 or -1")

    def cue_move(self, c, a):
        """Move locator `i` to `time`. Best effort: CuePoint.time may be read-only, so fall back to
        delete + re-create at the new time (jump playhead, set_or_delete_cue) and restore name/playhead/play state."""
        i = _i(need(a, "i"), "i", 0)
        new_t = float(_n(need(a, "time"), "time", 0))
        song = self.song
        cues = list(seq(g(song, "cue_points", ())))
        if i >= len(cues):
            raise OpError("no such locator")
        cue = cues[i]
        old_t = num(g(cue, "time", 0.0))
        if abs(new_t - old_t) < 1e-6:
            return
        for j, o in enumerate(cues):
            if j != i and abs(num(g(o, "time", 0.0)) - new_t) < 1e-3:
                raise OpError("another locator is already there")
        try:
            cue.time = new_t
            if abs(num(g(cue, "time", old_t)) - new_t) < 1e-3:
                self.core.dirty.add(("session",))
                return
        except Exception:
            pass
        if not hasattr(song, "set_or_delete_cue"):
            raise OpError("cannot move locators: Live's API does not allow setting a locator time")
        name = _s(g(cue, "name", ""))
        pos = num(g(song, "current_song_time", 0.0))
        playing = bool(g(song, "is_playing", False))
        try:
            song.current_song_time = old_t
            song.set_or_delete_cue()
            if len(list(seq(g(song, "cue_points", ())))) >= len(cues):
                raise OpError("cannot move locators: Live did not delete the locator")
            song.current_song_time = new_t
            song.set_or_delete_cue()
            after = list(seq(g(song, "cue_points", ())))
            if len(after) < len(cues):
                raise OpError("cannot move locators: Live did not create the locator")
            for nc in after:
                if abs(num(g(nc, "time", 0.0)) - new_t) < 1e-3:
                    if name:
                        try:
                            nc.name = name
                        except Exception:
                            pass
                    break
        except OpError:
            self._cue_rollback(old_t, cues)
            raise
        except Exception as e:
            self._cue_rollback(old_t, cues)
            raise OpError("cannot move locators: %r" % (e,))
        finally:
            self.core.dirty.add(("session",))
            try:
                song.current_song_time = pos
                if bool(g(song, "is_playing", False)) != playing:
                    if playing:
                        song.start_playing()
                    else:
                        song.stop_playing()
                        song.current_song_time = pos
            except Exception:
                pass

    def _cue_rollback(self, old_t, before):
        song = self.song
        try:
            now = list(seq(g(song, "cue_points", ())))
            if len(now) < len(before) and not any(abs(num(g(x, "time", 0.0)) - old_t) < 1e-3 for x in now):
                song.current_song_time = old_t
                song.set_or_delete_cue()
        except Exception:
            pass

    def song_set(self, c, a):
        k = need(a, "k", str)
        v = need(a, "v")
        s = self.song
        simple_bool = {"metronome": "metronome", "loop": "loop", "punch_in": "punch_in", "punch_out": "punch_out",
                       "rec": "record_mode", "session_rec": "session_record", "overdub": "arrangement_overdub",
                       "auto_arm": "exclusive_arm", "back_to_arr": "back_to_arranger"}
        if k in simple_bool:
            _put(s, simple_bool[k], _b(v, k))
        elif k == "tempo":
            _put(s, "tempo", float(_n(v, k, 20, 999)))
        elif k == "sig":
            if not (isinstance(v, (list, tuple)) and len(v) == 2):
                raise BadArgs("sig must be [num,den]")
            n, d = _i(v[0], "sig num", 1, 99), _i(v[1], "sig den", 1, 16)
            if d not in (1, 2, 4, 8, 16):
                raise BadArgs("bad denominator")
            _put(s, "signature_numerator", n)
            _put(s, "signature_denominator", d)
        elif k == "loop_start":
            _put(s, "loop_start", float(_n(v, k, 0)))
        elif k == "loop_len":
            _put(s, "loop_length", float(_n(v, k, 0.0001)))
        elif k == "count_in":
            _put(s, "count_in_duration", _i(v, k, 0, 4))
        elif k == "quant":
            _put(s, "clip_trigger_quantization", _i(v, k, 0, 13))
        elif k == "rec_quant":
            _put(s, "midi_recording_quantization", _i(v, k, 0, 13))
        elif k == "view":
            if v not in ("session", "arranger"):
                raise BadArgs("view must be session|arranger")
            av = g(self.app, "view", None)
            _call(av, "show_view", "Session" if v == "session" else "Arranger")
        else:
            raise BadArgs("unknown song key: " + k)

    # ------------------------------------------------------------ scenes / tracks
    def scene_fire(self, c, a):
        i = need_int(a, "i")
        sc = seq(g(self.song, "scenes", ()))
        if not 0 <= i < len(sc):
            raise BadArgs("no such scene")
        _call(sc[i], "fire")

    def scene_create(self, c, a):
        i = need_int(a, "i")
        n = len(seq(g(self.song, "scenes", ())))
        if not -1 <= i <= n:
            raise BadArgs("scene index out of range")
        _call(self.song, "create_scene", i)

    def track_create(self, c, a):
        kind = need(a, "kind", str)
        i = a.get("i", -1)
        i = _i(i, "i", -1)
        if kind == "audio":
            _call(self.song, "create_audio_track", i)
        elif kind == "midi":
            _call(self.song, "create_midi_track", i)
        elif kind == "return":
            _call(self.song, "create_return_track")
        else:
            raise BadArgs("kind must be audio|midi|return")

    def _track_index(self, tr, allow_return=True):
        k, i = parse_ref(tr)
        resolve_track(self.song, tr)
        if k == "m":
            raise BadArgs("cannot do that to the master track")
        if k == "r" and not allow_return:
            raise BadArgs("not supported for return tracks")
        return k, i

    def track_delete(self, c, a):
        k, i = self._track_index(need(a, "tr", str))
        _call(self.song, "delete_track" if k == "t" else "delete_return_track", i)

    def track_duplicate(self, c, a):
        k, i = self._track_index(need(a, "tr", str), False)
        _call(self.song, "duplicate_track", i)

    # ------------------------------------------------------------ track.move (rebuild-move; UNVERIFIED on real Live)
    def _move_src(self, a):
        """-> (index, track). Only plain audio/MIDI tracks can be rebuilt; returns, master and groups are refused."""
        tr = need(a, "tr", str)
        k, i = parse_ref(tr)
        if k == "m":
            raise BadArgs("the master track can't be moved")
        if k == "r":
            raise BadArgs("return tracks can't be moved")
        t = resolve_track(self.song, tr)
        if g(t, "is_foldable", False):
            raise BadArgs("group tracks can't be moved")
        return i, t

    @staticmethod
    def _automated(t):
        """True when any mixer / device parameter of `t` has automation (automation_state != 0)."""
        params = []
        mixer = g(t, "mixer_device", None)
        for n in ("volume", "panning"):
            params.append(g(mixer, n, None))
        params.extend(seq(g(mixer, "sends", ())))

        def walk(devs):
            for d in devs:
                params.extend(seq(g(d, "parameters", ())))
                for ch in seq(g(d, "chains", ())):
                    walk(seq(g(ch, "devices", ())))
        walk(seq(g(t, "devices", ())))
        for p in params:
            if p is not None and intv(g(p, "automation_state", 0)) != 0:
                return True
        return False

    def _move_losses(self, t):
        losses = []
        if self._automated(t):
            losses.append("arrangement automation")
        if g(t, "group_track", None) is not None:
            losses.append("group membership")
        if g(t, "is_frozen", False):
            losses.append("frozen")
        if any(lane_clips(l) for l in seq(g(t, "take_lanes", ()))):
            losses.append("take lanes")
        return losses

    def track_move_check(self, c, a):
        _, t = self._move_src(a)
        losses = self._move_losses(t)
        return {"ok_to_move": not losses, "losses": losses}

    def track_move(self, c, a):
        """track.move {tr,to,force?}: `to` = final index in the track list. Builds a same-type track at the target
        position, moves devices (song.move_device), copies clips / mixer / routing / flags, deletes the original.
        All in one tick; on any error the new track is removed again (devices moved back) and an error returned."""
        song = self.song
        src_i, src = self._move_src(a)
        n = len(seq(g(song, "tracks", ())))
        to = _i(need(a, "to"), "to", 0)
        if to >= n:
            raise BadArgs("index out of range")
        losses = self._move_losses(src)
        if losses and a.get("force") is not True:
            raise NeedsConfirm(losses)
        if to == src_i:
            return {"tr": "t%d" % src_i}
        midi = bool(g(src, "has_midi_input", False))
        mk = "create_midi_track" if midi else "create_audio_track"
        if not hasattr(song, mk) or not hasattr(song, "move_device") or not hasattr(song, "delete_track"):
            raise Unsupported("moving tracks")
        # Remember what each connected client looks at (indices shift) and the song's selected track.
        others = []
        for cl in self.core.clients:
            if cl.focus and cl.focus[:1] == "t":
                try:
                    others.append((cl, resolve_track(song, cl.focus)))
                except Exception:
                    pass
        view = g(song, "view", None)
        was_selected = g(view, "selected_track", None) is src
        ins = to + 1 if to > src_i else to
        before = seq(g(song, "tracks", ()))
        _call(song, mk, ins)
        after = seq(g(song, "tracks", ()))
        if len(after) != len(before) + 1 or after[ins] in before:
            raise OpError("could not create the new track")
        new = after[ins]
        moved = []
        try:
            self._rebuild_into(src, new, moved)
            cur = seq(g(song, "tracks", ()))
            oi = next((k for k, x in enumerate(cur) if x is src), None)
            if oi is None:
                raise OpError("original track vanished")
            _call(song, "delete_track", oi)
        except Exception as e:
            self._move_rollback(src, new, moved)
            if isinstance(e, OpError):
                raise OpError("move failed, nothing changed: %s" % (e,))
            raise OpError("move failed, nothing changed: %s: %s" % (type(e).__name__, e))
        ref = track_ref(song, new) or "t%d" % to
        if was_selected:
            try:
                view.selected_track = new
            except Exception:
                pass
        for cl, obj in others:
            if cl is c:
                continue
            r = track_ref(song, obj)
            if r is None:
                cl.focus = None
            elif r != cl.focus:
                self.core.set_focus(cl, r)
        self.core.set_focus(c, ref)
        for cl in self.core.clients:
            if cl.clip:
                cl.clip = None            # indices shifted: the open clip's track ref may now be another track
                cl.pending.add("clip")
        self.core.dirty.update((("session",), ("tracks",)))
        self.core.mark_devices(ref)
        return {"tr": ref}

    def _move_rollback(self, src, new, moved):
        """Best effort: put the devices back (in their original order) and delete the half-built track."""
        song = self.song
        for k, d in enumerate(moved):
            try:
                _call(song, "move_device", d, src, k)
            except Exception:
                pass
        try:
            cur = seq(g(song, "tracks", ()))
            ni = next((k for k, x in enumerate(cur) if x is new), None)
            if ni is not None:
                _call(song, "delete_track", ni)
        except Exception:
            pass

    def _rebuild_into(self, src, new, moved):
        song = self.song
        # devices first (in order), so clips land on a fully set-up track
        for d in list(seq(g(src, "devices", ()))):
            _call(song, "move_device", d, new, len(seq(g(new, "devices", ()))))
            moved.append(d)
        # session clips slot by slot
        ss, ns = slots_of(src), slots_of(new)
        for k, s in enumerate(ss):
            if g(s, "has_clip", False) and k < len(ns):
                _call(s, "duplicate_clip_to", ns[k])
        # arrangement clips
        for cl in list(seq(g(src, "arrangement_clips", ()))):
            _call(new, "duplicate_clip_to_arrangement", cl, num(g(cl, "start_time", 0.0)))
        # identity + mixer + routing (best effort per property: a missing/refused one never aborts the move)
        for k in ("name", "color", "mute", "solo"):
            self._try_put(new, k, g(src, k, None))
        if g(src, "can_be_armed", False):
            self._try_put(new, "current_monitoring_state", g(src, "current_monitoring_state", None))
            if g(src, "arm", False):
                self._try_put(new, "arm", True)
        sm, nm = g(src, "mixer_device", None), g(new, "mixer_device", None)
        for pn in ("volume", "panning"):
            self._copy_param(g(sm, pn, None), g(nm, pn, None))
        for sp, np_ in zip(seq(g(sm, "sends", ())), seq(g(nm, "sends", ()))):
            self._copy_param(sp, np_)
        for cur, avail in (("input_routing_type", "available_input_routing_types"),
                           ("input_routing_channel", "available_input_routing_channels"),
                           ("output_routing_type", "available_output_routing_types"),
                           ("output_routing_channel", "available_output_routing_channels")):
            c0 = g(src, cur, None)
            if c0 is None:
                continue
            nm_ = S.display_name(c0)
            for x in seq(g(new, avail, ())):
                if S.display_name(x) == nm_:
                    self._try_put(new, cur, x)
                    break

    @staticmethod
    def _try_put(obj, attr, val):
        if val is None:
            return
        try:
            setattr(obj, attr, val)
        except Exception:
            pass

    @staticmethod
    def _copy_param(sp, np_):
        if sp is None or np_ is None:
            return
        try:
            np_.value = sp.value
        except Exception:
            pass

    # ------------------------------------------------------------ focus
    def focus_set(self, c, a):
        tr = need(a, "tr", str)
        t = resolve_track(self.song, tr)
        self.core.set_focus(c, tr)
        if c.follow:
            # Two-way follow: picking a track on the phone selects it in Live too, so the next follow check
            # does not snap the phone back to Live's old selection (owner: "keeps jumping back to the midi 1 track").
            _put(g(self.song, "view", None), "selected_track", t)
            c.last_sel = tr

    def focus_follow(self, c, a):
        on = _b(need(a, "on"), "on")
        c.follow = on
        c.pending.add("session")
        c.last_sel = None
        if on:
            self.core.follow_check(c)

    def view_select(self, c, a):
        t = resolve_track(self.song, need(a, "tr", str))
        _put(g(self.song, "view", None), "selected_track", t)

    # ------------------------------------------------------------ track.set
    def _routing_set(self, t, cur, avail, name):
        lst = seq(g(t, avail, ()))
        for x in lst:
            if S.display_name(x) == name:
                _put(t, cur, x)
                return
        raise BadArgs("no such routing option: %s" % (name,))

    def track_set(self, c, a):
        tr = need(a, "tr", str)
        k = need(a, "k", str)
        v = need(a, "v")
        t = resolve_track(self.song, tr)
        mixer = g(t, "mixer_device", None)
        if k == "name":
            _put(t, "name", _s(v, k))
        elif k == "color":
            _put(t, "color", _i(v, k, 0, 0xFFFFFF))
        elif k in ("arm", "monitor"):
            if not g(t, "can_be_armed", False):
                raise Unsupported("%s on this track" % k)
            if k == "arm":
                _put(t, "arm", _b(v, k))
            else:
                _put(t, "current_monitoring_state", _i(v, k, 0, 2))
        elif k in ("mute", "solo"):
            if tr == "m" and k == "solo":
                raise Unsupported("solo on master")
            _put(t, k, _b(v, k))
        elif k == "folded":
            if not g(t, "is_foldable", False):
                raise Unsupported("fold on non-group track")
            _put(t, "fold_state", _b(v, k))
        elif k in ("vol", "pan", "send"):
            if k == "send":
                i = _i(need(a, "i"), "i", 0)
                sends = seq(g(mixer, "sends", ()))
                if i >= len(sends):
                    raise BadArgs("no such send")
                p = sends[i]
            else:
                p = g(mixer, "volume" if k == "vol" else "panning", None)
            if p is None:
                raise Unsupported(k)
            lo, hi = num(g(p, "min", 0.0)), num(g(p, "max", 1.0))
            p.value = max(lo, min(hi, float(_n(v, k))))
        elif k == "in_type":
            self._routing_set(t, "input_routing_type", "available_input_routing_types", _s(v, k))
        elif k == "in_ch":
            self._routing_set(t, "input_routing_channel", "available_input_routing_channels", _s(v, k))
        elif k == "out_type":
            self._routing_set(t, "output_routing_type", "available_output_routing_types", _s(v, k))
        else:
            raise BadArgs("unknown track key: " + k)

    def track_stop(self, c, a):
        _call(resolve_track(self.song, need(a, "tr", str)), "stop_all_clips")

    # ------------------------------------------------------------ slots
    def slot_fire(self, c, a):
        _, s = resolve_slot(self.song, need(a, "tr", str), need_int(a, "i"))
        _call(s, "fire")

    def slot_create(self, c, a):
        _, s = resolve_slot(self.song, need(a, "tr", str), need_int(a, "i"))
        ln = _n(a.get("len", 4.0), "len", 0.0001, 4096)
        if g(s, "has_clip", False):
            raise OpError("slot already has a clip")
        if g(self._track_of(a["tr"]), "has_midi_input", True) is False:
            raise Unsupported("creating empty audio clips")
        _call(s, "create_clip", float(ln))

    def _track_of(self, tr):
        return resolve_track(self.song, tr)

    def slot_delete(self, c, a):
        _, s = resolve_slot(self.song, need(a, "tr", str), need_int(a, "i"))
        if not g(s, "has_clip", False):
            raise OpError("slot is empty")
        _call(s, "delete_clip")

    def slot_duplicate(self, c, a):
        tr, i = need(a, "tr", str), need_int(a, "i")
        t, s = resolve_slot(self.song, tr, i)
        if not g(s, "has_clip", False):
            raise OpError("slot is empty")
        sl = slots_of(t)
        j = i + 1
        while j < len(sl) and g(sl[j], "has_clip", False):
            j += 1          # never overwrite: use the next FREE slot
        if j >= len(sl):
            _call(self.song, "create_scene", len(seq(g(self.song, "scenes", ()))))
            sl = slots_of(t)
            j = len(sl) - 1
        _call(s, "duplicate_clip_to", sl[j])

    def slot_copy(self, c, a, move):
        _, s = resolve_slot(self.song, need(a, "tr", str), need_int(a, "i"))
        _, d = resolve_slot(self.song, need(a, "to_tr", str), need_int(a, "to_i"))
        if not g(s, "has_clip", False):
            raise OpError("source slot is empty")
        if s is d or s == d:
            return
        swap = bool(a.get("swap")) and move
        if g(d, "has_clip", False) and not swap and a.get("replace") is not True:
            # Never silently destroy the clip that is already there (owner: data loss).
            raise OpError("target slot is occupied (pass replace:true to overwrite or swap:true to swap)")
        if swap and g(d, "has_clip", False):
            self._swap_slots(s, d, resolve_track(self.song, need(a, "tr", str)))
            return
        _call(s, "duplicate_clip_to", d)
        if move:
            _call(s, "delete_clip")

    def _swap_slots(self, s, d, track):
        """Swap two occupied slots via a free scratch slot on the source track (new scene if none is free)."""
        sl = slots_of(track)
        tmp = None
        for x in sl:
            if x is not s and x is not d and not g(x, "has_clip", False):
                tmp = x
                break
        if tmp is None:
            _call(self.song, "create_scene", len(seq(g(self.song, "scenes", ()))))
            tmp = slots_of(track)[-1]
        _call(d, "duplicate_clip_to", tmp)
        _call(d, "delete_clip")
        _call(s, "duplicate_clip_to", d)
        _call(s, "delete_clip")
        _call(tmp, "duplicate_clip_to", s)
        _call(tmp, "delete_clip")

    def take_next(self, c, a):
        tr = need(a, "tr", str)
        t = resolve_track(self.song, tr)
        if tr[0] != "t" or not g(t, "can_be_armed", False):
            raise BadArgs("track cannot record")
        sl = slots_of(t)
        start = max(0, self.last_take.get(tr, 0))
        idx = None
        for i in range(start, len(sl)):
            if not g(sl[i], "has_clip", False):
                idx = i
                break
        new_scene = False
        if idx is None:
            n = len(seq(g(self.song, "scenes", ())))
            _call(self.song, "create_scene", n)
            sl = slots_of(t)
            idx = len(sl) - 1
            new_scene = True
        # "Take N": one more than the highest existing "Take <n>" clip name on this track.
        top = 0
        for s_ in sl:
            if g(s_, "has_clip", False):
                nm = _s(g(g(s_, "clip", None), "name", ""), "name")
                if nm.startswith("Take ") and nm[5:].isdigit():
                    top = max(top, int(nm[5:]))
        label = "Take %d" % (top + 1)
        _put(t, "arm", True)
        sl[idx].fire()
        if g(sl[idx], "has_clip", False):
            try:
                _put(g(sl[idx], "clip", None), "name", label)
            except Exception:
                pass
        if new_scene:
            try:
                scenes = seq(g(self.song, "scenes", ()))
                if scenes and not g(scenes[-1], "name", ""):
                    _put(scenes[-1], "name", label)
            except Exception:
                pass
        self.last_take[tr] = idx
        return {"i": idx, "name": label}

    # ------------------------------------------------------------ clips
    def clip_fire(self, c, a):
        cr = self._clip(a)
        if cr.kind != "slot":
            raise Unsupported("firing arrangement clips")
        _call(cr.slot, "fire")

    def clip_stop(self, c, a):
        cr = self._clip(a)
        if cr.kind != "slot":
            return                      # PROTOCOL clarification: arr/take -> no-op ok
        _call(cr.slot, "stop")

    def clip_scrub(self, c, a):
        """Scrub inside a session clip (LOM Clip.scrub; sounds until clip.stop_scrub). Arrangement clips use song.seek."""
        cr = self._clip(a)
        _call(cr.clip, "scrub", float(max(0.0, _n(need(a, "beat"), "beat"))))

    def clip_stop_scrub(self, c, a):
        cr = self._clip(a)
        if hasattr(cr.clip, "stop_scrub"):
            cr.clip.stop_scrub()

    def clip_open(self, c, a):
        loc = clean_loc(need(a, "loc", dict))
        resolve_clip(self.song, loc)
        c.clip = loc
        c.pending.add("clip")
        self.core.listeners_dirty = True

    def clip_close(self, c, a):
        c.clip = None
        c.pending.add("clip")
        self.core.listeners_dirty = True

    def clip_set(self, c, a):
        cr = self._clip(a)
        k = need(a, "k", str)
        v = need(a, "v")
        clip = cr.clip
        audio = bool(g(clip, "is_audio_clip", False))
        if k in ("gain", "gain_db", "pitch_coarse", "pitch_fine", "warping", "warp_mode") and not audio:
            raise BadArgs("%s is for audio clips" % k)
        if k == "name":
            _put(clip, "name", _s(v, k))
        elif k == "color":
            _put(clip, "color", _i(v, k, 0, 0xFFFFFF))
        elif k in ("looping", "warping", "legato", "muted"):
            _put(clip, k, _b(v, k))
        elif k in ("loop_start", "loop_end", "start_marker", "end_marker"):
            v = float(_n(v, k, 0.0, 1e6))
            lo_k, hi_k = ("loop_start", "loop_end") if k.startswith("loop") else ("start_marker", "end_marker")
            if k == lo_k and v >= num(g(clip, hi_k, 0.0)):
                raise BadArgs("%s (%.3g) must be before %s (%.3g)" % (lo_k, v, hi_k, num(g(clip, hi_k, 0.0))))
            if k == hi_k and v <= num(g(clip, lo_k, 0.0)):
                raise BadArgs("%s (%.3g) must be after %s (%.3g)" % (hi_k, v, lo_k, num(g(clip, lo_k, 0.0))))
            _put(clip, k, v)
        elif k == "gain":
            _put(clip, "gain", float(_n(v, k, 0.0, 1.0)))
        elif k == "gain_db":
            # Edit in displayed units. Start from the linear guess (raw 0.4 == 0 dB), then refine against
            # the clip's own gain_display_string, since Live's raw->dB curve is not linear.
            db = float(_n(v, k, -70.0, 24.0))
            raw = min(1.0, max(0.0, 0.4 * 10 ** (db / 20.0)))
            for _ in range(6):
                _put(clip, "gain", raw)
                shown = _parse_db(g(clip, "gain_display_string", ""))
                if shown is None or abs(shown - db) < 0.05:
                    break
                raw = min(1.0, max(0.0, raw * 10 ** ((db - shown) / 20.0)))
        elif k == "pitch_coarse":
            _put(clip, "pitch_coarse", _i(v, k, -48, 48))
        elif k == "pitch_fine":
            _put(clip, "pitch_fine", float(_n(v, k, -50, 50)))
        elif k == "warp_mode":
            if v not in S.WARP_MODE_IDS:
                raise BadArgs("unknown warp mode")
            _put(clip, "warp_mode", S.WARP_MODE_IDS[v])
        elif k == "sig_num":
            _put(clip, "signature_numerator", _i(v, k, 1, 99))
        elif k == "sig_den":
            d = _i(v, k, 1, 16)
            if d not in (1, 2, 4, 8, 16):
                raise BadArgs("bad denominator")
            _put(clip, "signature_denominator", d)
        elif k == "launch_mode":
            _put(clip, "launch_mode", _i(v, k, 0, 3))
        elif k == "launch_quant":
            _put(clip, "launch_quantization", _i(v, k, 0, 13))
        else:
            raise BadArgs("unknown clip key: " + k)

    def clip_delete(self, c, a):
        cr = self._clip(a)
        if cr.kind == "slot":
            _call(cr.slot, "delete_clip")
        else:
            _call(cr.track, "delete_clip", cr.clip)

    def clip_quantize(self, c, a):
        cr = self._clip(a)
        grid = _i(need(a, "grid"), "grid", 0, 13)
        amount = float(_n(a.get("amount", 1.0), "amount", 0.0, 1.0))
        _call(cr.clip, "quantize", grid, amount)

    def clip_peaks(self, c, a):
        """Waveform min/max peaks, computed on the core's worker thread; the reply is sent later by Core.
        Only the file path is read here (main thread); the worker does pure file I/O."""
        n = _i(need(a, "n"), "n", 16, 8192)
        cr = self._clip(a)
        if not g(cr.clip, "is_audio_clip", False):
            raise BadArgs("not an audio clip")
        path = g(cr.clip, "file_path", "")
        if not isinstance(path, str) or not path:
            raise OpError("no audio file")
        sr = g(cr.clip, "sample_rate", 0)
        sr = int(sr) if isinstance(sr, (int, float)) and not isinstance(sr, bool) and sr > 0 else 0
        return self.core.defer_peaks(c, path, n, sr, self._env_key(cr))

    def _env_key(self, cr):
        """Describes the clip for the meter-envelope fallback (see recenv.approx_peaks)."""
        try:
            tr = track_ref(self.song, cr.track)
            t0 = float(g(cr.clip, "start_time", 0.0) or 0.0)
            ln = float(g(cr.clip, "length", 0.0) or 0.0)
            tempo = float(g(self.song, "tempo", 120.0) or 120.0)
            ek = {"w": cr.kind, "tr": tr, "i": cr.index, "t0": t0, "t1": t0 + ln, "secs": ln * 60.0 / max(tempo, 1.0)}
            return ek if cr.kind in ("slot", "arr") and tr else None
        except Exception:
            return None

    def clip_to_arr(self, c, a):
        cr = self._clip(a)
        t = float(_n(need(a, "time"), "time", 0.0))
        _call(cr.track, "duplicate_clip_to_arrangement", cr.clip, t)

    def arr_create_midi(self, c, a):
        t = resolve_track(self.song, need(a, "tr", str))
        if not g(t, "has_midi_input", False):
            raise BadArgs("not a MIDI track")
        _call(t, "create_midi_clip", float(_n(need(a, "start"), "start", 0.0)),
              float(_n(need(a, "len"), "len", 0.0001)))

    def arr_move(self, c, a):
        cr = self._clip(a)
        if cr.kind == "slot":
            raise BadArgs("not an arrangement clip")
        start = float(_n(need(a, "start"), "start", 0.0))
        if not hasattr(cr.track, "duplicate_clip_to_arrangement") or not hasattr(cr.track, "delete_clip"):
            raise Unsupported("moving arrangement clips")
        old = num(g(cr.clip, "start_time", 0.0))
        if abs(old - start) < 1e-6:
            return {"i": cr.index}
        new = cr.track.duplicate_clip_to_arrangement(cr.clip, start)
        try:
            cr.track.delete_clip(cr.clip)
        except Exception:
            pass      # the copy may have overwritten the (stale) original when the move overlaps it
        clips = list(seq(g(cr.track, "arrangement_clips", ())))
        for k, x in enumerate(clips):
            if x is new:
                return {"i": k}
        return {"i": None}

    def arr_trim(self, c, a):
        """Trim an arrangement clip's edges to absolute beats. Best effort via the clip markers; verified by
        reading start_time/end_time back, reverted + error if this Live build did not honour it."""
        cr = self._clip(a)
        if cr.kind == "slot":
            raise BadArgs("not an arrangement clip")
        if "start" not in a and "end" not in a:
            raise BadArgs("start or end required")
        clip = cr.clip
        cs, ce = num(g(clip, "start_time", 0.0)), num(g(clip, "end_time", 0.0))
        ns = float(_n(a["start"], "start", 0.0)) if "start" in a else cs
        ne = float(_n(a["end"], "end", 0.0)) if "end" in a else ce
        if ne - ns < MIN_ARR_LEN - 1e-9:
            raise BadArgs("clip would be shorter than 1/16 beat")
        for attr in ("start_marker", "end_marker"):
            if not hasattr(clip, attr):
                raise Unsupported("trimming arrangement clips")
        saved = {k: g(clip, k, None) for k in ("start_marker", "end_marker", "loop_start", "loop_end")}
        err = ""
        try:
            if abs(ne - ce) > 1e-6:
                new_end = saved["end_marker"] + (ne - ce)
                if saved["loop_end"] is not None and g(clip, "looping", False) and new_end > saved["loop_end"]:
                    clip.loop_end = new_end
                clip.end_marker = new_end
            if abs(ns - cs) > 1e-6:
                clip.start_marker = saved["start_marker"] + (ns - cs)
            ok = (abs(num(g(clip, "start_time", 0.0)) - ns) < 1e-3 and abs(num(g(clip, "end_time", 0.0)) - ne) < 1e-3)
        except Exception as e:
            ok = False
            err = repr(e)
        if not ok:
            for k, v in saved.items():
                try:
                    if v is not None:
                        setattr(clip, k, v)
                except Exception:
                    pass
            raise Unsupported("trimming this clip edge (" + (err or "Live ignored the marker change") + ")")

    # ------------------------------------------------------------ notes
    def _note_args(self, n, need_id=False):
        if not isinstance(n, dict):
            raise BadArgs("note must be an object")
        out = {}
        if "p" in n:
            out["pitch"] = _i(n["p"], "p", 0, 127)
        if "t" in n:
            out["start_time"] = float(_n(n["t"], "t", 0.0))
        if "d" in n:
            out["duration"] = float(_n(n["d"], "d", 0.0001))
        if "v" in n:
            out["velocity"] = float(_n(n["v"], "v", 1, 127))
        if "m" in n:
            out["mute"] = _b(n["m"], "m")
        if "prob" in n:
            out["probability"] = float(_n(n["prob"], "prob", 0.0, 1.0))
        if "vd" in n:
            out["velocity_deviation"] = float(_n(n["vd"], "vd", -127, 127))
        if "rv" in n:
            out["release_velocity"] = float(_n(n["rv"], "rv", 0, 127))
        return out

    def _add(self, clip, specs):
        if not specs:
            return
        _call(clip, "add_new_notes", tuple(_note_spec(s) for s in specs))

    def notes_add(self, c, a):
        cr = self._open_clip(c, True)
        lst = need(a, "notes", list)
        if len(lst) > MAX_OP_NOTES:
            raise BadArgs("too many notes in one op")
        specs = []
        for n in lst:
            d = self._note_args(n)
            for k in ("pitch", "start_time", "duration"):
                if k not in d:
                    raise BadArgs("note missing p/t/d")
            d.setdefault("velocity", 100.0)
            d.setdefault("mute", False)
            d.setdefault("probability", 1.0)
            d.setdefault("velocity_deviation", 0.0)
            d.setdefault("release_velocity", 64.0)
            specs.append(d)
        self._add(cr.clip, specs)
        return {"n": len(specs)}

    def _modify(self, clip, changes):
        """changes: {note_id: {attr: value}}"""
        notes = S.get_notes(clip)
        touched = []
        for n in notes:
            nid = intv(g(n, "note_id", -1), -1)
            if nid in changes:
                for k, v in changes[nid].items():
                    setattr(n, k, v)
                touched.append(n)
        if touched:
            _call(clip, "apply_note_modifications", touched)
        return len(touched)

    def notes_modify(self, c, a):
        cr = self._open_clip(c, True)
        lst = need(a, "notes", list)
        if len(lst) > MAX_OP_NOTES:
            raise BadArgs("too many notes in one op")
        changes = {}
        for n in lst:
            if not isinstance(n, dict) or not is_int(n.get("id")):
                raise BadArgs("note needs integer id")
            changes[n["id"]] = self._note_args(n)
        return {"n": self._modify(cr.clip, changes)}

    def _ids(self, a):
        ids = need(a, "ids", list)
        if len(ids) > MAX_OP_NOTES * 2:
            raise BadArgs("too many ids")
        return [_i(x, "id") for x in ids]

    def notes_remove(self, c, a):
        cr = self._open_clip(c, True)
        ids = self._ids(a)
        if ids:
            _call(cr.clip, "remove_notes_by_id", ids)

    def notes_clear(self, c, a):
        cr = self._open_clip(c, True)
        if hasattr(cr.clip, "remove_notes_extended"):
            cr.clip.remove_notes_extended(0, 128, 0.0, S.NOTE_RANGE_END)
            return
        ids = [intv(g(n, "note_id", 0)) for n in S.get_notes(cr.clip)]
        if ids:
            _call(cr.clip, "remove_notes_by_id", ids)

    def notes_transpose(self, c, a):
        cr = self._open_clip(c, True)
        ids = set(self._ids(a))
        semis = _i(need(a, "semis"), "semis", -127, 127)
        changes = {}
        for n in S.get_notes(cr.clip):
            nid = intv(g(n, "note_id", -1), -1)
            if nid in ids:
                changes[nid] = {"pitch": max(0, min(127, intv(g(n, "pitch", 60)) + semis))}
        return {"n": self._modify(cr.clip, changes)}

    def notes_duplicate(self, c, a):
        cr = self._open_clip(c, True)
        ids = set(self._ids(a))
        off = float(_n(need(a, "offset"), "offset"))
        specs = []
        for n in S.get_notes(cr.clip):
            if intv(g(n, "note_id", -1), -1) in ids:
                st = num(g(n, "start_time", 0.0)) + off
                if st < 0:
                    continue
                specs.append({"pitch": intv(g(n, "pitch", 60)), "start_time": st,
                              "duration": num(g(n, "duration", 0.25), 0.25), "velocity": num(g(n, "velocity", 100.0)),
                              "mute": bool(g(n, "mute", False)), "probability": num(g(n, "probability", 1.0), 1.0),
                              "velocity_deviation": num(g(n, "velocity_deviation", 0.0)),
                              "release_velocity": num(g(n, "release_velocity", 64.0), 64.0)})
        self._add(cr.clip, specs)
        return {"n": len(specs)}

    # ------------------------------------------------------------ warp
    def warp_add(self, c, a):
        cr = self._open_clip(c, False)
        beat = float(_n(need(a, "beat"), "beat"))
        st = a.get("sample_time")
        f = getattr(cr.clip, "add_warp_marker", None)
        if f is None:
            raise Unsupported("add_warp_marker")
        if st is None:
            f(beat)
        else:
            f(beat, float(_n(st, "sample_time")))

    def warp_move(self, c, a):
        cr = self._open_clip(c, False)
        beat = float(_n(need(a, "beat"), "beat"))
        to = float(_n(need(a, "to_beat"), "to_beat"))
        _call(cr.clip, "move_warp_marker", beat, to - beat)

    def warp_remove(self, c, a):
        cr = self._open_clip(c, False)
        _call(cr.clip, "remove_warp_marker", float(_n(need(a, "beat"), "beat")))

    # ------------------------------------------------------------ takes
    def takes_create_lane(self, c, a):
        t = resolve_track(self.song, need(a, "tr", str))
        _call(t, "create_take_lane")

    def takes_rename(self, c, a):
        t = resolve_track(self.song, need(a, "tr", str))
        lanes = seq(g(t, "take_lanes", None))
        i = need_int(a, "lane")
        if not 0 <= i < len(lanes):
            raise BadArgs("no such lane")
        _put(lanes[i], "name", _s(need(a, "name"), "name"))

    def takes_promote(self, c, a):
        loc = need(a, "loc", dict)
        if loc.get("w") != "take":
            raise BadArgs("loc must be a take")
        cr = resolve_clip(self.song, loc)
        _call(cr.track, "duplicate_clip_to_arrangement", cr.clip, num(g(cr.clip, "start_time", 0.0)))

    def _take_lane(self, track, a):
        lanes = seq(g(track, "take_lanes", None))
        i = need_int(a, "lane")
        if not 0 <= i < len(lanes):
            raise BadArgs("no such lane")
        return i, lanes[i]

    def _copy_to_lane(self, clip, lane, start):
        """Create a copy of `clip` in take lane `lane` at beat `start` (TakeLane.create_audio_clip /
        create_midi_clip). Returns the new clip. Missing API -> Unsupported (nothing has been changed)."""
        audio = g(clip, "is_audio_clip", False)
        what = "take-lane clip creation (TakeLane.%s missing in this Live)" % ("create_audio_clip" if audio else "create_midi_clip")
        if audio:
            path = g(clip, "file_path", "")
            if not isinstance(path, str) or not path:
                raise OpError("no audio file")
            if not hasattr(lane, "create_audio_clip"):
                raise Unsupported(what)
            new = lane.create_audio_clip(path, float(start))
        else:
            if not hasattr(lane, "create_midi_clip"):
                raise Unsupported(what)
            length = num(g(clip, "end_time", 0.0)) - num(g(clip, "start_time", 0.0))
            new = lane.create_midi_clip(float(start), float(max(length, MIN_ARR_LEN)))
            specs = []
            for n in S.get_notes(clip):
                specs.append({"pitch": intv(g(n, "pitch", 60)), "start_time": num(g(n, "start_time", 0.0)),
                              "duration": max(num(g(n, "duration", 0.25)), 0.0001),
                              "velocity": num(g(n, "velocity", 100.0)), "mute": bool(g(n, "mute", False)),
                              "probability": num(g(n, "probability", 1.0), 1.0),
                              "velocity_deviation": num(g(n, "velocity_deviation", 0.0)),
                              "release_velocity": num(g(n, "release_velocity", 64.0), 64.0)})
            if specs and new is not None:
                self._add(new, specs)
        if new is None:
            raise OpError("Live did not return the new take clip")
        for k in ("name", "color", "muted", "gain", "pitch_coarse", "pitch_fine", "warping"):     # best effort
            try:
                if hasattr(clip, k) and hasattr(new, k):
                    setattr(new, k, getattr(clip, k))
            except Exception:
                pass
        return new

    def _after_copy(self, track, clip, lane, new, lane_i):
        """Delete the original once its copy exists; returns where the copy is."""
        try:
            _call(track, "delete_clip", clip)
        except OpError:
            raise
        except Exception as e:
            raise OpError("copied, but could not remove the original: %r" % (e,))
        idx = None
        for k, x in enumerate(lane_clips(lane)):
            if x is new:
                idx = k
        return {"lane": lane_i, "i": idx}

    def takes_demote(self, c, a):
        """Main-lane (arrangement) clip -> take lane `lane`, same time: copy into the lane, then delete the original."""
        cr = self._clip(a)
        if cr.kind != "arr":
            raise BadArgs("loc must be an arrangement clip")
        lane_i, lane = self._take_lane(cr.track, a)
        new = self._copy_to_lane(cr.clip, lane, num(g(cr.clip, "start_time", 0.0)))
        return self._after_copy(cr.track, cr.clip, lane, new, lane_i)

    def takes_move(self, c, a):
        """Take clip -> another take lane, same time (copy + delete)."""
        cr = self._clip(a)
        if cr.kind != "take":
            raise BadArgs("loc must be a take")
        lane_i, lane = self._take_lane(cr.track, a)
        if lane is cr.lane:
            return {"lane": lane_i, "i": cr.index}
        new = self._copy_to_lane(cr.clip, lane, num(g(cr.clip, "start_time", 0.0)))
        return self._after_copy(cr.track, cr.clip, lane, new, lane_i)

    # ------------------------------------------------------------ devices
    def _dev(self, a):
        tr, path = need(a, "tr", str), need(a, "path", str)
        d, owner, idx = resolve_device(self.song, tr, path)
        return tr, path, d, owner, idx

    def device_open(self, c, a):
        tr, path, d, _, _ = self._dev(a)
        c.device = (tr, path)
        c.pending.add("params")
        self.core.listeners_dirty = True

    def device_close(self, c, a):
        c.device = None
        c.pending.add("params")
        self.core.listeners_dirty = True

    def device_toggle(self, c, a):
        _, _, d, _, _ = self._dev(a)
        ps = seq(g(d, "parameters", ()))
        if not ps:
            raise Unsupported("device on/off")
        ps[0].value = 0.0 if S.device_on(d) else 1.0

    def device_param(self, c, a):
        _, _, d, _, _ = self._dev(a)
        ps = seq(g(d, "parameters", ()))
        i = need_int(a, "i")
        if not 0 <= i < len(ps):
            raise BadArgs("no such parameter")
        v = float(_n(need(a, "v"), "v"))
        p = ps[i]
        p.value = max(num(g(p, "min", 0.0)), min(num(g(p, "max", 1.0), 1.0), v))

    def _container(self, tr, into):
        """-> container object for device lists: the track (into empty) or a rack chain ("2.c1")."""
        track = resolve_track(self.song, tr)
        if not into:
            return track
        head, _, last = into.rpartition(".")
        if not (last[:1] == "c" and last[1:].isdigit()) or not head:
            raise BadArgs("bad container path")
        d, _, _ = resolve_device(self.song, tr, head)
        chains = seq(g(d, "chains", ()))
        ci = int(last[1:])
        if ci >= len(chains):
            raise BadArgs("no such chain")
        return chains[ci]

    def device_move(self, c, a):
        """device.move {tr,path,to,into?}: `to` = final index in the destination (track, or rack chain
        `into` like "2.c1"). Live's move_device takes an insertion index measured before removal."""
        tr, path, d, owner, idx = self._dev(a)
        to = _i(need(a, "to"), "to", 0)
        into = a.get("into")
        dest = self._container(tr, into) if into else owner
        n = len(seq(g(dest, "devices", ())))
        if dest is owner:
            if to >= n:
                raise BadArgs("index out of range")
            if to == idx:
                return
            ins = to + 1 if to > idx else to
        else:
            ins = min(to, n)
        _call(self.song, "move_device", d, dest, ins)
        self.core.mark_devices(tr)

    def device_insert(self, c, a):
        tr = need(a, "tr", str)
        name = need(a, "name", str)
        into = a.get("into")
        dest = self._container(tr, into)
        n = len(seq(g(dest, "devices", ())))
        i = a.get("i")
        i = n if i is None else min(_i(i, "i", 0), n)
        try:
            _call(dest, "insert_device", name, i)
        except ValueError as e:
            if "not available" not in str(e).lower():
                raise
            self.core.log("device.insert: %s not available in this Live edition" % name)
            raise OpError("%s isn't available in your Live edition" % name)
        self.core.mark_devices(tr)

    def device_available(self, c, a):
        """Native devices installed in this Live edition: top-level device items of the three device roots (cached)."""
        if getattr(self, "_avail", None) is None:
            b = self._browser()
            out = {}
            for key in ("instruments", "audio_effects", "midi_effects"):
                names = []
                for ch in seq(g(g(b, key, None), "children", ())):
                    nm = _s(g(ch, "name", ""))
                    if nm and nm not in names and (g(ch, "is_device", False) or
                                                   (g(ch, "is_loadable", False) and not g(ch, "is_folder", False))):
                        names.append(nm)
                out[key] = names
            self._avail = out
        return {k: list(v) for k, v in self._avail.items()}

    def device_duplicate(self, c, a):
        tr, path, d, owner, idx = self._dev(a)
        _call(owner, "duplicate_device", idx)
        self.core.mark_devices(tr)

    def device_delete(self, c, a):
        tr, _, d, owner, idx = self._dev(a)
        _call(owner, "delete_device", idx)
        self.core.mark_devices(tr)

    def device_select(self, c, a):
        _, _, d, _, _ = self._dev(a)
        _call(g(self.song, "view", None), "select_device", d)

    def device_var(self, c, a):
        _, _, d, _, _ = self._dev(a)
        if g(d, "variation_count", None) is None:
            raise Unsupported("variations on this device")
        act = need(a, "act", str)
        if act == "store":
            _call(d, "store_variation")
            return
        i = _i(need(a, "i"), "i", 0)
        if i >= intv(g(d, "variation_count", 0)):
            raise BadArgs("no such variation")
        _put(d, "selected_variation_index", i)
        if act == "recall":
            _call(d, "recall_selected_variation")
        elif act == "delete":
            _call(d, "delete_selected_variation")
        elif act != "select":
            raise BadArgs("act must be store|recall|select|delete")

    def chain_select(self, c, a):
        _, _, d, _, _ = self._dev(a)
        chains = seq(g(d, "chains", ()))
        i = need_int(a, "i")
        if not 0 <= i < len(chains):
            raise BadArgs("no such chain")
        view = g(d, "view", None)
        if view is None or not hasattr(view, "selected_chain"):
            raise Unsupported("selected_chain")
        view.selected_chain = chains[i]

    # ------------------------------------------------------------ browser
    def _roots(self):
        b = self._browser()
        out = []
        for k in BROWSER_ROOTS:
            it = g(b, k, None)
            if it is not None:
                out.append((k, it))
        return out

    def _bitem(self, path):
        if not isinstance(path, list) or not all(isinstance(x, str) for x in path):
            raise BadArgs("path must be a list of strings")
        if not path:
            return None
        first = path[0].lower().replace(" ", "_")
        cur = None
        for k, it in self._roots():
            if k == first or _s(g(it, "name", "")).lower().replace(" ", "_") == first:
                cur = it
                break
        if cur is None:
            raise BadArgs("no such browser root")
        for name in path[1:]:
            kids = seq(g(cur, "children", ()))
            nxt = None
            for ch in kids:
                if g(ch, "name", None) == name:
                    nxt = ch
                    break
            if nxt is None:
                for ch in kids:
                    if _s(g(ch, "name", "")).lower() == name.lower():
                        nxt = ch
                        break
            if nxt is None:
                raise BadArgs("no such browser item: " + name)
            cur = nxt
        return cur

    def browser_list(self, c, a):
        path = need(a, "path", list)
        item = self._bitem(path)
        if item is None:
            return [{"name": _s(g(it, "name", k)), "folder": True, "loadable": False, "key": k}
                    for k, it in self._roots()]
        out = []
        for ch in seq(g(item, "children", ()))[:MAX_BROWSER_ITEMS]:
            e = {"name": _s(g(ch, "name", "")), "folder": bool(g(ch, "is_folder", False)),
                 "loadable": bool(g(ch, "is_loadable", False))}
            uri = g(ch, "uri", None)
            if uri:
                e["uri"] = str(uri)
            out.append(e)
        return out

    def browser_load(self, c, a):
        t = resolve_track(self.song, need(a, "tr", str))
        item = self._bitem(need(a, "path", list))
        if item is None or not g(item, "is_loadable", False):
            raise BadArgs("item is not loadable")
        view = g(self.song, "view", None)
        if g(view, "selected_track", None) != t:
            _put(view, "selected_track", t)
        _call(self._browser(), "load_item", item)
        self.core.mark_devices(need(a, "tr", str))

    def browser_preview(self, c, a):
        item = self._bitem(need(a, "path", list))
        if item is None:
            raise BadArgs("nothing to preview")
        _call(self._browser(), "preview_item", item)

    # ------------------------------------------------------------ meta
    def sys_info(self, c, a):
        from . import VERSION
        return {"live": self.core.live_version, "proto": 1, "script_version": VERSION, "host": self.core.host, "ops": sorted(self.table)}

    def sys_probe(self, c, a):
        return self.core.run_probe()
