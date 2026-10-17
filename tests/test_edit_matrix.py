"""Runs tools/live_edit_check.py's edit matrix against the mock (fake LOM follows verified real-Live semantics)."""
import os
import sys

from conftest import ROOT

sys.path.insert(0, os.path.join(ROOT, "tools"))
import live_edit_check as lec  # noqa: E402


def test_edit_matrix_on_mock(h):
    c = h.client()
    chk = lec.run(c, audio_track="t3", midi_track="t0", verbose=False)
    assert chk.passed > 100
