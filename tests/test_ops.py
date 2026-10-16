import json
import os
import time

import pytest

from conftest import Harness

SLOT = lambda tr, i: {"w": "slot", "tr": tr, "i": i}
PROTOCOL_OPS = """song.play song.stop song.continue song.tap_tempo song.undo song.redo song.capture_midi
song.stop_all_clips song.re_enable_automation song.jump song.seek song.scrub song.cue song.set scene.fire scene.create
track.create track.delete track.duplicate focus.set focus.follow view.select track.set track.stop slot.fire
slot.create slot.delete slot.duplicate slot.copy slot.move take.next clip.fire clip.stop clip.open clip.close clip.set
clip.delete clip.duplicate_loop clip.crop clip.quantize clip.to_arr arr.create_midi arr.move arr.trim notes.add notes.modify
notes.remove notes.clear notes.transpose notes.duplicate warp.add warp.move warp.remove takes.create_lane
takes.rename takes.promote takes.demote takes.move device.open device.close device.toggle device.param device.delete device.select
device.var device.move device.insert device.duplicate chain.select browser.list browser.load browser.preview browser.preview_stop sys.info sys.probe""".split()


def test_all_protocol_ops_registered(c):
    ops = c.ok("sys.info")["ops"]
    missing = [o for o in PROTOCOL_OPS if o not in ops]
    assert not missing


def test_transport_and_song_set(c, h):
    c.ok("song.play")
    c.until("session", lambda d: d["playing"] is True)
    c.until("session", lambda d: d["pos"] > 0)
    c.ok("song.stop")
    c.until("session", lambda d: d["playing"] is False)
    c.ok("song.continue")
    c.until("session", lambda d: d["playing"])
    c.ok("song.stop")
    for k, v, key in [("tempo", 140.5, "tempo"), ("metronome", True, "metronome"), ("loop", True, "loop"),
                      ("loop_start", 4, "loop_start"), ("loop_len", 16, "loop_len"), ("punch_in", True, "punch_in"),
                      ("punch_out", True, "punch_out"), ("rec", True, "rec"), ("session_rec", True, "session_rec"),
                      ("overdub", True, "overdub"), ("count_in", 2, "count_in"), ("quant", 4, "quant"),
                      ("rec_quant", 3, "rec_quant"), ("auto_arm", False, "auto_arm"),
                      ("back_to_arr", True, "back_to_arr")]:
        c.ok("song.set", k=k, v=v)
        c.until("session", lambda d, key=key, v=v: d[key] == v)
    c.ok("song.set", k="sig", v=[3, 8])
    c.until("session", lambda d: d["sig"] == [3, 8])
    c.ok("song.set", k="view", v="arranger")
    c.until("session", lambda d: d["view"] == "arranger")
    c.ok("song.set", k="view", v="session")
    c.until("session", lambda d: d["view"] == "session")


def test_seek_jump_cue_undo(c):
    c.ok("song.seek", beats=10)
    c.until("session", lambda d: abs(d["pos"] - 10) < 1e-6)
    c.ok("song.jump", beats=-4)
    c.until("session", lambda d: abs(d["pos"] - 6) < 1e-6)
    c.ok("song.cue", dir=1)
    c.until("session", lambda d: d["pos"] == 16.0)
    c.ok("song.cue", dir=-1)
    c.until("session", lambda d: d["pos"] == 0.0)
    c.ok("slot.create", tr="t7", i=0, len=4)
    c.until("session", lambda d: d["can_undo"])
    c.ok("song.undo")
    c.until("session", lambda d: d["can_redo"] and not d["can_undo"])
    c.ok("song.redo")
    c.ok("song.tap_tempo")
    c.ok("song.re_enable_automation")
    c.ok("song.stop_all_clips")


def test_scene_fire_and_slots_status(c):
    c.ok("focus.set", tr="t0")
    c.ok("scene.fire", i=1)
    s = c.until("slots", lambda d: d["slots"][1]["status"] == "playing")
    assert s["slots"][0]["status"] == "stopped"
    c.until("tracks", lambda d: d["tracks"][0]["playing_slot"] == 1 and d["tracks"][1]["playing_slot"] == 1)
    c.ok("track.stop", tr="t0")
    c.until("slots", lambda d: d["slots"][1]["status"] == "stopped")
    c.ok("slot.fire", tr="t0", i=2)
    c.until("slots", lambda d: d["slots"][2]["status"] == "playing")
    c.ok("clip.stop", loc=SLOT("t0", 2))
    c.until("slots", lambda d: d["slots"][2]["status"] == "stopped")
    c.ok("clip.fire", loc=SLOT("t0", 3))
    c.until("slots", lambda d: d["slots"][3]["status"] == "playing")
    c.ok("song.stop_all_clips")
    c.until("slots", lambda d: all(s["status"] != "playing" for s in d["slots"]))
    c.ok("scene.create", i=8)
    c.until("tracks", lambda d: len(d["scenes"]) == 9)
    c.until("slots", lambda d: len(d["slots"]) == 9)


def test_triggered_status_with_quantization(h, c):
    h.song.clip_trigger_quantization = 7
    h.song.is_playing = True
    c.ok("focus.set", tr="t1")
    c.ok("slot.fire", tr="t1", i=0)
    c.until("slots", lambda d: d["slots"][0]["status"] in ("triggered", "playing"))
    c.until("slots", lambda d: d["slots"][0]["status"] == "playing")


