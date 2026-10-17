"""Meter-based envelope of the clip(s) being recorded on the focused track(s).

Live holds fresh recordings under an exclusive lock (and the .asd overview may not exist yet), so while a clip
records we sample the track's meters once per tick (cheap property reads, main thread) into a coarse peak
envelope on a fixed song-time grid (STEP beats per point, absolute index = floor(song_time / STEP)).
  * rt frames carry the newest points (`rec_env`) so the phone can draw the growing red region with a shape;
  * after recording it is the approximate `clip.peaks` fallback (flagged "approx") if the file stays locked."""
from __future__ import annotations

import math

from .util import g, num, slots_of, resolve_track

STEP = 0.0625          # beats per envelope point (1/16 beat: smooth; the app interpolates between points)
RT_POINTS = 160        # newest points sent per rt frame (~10 beats)
MAX_POINTS = 64000     # per envelope (~8.9 h at 120 bpm); recording past it stops growing the envelope
MAX_ENVS = 24


def _level(track):
    v = 0.0
    for name in ("output_meter_left", "output_meter_right", "input_meter_left", "input_meter_right"):
        x = num(g(track, name, 0.0))
        if x > v:
            v = x
    return min(1.0, v)


class Env(object):
    __slots__ = ("tr", "slot", "first", "vals", "last", "active", "level")

    def __init__(self, tr, slot, first):
        self.tr, self.slot, self.first = tr, slot, first
        self.vals = []          # vals[k] = peak (0..1) at index first + k
        self.last = first - 1
        self.level = 0.0        # level of the newest tick (start of the ramp towards the next one)
        self.active = True

    def add(self, idx, v):
        if idx < self.first:
            return
        if idx - self.first >= MAX_POINTS:
            return
        k = idx - self.first
        if k >= len(self.vals):
            fill = max(0.0, v)
            self.vals.extend([fill] * (k + 1 - len(self.vals)))     # carry the new level over skipped steps
        elif v > self.vals[k]:
            self.vals[k] = v
        self.last = max(self.last, idx)


    def vals_set(self, idx, v):
        """Write a ramp point (overwrites: skipped points have no data of their own)."""
        k = idx - self.first
        if k < 0 or k >= MAX_POINTS:
            return
        if k >= len(self.vals):
            self.vals.extend([0.0] * (k + 1 - len(self.vals)))
        self.vals[k] = max(0.0, v)
        self.last = max(self.last, idx)


class RecEnv(object):
    def __init__(self):
        self.envs = {}          # (tr, slot|"arr") -> Env

    # ------------------------------------------------------------ sampling (main thread, once per tick)
    def sample(self, song, refs):
        """refs: track refs focused by clients. Cheap: reads is_recording per slot + 4 meters per recording track."""
        pos = num(g(song, "current_song_time", 0.0))
        idx = int(math.floor(pos / STEP))
        for tr in refs:
            if not tr or tr[0] != "t":
                continue
            try:
                t = resolve_track(song, tr)
            except Exception:
                continue
            rec_slots = []
            for i, s in enumerate(slots_of(t)):
                if g(s, "has_clip", False):
                    c = g(s, "clip", None)
                    if c is not None and g(c, "is_recording", False):
                        rec_slots.append(i)
                elif g(s, "is_recording", False):
                    rec_slots.append(i)
            arr = bool(g(song, "record_mode", False)) and bool(g(t, "arm", False)) and bool(g(song, "is_playing", False))
            keys = [(tr, i) for i in rec_slots] + ([(tr, "arr")] if arr else [])
            live = set(keys)
            for k, e in self.envs.items():
                if k[0] == tr and e.active and k not in live:
                    e.active = False
            if not keys:
                continue
            v = _level(t)    # sampled EVERY tick; points between two ticks ramp linearly (no stair steps)
            for k in keys:
                e = self.envs.get(k)
                if e is None or not e.active:
                    e = Env(tr, k[1], idx)
                    self.envs.pop(k, None)
                    self.envs[k] = e
                    while len(self.envs) > MAX_ENVS:
                        self.envs.pop(next(iter(self.envs)))
                if e.last >= e.first and idx - e.last > 1:
                    # grid points skipped since the previous tick: ramp from the previous level to the new one
                    last0, span = e.last, idx - e.last
                    for j in range(last0 + 1, idx):
                        e.vals_set(j, e.level + (v - e.level) * (j - last0) / float(span))
                e.add(idx, v)
                e.level = v

    def forget_track(self, tr):
        for k in [k for k in self.envs if k[0] == tr]:
            del self.envs[k]

    # ------------------------------------------------------------ rt payload
    def rt_payload(self, tr):
        """{"k": "s3"|"arr", "step", "f": index of the first point, "i": index of v[0], "v": [0..100 ints]} for the active recording, else None."""
        if not tr:
            return None
        best = None
        for k, e in self.envs.items():
            if k[0] == tr and e.active and e.vals:
                best = (k, e)
        if best is None:
            return None
        k, e = best
        tail = e.vals[-RT_POINTS:]
        return {"k": ("arr" if k[1] == "arr" else "s%d" % k[1]), "step": STEP,
                "f": e.first, "i": e.first + len(e.vals) - len(tail), "v": [int(round(x * 100)) for x in tail]}

    # ------------------------------------------------------------ peaks fallback
    def approx_peaks(self, ek, n):
        """Coarse clip.peaks dict (flagged approx) for a finished recording, or None.
        ek: {"w":"slot"|"arr","tr","i","t0","t1","secs"} describing the clip."""
        e = None
        if ek.get("w") == "slot":
            e = self.envs.get((ek.get("tr"), ek.get("i")))
        elif ek.get("w") == "arr":
            t0, t1 = ek.get("t0"), ek.get("t1")
            for (tr, kind), cand in self.envs.items():
                if tr == ek.get("tr") and kind == "arr" and t0 is not None and t1 is not None:
                    a, b = cand.first * STEP, (cand.last + 1) * STEP
                    if a < t1 and b > t0:
                        e = cand
        if e is None or not e.vals:
            return None
        vals = e.vals
        if ek.get("w") == "arr":      # keep only the part under the clip
            lo = max(0, int((ek["t0"] - e.first * STEP) / STEP))
            hi = min(len(vals), max(lo + 1, int(math.ceil((ek["t1"] - e.first * STEP) / STEP))))
            vals = vals[lo:hi]
            if not vals:
                return None
        m = len(vals)
        mx = []
        for k in range(n):
            a = k * m // n
            b = max(a + 1, (k + 1) * m // n)
            mx.append(round(max(vals[a:min(b, m)] or [0.0]), 3))
        secs = max(0.001, float(ek.get("secs") or (m * STEP * 0.5)))
        return {"sr": 1000, "ch": 1, "frames": int(secs * 1000), "n": n,
                "min": [[(-x if x else 0.0) for x in mx]], "max": [mx], "approx": True}
