"""Tests for helper/helper.py: peaks decoding, cache, path translation, sets, launch wiring."""
from __future__ import annotations

import json
import math
import os
import struct
import sys
import threading
import urllib.error
import urllib.request
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "helper"))
import helper as H  # noqa: E402


# ---------------------------------------------------------------- fixtures

@pytest.fixture
def cache_dir(tmp_path, monkeypatch):
    d = tmp_path / "cache"
    monkeypatch.setattr(H, "CACHE_DIR", d)
    return d


@pytest.fixture
def server(cache_dir):
    srv = H.make_server("127.0.0.1", 0)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()
    srv.server_close()


def _get(base, path):
    try:
        with urllib.request.urlopen(base + path, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def _post(base, path, body: bytes):
    req = urllib.request.Request(base + path, data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


# ---------------------------------------------------------------- writers

def _sine(freq, amp, sr, frames, phase=0.0):
    return [amp * math.sin(2 * math.pi * freq * i / sr + phase) for i in range(frames)]


def _interleave(chans):
    return [chans[c][i] for i in range(len(chans[0])) for c in range(len(chans))]


def write_wav(path: Path, chans, bits=16, fmt=1, sr=8000, extensible=False) -> Path:
    """chans: list of per-channel float lists in -1..1."""
    nch = len(chans)
    bps = bits // 8
    data = bytearray()
    for v in _interleave(chans):
        if fmt == 3:
            data += struct.pack("<f" if bits == 32 else "<d", v)
        elif bits == 16:
            data += struct.pack("<h", int(round(v * 32767)))
        elif bits == 24:
            data += (int(round(v * 8388607)) & 0xFFFFFF).to_bytes(3, "little")
        elif bits == 32:
            data += struct.pack("<i", int(round(v * 2147483647)))
        else:
            raise ValueError(bits)
    align = nch * bps
    if extensible:
        guid = struct.pack("<H", fmt) + bytes([0, 0, 0, 0, 0x10, 0, 0x80, 0, 0, 0xAA, 0, 0x38, 0x9B, 0x71])
        fmt_body = (struct.pack("<HHIIHH", 0xFFFE, nch, sr, sr * align, align, bits)
                    + struct.pack("<HHI", 22, bits, 3) + guid)
    else:
        fmt_body = struct.pack("<HHIIHH", fmt, nch, sr, sr * align, align, bits)
    chunks = b"fmt " + struct.pack("<I", len(fmt_body)) + fmt_body
    chunks += b"data" + struct.pack("<I", len(data)) + bytes(data)
    if len(data) & 1:
        chunks += b"\x00"
    path.write_bytes(b"RIFF" + struct.pack("<I", 4 + len(chunks)) + b"WAVE" + chunks)
    return path


def _ieee80_encode(x: int) -> bytes:
    e = x.bit_length() - 1
    return struct.pack(">HQ", 16383 + e, x << (63 - e))


def write_aiff(path: Path, chans, bits=16, sr=8000) -> Path:
    nch = len(chans)
    bps = bits // 8
    data = bytearray()
    for v in _interleave(chans):
        if bits == 16:
            data += struct.pack(">h", int(round(v * 32767)))
        elif bits == 24:
            data += (int(round(v * 8388607)) & 0xFFFFFF).to_bytes(3, "big")
        else:
            raise ValueError(bits)
    comm = struct.pack(">hIh", nch, len(chans[0]), bits) + _ieee80_encode(sr)
    ssnd = struct.pack(">II", 0, 0) + bytes(data)
    body = b"AIFF"
    body += b"COMM" + struct.pack(">I", len(comm)) + comm
    body += b"SSND" + struct.pack(">I", len(ssnd)) + ssnd
    if len(ssnd) & 1:
        body += b"\x00"
    path.write_bytes(b"FORM" + struct.pack(">I", len(body)) + body)
    return path


def _peak(vals):
    return max(abs(v) for v in vals)


# ---------------------------------------------------------------- decoding

def _check_shape(res, nch, frames, n):
    assert res["ch"] == nch
    assert res["frames"] == frames
    assert res["n"] == n
    assert len(res["min"]) == nch and len(res["max"]) == nch
    for c in range(nch):
        assert len(res["min"][c]) == n and len(res["max"][c]) == n
        for v in res["min"][c] + res["max"][c]:
            assert -1.0 <= v <= 1.0
        for lo, hi in zip(res["min"][c], res["max"][c]):
            assert lo <= hi


def test_wav16_stereo_peaks(server, tmp_path):
    left = _sine(100, 0.5, 8000, 4000)
    right = _sine(100, 0.25, 8000, 4000, phase=1.0)
    p = write_wav(tmp_path / "s16.wav", [left, right], bits=16)
    st, res = _get(server, f"/peaks?path={_q(p)}&n=64")
    assert st == 200
    assert res["sr"] == 8000
    _check_shape(res, 2, 4000, 64)
    assert abs(max(res["max"][0]) - 0.5) < 0.02
    assert abs(max(res["max"][1]) - 0.25) < 0.02


def test_wav24_peaks(server, tmp_path):
    chans = [_sine(100, 0.5, 8000, 4000), _sine(100, 0.5, 8000, 4000)]
    p = write_wav(tmp_path / "s24.wav", chans, bits=24)
    st, res = _get(server, f"/peaks?path={_q(p)}&n=128")
    assert st == 200
    _check_shape(res, 2, 4000, 128)
    assert abs(max(res["max"][0]) - 0.5) < 0.01
    assert abs(min(res["min"][0]) + 0.5) < 0.01


def test_wav_float32_peaks(server, tmp_path):
    chans = [[0.9 * math.sin(i / 7.0) for i in range(3000)]]
    p = write_wav(tmp_path / "f32.wav", chans, bits=32, fmt=3)
    st, res = _get(server, f"/peaks?path={_q(p)}&n=100")
    assert st == 200
    _check_shape(res, 1, 3000, 100)
    assert abs(max(res["max"][0]) - 0.9) < 0.01


def test_wav_extensible_24_peaks(server, tmp_path):
    p = write_wav(tmp_path / "ext24.wav", [_sine(200, 0.3, 8000, 2000)], bits=24, extensible=True)
    st, res = _get(server, f"/peaks?path={_q(p)}&n=50")
    assert st == 200
    _check_shape(res, 1, 2000, 50)
    assert abs(max(res["max"][0]) - 0.3) < 0.01


def test_aiff16_big_endian_peaks(server, tmp_path):
    p = write_aiff(tmp_path / "a16.aiff", [_sine(100, 0.6, 8000, 3000)], bits=16)
    st, res = _get(server, f"/peaks?path={_q(p)}&n=40")
    assert st == 200
    assert res["sr"] == 8000
    _check_shape(res, 1, 3000, 40)
    assert abs(max(res["max"][0]) - 0.6) < 0.02


def test_aiff24_peaks(server, tmp_path):
    p = write_aiff(tmp_path / "a24.aiff", [_sine(100, 0.4, 8000, 3000)], bits=24)
    st, res = _get(server, f"/peaks?path={_q(p)}&n=40")
    assert st == 200
    _check_shape(res, 1, 3000, 40)
    assert abs(max(res["max"][0]) - 0.4) < 0.01


def test_more_buckets_than_frames_gives_zero_buckets(server, tmp_path):
    p = write_wav(tmp_path / "tiny.wav", [[0.5, -0.5, 0.25]], bits=16)
    st, res = _get(server, f"/peaks?path={_q(p)}&n=16")
    assert st == 200
    _check_shape(res, 1, 3, 16)
    assert res["max"][0].count(0.0) > 0


def test_n_is_clamped(server, tmp_path):
    p = write_wav(tmp_path / "c.wav", [_sine(100, 0.5, 8000, 1000)])
    assert _get(server, f"/peaks?path={_q(p)}&n=1")[1]["n"] == 16
    assert _get(server, f"/peaks?path={_q(p)}&n=100000")[1]["n"] == 8192


# ---------------------------------------------------------------- cache

def test_cache_hit_returns_cached_json(server, tmp_path, cache_dir):
    p = write_wav(tmp_path / "cache.wav", [_sine(100, 0.5, 8000, 1000)])
    st, first = _get(server, f"/peaks?path={_q(p)}&n=32")
    assert st == 200
    files = list(cache_dir.glob("*.json"))
    assert len(files) == 1
    sentinel = dict(first, sr=12345)
    files[0].write_text(json.dumps(sentinel), encoding="utf-8")
    st, again = _get(server, f"/peaks?path={_q(p)}&n=32")
    assert st == 200
    assert again["sr"] == 12345          # served from cache, not re-decoded


def test_cache_key_changes_with_mtime(server, tmp_path, cache_dir):
    p = write_wav(tmp_path / "mt.wav", [_sine(100, 0.5, 8000, 1000)])
    _get(server, f"/peaks?path={_q(p)}&n=32")
    os.utime(p, (1_000_000_000, 1_000_000_000))
    _get(server, f"/peaks?path={_q(p)}&n=32")
    assert len(list(cache_dir.glob("*.json"))) == 2


# ---------------------------------------------------------------- errors

def test_404_for_missing_file(server, tmp_path):
    st, body = _get(server, f"/peaks?path={_q(tmp_path / 'nope.wav')}&n=32")
    assert st == 404
    assert body["ok"] is False


def test_400_without_path(server):
    st, body = _get(server, "/peaks?n=32")
    assert st == 400


def test_415_for_unknown_format_without_ffmpeg(server, tmp_path, monkeypatch):
    p = tmp_path / "x.mp3"
    p.write_bytes(b"ID3\x03\x00" + b"\x00" * 200)
    monkeypatch.setattr(H.shutil, "which", lambda *a, **k: None)
    st, body = _get(server, f"/peaks?path={_q(p)}&n=32")
    assert st == 415
    assert body["ok"] is False


def test_health(server):
    assert _get(server, "/health") == (200, {"ok": True, "version": "0.1.0"})


def test_unknown_route_404(server):
    assert _get(server, "/nope")[0] == 404


# ---------------------------------------------------------------- path translation

def test_wine_drive_z_maps_to_unix_root():
    assert H.translate_path("Z:\\home\\x\\a.wav", platform="linux") == "/home/x/a.wav"


def test_wine_drive_c_maps_into_prefix():
    assert H.translate_path("C:\\users\\willi\\a.wav", platform="linux", wineprefix="/tmp/wp") \
        == "/tmp/wp/drive_c/users/willi/a.wav"


def test_windows_paths_pass_through_on_windows():
    p = "C:\\Users\\willi\\a.wav"
    assert H.translate_path(p, platform="win32") == p


def test_unix_path_untouched_on_linux():
    assert H.translate_path("/home/x/a.wav", platform="linux") == "/home/x/a.wav"


def test_launch_cmd_windows_and_wine():
    assert H.build_launch_cmd("C:\\x\\Live.exe", "C:\\s\\a.als", platform="win32") \
        == ["C:\\x\\Live.exe", "C:\\s\\a.als"]
    assert H.build_launch_cmd("/w/Live.exe", "/home/x/a.als", platform="linux") \
        == ["wine", "/w/Live.exe", "Z:\\home\\x\\a.als"]


# ---------------------------------------------------------------- sets + launch

def test_sets_newest_first_capped(tmp_path, monkeypatch):
    root = tmp_path / "Documents" / "Ableton"
    (root / "Backup").mkdir(parents=True)
    old = root / "old.als"
    new = root / "new.als"
    skipped = root / "Backup" / "auto.als"
    for f in (old, new, skipped):
        f.write_bytes(b"x")
    os.utime(old, (1_000_000_000, 1_000_000_000))
    os.utime(new, (2_000_000_000, 2_000_000_000))
    os.utime(skipped, (3_000_000_000, 3_000_000_000))
    monkeypatch.setattr(H, "_set_roots", lambda: [root])
    sets = H.list_sets()
    assert [s["name"] for s in sets] == ["new", "old"]
    assert sets[0]["path"] == str(new)
    assert sets[0]["mtime"] == 2_000_000_000


def test_launch_404_when_live_missing(server, monkeypatch):
    monkeypatch.setattr(H, "find_live_exe", lambda *a, **k: None)
    st, body = _post(server, "/launch", b"{}")
    assert st == 404 and body["ok"] is False


def test_launch_404_for_missing_set(server, tmp_path, monkeypatch):
    monkeypatch.setattr(H, "find_live_exe", lambda *a, **k: "/fake/Live.exe")
    st, body = _post(server, "/launch", json.dumps({"set": str(tmp_path / "gone.als")}).encode())
    assert st == 404


def test_launch_400_on_bad_json(server):
    st, _ = _post(server, "/launch", b"{nope")
    assert st == 400


def test_launch_spawns_with_set(server, tmp_path, monkeypatch):
    exe = tmp_path / "Live.exe"
    exe.write_bytes(b"")
    als = tmp_path / "song.als"
    als.write_bytes(b"x")
    calls = []

    class FakeProc:
        pid = 4242

    monkeypatch.setattr(H, "find_live_exe", lambda *a, **k: str(exe))
    monkeypatch.setattr(H, "_spawn", lambda cmd, cwd: (calls.append(cmd), FakeProc())[1])
    st, body = _post(server, "/launch", json.dumps({"set": str(als)}).encode())
    assert st == 200 and body["ok"] is True and body["pid"] == 4242
    assert calls[0][0] == str(exe)
    assert calls[0][-1] == str(als) or calls[0][-1].endswith("song.als")


# ---------------------------------------------------------------- helpers

def _q(p) -> str:
    from urllib.parse import quote
    return quote(str(p), safe="")