def test_slot_ops(c):
    c.ok("focus.set", tr="t7")
    c.ok("slot.create", tr="t7", i=0, len=2)
    s = c.until("slots", lambda d: d["slots"][0]["has_clip"])
    assert s["slots"][0]["clip"]["len"] == 2.0
    assert c.req("slot.create", tr="t7", i=0, len=2)["ok"] is False        # occupied
    assert c.req("slot.create", tr="t3", i=2, len=2)["err"].startswith("unsupported")  # audio track
    c.ok("slot.duplicate", tr="t7", i=0)
    c.until("slots", lambda d: d["slots"][1]["has_clip"])
    c.ok("slot.copy", tr="t7", i=0, to_tr="t7", to_i=3)
    c.until("slots", lambda d: d["slots"][3]["has_clip"])
    c.ok("slot.move", tr="t7", i=3, to_tr="t7", to_i=4)
    c.until("slots", lambda d: d["slots"][4]["has_clip"] and not d["slots"][3]["has_clip"])
    # occupied target: refused unless replace/swap (no silent data loss)
    s = c.until("slots", lambda d: d["slots"][5]["has_clip"])
    assert s["slots"][4]["clip"]["len"] == 2.0 and s["slots"][5]["clip"]["len"] == 4.0
    r = c.req("slot.move", tr="t7", i=4, to_tr="t7", to_i=5)
    assert r["ok"] is False and "occupied" in r["err"]
    assert c.req("slot.copy", tr="t7", i=4, to_tr="t7", to_i=5)["ok"] is False
    s = c.until("slots", lambda d: d["slots"][4]["has_clip"] and d["slots"][5]["has_clip"])
    assert s["slots"][5]["clip"]["len"] == 4.0                                # untouched (pre-existing "Lead", 4 beats)
    c.ok("slot.move", tr="t7", i=4, to_tr="t7", to_i=5, swap=True)
    c.until("slots", lambda d: d["slots"][4]["clip"]["len"] == 4.0 and d["slots"][5]["clip"]["len"] == 2.0)
    c.ok("slot.move", tr="t7", i=4, to_tr="t7", to_i=5, replace=True)
    c.until("slots", lambda d: not d["slots"][4]["has_clip"] and d["slots"][5]["clip"]["len"] == 4.0)
    c.ok("slot.delete", tr="t7", i=5)
    c.until("slots", lambda d: not d["slots"][5]["has_clip"])
    assert c.req("slot.delete", tr="t7", i=5)["ok"] is False
    assert c.req("slot.delete", tr="t7", i=4)["ok"] is False


def test_track_set_variants(c, h):
    c.ok("focus.set", tr="t1")
    for k, v, path in [("name", "Basso", "name"), ("mute", True, "mute"), ("solo", True, "solo"),
                       ("arm", True, "arm"), ("monitor", 2, "monitor")]:
        c.ok("track.set", tr="t1", k=k, v=v)
        c.until("focus", lambda d, path=path, v=v: d[path] == v)
    c.ok("track.set", tr="t1", k="color", v=0x123456)
    c.until("tracks", lambda d: d["tracks"][1]["color"] == 0x123456)
    c.ok("track.set", tr="t1", k="vol", v=0.5)
    c.until("focus", lambda d: abs(d["vol"]["v"] - 0.5) < 1e-6)
    c.ok("track.set", tr="t1", k="vol", v=7)             # clamps to max
    c.until("focus", lambda d: d["vol"]["v"] == 1.0)
    c.ok("track.set", tr="t1", k="pan", v=-0.5)
    c.until("focus", lambda d: d["pan"]["v"] == -0.5 and d["pan"]["str"].endswith("L"))
    c.ok("track.set", tr="t1", k="send", i=1, v=0.4)
    c.until("focus", lambda d: abs(d["sends"][1]["v"] - 0.4) < 1e-6)
    assert c.req("track.set", tr="t1", k="send", i=5, v=0.4)["ok"] is False
    c.ok("track.set", tr="t1", k="in_type", v="No Input")
    c.until("focus", lambda d: d["in_type"] == "No Input")
    c.ok("track.set", tr="t1", k="out_type", v="Ext. Out")
    c.until("focus", lambda d: d["out_type"] == "Ext. Out")
    c.ok("track.set", tr="t1", k="in_ch", v="2")
    c.until("focus", lambda d: d["in_ch"] == "2")
    assert c.req("track.set", tr="t1", k="in_type", v="Bogus")["ok"] is False
    c.ok("track.set", tr="t5", k="folded", v=True)
    c.until("tracks", lambda d: d["tracks"][5]["folded"] is True)
    assert c.req("track.set", tr="t0", k="folded", v=True)["err"].startswith("unsupported")
    # return + master
    assert c.req("track.set", tr="m", k="arm", v=True)["err"].startswith("unsupported")
    c.ok("track.set", tr="r0", k="mute", v=True)
    c.until("tracks", lambda d: d["returns"][0]["mute"] is True)
    c.ok("track.set", tr="m", k="vol", v=0.6)
    c.ok("focus.set", tr="m")
    c.until("focus", lambda d: d["ref"] == "m" and abs(d["vol"]["v"] - 0.6) < 1e-6 and d["sends"] == [])


