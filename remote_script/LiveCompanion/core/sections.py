"""Section serializers (PROTOCOL.md). Pure functions of (song, application, ...) -> JSON-able data.

Defensive: every LOM attribute is read through g()/try so a missing API degrades instead of raising."""
from .util import (g, num, intv, seq, resolve_track, resolve_clip, resolve_device, slots_of, lane_clips,
                   track_ref)

MAX_NOTES = 5000
MAX_DEPTH = 6
WARP_MODE_NAMES = {0: "Beats", 1: "Tones", 2: "Texture", 3: "Re-Pitch", 4: "Complex", 6: "Complex Pro"}
WARP_MODE_IDS = dict((v, k) for k, v in WARP_MODE_NAMES.items())
NOTE_RANGE_END = 99999.0


def _bool(x):
    return bool(x)


def _s(x):
    return "" if x is None else str(x)


def param_str(p):
    try:
        return str(p.str_for_value(p.value))
    except Exception:
        try:
            return str(p)
        except Exception:
            return ""


def P(p):
    """Mixer-style parameter: {v,min,max,str}."""
    if p is None:
        return {"v": 0.0, "min": 0.0, "max": 1.0, "str": ""}
    return {"v": num(g(p, "value", 0.0)), "min": num(g(p, "min", 0.0)), "max": num(g(p, "max", 1.0)),
            "str": param_str(p)}


def display_name(x):
    n = g(x, "display_name", None)
    return _s(n if n is not None else x)


# ------------------------------------------------------------------ session

def view_name(app):
    try:
        if app.view.is_view_visible("Arranger"):
            return "arranger"
    except Exception:
        pass
    return "session"


def scale_info(song):
    """Song scale {root:0-11, name, intervals:[0-11...]} or None when Live does not expose it."""
    try:
        ivs = g(song, "scale_intervals", None)
        if ivs is None:
            return None
        return {"root": intv(g(song, "root_note", 0)) % 12, "name": _s(g(song, "scale_name", "")),
                "intervals": [intv(i) % 12 for i in seq(ivs)]}
    except Exception:
        return None


def cpu_load(app):
    """Live's CPU load 0..1 (application.average_process_usage); 0 when unavailable."""
    v = num(g(app, "average_process_usage", 0.0))
    return round(min(max(v, 0.0), 1.0), 3)


def _cues(song):
    out = []
    for c in seq(g(song, "cue_points", ()))[:128]:
        out.append({"t": num(g(c, "time", 0.0)), "n": _s(g(c, "name", ""))})
    return out


def session(song, app, follow=False):
    view = g(song, "view", None)
    sel_t = g(view, "selected_track", None) if view is not None else None
    sel_ref = track_ref(song, sel_t) if sel_t is not None else None
    sel_scene = 0
    ss = g(view, "selected_scene", None) if view is not None else None
    if ss is not None:
        for i, sc in enumerate(seq(g(song, "scenes", ()))):
            if sc is ss or sc == ss:
                sel_scene = i
                break
    return {
        "tempo": num(g(song, "tempo", 120.0), 120.0),
        "sig": [intv(g(song, "signature_numerator", 4), 4), intv(g(song, "signature_denominator", 4), 4)],
        "playing": _bool(g(song, "is_playing", False)),
        "pos": num(g(song, "current_song_time", 0.0)),
        "rec": _bool(g(song, "record_mode", False)),
        "session_rec": _bool(g(song, "session_record", False)),
        "overdub": _bool(g(song, "arrangement_overdub", False)),
        "punch_in": _bool(g(song, "punch_in", False)),
        "punch_out": _bool(g(song, "punch_out", False)),
        "loop": _bool(g(song, "loop", False)),
        "loop_start": num(g(song, "loop_start", 0.0)),
        "loop_len": num(g(song, "loop_length", 4.0)),
        "metronome": _bool(g(song, "metronome", False)),
        "count_in": intv(g(song, "count_in_duration", 0)),
        "quant": intv(g(song, "clip_trigger_quantization", 0)),
        "rec_quant": intv(g(song, "midi_recording_quantization", 0)),
        "can_undo": _bool(g(song, "can_undo", False)),
        "can_redo": _bool(g(song, "can_redo", False)),
        "back_to_arr": _bool(g(song, "back_to_arranger", False)),
        "auto_arm": _bool(g(song, "exclusive_arm", False)),
        "view": view_name(app),
        "sel_track": sel_ref,
        "sel_scene": sel_scene,
        "song_len": num(g(song, "song_length", 0.0)),
        "follow": bool(follow),
        "cues": _cues(song),
        "scale": scale_info(song),
    }


