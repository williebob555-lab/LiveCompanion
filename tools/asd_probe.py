"""Reverse-engineering / verification probe for Ableton .asd waveform overviews.

Usage:  python tools/asd_probe.py [root_dir ...]       (default: ~/Documents/Ableton/Live Recordings)
        python tools/asd_probe.py --dump file.wav.asd  (levels + header fields of one file)
For every readable <x>.wav + <x>.wav.asd pair it checks the layout documented in docs/LOM_NOTES.md:
  * header: last seek-table entry == wav frames; u32 at table_end+12 == 2*channels
  * level 0..2 = float16 (min,max) bins per channel at 128 / 16384 / 2097152 frames per bin
  * accuracy of core/asd.py overview() vs the true min/max computed from the wav (core/peaks.py)
Read-only: never writes to the audio / .asd files."""
from __future__ import annotations

import glob
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "remote_script"))
from LiveCompanion.core import asd, peaks  # noqa: E402


def wav_header(path):
    with open(path, "rb") as f:
        size = os.path.getsize(path)
        if f.read(4) != b"RIFF":
            return None
        f.seek(0)
        f.read(12)
        return peaks._parse_wav(f, size)


def compare(wav, n):
    t = peaks.decode_peaks(wav, n)
    with open(wav + ".asd", "rb") as f:
        a = asd.overview(f.read(), n)
    errs = []
    for key in ("min", "max"):
        for c in range(min(len(t[key]), len(a[key]))):
            errs += [abs(x - y) for x, y in zip(t[key][c], a[key][c])]
    return max(errs), sum(errs) / len(errs)


def dump(path):
    d = open(path, "rb").read()
    n = struct.unpack_from("<I", d, 2)[0]
    print("size", len(d), "table entries", n, "last entry (frames)", struct.unpack_from("<I", d, 6 + 4 * (n - 1))[0])
    p = asd.parse(d)
    print("channels field ->", p["ch"])
    for k, (fpb, cnt, raw) in enumerate(p["levels"]):
        h = struct.unpack("<%de" % min(cnt, 8), raw[:16])
        print("level", k, "frames/bin", fpb, "halves", cnt, "first", [round(x, 3) for x in h])


def main(argv):
    if argv and argv[0] == "--dump":
        return dump(argv[1])
    roots = argv or [os.path.expanduser("~/Documents/Ableton/Live Recordings")]
    pairs = []
    for r in roots:
        pairs += [a[:-4] for a in glob.glob(os.path.join(r, "**", "*.wav.asd"), recursive=True)]
    ok = bad = locked = 0
    worst = 0.0
    avgs = []
    for w in pairs:
        try:
            h = wav_header(w)
            if h is None or h.frames == 0:
                continue
            d = open(w + ".asd", "rb").read()
            p = asd.parse(d)
        except (OSError, peaks.PeaksError, peaks.UnsupportedFormat, asd.AsdError, struct.error):
            locked += 1
            continue
        hdr_ok = p["frames"] == h.frames and p["ch"] == h.nch
        lvl0 = p["levels"][0]
        bins_ok = lvl0[1] == ((h.frames + 127) // 128) * 2 * h.nch
        try:
            mx, avg = compare(w, 512)
        except OSError:
            locked += 1
            continue
        worst = max(worst, mx)
        avgs.append(avg)
        if hdr_ok and bins_ok:
            ok += 1
        else:
            bad += 1
            print("MISMATCH", w, "frames", p["frames"], h.frames, "ch", p["ch"], h.nch, "bins", lvl0[1])
    print("pairs ok=%d mismatch=%d unreadable=%d | n=512 max abs err %.4f, mean abs err %.5f" % (
        ok, bad, locked, worst, (sum(avgs) / len(avgs)) if avgs else 0))


if __name__ == "__main__":
    main(sys.argv[1:])