def test_track_create_delete_duplicate(c):
    c.ok("track.create", kind="midi", i=1)
    c.until("tracks", lambda d: len(d["tracks"]) == 9 and d["tracks"][1]["kind"] == "midi")
    c.ok("track.create", kind="audio", i=-1)
    c.until("tracks", lambda d: len(d["tracks"]) == 10)
    c.ok("track.create", kind="return")
    c.until("tracks", lambda d: len(d["returns"]) == 3)
    c.ok("track.duplicate", tr="t0")
    c.until("tracks", lambda d: len(d["tracks"]) == 11 and d["tracks"][1]["name"].endswith("copy"))
    c.ok("focus.set", tr="t10")
    c.until("focus", lambda d: d["ref"] == "t10")
    c.ok("track.delete", tr="t10")
    c.until("tracks", lambda d: len(d["tracks"]) == 10)
    c.take(lambda m: m.get("t") == "log" and "no longer exists" in m["msg"])
    c.ok("track.delete", tr="r2")
    c.until("tracks", lambda d: len(d["returns"]) == 2)
    assert c.req("track.delete", tr="m")["ok"] is False
    assert c.req("track.duplicate", tr="r0")["ok"] is False


def test_take_next_records_new_clip(c, h):
    c.ok("focus.set", tr="t0")
    r = c.ok("take.next", tr="t0")
    assert r["i"] == 6 and r["name"] == "Take 1"
    s = c.until("slots", lambda d: d["slots"][6]["status"] == "recording")
    c.until("slots", lambda d: d["slots"][6]["clip"]["name"] == "Take 1")
    c.until("focus", lambda d: d["arm"] is True)
    c.until("slots", lambda d: d["slots"][6]["clip"] is not None and d["slots"][6]["clip"]["len"] > 0.2)
    c.ok("clip.open", loc=SLOT("t0", 6))
    c.until("clip", lambda d: d and d["rec"] is True)
    c.ok("clip.close")
    r2 = c.ok("take.next", tr="t0")
    assert r2["i"] == 7 and r2["name"] == "Take 2"
    c.until("slots", lambda d: d["slots"][6]["status"] != "recording" and d["slots"][7]["status"] == "recording")
    c.ok("take.next", tr="t0")           # no free slot -> creates a scene
    c.until("tracks", lambda d: len(d["scenes"]) == 9)
    c.until("slots", lambda d: len(d["slots"]) == 9 and d["slots"][8]["status"] == "recording")
    c.ok("track.stop", tr="t0")
    c.until("slots", lambda d: d["slots"][8]["status"] == "empty" or d["slots"][8]["status"] == "stopped")
    assert c.req("take.next", tr="r0")["ok"] is False
    assert c.req("take.next", tr="t5")["ok"] is False


def test_record_into_empty_audio_slot(c):
    c.ok("focus.set", tr="t4")
    c.ok("track.set", tr="t4", k="arm", v=True)
    c.ok("slot.fire", tr="t4", i=0)
    c.until("slots", lambda d: d["slots"][0]["status"] == "recording")
    c.ok("track.stop", tr="t4")
    c.until("slots", lambda d: d["slots"][0]["status"] == "stopped" and d["slots"][0]["clip"]["len"] >= 1)


def test_clip_open_set_close(c):
    loc = SLOT("t0", 0)
    c.ok("clip.open", loc=loc)
    d = c.until("clip", lambda d: d and d["name"] == "Beat 1")
    assert d["loc"] == loc and d["audio"] is False and len(d["notes"]) > 10 and "gain" not in d
    n0 = d["notes"][0]
    assert set(n0) == {"id", "p", "t", "d", "v", "m", "prob", "vd", "rv"}
    for k, v, key in [("name", "Renamed", "name"), ("color", 0xABCDEF, "color"), ("looping", False, "looping"),
                      ("muted", True, "muted"), ("legato", True, "legato"), ("launch_mode", 2, "launch_mode"),
                      ("launch_quant", 4, "launch_quant"), ("loop_end", 4.0, "loop_end"),
                      ("loop_start", 1.0, "loop_start"), ("start_marker", 0.5, "start_marker"),
                      ("end_marker", 7.0, "end_marker")]:
        c.ok("clip.set", loc=loc, k=k, v=v)
        c.until("clip", lambda d, key=key, v=v: d[key] == v)
    for k, v, sig in [("sig_num", 7, [7, 4]), ("sig_den", 8, [7, 8])]:
        c.ok("clip.set", loc=loc, k=k, v=v)
        c.until("clip", lambda d, sig=sig: d["signature"] == sig)
    for k, v in [("sig_den", 3), ("sig_num", 0), ("sig_num", 100)]:
        assert c.req("clip.set", loc=loc, k=k, v=v)["ok"] is False, (k, v)
    assert c.req("clip.set", loc=loc, k="gain", v=0.5)["ok"] is False     # audio only
    assert c.req("clip.set", loc=loc, k="loop_end", v=0.5)["ok"] is False  # before loop_start
    r = c.req("clip.set", loc=loc, k="loop_start", v=6.0)                  # after loop_end (4.0)
    assert r["ok"] is False and "loop_start" in r["err"]
    for k, v in [("end_marker", 0.1), ("start_marker", 99.0), ("loop_start", -1), ("start_marker", float("nan")),
                 ("end_marker", 1e9), ("loop_end", "x")]:
        assert c.req("clip.set", loc=loc, k=k, v=v)["ok"] is False, (k, v)
    c.ok("clip.close")
    c.until("clip", lambda d: d is None)
    assert c.req("notes.add", notes=[{"p": 60, "t": 0, "d": 1}])["ok"] is False   # nothing open


def test_clip_live_updates_from_listener(c, h):
    c.ok("clip.open", loc=SLOT("t1", 0))
    c.until("clip", lambda d: d and d["name"] == "Bass 1")
    h.song.tracks[1].clip_slots[0].clip.name = "From Live"          # change made inside Live
    c.until("clip", lambda d: d["name"] == "From Live")


