import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import dump_fixtures  # noqa: E402


def test_dump_fixtures_runs(tmp_path):
    names = dump_fixtures.dump(str(tmp_path))
    for need in ("sec_session", "sec_tracks", "sec_clip_audio_slot", "sec_clip_midi_slot", "sec_clip_audio_take",
                 "sec_clip_audio_arr", "sec_params_rack", "rt", "res_browser_roots", "res_browser_sub"):
        assert need in names, need
    clip = json.load(open(tmp_path / "sec_clip_audio_slot.json"))["d"]
    assert clip["audio"] and clip["file_path"]
    assert json.load(open(tmp_path / "sec_tracks.json"))["d"]["returns"][0]["vol"]