# ------------------------------------------------------------------ tracks

def track_kind(t, ref):
    if ref == "m":
        return "master"
    if ref.startswith("r"):
        return "return"
    if g(t, "is_foldable", False):
        return "group"
    if g(t, "has_midi_input", False):
        return "midi"
    return "audio"


def _armable(t):
    return _bool(g(t, "can_be_armed", False))


def ser_track(t, ref, group_ref=None):
    armable = _armable(t)
    return {
        "ref": ref,
        "name": _s(g(t, "name", "")),
        "color": intv(g(t, "color", 0)),
        "kind": track_kind(t, ref),
        "armable": armable,
        "arm": _bool(g(t, "arm", False)) if armable else False,
        "mute": _bool(g(t, "mute", False)),
        "solo": _bool(g(t, "solo", False)),
        "monitor": intv(g(t, "current_monitoring_state", 1), 1),
        "group": group_ref,
        "folded": _bool(g(t, "fold_state", False)),
        "foldable": _bool(g(t, "is_foldable", False)),
        "playing_slot": intv(g(t, "playing_slot_index", -1), -1),
        "fired_slot": intv(g(t, "fired_slot_index", -1), -1),
    }


def _with_vol(d, t):
    d["vol"] = P(g(g(t, "mixer_device", None), "volume", None))
    return d


def tracks(song, app=None):
    tl = seq(g(song, "tracks", ()))
    out = []
    for i, t in enumerate(tl):
        gref = None
        gt = g(t, "group_track", None)
        if gt is not None:
            for j, o in enumerate(tl):
                if o is gt or o == gt:
                    gref = "t%d" % j
                    break
        out.append(ser_track(t, "t%d" % i, gref))
    rets = [_with_vol(ser_track(t, "r%d" % i), t) for i, t in enumerate(seq(g(song, "return_tracks", ())))]
    master = g(song, "master_track", None)
    scenes = []
    for i, s in enumerate(seq(g(song, "scenes", ()))):
        scenes.append({"i": i, "name": _s(g(s, "name", "")), "color": intv(g(s, "color", 0))})
    return {"tracks": out, "returns": rets, "master": _with_vol(ser_track(master, "m"), master) if master is not None else None,
            "scenes": scenes}


# ------------------------------------------------------------------ focus

def _routing(t, cur, avail):
    c = g(t, cur, None)
    lst = g(t, avail, None)
    return (display_name(c) if c is not None else ""), [display_name(x) for x in seq(lst)] if lst is not None else []


def focus(song, app, ref):
    t = resolve_track(song, ref)
    d = ser_track(t, ref)
    mixer = g(t, "mixer_device", None)
    returns = seq(g(song, "return_tracks", ()))
    sends = []
    for i, p in enumerate(seq(g(mixer, "sends", ()))):
        e = P(p)
        e["name"] = _s(g(returns[i], "name", "")) if i < len(returns) else ""
        sends.append(e)
    it, its = _routing(t, "input_routing_type", "available_input_routing_types")
    ic, ics = _routing(t, "input_routing_channel", "available_input_routing_channels")
    ot, ots = _routing(t, "output_routing_type", "available_output_routing_types")
    return {
        "ref": ref, "name": d["name"], "color": d["color"], "kind": d["kind"], "armable": d["armable"],
        "arm": d["arm"], "mute": d["mute"], "solo": d["solo"], "monitor": d["monitor"],
        "vol": P(g(mixer, "volume", None)), "pan": P(g(mixer, "panning", None)), "sends": sends,
        "in_type": it, "in_types": its, "in_ch": ic, "in_chs": ics, "out_type": ot, "out_types": ots,
        "playing_slot": d["playing_slot"], "fired_slot": d["fired_slot"],
    }


# ------------------------------------------------------------------ slots / arr / takes