def test_notes_ops(c):
    loc = SLOT("t1", 1)
    c.ok("clip.open", loc=loc)
    d = c.until("clip", lambda d: d and d["loc"] == loc)
    n = len(d["notes"])
    c.ok("notes.add", notes=[{"p": 70, "t": 0.5, "d": 0.5, "v": 90}, {"p": 72, "t": 1.0, "d": 0.25, "v": 80, "prob": 0.5}])
    d = c.until("clip", lambda d: len(d["notes"]) == n + 2)
    new = [x for x in d["notes"] if x["p"] in (70, 72)]
    assert len(new) == 2 and any(x["prob"] == 0.5 for x in new)
    ids = [x["id"] for x in new]
    c.ok("notes.modify", notes=[{"id": ids[0], "p": 71, "v": 50, "d": 2.0, "m": True}])
    d = c.until("clip", lambda d: any(x["id"] == ids[0] and x["p"] == 71 for x in d["notes"]))
    m = [x for x in d["notes"] if x["id"] == ids[0]][0]
    assert m["v"] == 50 and m["d"] == 2.0 and m["m"] is True
    c.ok("notes.transpose", ids=ids, semis=5)
    d = c.until("clip", lambda d: any(x["id"] == ids[0] and x["p"] == 76 for x in d["notes"]))
    c.ok("notes.transpose", ids=ids, semis=-100)
    c.until("clip", lambda d: any(x["id"] == ids[0] and x["p"] == 0 for x in d["notes"]))
    c.ok("notes.duplicate", ids=ids, offset=4.0)
    c.until("clip", lambda d: len(d["notes"]) == n + 4)
    c.ok("notes.remove", ids=ids)
    c.until("clip", lambda d: len(d["notes"]) == n + 2 and all(x["id"] not in ids for x in d["notes"]))
    c.ok("notes.clear")
    c.until("clip", lambda d: d["notes"] == [])
    # validation
    for bad in ([{"p": 200, "t": 0, "d": 1}], [{"p": 60, "t": -1, "d": 1}], [{"p": 60, "t": 0, "d": 0}],
                [{"p": 60}], ["x"], [{"p": 60, "t": 0, "d": 1, "v": 0}]):
        assert c.req("notes.add", notes=bad)["ok"] is False, bad
    assert c.req("notes.modify", notes=[{"p": 1}])["ok"] is False
    assert c.req("notes.remove", ids="x")["ok"] is False


def test_clip_midi_edit_ops(c):
    loc = SLOT("t2", 0)
    c.ok("clip.open", loc=loc)
    d = c.until("clip", lambda d: d and d["loc"] == loc)
    n, ln = len(d["notes"]), d["len"]
    c.ok("clip.duplicate_loop", loc=loc)
    c.until("clip", lambda d: d["len"] == ln * 2 and len(d["notes"]) == n * 2)
    c.ok("clip.quantize", loc=loc, grid=1, amount=1.0)
    c.until("clip", lambda d: all(abs(x["t"] - round(x["t"])) < 1e-6 for x in d["notes"]))
    c.ok("clip.set", loc=loc, k="loop_end", v=4.0)
    c.ok("clip.crop", loc=loc)
    c.until("clip", lambda d: d["len"] == 4.0 and all(x["t"] < 4.0 for x in d["notes"]))


def test_audio_clip_and_warp(c):
    loc = SLOT("t3", 0)
    c.ok("clip.open", loc=loc)
    d = c.until("clip", lambda d: d and d["loc"] == loc)
    assert d["audio"] and d["file_path"].endswith("sine_a.wav") and os.path.exists(d["file_path"])
    assert d["sample_rate"] == 22050 and "notes" not in d and "Complex Pro" in d["warp_modes"]
    assert len(d["warp_markers"]) == 2 and d["warp_mode"] == "Beats"
    for k, v, key in [("gain", 0.7, "gain"), ("pitch_coarse", 5, "pitch_coarse"), ("pitch_fine", -20.0, "pitch_fine"),
                      ("warping", False, "warping"), ("warp_mode", "Complex", "warp_mode")]:
        c.ok("clip.set", loc=loc, k=k, v=v)
        c.until("clip", lambda d, key=key, v=v: d[key] == v)
    c.ok("clip.set", loc=loc, k="gain_db", v=-6.0)
    d = c.until("clip", lambda d: d["gain_str"] == "-6.0 dB")
    assert abs(d["gain"] - 0.2005) < 0.01
    assert c.req("clip.set", loc=loc, k="gain_db", v=99)["ok"] is False
    assert c.req("clip.set", loc=loc, k="warp_mode", v="Nope")["ok"] is False
    assert c.req("clip.set", loc=loc, k="pitch_coarse", v=99)["ok"] is False
    c.ok("warp.add", beat=3.0)
    d = c.until("clip", lambda d: len(d["warp_markers"]) == 3)
    c.ok("warp.add", beat=1.0, sample_time=0.4)
    c.until("clip", lambda d: len(d["warp_markers"]) == 4)
    c.ok("warp.move", beat=3.0, to_beat=3.5)
    c.until("clip", lambda d: any(abs(m["beat"] - 3.5) < 1e-6 for m in d["warp_markers"]))
    c.ok("warp.remove", beat=3.5)
    c.until("clip", lambda d: len(d["warp_markers"]) == 3)
    assert c.req("notes.add", notes=[{"p": 60, "t": 0, "d": 1}])["ok"] is False
    c.ok("clip.open", loc=SLOT("t0", 0))
    c.until("clip", lambda d: d and not d["audio"])
    assert c.req("warp.add", beat=1)["ok"] is False


