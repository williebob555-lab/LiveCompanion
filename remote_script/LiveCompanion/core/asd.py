"""Ableton analysis-file (<audio>.asd) waveform overview reader. Stdlib only, pure file I/O (worker-thread safe).

Why: Live keeps freshly recorded .wav files of the OPEN set exclusively locked (nobody can read them), but the
"<file>.wav.asd" next to them is readable and contains a multi-resolution min/max overview.
Format (reverse-engineered, see docs/LOM_NOTES.md ".asd overview format"):
  u8 0x06, u8 0x49, u32 n, then n u32 frame positions (last == total frames), 4 unknown bytes, u32 0, u32 100,
  u32 2*channels ... a typed object stream ... and finally 3 records
      0x13 "SampleOverViewLevel"  u32 level  u32 count  count x float16 (little endian)
  level 0 = 128 frames per bin, level 1 = 16384, level 2 = 2097152. Per bin and channel: (min, max) interleaved
  as [ch0 min, ch0 max, ch1 min, ch1 max ...]."""
from __future__ import annotations

import struct

_MARK = b"\x13SampleOverViewLevel"
LEVEL0_LOG2 = 7            # frames per bin of level 0 (128); each further level is 2^7 coarser


class AsdError(Exception):
    pass


def parse(data: bytes) -> dict:
    """-> {frames, ch, levels:[(frames_per_bin, bins, halves_bytes)]}; raises AsdError."""
    if len(data) < 64 or data[0] != 0x06 or data[1] != 0x49:
        raise AsdError("not an Ableton .asd analysis file")
    n = struct.unpack_from("<I", data, 2)[0]
    if n < 1 or 6 + 4 * n + 16 > len(data):
        raise AsdError("bad .asd seek table")
    frames = struct.unpack_from("<I", data, 6 + 4 * (n - 1))[0]
    ch = 0
    cf = 6 + 4 * (n + 1) + 8
    if cf + 4 <= len(data):
        v = struct.unpack_from("<I", data, cf)[0]
        if v in (2, 4, 6, 8):
            ch = v // 2
    recs = []
    pos = 0
    while True:
        i = data.find(_MARK, pos)
        if i < 0:
            break
        p = i + len(_MARK)
        pos = p
        if p + 8 > len(data):
            break
        idx, cnt = struct.unpack_from("<II", data, p)
        if idx < 8 and 0 < cnt <= (len(data) - p - 8) // 2:
            recs.append((idx, cnt, p + 8))
    # skip schema mentions of the type name; real data starts at the first level-0 record
    while recs and recs[0][0] != 0:
        recs.pop(0)
    levels = []
    for k, (idx, cnt, off) in enumerate(recs):
        if idx != k:
            break
        levels.append((1 << (LEVEL0_LOG2 * (k + 1)), cnt, data[off:off + cnt * 2]))
    if not levels:
        raise AsdError(".asd has no overview levels")
    return {"frames": frames, "ch": ch, "levels": levels}


def overview(data: bytes, n: int, ch_hint: int = 0, sr: int = 0) -> dict:
    """Peaks dict like peaks.decode_peaks: {sr,ch,frames,n,min:[[..]],max:[[..]]} (-1..1)."""
    a = parse(data)
    ch = a["ch"] or ch_hint or 1
    frames = a["frames"]
    # coarsest level that still has >= n bins (fallback: finest)
    chosen = a["levels"][0]
    for lv in a["levels"]:
        if lv[1] // (2 * ch) >= n:
            chosen = lv
    fpb, cnt, raw = chosen
    nb = cnt // (2 * ch)
    if nb < 1:
        raise AsdError(".asd overview is empty")
    vals = struct.unpack("<%de" % (nb * 2 * ch), raw[: nb * 2 * ch * 2])
    mins = [[0.0] * n for _ in range(ch)]
    maxs = [[0.0] * n for _ in range(ch)]
    for k in range(n):
        lo = k * nb // n
        hi = max(lo + 1, (k + 1) * nb // n)
        for c in range(ch):
            b0 = 2 * c
            mn = min(vals[(j * ch * 2) + b0] for j in range(lo, min(hi, nb)))
            mx = max(vals[(j * ch * 2) + b0 + 1] for j in range(lo, min(hi, nb)))
            mins[c][k] = round(max(-1.0, min(1.0, mn)), 4)
            maxs[c][k] = round(max(-1.0, min(1.0, mx)), 4)
    return {"sr": sr or 0, "ch": ch, "frames": frames or nb * (1 << LEVEL0_LOG2), "n": n,
            "min": mins, "max": maxs}


def read_overview(wav_path: str, n: int, ch_hint: int = 0, sr: int = 0) -> dict:
    """Read '<wav_path>.asd' (OSError if missing/locked) and build the peaks dict."""
    with open(wav_path + ".asd", "rb") as f:
        return overview(f.read(), n, ch_hint, sr)
