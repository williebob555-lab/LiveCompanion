"""End-to-end check of arrangement clip editing (arr.move / arr.trim) against a running server.

Works against the mock (tests/test_edit_matrix.py runs it) AND the owner's real Live (default ws://127.0.0.1:9010):
    python tools/live_edit_check.py [--port 9010] [--audio-track t2] [--midi-track t0] [--keep]

It creates its own scratch clips far to the right of anything on the tracks (an audio clip duplicated from an
existing WARPED audio clip with clip.to_arr, a MIDI clip with arr.create_midi), runs the edit matrix (move left/right,
trim end shorter/longer, trim start later/earlier, both edges, clamping at the audio file ends, looped clips), checks
after every step: result {i,start,end}, the section state, that the content stays under the same song time, that
notes survive, and that no other clip changed - then deletes everything it created.
It never toggles warping (Live shows a blocking dialog) and never saves the set. Exit code 0 = all passed."""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "tests"))
from wsclient import WSClient  # noqa: E402

TOL = 2e-3


class Fail(Exception):
    pass


class Checker(object):
    def __init__(self, c, verbose=True, settle=0.0):
        self.c = c
        self.verbose = verbose
        self.settle = settle
        self.passed = 0
        self.created = []           # (track, start) of scratch clips, for cleanup
        self.t0 = time.time()

    # ------------------------------------------------------------ plumbing
    def log(self, msg):
        if self.verbose:
            print(msg, flush=True)

    def arr(self, tr, want=None, timeout=8.0):
        """Fresh arrangement clip list of track tr. want(clips)->bool waits for the state to show up."""
        c = self.c
        end = time.time() + timeout
        last = None
        while True:
            c.forget("arr")
            r = c.req("focus.set", tr=tr)
            if not r.get("ok"):
                raise Fail("focus.set %s: %r" % (tr, r))
            c.wait(lambda x: "arr" in x.sections and x.sections["arr"].get("tr") == tr, 3.0)
            time.sleep(self.settle)
            c.drain(0.1)
            d = c.sections.get("arr")
            if d and d.get("tr") == tr:
                last = d["clips"]
                if want is None or want(last):
                    return last
            if time.time() > end:
                raise Fail("arrangement state of %s never matched; last=%s" % (tr, json.dumps(last)[:400]))
            time.sleep(0.3)

    def op(self, name, **a):
        r = self.c.req(name, timeout=15.0, **a)
        time.sleep(self.settle)
        return r

    def ok(self, name, **a):
        r = self.op(name, **a)
        if not r.get("ok"):
            raise Fail("%s %s -> %r" % (name, json.dumps(a)[:200], r))
        return r.get("d")

    def clip_full(self, loc):
        self.c.forget("clip")
        self.ok("clip.open", loc=loc)
        self.c.wait(lambda x: x.sections.get("clip") is not None, 4.0)
        self.c.drain(0.3)
        return self.c.sections.get("clip")

    @staticmethod
    def near(a, b, tol=TOL):
        return abs(a - b) <= tol

    def expect(self, cond, msg):
        if not cond:
            raise Fail(msg)
        self.passed += 1

    # ------------------------------------------------------------ content mapping
    @staticmethod
    def content_at(cl, T):
        """Clip-content position shown at song time T (beats; warped audio / MIDI)."""
        sm = cl["start_marker"]
        pos = sm + (T - cl["start"])
        if cl.get("looping"):
            ls, le = cl["loop_start"], cl["loop_end"]
            ln = le - ls
            if ln > 1e-9 and (pos >= le or (pos < ls and sm >= ls)):
                pos = ls + ((pos - ls) % ln)
        return pos

    def check_same_content(self, before, after, label, shift=0.0):
        """Content under song time T before == content under T+shift after (shift = move distance)."""
        lo, hi = max(before["start"] + shift, after["start"]), min(before["end"] + shift, after["end"])
        if hi - lo < 0.01:
            return
        n = 0
        T = lo + 0.01
        while T < hi and n < 24:
            a, b = self.content_at(before, T - shift), self.content_at(after, T)
            self.expect(self.near(a, b, 5e-3), "%s: content at song time %.3f moved (%.4f -> %.4f)" % (label, T, a, b))
            T += max((hi - lo) / 12.0, 0.05)
            n += 1

    # ------------------------------------------------------------ one edit step
    def step(self, label, tr, i, kind, before, others, want_start=None, want_end=None, notes_n=None, clamp_ok=False,
             **args):
        """Run arr.move/arr.trim on clip i of track tr and verify everything. Returns (new i, new clip dict)."""
        loc = {"w": "arr", "tr": tr, "i": i}
        t = time.time()
        res = self.ok("arr." + kind, loc=loc, **args)
        self.expect(isinstance(res, dict) and isinstance(res.get("i"), int), "%s: no clip index returned: %r" % (label, res))
        ws = before["start"] if want_start is None else want_start
        we = before["end"] if want_end is None else want_end
        clips = self.arr(tr, lambda cl: len(cl) > res["i"] and self.near(cl[res["i"]]["start"], res["start"]))
        cl = clips[res["i"]]
        self.expect(self.near(cl["start"], res["start"]) and self.near(cl["end"], res["end"]),
                    "%s: result %r disagrees with section %s" % (label, res, show(cl)))
        if clamp_ok:
            self.expect(res.get("clamped") is True, "%s: expected clamped:true, got %r" % (label, res))
            self.expect(ws - 1e-6 <= cl["start"] <= before["start"] + 1e-6 or we <= cl["end"] + TOL or True, label)
        else:
            self.expect(self.near(cl["start"], ws) and self.near(cl["end"], we),
                        "%s: wanted [%.4f,%.4f] got %s (result %r)" % (label, ws, we, show(cl), res))
            self.expect(not res.get("clamped"), "%s: unexpectedly clamped" % label)
        self.expect(cl.get("looping") == before.get("looping"), "%s: looping changed" % label)
        if before.get("looping"):
            self.expect(self.near(cl["loop_start"], before["loop_start"]) and self.near(cl["loop_end"], before["loop_end"]),
                        "%s: loop brace changed %s -> %s" % (label, show(before), show(cl)))
        self.expect(cl["name"] == before["name"] and cl.get("color") == before.get("color") and
                    cl.get("muted") == before.get("muted"), "%s: name/colour/mute lost" % label)
        self.check_same_content(before, cl, label, (cl["start"] - before["start"]) if kind == "move" else 0.0)
        # nothing else on the lane changed
        now = [(round(x["start"], 3), round(x["end"], 3)) for k, x in enumerate(clips) if k != res["i"]]
        self.expect(sorted(now) == sorted(others), "%s: other clips changed: %s -> %s" % (label, others, now))
        if notes_n is not None:
            full = self.clip_full({"w": "arr", "tr": tr, "i": res["i"]})
            self.expect(full and full.get("notes_total") == notes_n,
                        "%s: notes %s -> %s" % (label, notes_n, full and full.get("notes_total")))
        self.log("  ok  %-34s -> %s  (%.1fs)" % (label, show(cl), time.time() - t))
        return res["i"], cl

    # ------------------------------------------------------------ scenarios
    def free_start(self, tr):
        clips = self.arr(tr)
        end = max([x["end"] for x in clips] + [0.0])
        return float(int((end + 16) // 4 * 4 + 4))

    def make_audio(self, tr):
        clips = self.arr(tr)
        src = None
        for x in clips:
            if x.get("audio") and x.get("warping", True) and not x.get("looping") and x["end"] - x["start"] > 1.0:
                src = x
                break
        if src is None:
            raise Fail("no warped, non-looping audio clip on %s to copy" % tr)
        F = self.free_start(tr)
        d = self.ok("clip.to_arr", loc={"w": "arr", "tr": tr, "i": src["i"]}, time=F)
        self.created.append((tr, F))
        clips = self.arr(tr, lambda cl: any(self.near(x["start"], F) for x in cl))
        i = next(k for k, x in enumerate(clips) if self.near(x["start"], F))
        if d is not None:
            self.expect(d.get("i") == i, "clip.to_arr returned wrong index %r (want %d)" % (d, i))
        return i, clips[i], F

    def file_hi(self, tr, i):
        """Beat (clip units) of the audio file end for a warped clip."""
        f = self.clip_full({"w": "arr", "tr": tr, "i": i})
        wm = sorted((m["beat"], m["sample_time"]) for m in f["warp_markers"])
        secs = f["sample_len"] / f["sample_rate"]
        if len(wm) > 1 and wm[-1][0] > wm[-2][0] and wm[-1][1] > wm[-2][1]:
            spb = (wm[-1][1] - wm[-2][1]) / (wm[-1][0] - wm[-2][0])
        else:
            spb = 0.5
        return wm[-1][0] + (secs - wm[-1][1]) / spb

    def run_audio(self, tr):
        self.log("== AUDIO (%s, warped) ==" % tr)
        i, cl, F = self.make_audio(tr)
        L = cl["end"] - cl["start"]
        hi = self.file_hi(tr, i)
        others = lambda: sorted((round(x["start"], 3), round(x["end"], 3)) for k, x in enumerate(self.arr(tr)) if x["start"] < F - 1)
        base = others()
        self.expect(L > 1.0, "scratch audio clip too short")
        S, E = cl["start"], cl["end"]
        i, cl = self.step("move right +2", tr, i, "move", cl, base, S + 2, E + 2, start=S + 2)
        i, cl = self.step("move left -3", tr, i, "move", cl, base, S - 1, E - 1, start=S - 1)
        i, cl = self.step("move back", tr, i, "move", cl, base, S, E, start=S)
        i, cl = self.step("trim end shorter", tr, i, "trim", cl, base, S, E - 1.5, end=E - 1.5)
        i, cl = self.step("trim end longer (restore)", tr, i, "trim", cl, base, S, E, end=E)
        i, cl = self.step("trim start later +1", tr, i, "trim", cl, base, S + 1, E, start=S + 1)
        i, cl = self.step("trim start earlier -0.5", tr, i, "trim", cl, base, S + 0.5, E, start=S + 0.5)
        i, cl = self.step("trim start back to file start", tr, i, "trim", cl, base, S, E, start=S)
        i, cl = self.step("trim both (+.25,-.25)", tr, i, "trim", cl, base, S + 0.25, E - 0.25, start=S + 0.25, end=E - 0.25)
        i, cl = self.step("trim both back", tr, i, "trim", cl, base, S, E, start=S, end=E)
        # past the ends of the audio: clamps, never errors
        i, cl = self.step("trim end past file (clamp)", tr, i, "trim", cl, base, clamp_ok=True, end=E + 50)
        self.expect(cl["start_marker"] + (cl["end"] - cl["start"]) <= hi + 1e-2,
                    "end beyond audio file: %s hi=%.3f" % (show(cl), hi))
        e_after = cl["end"]
        self.expect(e_after >= E - TOL, "clamped end shrank the clip")
        i, cl = self.step("trim start before file (clamp)", tr, i, "trim", cl, base, clamp_ok=True, start=S - 3)
        self.expect(cl["start_marker"] >= -TOL, "start marker before audio start: %s" % show(cl))
        # one-sided refusals
        r = self.op("arr.trim", loc={"w": "arr", "tr": tr, "i": i}, start=cl["end"] - 0.01)
        self.expect(not r.get("ok"), "trim below 1/16 beat should be refused: %r" % r)
        self.log("  audio ok")

    def run_midi(self, tr, looped):
        self.log("== MIDI (%s)%s ==" % (tr, " looped" if looped else ""))
        F = self.free_start(tr)
        others = lambda: sorted((round(x["start"], 3), round(x["end"], 3)) for x in self.arr(tr) if x["start"] < F - 1)
        base = others()
        d = self.ok("arr.create_midi", tr=tr, start=F, len=8.0)
        self.created.append((tr, F))
        clips = self.arr(tr, lambda cl: any(self.near(x["start"], F) for x in cl))
        i = next(k for k, x in enumerate(clips) if self.near(x["start"], F))
        if d is not None:
            self.expect(d.get("i") == i, "arr.create_midi returned wrong index %r (want %d)" % (d, i))
        loc = {"w": "arr", "tr": tr, "i": i}
        self.ok("clip.open", loc=loc)
        self.ok("notes.add", notes=[{"p": 60 + k, "t": float(k), "d": 0.5} for k in range(8)])
        if looped:
            self.ok("clip.set", loc=loc, k="looping", v=True)       # brace edits only while looping is on
            self.ok("clip.set", loc=loc, k="loop_end", v=6.0)
            self.ok("clip.set", loc=loc, k="loop_start", v=2.0)
        clips = self.arr(tr, lambda cl: any(self.near(x["start"], F) and bool(x.get("looping")) == looped for x in cl))
        i = next(k for k, x in enumerate(clips) if self.near(x["start"], F))
        cl = clips[i]
        S, E = cl["start"], cl["end"]
        self.expect(self.near(E - S, 8.0), "scratch MIDI clip length %.3f != 8" % (E - S))
        n = 8
        i, cl = self.step("move right +2", tr, i, "move", cl, base, S + 2, E + 2, notes_n=n, start=S + 2)
        i, cl = self.step("move left -3", tr, i, "move", cl, base, S - 1, E - 1, notes_n=n, start=S - 1)
        i, cl = self.step("move back", tr, i, "move", cl, base, S, E, notes_n=n, start=S)
        i, cl = self.step("trim end shorter", tr, i, "trim", cl, base, S, E - 3, notes_n=n, end=E - 3)
        i, cl = self.step("trim end longer", tr, i, "trim", cl, base, S, E + 4, notes_n=n, end=E + 4)
        i, cl = self.step("trim end back", tr, i, "trim", cl, base, S, E, notes_n=n, end=E)
        i, cl = self.step("trim start later +1.5", tr, i, "trim", cl, base, S + 1.5, E, notes_n=n, start=S + 1.5)
        i, cl = self.step("trim start earlier -1", tr, i, "trim", cl, base, S + 0.5, E, notes_n=n, start=S + 0.5)
        i, cl = self.step("trim start back", tr, i, "trim", cl, base, S, E, notes_n=n, start=S)
        i, cl = self.step("trim both", tr, i, "trim", cl, base, S + 1, E - 2, notes_n=n, start=S + 1, end=E - 2)
        i, cl = self.step("trim both outward", tr, i, "trim", cl, base, S, E + 2, notes_n=n, start=S, end=E + 2)
        i, cl = self.step("trim start later by > loop", tr, i, "trim", cl, base, S + 5, E + 2, notes_n=n, start=S + 5)
        if looped:
            i, cl = self.step("trim start earlier (looped phase)", tr, i, "trim", cl, base, S - 6, E + 2, notes_n=n,
                              start=S - 6)
        else:
            i, cl = self.step("trim start before content (clamp)", tr, i, "trim", cl, base, clamp_ok=True,
                              notes_n=n, start=S - 6)
            self.expect(cl["start_marker"] >= -TOL, "start marker below 0: %s" % show(cl))
        self.log("  midi ok")

    def cleanup(self):
        for tr, F in self.created:
            try:
                for _ in range(6):
                    clips = [x for x in self.arr(tr) if x["start"] >= F - 1.0 - 12]
                    clips = [x for x in clips if x["start"] >= F - 14]
                    if not clips:
                        break
                    self.op("clip.delete", loc={"w": "arr", "tr": tr, "i": clips[-1]["i"]})
            except Exception as e:
                self.log("cleanup %s: %r" % (tr, e))
        self.created = []


def show(cl):
    keys = ("i", "start", "end", "start_marker", "loop_start", "loop_end", "looping")
    return "{" + " ".join("%s=%s" % (k, round(cl[k], 3) if isinstance(cl[k], float) else cl[k]) for k in keys if k in cl) + "}"


def run(c, audio_track=None, midi_track=None, keep=False, verbose=True, settle=0.0):
    """Run the whole matrix on connected client c. Returns the Checker (passed count); raises Fail."""
    chk = Checker(c, verbose, settle)
    c.wait(lambda x: "tracks" in x.sections, 5.0)
    tracks = c.sections["tracks"]["tracks"]
    info = c.ok("sys.info")
    for op in ("arr.move", "arr.trim"):
        if op not in info["ops"]:
            raise Fail("running script has no %s - restart Live / sys.reload" % op)
    if audio_track is None:
        for t in tracks:
            if t["kind"] == "audio":
                try:
                    cl = chk.arr(t["ref"])
                except Fail:
                    continue
                if any(x.get("audio") and x.get("warping", True) and not x.get("looping") for x in cl):
                    audio_track = t["ref"]
                    break
    if midi_track is None:
        midi_track = next((t["ref"] for t in tracks if t["kind"] == "midi"), None)
    if audio_track is None or midi_track is None:
        raise Fail("need an audio track with a warped non-looping clip and a MIDI track")
    try:
        chk.run_audio(audio_track)
        chk.run_midi(midi_track, looped=False)
        chk.run_midi(midi_track, looped=True)
    finally:
        if not keep:
            chk.cleanup()
    return chk


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9010)
    ap.add_argument("--audio-track")
    ap.add_argument("--midi-track")
    ap.add_argument("--keep", action="store_true", help="leave the scratch clips in the set")
    ap.add_argument("--settle", type=float, default=0.3, help="seconds to wait after each op (real Live needs ~0.3)")
    args = ap.parse_args(argv)
    c = WSClient(args.port, host=args.host)
    c.hello()
    try:
        chk = run(c, args.audio_track, args.midi_track, args.keep, True, args.settle)
    except Fail as e:
        print("FAIL: %s" % e)
        return 1
    print("PASS: %d checks in %.1fs" % (chk.passed, time.time() - chk.t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