def test_scrub_and_cues(c):
    c.ok("song.seek", beats=8)
    c.until("session", lambda d: abs(d["pos"] - 8) < 1e-6)
    c.ok("song.scrub", beats=0.25)
    c.until("session", lambda d: abs(d["pos"] - 8.25) < 1e-6)
    c.ok("song.scrub", beats=-100)
    c.until("session", lambda d: d["pos"] == 0.0)
    assert c.req("song.scrub", beats="x")["ok"] is False
    s = c.until("session", lambda d: "cues" in d)
    assert s["cues"][1] == {"t": 16.0, "n": "Verse"}


def test_arr_section_preview_and_trim(c, h):
    c.ok("focus.set", tr="t0")
    c.ok("arr.create_midi", tr="t0", start=100, len=8)
    arr = c.until("arr", lambda d: d["tr"] == "t0" and any(x["start"] == 100 for x in d["clips"]))
    i = [x["i"] for x in arr["clips"] if x["start"] == 100][0]
    x = arr["clips"][i]
    assert x["audio"] is False and isinstance(x["pn"], list) and "start_marker" in x and "end_marker" in x
    loc = {"w": "arr", "tr": "t0", "i": i}
    c.ok("arr.trim", loc=loc, end=106)
    c.until("arr", lambda d: any(y["start"] == 100 and y["end"] == 106 for y in d["clips"]))
    c.ok("arr.trim", loc=loc, start=102)
    c.until("arr", lambda d: any(y["start"] == 102 and y["end"] == 106 for y in d["clips"]))
    assert c.req("arr.trim", loc=loc)["ok"] is False
    assert c.req("arr.trim", loc=loc, start=105.999, end=106)["ok"] is False
    assert c.req("arr.trim", loc=loc, start=105.95, end=106)["ok"] is False      # < 1/16 beat
    assert c.req("arr.trim", loc=loc, start=107, end=106)["ok"] is False         # negative
    assert c.req("arr.trim", loc={"w": "slot", "tr": "t0", "i": 0}, end=5)["ok"] is False
    r = c.ok("arr.move", loc=loc, start=120)
    assert r is not None
    arr = c.until("arr", lambda d: any(y["start"] == 120 and y["end"] == 124 for y in d["clips"]))
    assert not any(y["start"] == 102 for y in arr["clips"])


def test_arr_audio_has_file_path(c):
    c.ok("focus.set", tr="t3")
    arr = c.until("arr", lambda d: d["tr"] == "t3")
    aud = [x for x in arr["clips"] if x["audio"]]
    assert aud and all("file_path" in x and "warping" in x for x in aud)


def test_arrangement_and_takes(c, h):
    c.ok("focus.set", tr="t0")
    arr = c.until("arr", lambda d: d["tr"] == "t0")
    n = len(arr["clips"])
    c.ok("arr.create_midi", tr="t0", start=40, len=4)
    arr = c.until("arr", lambda d: len(d["clips"]) == n + 1)
    assert arr["clips"][-1]["start"] == 40 and arr["clips"][-1]["end"] == 44
    c.ok("clip.to_arr", loc=SLOT("t0", 0), time=50)
    c.until("arr", lambda d: len(d["clips"]) == n + 2 and d["clips"][-1]["start"] == 50)
    c.ok("arr.move", loc={"w": "arr", "tr": "t0", "i": n + 1}, start=60)
    c.until("arr", lambda d: d["clips"][-1]["start"] == 60 and len(d["clips"]) == n + 2)
    c.ok("clip.open", loc={"w": "arr", "tr": "t0", "i": 0})
    d = c.until("clip", lambda d: d and d["loc"]["w"] == "arr")
    assert d["end"] > d["start"] and len(d["notes"]) > 0
    c.ok("clip.delete", loc={"w": "arr", "tr": "t0", "i": n + 1})
    c.until("arr", lambda d: len(d["clips"]) == n + 1)
    assert c.req("arr.create_midi", tr="t3", start=0, len=4)["ok"] is False   # audio track
    c.ok("clip.stop", loc={"w": "arr", "tr": "t0", "i": 0})
    assert c.req("clip.fire", loc={"w": "arr", "tr": "t0", "i": 0})["err"].startswith("unsupported")
    # takes
    c.ok("focus.set", tr="t3")
    tk = c.until("takes", lambda d: d["tr"] == "t3")
    assert tk["lanes"][2]["name"] == "Take Lane 3"
    c.ok("takes.create_lane", tr="t3")
    c.until("takes", lambda d: len(d["lanes"]) == 4)
    c.ok("takes.rename", tr="t3", lane=3, name="Keeper")
    c.until("takes", lambda d: d["lanes"][3]["name"] == "Keeper")
    assert c.req("takes.rename", tr="t3", lane=9, name="x")["ok"] is False
    na = len(c.until("arr", lambda d: d["tr"] == "t3")["clips"])
    c.ok("takes.promote", loc={"w": "take", "tr": "t3", "lane": 1, "i": 0})
    c.until("arr", lambda d: len(d["clips"]) == na + 1)
    assert c.req("takes.promote", loc=SLOT("t3", 0))["ok"] is False
    c.ok("clip.open", loc={"w": "take", "tr": "t3", "lane": 0, "i": 1})
    c.until("clip", lambda d: d and d["loc"]["w"] == "take" and d["name"] == "Vox take 1b")
    assert c.req("takes.create_lane", tr="t5")["err"].startswith("unsupported")


