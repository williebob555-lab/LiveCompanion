import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "remote_script"))

from LiveCompanion.core import recenv  # noqa: E402
from LiveCompanion.core.recenv import Env, RecEnv, STEP  # noqa: E402


class _T(object):
    arm = True
    clip_slots = []
    output_meter_left = 0.0
    output_meter_right = 0.0
    input_meter_left = 0.0
    input_meter_right = 0.0


class _Song(object):
    record_mode = True
    is_playing = True
    current_song_time = 0.0


def test_step_is_fine_grained():
    assert STEP <= 0.0625


def test_gap_between_ticks_ramps_linearly(monkeypatch):
    t, song = _T(), _Song()
    monkeypatch.setattr(recenv, "resolve_track", lambda s, tr: t)
    r = RecEnv()
    t.output_meter_left = 0.0
    song.current_song_time = 0.0
    r.sample(song, ["t0"])
    t.output_meter_left = 0.8
    song.current_song_time = 8 * STEP                     # one tick later: 8 grid points further
    r.sample(song, ["t0"])
    e = r.envs[("t0", "arr")]
    assert len(e.vals) == 9
    diffs = [e.vals[i + 1] - e.vals[i] for i in range(8)]
    assert all(abs(d - 0.1) < 1e-9 for d in diffs)       # smooth ramp, no stair step
    assert abs(e.vals[-1] - 0.8) < 1e-9


def test_every_tick_keeps_peak_in_same_bucket():
    e = Env("t0", "arr", 0)
    e.add(3, 0.2)
    e.add(3, 0.7)
    e.add(3, 0.4)
    assert e.vals[3] == 0.7


def test_rt_payload_is_compact_delta():
    r = RecEnv()
    e = Env("t0", "arr", 100)
    for k in range(400):
        e.add(100 + k, 0.5)
    r.envs[("t0", "arr")] = e
    p = r.rt_payload("t0")
    assert p["step"] == STEP and p["f"] == 100 and len(p["v"]) == recenv.RT_POINTS
    assert p["i"] == 100 + 400 - recenv.RT_POINTS
