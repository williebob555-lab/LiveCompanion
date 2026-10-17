"""arr.move / arr.trim edge cases against the fake LOM (which follows the verified real-Live arrangement model)."""
import time


def lane(h, tr):
    return h.song.tracks[tr].arrangement_clips


def arr_of(c, tr):
    c.forget("arr")
    c.ok("focus.set", tr=tr)
    return c.until("arr", lambda d: d["tr"] == tr)["clips"]


def test_markers_do_not_move_edges_in_fake(h):
    """Regression guard for the old mock lie: start/end_marker writes must never resize an arrangement clip."""
    clip = lane(h, 0)[0]
    clip.looping = False
    s, e = clip.start_time, clip.end_time
    clip.end_marker = clip.end_marker - 1
    clip.start_marker = clip.start_marker + 1
    assert (clip.start_time, clip.end_time) == (s, e)
    clip.loop_end = clip.loop_end - 1                 # unlooped: this IS the right edge
    assert clip.end_time == e - 1 and clip.start_time == s


def test_trim_returns_index_and_follows_open_clip(c, h):
    clips = arr_of(c, "t0")
    first = clips[0]
    c.ok("clip.open", loc={"w": "arr", "tr": "t0", "i": 0})
    c.until("clip", lambda d: d and d["loc"]["i"] == 0)
    r = c.ok("arr.trim", loc={"w": "arr", "tr": "t0", "i": 0}, start=first["start"] + 1, end=first["end"] - 1)
    assert r["start"] == first["start"] + 1 and r["end"] == first["end"] - 1 and isinstance(r["i"], int)
    d = c.until("clip", lambda d: d and abs(d["start"] - r["start"]) < 1e-6)
    assert d["loc"]["i"] == r["i"]


def test_unwarped_audio_trim_uses_seconds_and_clamps_at_file(c, h):
    clip = lane(h, 3)[0]
    assert clip.is_audio_clip
    tr = "t3"
    clip.warping = False                                # fake converts beats -> seconds like Live
    s, e = clip.start_time, clip.end_time
    k = h.song.tempo / 60.0
    r = c.ok("arr.trim", loc={"w": "arr", "tr": tr, "i": 0}, end=s + (e - s) / 2)
    assert abs(r["end"] - (s + (e - s) / 2)) < 2e-3
    # beyond the file: clamped to the file length (seconds * tempo/60 beats), not an error
    r = c.ok("arr.trim", loc={"w": "arr", "tr": tr, "i": r["i"]}, end=s + 500)
    assert r["clamped"] is True
    secs = clip.sample_length / clip.sample_rate
    assert abs((r["end"] - r["start"]) - (secs - clip.start_marker) * k) < 5e-3
    # later start keeps the content under the same song time
    cl = lane(h, 3)[r["i"]]
    before = cl.start_marker + (s + 1 - cl.start_time) / k
    r = c.ok("arr.trim", loc={"w": "arr", "tr": tr, "i": r["i"]}, start=s + 1)
    cl = lane(h, 3)[r["i"]]
    assert abs(cl.start_time - (s + 1)) < 2e-3 and abs(cl.start_marker - before) < 5e-3


def test_move_overwrites_neighbour_like_live_and_returns_index(c, h):
    clips = arr_of(c, "t3")
    n = len(clips)
    a, b = clips[0], clips[1]
    r = c.ok("arr.move", loc={"w": "arr", "tr": "t3", "i": 0}, start=b["start"] + 1)
    assert abs(r["start"] - (b["start"] + 1)) < 1e-6 and r["end"] - r["start"] == a["end"] - a["start"]
    after = arr_of(c, "t3")
    assert after[r["i"]]["start"] == r["start"]
    assert len(after) <= n                               # original gone, neighbour trimmed/overwritten


def test_move_to_same_start_is_noop_with_index(c, h):
    clips = arr_of(c, "t0")
    r = c.ok("arr.move", loc={"w": "arr", "tr": "t0", "i": 0}, start=clips[0]["start"])
    assert r["i"] == 0 and r["start"] == clips[0]["start"]


def test_trim_errors_are_precise(c, h):
    clips = arr_of(c, "t0")
    loc = {"w": "arr", "tr": "t0", "i": 0}
    r = c.req("arr.trim", loc=loc, start=clips[0]["end"] - 0.01)
    assert not r["ok"] and "1/16" in r["err"]
    assert not c.req("arr.trim", loc=loc)["ok"]
    h.song.tracks[0].arrangement_clips[0].is_recording = True
    r = c.req("arr.trim", loc=loc, end=clips[0]["end"] - 1)
    assert not r["ok"] and "recording" in r["err"]
    r = c.req("arr.move", loc=loc, start=3)
    assert not r["ok"] and "recording" in r["err"]


def test_looped_clip_end_trim_keeps_brace(c, h):
    clip = lane(h, 0)[0]
    clip.looping = True
    clip.loop_end = min(clip.loop_end, 4.0)
    brace = (clip.loop_start, clip.loop_end)
    s, e = clip.start_time, clip.end_time
    r = c.ok("arr.trim", loc={"w": "arr", "tr": "t0", "i": 0}, end=e + 6)
    cl = lane(h, 0)[r["i"]]
    assert cl.looping and (cl.loop_start, cl.loop_end) == brace and abs(cl.end_time - (e + 6)) < 1e-6
    r = c.ok("arr.trim", loc={"w": "arr", "tr": "t0", "i": r["i"]}, start=s + 2)
    cl = lane(h, 0)[r["i"]]
    assert cl.looping and (cl.loop_start, cl.loop_end) == brace and abs(cl.start_time - (s + 2)) < 1e-6


def test_looped_clip_whose_length_differs_from_marker_region(c, h):
    """Real Live: a looped clip 7.47 long with end_marker-start_marker = 4 shrinks to 4 when un-looped; trim must still work."""
    clip = lane(h, 0)[0]
    clip.looping = True
    clip.__dict__["_arr_len"] = 11.0
    clip.__dict__["end_marker"] = 6.0
    clip.__dict__["start_marker"] = 0.0
    clip.__dict__["loop_start"], clip.__dict__["loop_end"] = 1.0, 4.0
    s = clip.start_time
    r = c.ok("arr.trim", loc={"w": "arr", "tr": "t0", "i": 0}, end=s + 9)
    cl = lane(h, 0)[r["i"]]
    assert cl.looping and abs(cl.end_time - (s + 9)) < 1e-6 and (cl.loop_start, cl.loop_end) == (1.0, 4.0)