def test_takes_demote_and_move(c, h):
    c.ok("focus.set", tr="t3")
    c.until("takes", lambda d: d["tr"] == "t3")
    arr0 = c.until("arr", lambda d: d["tr"] == "t3")
    na = len(arr0["clips"])
    src = next(x for x in arr0["clips"] if x["audio"])
    lanes0 = c.sections["takes"]["lanes"]
    n_l2 = len(lanes0[2]["clips"])
    # demote: main-lane clip -> take lane 2 at the same time, original removed
    r = c.ok("takes.demote", loc={"w": "arr", "tr": "t3", "i": src["i"]}, lane=2)
    assert r["lane"] == 2 and r["i"] is not None
    c.until("arr", lambda d: len(d["clips"]) == na - 1)
    tk = c.until("takes", lambda d: len(d["lanes"][2]["clips"]) == n_l2 + 1)
    assert any(abs(x["start"] - src["start"]) < 1e-6 and x["name"] == src["name"] for x in tk["lanes"][2]["clips"])
    # move between take lanes: lane 0 clip 0 -> lane 1
    first = tk["lanes"][0]["clips"][0]
    n0, n1 = len(tk["lanes"][0]["clips"]), len(tk["lanes"][1]["clips"])
    c.ok("takes.move", loc={"w": "take", "tr": "t3", "lane": 0, "i": 0}, lane=1)
    tk = c.until("takes", lambda d: len(d["lanes"][0]["clips"]) == n0 - 1 and len(d["lanes"][1]["clips"]) == n1 + 1)
    assert any(abs(x["start"] - first["start"]) < 1e-6 and x["name"] == first["name"] for x in tk["lanes"][1]["clips"])
    # same lane is a no-op, bad args are refused
    assert c.ok("takes.move", loc={"w": "take", "tr": "t3", "lane": 1, "i": 0}, lane=1)["lane"] == 1
    assert c.req("takes.demote", loc={"w": "take", "tr": "t3", "lane": 1, "i": 0}, lane=0)["ok"] is False
    assert c.req("takes.move", loc={"w": "arr", "tr": "t3", "i": 0}, lane=0)["ok"] is False
    assert c.req("takes.demote", loc={"w": "arr", "tr": "t3", "i": 0}, lane=9)["ok"] is False


def test_takes_demote_midi_copies_notes(c, h):
    c.ok("focus.set", tr="t0")
    arr = c.until("arr", lambda d: d["tr"] == "t0")
    c.ok("takes.create_lane", tr="t0")
    c.until("takes", lambda d: d["tr"] == "t0" and len(d["lanes"]) == 1)
    clip = h.song.tracks[0].arrangement_clips[0]
    n_notes = len(clip.get_notes_extended())
    assert n_notes > 0
    c.ok("takes.demote", loc={"w": "arr", "tr": "t0", "i": 0}, lane=0)
    tk = c.until("takes", lambda d: len(d["lanes"][0]["clips"]) == 1)
    lane_clip = h.song.tracks[0].take_lanes[0].arrangement_clips[0]
    assert len(lane_clip.get_notes_extended()) == n_notes
    c.until("arr", lambda d: len(d["clips"]) == len(arr["clips"]) - 1)


def test_takes_demote_unsupported_when_lane_api_missing(c, h):
    c.ok("focus.set", tr="t3")
    c.until("takes", lambda d: d["tr"] == "t3")
    import fake_lom
    saved = fake_lom.FakeLane.create_audio_clip
    del fake_lom.FakeLane.create_audio_clip
    try:
        r = c.req("takes.demote", loc={"w": "arr", "tr": "t3", "i": 0}, lane=0)
        assert r["ok"] is False and r["err"].startswith("unsupported")
        n = len(h.song.tracks[3].arrangement_clips)
        assert n > 0                       # nothing was deleted
    finally:
        fake_lom.FakeLane.create_audio_clip = saved


def test_device_move_insert_duplicate(c, h):
    c.ok("focus.set", tr="t2")
    c.until("devices", lambda d: d["tr"] == "t2")
    names = lambda d: [x["name"] for x in d["devices"]]
    base = names(c.sections["devices"])
    assert len(base) == 3
    t0 = time.time()
    c.ok("device.move", tr="t2", path="0", to=2)
    d = c.until("devices", lambda d: names(d) == [base[1], base[2], base[0]])
    assert time.time() - t0 < 1.0
    c.ok("device.move", tr="t2", path="2", to=0)
    c.until("devices", lambda d: names(d) == base)
    assert c.req("device.move", tr="t2", path="0", to=9)["ok"] is False
    c.ok("device.insert", tr="t2", name="Reverb", i=1)
    c.until("devices", lambda d: names(d)[1] == "Reverb" and len(d["devices"]) == 4)
    c.ok("device.insert", tr="t2", name="Utility")
    c.until("devices", lambda d: names(d)[-1] == "Utility")
    assert c.req("device.insert", tr="t2", name="!nope")["ok"] is False
    c.ok("device.duplicate", tr="t2", path="1")
    c.until("devices", lambda d: names(d)[1:3] == ["Reverb", "Reverb"])
    c.ok("device.delete", tr="t2", path="1")
    c.until("devices", lambda d: names(d)[1:3] != ["Reverb", "Reverb"] and len(d["devices"]) == 5)
    # into a rack chain
    c.ok("device.insert", tr="t2", name="Saturator", into="2.c0")
    c.until("devices", lambda d: any(x["name"] == "Saturator" for x in d["devices"][2]["chains"][0]["devices"]))


