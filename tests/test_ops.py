import json
import os
import time

import pytest

from conftest import Harness

SLOT = lambda tr, i: {"w": "slot", "tr": tr, "i": i}
PROTOCOL_OPS = """song.play song.stop song.continue song.tap_tempo song.undo song.redo song.capture_midi
song.stop_all_clips song.re_enable_automation song.jump song.seek song.scrub song.cue cue.move song.set scene.fire scene.create
track.create track.delete track.duplicate track.move_check track.move focus.set focus.follow view.select track.set track.stop slot.fire
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

    def slow(path, n, sr=0):
        gate.wait(5)
        return orig(path, n, sr)

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


def test_cue_move(c, h):
    from fake_lom import FakeCue
    s = c.until("session", lambda d: "cues" in d)
    assert s["cues"][1] == {"t": 16.0, "n": "Verse"}
    c.ok("cue.move", i=1, time=20)
    c.until("session", lambda d: d["cues"][1] == {"t": 20.0, "n": "Verse"})
    assert c.req("cue.move", i=1, time=0)["ok"] is False          # occupied by Intro
    assert c.req("cue.move", i=9, time=3)["ok"] is False
    assert c.req("cue.move", i=1, time=-1)["ok"] is False
    # read-only CuePoint.time -> delete + re-create fallback, name and playhead preserved
    FakeCue.locked = True
    try:
        c.ok("song.seek", beats=5)
        c.until("session", lambda d: abs(d["pos"] - 5) < 1e-6)
        c.ok("cue.move", i=1, time=24)
        s = c.until("session", lambda d: any(x["t"] == 24.0 for x in d["cues"]))
        assert [x["n"] for x in s["cues"]] == ["Intro", "Verse", ""]
        assert abs(s["pos"] - 5) < 1e-6
    finally:
        FakeCue.locked = False


def _names(h):
    return [t.name for t in h.song.tracks]


def test_track_move_down_and_up_keeps_everything(c, h):
    song = h.song
    keys = song.tracks[2]
    devs = list(keys.devices)
    clips = [(i, s.clip.name) for i, s in enumerate(keys.clip_slots) if s.has_clip]
    keys.mixer_device.volume.value = 0.5
    keys.mixer_device.panning.value = -0.25
    keys.mixer_device.sends[1].value = 0.7
    keys.mute = True
    keys.color = 12
    keys.arm = True
    keys.current_monitoring_state = 2
    arr = [(x.name, x.start_time) for x in keys.arrangement_clips]
    n0 = len(song.tracks)
    c.ok("focus.set", tr="t0")
    chk = c.ok("track.move_check", tr="t2")
    assert chk == {"ok_to_move": True, "losses": []}
    # down: Keys (t2) -> final index 4
    r = c.ok("track.move", tr="t2", to=4)
    assert r["tr"] == "t4"
    names = _names(h)
    assert names[:5] == ["Drums", "Bass", "Vocals", "Guitar", "Keys"] and len(names) == n0
    new = song.tracks[4]
    assert new is not keys and new.name == "Keys" and new.color == 12 and new.mute is True
    assert new.devices == devs and keys.devices == []                 # order preserved, moved not copied
    assert [(i, s.clip.name) for i, s in enumerate(new.clip_slots) if s.has_clip] == clips
    assert [(x.name, x.start_time) for x in new.arrangement_clips] == arr
    assert new.mixer_device.volume.value == 0.5 and new.mixer_device.panning.value == -0.25
    assert new.mixer_device.sends[1].value == 0.7
    assert new.arm is True and new.current_monitoring_state == 2
    assert new.input_routing_type == keys.input_routing_type
    c.until("tracks", lambda d: [t["name"] for t in d["tracks"]][:5] == names[:5])
    c.until("focus", lambda d: d["ref"] == "t4" and d["name"] == "Keys")
    # up: back to index 1
    r = c.ok("track.move", tr="t4", to=1)
    assert r["tr"] == "t1"
    assert _names(h)[:5] == ["Drums", "Keys", "Bass", "Vocals", "Guitar"]
    assert song.tracks[1].devices == devs
    c.until("focus", lambda d: d["ref"] == "t1")
    # same index is a no-op
    assert c.ok("track.move", tr="t1", to=1)["tr"] == "t1"
    assert _names(h)[1] == "Keys"
    # move to the very end and the very start
    assert c.ok("track.move", tr="t1", to=n0 - 1)["tr"] == "t%d" % (n0 - 1)
    assert _names(h)[-1] == "Keys"
    assert c.ok("track.move", tr="t%d" % (n0 - 1), to=0)["tr"] == "t0"
    assert _names(h)[0] == "Keys" and len(song.tracks) == n0


def test_track_move_audio_track_and_devices_of_rack(c, h):
    song = h.song
    gtr = song.tracks[4]          # Guitar (audio)
    assert c.ok("track.move_check", tr="t4")["losses"] == ["take lanes"]
    gtr.take_lanes = []
    devs = list(gtr.devices)
    c.ok("track.move", tr="t4", to=0)
    assert song.tracks[0].name == "Guitar" and song.tracks[0].has_audio_input
    assert song.tracks[0].devices == devs
    drums = song.tracks[1]
    assert drums.name == "Drums" and drums.devices[0].name == "Drum Rack"


def test_track_move_refusals(c, h):
    song = h.song
    for tr in ("r0", "m", "t5"):          # return, master, group
        assert c.req("track.move_check", tr=tr)["ok"] is False
        assert c.req("track.move", tr=tr, to=0, force=True)["ok"] is False
    assert "group" in c.req("track.move", tr="t5", to=0)["err"]
    assert c.req("track.move", tr="t0", to=99)["ok"] is False
    assert c.req("track.move", tr="t0", to=-1)["ok"] is False
    assert c.req("track.move", tr="t99", to=0)["ok"] is False
    assert _names(h)[0] == "Drums"


def test_track_move_needs_confirm_then_force(c, h):
    song = h.song
    child = song.tracks[6]                # FX Audio, member of the FX Group
    child.mixer_device.volume.automation_state = 1
    child.is_frozen = True
    chk = c.ok("track.move_check", tr="t6")
    assert chk["ok_to_move"] is False
    assert set(chk["losses"]) == {"arrangement automation", "group membership", "frozen"}
    before = _names(h)
    r = c.req("track.move", tr="t6", to=0)
    assert r["ok"] is False and r["err"] == "needs_confirm"
    assert set(r["d"]["losses"]) == set(chk["losses"])
    assert _names(h) == before            # nothing touched
    r = c.ok("track.move", tr="t6", to=0, force=True)
    assert r["tr"] == "t0" and _names(h)[0] == "FX Audio"
    # take lanes with clips are a loss; an empty lane is not
    t3 = song.tracks[4]                   # Vocals now at 4
    assert t3.name == "Vocals"
    chk = c.ok("track.move_check", tr="t4")
    assert chk == {"ok_to_move": False, "losses": ["take lanes"]}


def test_track_move_rolls_back_on_error(c, h):
    song = h.song
    keys = song.tracks[2]
    devs = list(keys.devices)
    n0, names = len(song.tracks), _names(h)
    orig = keys.clip_slots[0].__class__.duplicate_clip_to

    def boom(self, target):
        raise RuntimeError("boom")
    keys.clip_slots[0].__class__.duplicate_clip_to = boom
    try:
        r = c.req("track.move", tr="t2", to=4)
    finally:
        keys.clip_slots[0].__class__.duplicate_clip_to = orig
    assert r["ok"] is False and "nothing changed" in r["err"]
    assert _names(h) == names and len(song.tracks) == n0
    assert song.tracks[2] is keys and keys.devices == devs      # devices moved back, order intact


def test_track_move_remaps_other_clients_focus(h):
    a = h.client()
    b = h.client()
    a.wait(lambda x: "tracks" in x.sections)
    b.wait(lambda x: "tracks" in x.sections)
    b.ok("focus.set", tr="t4")
    b.until("focus", lambda d: d["ref"] == "t4")
    a.ok("track.move", tr="t0", to=6)
    b.until("focus", lambda d: d["ref"] == "t3" and d["name"] == "Guitar")
    a.until("focus", lambda d: d["ref"] == "t6" and d["name"] == "Drums")


def test_sys_info_has_script_version(c):
    from LiveCompanion.core import VERSION
    d = c.ok("sys.info")
    assert d["script_version"] == VERSION and "track.move" in d["ops"]



# ---------------------------------------------------------------- .asd overview + locked audio + meter envelope

FIX = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


def _synthetic_asd(ch, bins, frames):
    """Tiny hand-built .asd: seek table + header + 3 SampleOverViewLevel records (float16 min/max)."""
    import struct
    table = [0, frames // 2, frames]
    d = bytearray(b"\x06I" + struct.pack("<I", len(table)) + b"".join(struct.pack("<I", x) for x in table))
    d += b"\x00" * 4 + struct.pack("<III", 0, 100, 2 * ch) + b"\x00" * 40
    d += b"\x13SampleOverViewLevel" + struct.pack("<II", 1, 18) + b"x" * 36      # schema mention (must be ignored)
    lv0 = []
    for b in range(bins):
        for c in range(ch):
            lv0 += [-0.5 + 0.25 * c - b * 0.0, 0.25 + 0.5 * (b % 2) + 0.125 * c]
    for idx, vals in ((0, lv0), (1, lv0[:2 * ch]), (2, lv0[:2 * ch])):
        d += b"\x13SampleOverViewLevel" + struct.pack("<II", idx, len(vals)) + struct.pack("<%de" % len(vals), *vals)
    return bytes(d)


def test_asd_parser_synthetic():
    from LiveCompanion.core import asd
    data = _synthetic_asd(2, 8, 1000)
    a = asd.parse(data)
    assert a["ch"] == 2 and a["frames"] == 1000 and [lv[0] for lv in a["levels"]] == [128, 16384, 2097152]
    o = asd.overview(data, 4, sr=44100)
    assert o["n"] == 4 and o["ch"] == 2 and o["sr"] == 44100 and o["frames"] == 1000
    assert o["min"][0] == [-0.5] * 4 and o["min"][1] == [-0.25] * 4
    assert o["max"][0] == [0.75] * 4 and o["max"][1] == [0.875] * 4
    o16 = asd.overview(data, 16)                       # more buckets than bins: nearest bin repeated
    assert len(o16["max"][0]) == 16
    with pytest.raises(asd.AsdError):
        asd.overview(b"nope" * 40, 8)


def test_asd_parser_real_fixtures():
    import json
    from LiveCompanion.core import asd
    exp = json.load(open(os.path.join(FIX, "asd_expected.json")))
    for name in ("mono", "stereo"):
        with open(os.path.join(FIX, name + ".wav.asd"), "rb") as f:
            o = asd.overview(f.read(), 16)
        e = exp[name]
        assert o["ch"] == e["ch"] and o["frames"] == e["frames"]
        errs = [abs(x - y) for key in ("min", "max") for c in range(e["ch"]) for x, y in zip(o[key][c], e[key][c])]
        assert sum(errs) / len(errs) < (0.03 if name == "stereo" else 0.2), (name, errs)
        if name == "stereo":
            assert max(errs) < 0.3


def _lock_wav(monkeypatch):
    from LiveCompanion.core import peaks as P

    def locked(path, n):
        raise PermissionError(13, "Permission denied")
    monkeypatch.setattr(P, "decode_peaks", locked)


def test_clip_peaks_falls_back_to_asd_when_wav_locked(c, h, tmp_path, monkeypatch):
    import shutil
    clip = h.song.tracks[3].clip_slots[0].clip
    old = clip.file_path
    wav = tmp_path / "rec.wav"
    wav.write_bytes(b"RIFF....WAVE")
    shutil.copyfile(os.path.join(FIX, "stereo.wav.asd"), str(wav) + ".asd")
    _lock_wav(monkeypatch)
    try:
        clip.file_path = str(wav)
        d = c.ok("clip.peaks", loc=SLOT("t3", 0), n=32)
        assert d["n"] == 32 and d["ch"] == 2 and d["frames"] == 211968 and d["src"] == "asd" and "approx" not in d
        assert max(max(x) for x in d["max"]) > 0.05
        os.remove(str(wav) + ".asd")                    # no overview either -> clear error
        clip.file_path = str(tmp_path / "other.wav")
        (tmp_path / "other.wav").write_bytes(b"RIFF....WAVE")
        r = c.req("clip.peaks", loc=SLOT("t3", 0), n=32)
        assert r["ok"] is False and "locked by Live" in r["err"]
    finally:
        clip.file_path = old


def test_recording_streams_rec_env_and_approx_peaks(c, h, tmp_path, monkeypatch):
    c.ok("focus.set", tr="t4")
    c.ok("track.set", tr="t4", k="arm", v=True)
    c.ok("slot.fire", tr="t4", i=0)
    c.until("slots", lambda d: d["slots"][0]["status"] == "recording")
    c.drain(0.1)
    c.wait(lambda x: any(m.get("t") == "rt" and m["d"].get("rec_env") for m in x.msgs), timeout=5)
    c.wait(lambda x: sum(1 for m in x.msgs if m.get("t") == "rt" and m["d"].get("rec_env")) > 12, timeout=8)
    re_ = [m["d"]["rec_env"] for m in c.msgs if m.get("t") == "rt" and m["d"].get("rec_env")][-1]
    assert re_["k"] == "s0" and re_["step"] == 0.25 and len(re_["v"]) >= 2 and max(re_["v"]) > 5
    assert len(re_["v"]) <= 96 and all(0 <= v <= 100 for v in re_["v"])
    c.ok("track.stop", tr="t4")
    c.until("slots", lambda d: d["slots"][0]["status"] == "stopped")
    clip = h.song.tracks[4].clip_slots[0].clip
    f = tmp_path / "new.wav"
    f.write_bytes(b"RIFF....WAVE")
    clip.file_path = str(f)                              # recorded file, locked by Live, no .asd yet
    _lock_wav(monkeypatch)
    d = c.ok("clip.peaks", loc=SLOT("t4", 0), n=24)
    assert d["approx"] is True and d["n"] == 24 and d["ch"] == 1
    assert max(d["max"][0]) > 0.05 and min(d["min"][0]) < -0.05 and d["frames"] > 0
    # no rec_env after the recording ended
    c.drain(0.3)
    c.wait(lambda x: any(m.get("t") == "rt" for m in x.msgs))
    assert not any(m["d"].get("rec_env") for m in c.msgs if m.get("t") == "rt")
