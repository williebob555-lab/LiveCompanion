"""LOM probe: dumps dir() + sample values of key Live objects to ~/LiveCompanion_probe.json.

Runs once on script load (and on demand via op `sys.probe`). Output is used to verify LOM_NOTES.md.
Pure Python, no Live imports. Only reads properties; never calls methods."""
import json
import os
import time

MAX_STR = 120


def _val(v):
    if v is None or isinstance(v, (bool, int, float)):
        return v
    if isinstance(v, str):
        return v[:MAX_STR]
    if callable(v):
        return {"callable": True}
    if isinstance(v, (list, tuple)):
        return {"seq": type(v).__name__, "len": len(v)}
    try:
        r = repr(v)
    except Exception:
        r = "<unrepr>"
    return {"type": type(v).__name__, "repr": r[:MAX_STR]}


def describe(obj):
    out = {"class": type(obj).__name__, "module": getattr(type(obj), "__module__", ""), "attrs": {}}
    try:
        names = sorted(n for n in dir(obj) if not n.startswith("_"))
    except Exception as e:
        out["error"] = repr(e)
        return out
    for n in names:
        try:
            v = getattr(obj, n)
        except Exception as e:
            out["attrs"][n] = {"error": repr(e)[:MAX_STR]}
            continue
        out["attrs"][n] = _val(v)
    return out


def _first(seq_, pred=None):
    try:
        for x in seq_:
            try:
                if pred is None or pred(x):
                    return x
            except Exception:
                pass
    except Exception:
        pass
    return None


def _safe(f, default=None):
    try:
        return f()
    except Exception:
        return default


def collect_targets(song, app):
    T = {}

    def add(label, obj):
        if obj is not None and label not in T:
            T[label] = obj

    add("song", song)
    add("song.view", _safe(lambda: song.view))
    add("application", app)
    add("application.view", _safe(lambda: app.view))
    browser = _safe(lambda: app.browser)
    add("browser", browser)
    root = None
    for k in ("instruments", "audio_effects", "samples", "user_library"):
        root = _safe(lambda: getattr(browser, k))
        if root is not None:
            add("browser.root." + k, root)
            break
    if root is not None:
        add("browser.item", _first(_safe(lambda: root.children, ())))
    tracks = list(_safe(lambda: song.tracks, ()))
    midi = _first(tracks, lambda t: t.has_midi_input and not t.is_foldable)
    audio = _first(tracks, lambda t: t.has_audio_input and not t.has_midi_input)
    group = _first(tracks, lambda t: t.is_foldable)
    add("track.midi", midi)
    add("track.audio", audio)
    add("track.group", group)
    add("track.return", _first(_safe(lambda: song.return_tracks, ())))
    add("track.master", _safe(lambda: song.master_track))
    add("scene", _first(_safe(lambda: song.scenes, ())))
    t0 = midi or audio or _first(tracks)
    if t0 is not None:
        mixer = _safe(lambda: t0.mixer_device)
        add("mixer_device", mixer)
        add("mixer.volume", _safe(lambda: mixer.volume))
        add("mixer.send", _first(_safe(lambda: mixer.sends, ())))
        add("routing.input_type", _safe(lambda: t0.input_routing_type))
        add("routing.available_input_type", _first(_safe(lambda: t0.available_input_routing_types, ())))
    # clips
    audio_clip = midi_clip = arr_clip = None
    slot_any = None
    for t in tracks:
        for s in _safe(lambda: t.clip_slots, ()):
            if slot_any is None:
                slot_any = s
            if _safe(lambda: s.has_clip):
                c = s.clip
                if audio_clip is None and _safe(lambda: c.is_audio_clip):
                    audio_clip = c
                if midi_clip is None and _safe(lambda: c.is_midi_clip):
                    midi_clip = c
        if arr_clip is None:
            arr_clip = _first(_safe(lambda: t.arrangement_clips, ()))
    add("clip_slot", slot_any)
    add("clip.audio", audio_clip)
    add("clip.midi", midi_clip)
    add("clip.arrangement", arr_clip)
    if audio_clip is not None:
        add("warp_marker", _first(_safe(lambda: audio_clip.warp_markers, ())))
    if midi_clip is not None:
        add("note", _first(_safe(lambda: midi_clip.get_notes_extended(0, 128, 0.0, 99999.0), ())))
    # take lanes
    for t in tracks:
        lanes = _safe(lambda: t.take_lanes)
        if lanes is not None:
            lane = _first(lanes)
            if lane is not None:
                add("take_lane", lane)
                add("take_lane.clip", _first(_safe(lambda: lane.arrangement_clips, ())
                                             or _safe(lambda: lane.clips, ())))
                break
    # devices
    dev = rack = chain = None
    for t in tracks + list(_safe(lambda: song.return_tracks, ())):
        for d in _safe(lambda: t.devices, ()):
            if dev is None:
                dev = d
            if rack is None and _safe(lambda: d.can_have_chains):
                rack = d
        if dev is not None and rack is not None:
            break
    add("device", dev)
    if dev is not None:
        add("device.parameter", _first(_safe(lambda: dev.parameters, ())))
        add("device.view", _safe(lambda: dev.view))
    add("device.rack", rack)
    if rack is not None:
        chain = _first(_safe(lambda: rack.chains, ()))
        add("rack.chain", chain)
        add("rack.view", _safe(lambda: rack.view))
        if chain is not None:
            add("chain.device", _first(_safe(lambda: chain.devices, ())))
    return T


def run_probe(song, app, path=None, live_version=""):
    path = path or os.path.join(os.path.expanduser("~"), "LiveCompanion_probe.json")
    result = {"live": live_version, "time": time.strftime("%Y-%m-%d %H:%M:%S"), "objects": {}}
    try:
        targets = collect_targets(song, app)
    except Exception as e:
        targets = {}
        result["error"] = repr(e)
    for label, obj in targets.items():
        try:
            result["objects"][label] = describe(obj)
        except Exception as e:
            result["objects"][label] = {"error": repr(e)}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=1, default=str, sort_keys=True)
    return path