def test_device_changes_reach_phone_without_lom_listener(c, h):
    """Live applies browser loads late and a listener may not fire: the burst re-check must still deliver."""
    c.ok("focus.set", tr="t7")
    c.until("devices", lambda d: d["tr"] == "t7")
    track = h.song.tracks[7]
    n = len(c.sections["devices"]["devices"])
    orig = track.insert_device

    def silent_insert(name, index=None):
        import threading
        from fake_lom import _make_native_device

        def later():
            time.sleep(0.4)
            object.__setattr__(track, "devices", list(track.devices) + [_make_native_device(name)])
        threading.Thread(target=later, daemon=True).start()
    track.insert_device = silent_insert
    t0 = time.time()
    c.ok("device.insert", tr="t7", name="Limiter")
    c.until("devices", lambda d: len(d["devices"]) == n + 1, timeout=3)
    assert time.time() - t0 < 1.5


def test_devices_and_params(c):
    c.ok("focus.set", tr="t2")
    c.until("devices", lambda d: d["tr"] == "t2")
    c.ok("device.open", tr="t2", path="1")
    p = c.until("params", lambda d: d and d["path"] == "1")
    assert p["name"] == "Layer Pad" and p["params"][0]["name"] == "Device On" and p["params"][0]["quant"]
    assert p["params"][0]["items"] == ["Off", "On"] and p["params"][1]["name"] == "Cutoff"
    c.ok("device.param", tr="t2", path="1", i=2, v=0.25)
    c.until("params", lambda d: d["params"][2]["v"] == 0.25)
    c.until("devices", lambda d: abs(d["devices"][1]["macros"][1]["v"] - 0.25) < 1e-6)
    c.ok("device.param", tr="t2", path="1", i=2, v=99)
    c.until("params", lambda d: d["params"][2]["v"] == 1.0)
    c.ok("device.toggle", tr="t2", path="1")
    c.until("devices", lambda d: d["devices"][1]["on"] is False)
    c.ok("device.toggle", tr="t2", path="1")
    c.until("devices", lambda d: d["devices"][1]["on"] is True)
    # nested path
    c.ok("device.open", tr="t2", path="1.c0.1")
    c.until("params", lambda d: d["name"] == "Auto Filter")
    # variations
    c.ok("device.var", tr="t2", path="1", act="store")
    c.until("devices", lambda d: d["devices"][1]["var"]["count"] == 4)
    c.ok("device.var", tr="t2", path="1", act="select", i=0)
    c.until("devices", lambda d: d["devices"][1]["var"]["sel"] == 0)
    c.ok("device.param", tr="t2", path="1", i=1, v=0.99)
    c.ok("device.var", tr="t2", path="1", act="recall", i=0)
    c.until("devices", lambda d: abs(d["devices"][1]["macros"][0]["v"] - 0.3) < 1e-6)
    c.ok("device.var", tr="t2", path="1", act="delete", i=3)
    c.until("devices", lambda d: d["devices"][1]["var"]["count"] == 3)
    assert c.req("device.var", tr="t2", path="1", act="select", i=77)["ok"] is False
    assert c.req("device.var", tr="t2", path="1", act="zap", i=0)["ok"] is False
    assert c.req("device.var", tr="t2", path="2", act="store")["err"].startswith("unsupported")
    c.ok("chain.select", tr="t2", path="1", i=2)
    assert c.req("chain.select", tr="t2", path="1", i=9)["ok"] is False
    c.ok("device.select", tr="t2", path="0")
    c.ok("device.close")
    c.until("params", lambda d: d is None)
    c.ok("device.delete", tr="t2", path="1.c2.0")
    c.until("devices", lambda d: len(d["devices"][1]["chains"][2]["devices"]) == 0)
    c.ok("device.delete", tr="t2", path="2")
    c.until("devices", lambda d: [x["name"] for x in d["devices"]] == ["Arpeggiator", "Layer Pad"])


def test_open_device_deleted_closes_params(c):
    c.ok("focus.set", tr="t3")
    c.ok("device.open", tr="t3", path="2")
    c.until("params", lambda d: d and d["name"] == "Reverb")
    c.ok("device.delete", tr="t3", path="2")
    c.until("params", lambda d: d is None)


def test_browser(c, h):
    roots = c.ok("browser.list", path=[])
    names = [r["name"] for r in roots]
    assert "Instruments" in names and "Audio Effects" in names and all(r["folder"] for r in roots)
    inst = c.ok("browser.list", path=["instruments"])
    assert {"Drum Rack", "Synths"} <= {x["name"] for x in inst}
    synths = c.ok("browser.list", path=["Instruments", "Synths"])
    assert any(x["name"] == "Operator" and x["loadable"] and not x["folder"] and x["uri"] for x in synths)
    c.ok("focus.set", tr="t7")
    c.ok("browser.load", tr="t7", path=["instruments", "Synths", "Wavetable"])
    dv = c.until("devices", lambda d: d["tr"] == "t7" and any(x["name"] == "Wavetable" for x in d["devices"]))
    assert dv["devices"][0]["type"] == "instrument"
    c.ok("browser.load", tr="t7", path=["audio_effects", "Dynamics", "Limiter"])
    c.until("devices", lambda d: d["devices"][-1]["name"] == "Limiter" and d["devices"][-1]["type"] == "audio_fx")
    assert h.song.view.selected_track is h.song.tracks[7]
    assert c.req("browser.load", tr="t7", path=["instruments", "Synths"])["ok"] is False      # folder
    assert c.req("browser.load", tr="t7", path=[])["ok"] is False
    c.ok("browser.preview", path=["instruments", "Synths", "Operator"])
    assert h.app.browser.previewing == "Operator"
    c.ok("browser.preview_stop")
    assert h.app.browser.previewing is None
    c.ok("browser.load", tr="t4", path=["samples", "Loops", "pad_chord.wav"])
    c.ok("focus.set", tr="t4")
    c.until("slots", lambda d: any(s["clip"] and s["clip"]["audio"] and s["i"] not in (1, 2) for s in d["slots"]))