def slot_status(slot):
    clip = g(slot, "clip", None) if g(slot, "has_clip", False) else None
    if clip is not None:
        if g(clip, "is_recording", False):
            return "recording"
        if g(clip, "is_playing", False):
            return "playing"
        if g(slot, "is_triggered", False) or g(clip, "is_triggered", False):
            return "triggered"
        return "stopped"
    if g(slot, "is_recording", False):
        return "recording"
    if g(slot, "is_triggered", False):
        return "triggered"
    return "empty"


def slots(song, app, ref):
    t = resolve_track(song, ref)
    out = []
    for i, s in enumerate(slots_of(t)):
        has = _bool(g(s, "has_clip", False))
        clip = None
        if has:
            c = g(s, "clip", None)
            if c is not None:
                clip = {"name": _s(g(c, "name", "")), "color": intv(g(c, "color", 0)),
                        "len": num(g(c, "length", 0.0)), "looping": _bool(g(c, "looping", False)),
                        "audio": _bool(g(c, "is_audio_clip", False)), "muted": _bool(g(c, "muted", False))}
        out.append({"i": i, "has_clip": has, "has_stop": _bool(g(s, "has_stop_button", True)),
                    "status": slot_status(s), "clip": clip})
    return {"tr": ref, "slots": out}


MAX_PREVIEW_NOTES = 160      # per clip, arr section mini note bars
MAX_PREVIEW_TOTAL = 900


def _preview_notes(c, budget):
    """Compact [t, d, p] triples (rounded) for the track-view MIDI preview; thinned evenly when over budget."""
    try:
        notes = get_notes(c)
    except Exception:
        return []
    n = len(notes)
    if n == 0 or budget <= 0:
        return []
    cap = min(MAX_PREVIEW_NOTES, budget)
    step = max(1, -(-n // cap))
    out = []
    for k in range(0, n, step):
        x = notes[k]
        out.append([round(num(g(x, "start_time", 0.0)), 3), round(num(g(x, "duration", 0.0)), 3),
                    intv(g(x, "pitch", 60))])
    return out


def arr(song, app, ref):
    t = resolve_track(song, ref)
    out = []
    budget = MAX_PREVIEW_TOTAL
    for i, c in enumerate(seq(g(t, "arrangement_clips", ()))):
        audio = _bool(g(c, "is_audio_clip", False))
        d = {"i": i, "name": _s(g(c, "name", "")), "color": intv(g(c, "color", 0)),
             "start": num(g(c, "start_time", 0.0)), "end": num(g(c, "end_time", 0.0)),
             "audio": audio, "muted": _bool(g(c, "muted", False)),
             "looping": _bool(g(c, "looping", False)),
             "start_marker": num(g(c, "start_marker", 0.0)), "end_marker": num(g(c, "end_marker", 0.0)),
             "loop_start": num(g(c, "loop_start", 0.0)), "loop_end": num(g(c, "loop_end", 0.0))}
        if audio:
            d["file_path"] = _s(g(c, "file_path", ""))
            d["warping"] = _bool(g(c, "warping", False))
            wms = seq(g(c, "warp_markers", ()))
            if wms:
                d["warp_first"] = num(g(wms[0], "beat_time", 0.0))
                d["warp_last"] = num(g(wms[-1], "beat_time", 0.0))
        else:
            pn = _preview_notes(c, budget)
            budget -= len(pn)
            d["pn"] = pn
        out.append(d)
    return {"tr": ref, "clips": out}


def takes(song, app, ref):
    t = resolve_track(song, ref)
    lanes_attr = g(t, "take_lanes", None)
    if lanes_attr is None:
        return {"tr": ref, "supported": False, "lanes": []}
    lanes = []
    for li, lane in enumerate(seq(lanes_attr)):
        clips = []
        for ci, c in enumerate(lane_clips(lane)):
            clips.append({"i": ci, "name": _s(g(c, "name", "")), "color": intv(g(c, "color", 0)),
                          "start": num(g(c, "start_time", 0.0)), "end": num(g(c, "end_time", 0.0))})
        lanes.append({"i": li, "name": _s(g(lane, "name", "")), "clips": clips})
    return {"tr": ref, "supported": True, "lanes": lanes}


# ------------------------------------------------------------------ devices

def device_type(d):
    if g(d, "can_have_chains", False):
        return "rack"
    t = g(d, "type", None)
    if t is None:
        return "other"
    s = str(t).lower()
    if "instrument" in s:
        return "instrument"
    if "audio" in s:
        return "audio_fx"
    if "midi" in s:
        return "midi_fx"
    try:
        return {1: "instrument", 2: "audio_fx", 3: "midi_fx"}.get(int(t), "other")
    except Exception:
        return "other"


def device_on(d):
    ps = seq(g(d, "parameters", ()))
    if ps and _s(g(ps[0], "name", "")) == "Device On":
        return num(g(ps[0], "value", 1.0)) >= 0.5
    return _bool(g(d, "is_active", True))


def macros(d):
    if g(d, "variation_count", None) is None:
        return None
    ps = seq(g(d, "parameters", ()))
    n = intv(g(d, "visible_macro_count", 8), 8)
    out = []
    for i in range(n):
        if i + 1 >= len(ps):
            break
        p = ps[i + 1]
        e = P(p)
        e["i"] = i
        e["name"] = _s(g(p, "name", ""))
        out.append(e)
    return out


PREVIEW_PARAMS = 16


def ser_param(i, p):
    quant = _bool(g(p, "is_quantized", False))
    items = None
    if quant:
        vi = g(p, "value_items", None)
        items = [_s(x) for x in seq(vi)] if vi is not None else None
    e = P(p)
    dv = g(p, "default_value", None)
    e.update({"i": i, "name": _s(g(p, "name", "")), "quant": quant, "items": items,
              "def": num(dv) if dv is not None else None})
    return e


def preview_params(d):
    """First few non-macro parameters (after Device On), shown inline in the signal chain."""
    if g(d, "can_have_chains", False):
        return []
    ps = seq(g(d, "parameters", ()))
    return [ser_param(i, p) for i, p in enumerate(ps[:PREVIEW_PARAMS + 1]) if i > 0]


def ser_device(d, path, depth=0):
    typ = device_type(d)
    chains = []
    if g(d, "can_have_chains", False) and depth < MAX_DEPTH:
        for ci, ch in enumerate(seq(g(d, "chains", ()))):
            chains.append({"i": ci, "name": _s(g(ch, "name", "")),
                           "devices": [ser_device(x, "%s.c%d.%d" % (path, ci, xi), depth + 1)
                                       for xi, x in enumerate(seq(g(ch, "devices", ())))]})
    vc = g(d, "variation_count", None)
    return {"path": path, "name": _s(g(d, "name", "")), "cls": _s(g(d, "class_name", "")),
            "on": device_on(d), "type": typ, "chains": chains,
            "macros": macros(d), "pp": preview_params(d),
            "var": ({"count": intv(vc), "sel": intv(g(d, "selected_variation_index", -1), -1)}
                    if vc is not None else None)}


def devices(song, app, ref):
    t = resolve_track(song, ref)
    return {"tr": ref, "devices": [ser_device(d, str(i)) for i, d in enumerate(seq(g(t, "devices", ())))]}


def params(song, app, ref, path):
    dev, _, _ = resolve_device(song, ref, path)
    out = []
    for i, p in enumerate(seq(g(dev, "parameters", ()))):
        out.append(ser_param(i, p))
    return {"tr": ref, "path": path, "name": _s(g(dev, "name", "")), "params": out}


# ------------------------------------------------------------------ clip

def get_notes(clip):
    f = g(clip, "get_notes_extended", None)
    if f is None:
        return []
    try:
        return seq(f(0, 128, 0.0, NOTE_RANGE_END))
    except Exception:
        try:
            return seq(f(from_pitch=0, pitch_span=128, from_time=0.0, time_span=NOTE_RANGE_END))
        except Exception:
            return []


def ser_note(n):
    return {"id": intv(g(n, "note_id", 0)), "p": intv(g(n, "pitch", 60)), "t": num(g(n, "start_time", 0.0)),
            "d": num(g(n, "duration", 0.25)), "v": num(g(n, "velocity", 100.0)), "m": _bool(g(n, "mute", False)),
            "prob": num(g(n, "probability", 1.0), 1.0), "vd": num(g(n, "velocity_deviation", 0.0)),
            "rv": num(g(n, "release_velocity", 64.0), 64.0)}


def clip(song, app, loc):
    cr = resolve_clip(song, loc)
    c = cr.clip
    audio = _bool(g(c, "is_audio_clip", False))
    d = {
        "loc": loc, "name": _s(g(c, "name", "")), "color": intv(g(c, "color", 0)), "audio": audio,
        "len": num(g(c, "length", 0.0)), "looping": _bool(g(c, "looping", False)),
        "loop_start": num(g(c, "loop_start", 0.0)), "loop_end": num(g(c, "loop_end", 0.0)),
        "start_marker": num(g(c, "start_marker", 0.0)), "end_marker": num(g(c, "end_marker", 0.0)),
        "start": num(g(c, "start_time", 0.0)),
        "signature": [intv(g(c, "signature_numerator", 4), 4), intv(g(c, "signature_denominator", 4), 4)],
        "launch_mode": intv(g(c, "launch_mode", 0)), "launch_quant": intv(g(c, "launch_quantization", 0)),
        "legato": _bool(g(c, "legato", False)), "muted": _bool(g(c, "muted", False)),
        "rec": _bool(g(c, "is_recording", False)),
    }
    if cr.kind != "slot":
        d["end"] = num(g(c, "end_time", 0.0))
    if audio:
        modes = [WARP_MODE_NAMES.get(intv(m, -1), str(m)) for m in seq(g(c, "available_warp_modes", ()))]
        if not modes:
            modes = [WARP_MODE_NAMES[k] for k in sorted(WARP_MODE_NAMES)]
        wm = g(c, "warp_mode", None)
        d.update({
            "gain": num(g(c, "gain", 0.0)), "gain_str": _s(g(c, "gain_display_string", "")),
            "pitch_coarse": intv(g(c, "pitch_coarse", 0)), "pitch_fine": num(g(c, "pitch_fine", 0.0)),
            "warping": _bool(g(c, "warping", False)),
            "warp_mode": WARP_MODE_NAMES.get(intv(wm, -1), _s(wm)) if wm is not None else "",
            "warp_modes": modes, "file_path": _s(g(c, "file_path", "")),
            "sample_rate": num(g(c, "sample_rate", 0.0)), "sample_len": num(g(c, "sample_length", 0.0)),
            "warp_markers": [{"beat": num(g(m, "beat_time", 0.0)), "sample_time": num(g(m, "sample_time", 0.0))}
                             for m in seq(g(c, "warp_markers", ()))],
        })
    else:
        notes = get_notes(c)
        total = len(notes)
        if total > MAX_NOTES:
            notes = notes[:MAX_NOTES]
        d["notes"] = [ser_note(n) for n in notes]
        d["notes_total"] = total
    return d


# ------------------------------------------------------------------ realtime

def rt(song, app, focus_ref, clip_loc, beat_in_bar_origin=0.0):
    pos = num(g(song, "current_song_time", 0.0))
    num_ = intv(g(song, "signature_numerator", 4), 4) or 4
    den = intv(g(song, "signature_denominator", 4), 4) or 4
    bar = num_ * 4.0 / den
    d = {"pos": pos, "playing": _bool(g(song, "is_playing", False)), "beat_in_bar": pos % bar if bar else 0.0,
         "meters": {"focus": [0.0, 0.0], "master": [0.0, 0.0]}, "clip_pos": None, "slot_pos": {},
         "cpu": cpu_load(app)}
    m = g(song, "master_track", None)
    if m is not None:
        d["meters"]["master"] = [num(g(m, "output_meter_left", 0.0)), num(g(m, "output_meter_right", 0.0))]
    if focus_ref:
        try:
            t = resolve_track(song, focus_ref)
            d["meters"]["focus"] = [num(g(t, "output_meter_left", 0.0)), num(g(t, "output_meter_right", 0.0))]
            if focus_ref[0] == "t":
                sp = {}
                for i, s in enumerate(slots_of(t)):
                    if g(s, "has_clip", False):
                        c = g(s, "clip", None)
                        if c is not None and g(c, "is_playing", False):
                            sp[str(i)] = num(g(c, "playing_position", 0.0))
                d["slot_pos"] = sp
        except Exception:
            pass
    if clip_loc:
        try:
            c = resolve_clip(song, clip_loc).clip
            if g(c, "is_playing", False):
                d["clip_pos"] = num(g(c, "playing_position", 0.0))
        except Exception:
            pass
    return d