def test_probe_op(tmp_path):
    p = tmp_path / "probe.json"
    hh = Harness(probe_path=str(p))
    try:
        c = hh.client()
        r = c.ok("sys.probe")
        assert r["path"] == str(p)
        data = json.loads(p.read_text())
        objs = data["objects"]
        for key in ("song", "track.midi", "track.audio", "track.group", "track.master", "clip.audio", "clip.midi",
                    "clip.arrangement", "clip_slot", "take_lane", "device", "device.rack", "browser", "mixer_device",
                    "scene", "device.parameter", "application"):
            assert key in objs, key
        assert "tempo" in objs["song"]["attrs"] and objs["song"]["attrs"]["tempo"] == 120.0
        assert "add_notes_listener" in objs["clip.midi"]["attrs"]
    finally:
        hh.close()


def test_op_exceptions_do_not_break_tick(c, h):
    h.song.tracks[0].clip_slots[0].fire = lambda: (_ for _ in ()).throw(RuntimeError("boom"))
    r = c.req("slot.fire", tr="t0", i=0)
    assert r["ok"] is False and "boom" in r["err"]
    assert c.ok("sys.info")
    # missing LOM API -> unsupported, not crash
    del_attr = h.song.__class__.tap_tempo
    try:
        h.song.__class__.tap_tempo = property(lambda self: (_ for _ in ()).throw(AttributeError("x")))
        r = c.req("song.tap_tempo")
        assert r["ok"] is False
    finally:
        h.song.__class__.tap_tempo = del_attr
    assert c.ok("sys.info")


def test_clip_peaks_shape_and_deferred(c, h):
    d = c.ok("clip.peaks", loc=SLOT("t3", 0), n=64)
    assert d["n"] == 64 and d["sr"] == 22050 and d["frames"] > 0 and d["ch"] >= 1
    assert len(d["min"]) == d["ch"] and all(len(x) == 64 for x in d["min"] + d["max"])
    assert all(-1.0 <= v <= 0.0 for ch in d["min"] for v in ch)
    assert all(0.0 <= v <= 1.0 for ch in d["max"] for v in ch)
    assert max(max(ch) for ch in d["max"]) > 0.1          # the sine is audible
    d2 = c.ok("clip.peaks", loc=SLOT("t3", 0), n=64)       # served from the cache
    assert d2 == d
    arr = h.song.tracks[3].arrangement_clips
    if arr and getattr(arr[0], "is_audio_clip", False):
        assert c.ok("clip.peaks", loc={"w": "arr", "tr": "t3", "i": 0}, n=16)["n"] == 16


def test_clip_peaks_does_not_block_ticks(h):
    import threading
    gate = threading.Event()
    orig = h.core.peaks._compute

    def slow(path, n):
        gate.wait(5)
        return orig(path, n)

    h.core.peaks._compute = slow
    c = h.client()
    c.wait(lambda x: "tracks" in x.sections)
    rid = c.next_id
    c.next_id += 1
    c.send_json({"t": "req", "id": rid, "op": "clip.peaks", "a": {"loc": SLOT("t3", 0), "n": 32}})
    # while the job is parked on the worker, ordinary requests are answered promptly
    t0 = time.monotonic()
    c.ok("song.set", k="tempo", v=133.0)
    assert c.req("sys.info")["ok"]
    assert time.monotonic() - t0 < 1.0
    assert not any(m.get("t") == "res" and m.get("id") == rid for m in c.msgs)
    gate.set()
    r = c.take(lambda m: m.get("t") == "res" and m.get("id") == rid)
    assert r["ok"] and r["d"]["n"] == 32


def test_clip_peaks_errors(c, h, tmp_path):
    assert c.req("clip.peaks", loc=SLOT("t3", 0), n=4)["ok"] is False          # n out of range
    assert c.req("clip.peaks", loc=SLOT("t3", 0), n=99999)["ok"] is False
    assert c.req("clip.peaks", loc=SLOT("t3", 0))["ok"] is False
    assert c.req("clip.peaks", loc={"w": "slot", "tr": "t3", "i": 99}, n=64)["ok"] is False
    assert c.req("clip.peaks", loc={"w": "nope"}, n=64)["ok"] is False
    assert c.req("clip.peaks", n=64)["ok"] is False
    assert c.req("clip.peaks", loc=SLOT("t0", 0), n=64)["err"] == "not an audio clip"
    clip = h.song.tracks[3].clip_slots[0].clip
    old = clip.file_path
    try:
        f = tmp_path / "x.mp3"
        f.write_bytes(b"ID3" + b"\0" * 64)
        clip.file_path = str(f)
        r = c.req("clip.peaks", loc=SLOT("t3", 0), n=64)
        assert r["ok"] is False and "unsupported format" in r["err"]
        clip.file_path = str(tmp_path / "missing.wav")
        assert c.req("clip.peaks", loc=SLOT("t3", 0), n=64)["ok"] is False
        clip.file_path = ""
        assert c.req("clip.peaks", loc=SLOT("t3", 0), n=64)["ok"] is False
    finally:
        clip.file_path = old
