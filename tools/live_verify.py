"""Scripted verification of LiveCompanion against REAL Ableton Live.

Run with Live open on a NEW, EMPTY set (File > New Live Set) and the LiveCompanion control surface active:
    python tools/live_verify.py            # read-only checks only
    python tools/live_verify.py --mutate   # also exercises editing ops (refuses unless the set looks empty)

Writes a markdown report to qa/live_verify_<timestamp>.md. Never saves the set. Afterwards just close the set
without saving (or press Undo repeatedly).
"""
import argparse
import datetime
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tests"))
from wsclient import WSClient  # noqa: E402

ROWS = []


def step(c, label, op, check=None, **a):
    t0 = time.time()
    try:
        r = c.req(op, timeout=8.0, **a)
    except Exception as e:  # timeout etc.
        ROWS.append((label, op, "TIMEOUT", str(e)[:120]))
        return None
    ms = int((time.time() - t0) * 1000)
    if not r.get("ok"):
        ROWS.append((label, op, "ERR", "%s (%d ms)" % (r.get("err"), ms)))
        return None
    note = ""
    if check:
        try:
            c.drain(0.6)
            res = check()
            note = "verified" if res is True else ("NOT applied: %s" % res if res else "NOT applied")
        except Exception as e:
            note = "check failed: %s" % str(e)[:100]
    ROWS.append((label, op, "ok", ("%s (%d ms)" % (note, ms)).strip()))
    return r.get("d")


def sec(c, name):
    c.pump(0.3)
    return c.sections.get(name) or {}


def looks_empty(c):
    tr = sec(c, "tracks").get("tracks", [])
    if len(tr) > 8:
        return False, "%d tracks" % len(tr)
    for i in range(len(tr)):
        c.forget("slots", "arr")
        c.req("focus.set", tr="t%d" % i)
        try:
            slots = c.until("slots", timeout=4)
            arr = c.until("arr", timeout=4)
        except AssertionError:
            continue
        if any(s.get("has_clip") for s in slots.get("slots", [])) or arr.get("clips"):
            return False, "track %d has clips" % i
    return True, ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9010)
    ap.add_argument("--mutate", action="store_true")
    ap.add_argument("--mock-ok", action="store_true", help="skip the empty-set gate when talking to the mock (self-test)")
    args = ap.parse_args()
    c = WSClient(args.port, timeout=8.0)
    hello = c.hello()
    c.drain(1.0)
    c.pump(1.0)
    info = step(c, "sys.info", "sys.info")
    for name in ("session", "tracks"):
        ROWS.append(("section " + name, "-", "ok" if sec(c, name) else "MISSING", ""))
    tracks = sec(c, "tracks").get("tracks", [])
    for i in range(min(3, len(tracks))):
        step(c, "focus t%d" % i, "focus.set", tr="t%d" % i)
        for s in ("focus", "slots", "arr", "takes", "devices"):
            ROWS.append(("  section %s (t%d)" % (s, i), "-", "ok" if sec(c, s) else "MISSING", ""))
    step(c, "browser roots", "browser.list", path=[])
    # Waveform without the helper: first audio clip found in the first tracks' arrangements (read-only).
    for i in range(min(8, len(tracks))):
        c.forget("arr")
        c.req("focus.set", tr="t%d" % i)
        try:
            arr = c.until("arr", timeout=4)
        except AssertionError:
            continue
        audio = [k for k in arr.get("clips", []) if k.get("audio")]
        if audio:
            d = step(c, "clip.peaks (no helper) t%d" % i, "clip.peaks", loc={"w": "arr", "tr": "t%d" % i, "i": audio[0]["i"]}, n=512)
            if d:
                ROWS.append(("  peaks shape", "-", "ok", "ch=%s n=%s" % (d.get("ch"), d.get("n"))))
            break

    peaks_all(c, tracks)

    if args.mutate:
        if args.mock_ok and hello.get("host") == "mock":
            run_mutations(c)
            write_report(hello, info)
            c.close()
            return
        empty, why = looks_empty(c)
        if not empty:
            ROWS.append(("MUTATION GATE", "-", "REFUSED", "set not empty: " + why))
        else:
            run_mutations(c)
    write_report(hello, info)
    c.close()


def peaks_all(c, tracks):
    """READ-ONLY: clip.peaks for every audio clip (session slots + arrangement) of the first tracks; reports src/approx/peak."""
    for i in range(min(16, len(tracks))):
        tr = "t%d" % i
        c.forget("slots", "arr")
        c.req("focus.set", tr=tr)
        try:
            slots = c.until("slots", timeout=4)
            arr = c.until("arr", timeout=4)
        except AssertionError:
            continue
        locs = [("slot %d" % s.get("i", k), {"w": "slot", "tr": tr, "i": s.get("i", k)})
                for k, s in enumerate(slots.get("slots", [])) if s.get("has_clip") and (s.get("clip") or {}).get("audio")]
        locs += [("arr %d" % k["i"], {"w": "arr", "tr": tr, "i": k["i"]}) for k in arr.get("clips", []) if k.get("audio")]
        for name, loc in locs:
            try:
                r = c.req("clip.peaks", timeout=20.0, loc=loc, n=200)
            except Exception as e:
                ROWS.append(("peaks %s %s" % (tr, name), "clip.peaks", "TIMEOUT", str(e)[:100]))
                continue
            if not r.get("ok"):
                ROWS.append(("peaks %s %s" % (tr, name), "clip.peaks", "ERR", str(r.get("err"))))
                continue
            d = r.get("d") or {}
            pk = max([abs(x) for ch in (d.get("max") or []) for x in ch] + [abs(x) for ch in (d.get("min") or []) for x in ch] + [0.0])
            ROWS.append(("peaks %s %s" % (tr, name), "clip.peaks", "ok",
                         "src=%s approx=%s n=%s peak=%.3f" % (d.get("src", "wav"), bool(d.get("approx")), d.get("n"), pk)))


def run_mutations(c):
    step(c, "create midi track", "track.create", kind="midi", i=0)
    c.drain(0.5)
    step(c, "focus new track", "focus.set", tr="t0")
    step(c, "slot.create 4 beats", "slot.create", tr="t0", i=0, len=4.0,
         check=lambda: c.until("slots", lambda d: d["slots"][0]["has_clip"], 4) and True)
    loc = {"w": "slot", "tr": "t0", "i": 0}
    step(c, "clip.open", "clip.open", loc=loc, check=lambda: bool(c.until("clip", lambda d: d, 4)))
    step(c, "notes.add", "notes.add", notes=[{"p": 60, "t": 0.0, "d": 1.0, "v": 100}, {"p": 64, "t": 1.0, "d": 0.5, "v": 90}],
         check=lambda: len(c.until("clip", lambda d: d and len(d.get("notes", [])) >= 2, 4)["notes"]) >= 2)
    step(c, "clip.set loop_end", "clip.set", loc=loc, k="loop_end", v=8.0,
         check=lambda: abs(c.until("clip", lambda d: d and d.get("loop_end") == 8.0, 4)["loop_end"] - 8.0) < 1e-6)
    step(c, "clip.set sig 3/4", "clip.set", loc=loc, k="sig_num", v=3)
    step(c, "slot.duplicate", "slot.duplicate", tr="t0", i=0)
    step(c, "slot.move onto occupied (expect refusal)", "slot.move", tr="t0", i=0, to_tr="t0", to_i=1)
    step(c, "slot.move swap", "slot.move", tr="t0", i=0, to_tr="t0", to_i=1, swap=True)
    step(c, "arr.create_midi", "arr.create_midi", tr="t0", start=0.0, len=4.0,
         check=lambda: bool(c.until("arr", lambda d: d.get("clips"), 4)["clips"]))
    aloc = {"w": "arr", "tr": "t0", "i": 0}
    step(c, "arr.move to beat 8", "arr.move", loc=aloc, start=8.0,
         check=lambda: c.until("arr", lambda d: any(abs(x["start"] - 8.0) < 1e-3 for x in d.get("clips", [])), 4) and True)
    step(c, "arr.trim end", "arr.trim", loc=aloc, end=10.0)
    step(c, "clip.to_arr", "clip.to_arr", loc=loc, time=16.0)
    step(c, "takes.create_lane", "takes.create_lane", tr="t0")
    for name in ("Auto Filter", "EQ Eight", "Reverb"):
        step(c, "device.insert " + name, "device.insert", tr="t0", name=name,
             check=lambda n=name: any(d["name"] == n for d in c.until("devices", lambda d: d.get("devices"), 3)["devices"]) or "not in chain")
    step(c, "device.move 0 -> 2", "device.move", tr="t0", path="0", to=2)
    step(c, "device.duplicate", "device.duplicate", tr="t0", path="0")
    step(c, "device.param", "device.param", tr="t0", path="0", i=1, v=0.5)
    # track.move (rebuild-move): the MIDI track made above has devices, a clip and a name - they must survive the move.
    pre = sec(c, "tracks") or {}
    n_tr = len(pre.get("tracks", []))
    if n_tr >= 2:
        before_dev = [d["name"] for d in (sec(c, "devices") or {}).get("devices", [])]
        step(c, "track.move_check", "track.move_check", tr="t0")
        step(c, "track.move t0 -> 1", "track.move", tr="t0", to=1, force=True)
        c.drain(1.0)
        step(c, "focus moved track", "focus.set", tr="t1")
        after = c.until("devices", lambda d: d.get("tr") == "t1", 4) or {}
        after_dev = [d["name"] for d in after.get("devices", [])]
        slots = c.until("slots", lambda d: d.get("tr") == "t1", 4) or {}
        ok = (after_dev == before_dev and slots.get("slots") and slots["slots"][0].get("has_clip"))
        print("  track.move survived (devices %s -> %s, clip in slot 0: %s) -> %s" % (
            before_dev, after_dev, bool(slots.get("slots") and slots["slots"][0].get("has_clip")), "OK" if ok else "CHECK MANUALLY"))
        step(c, "track.move back t1 -> 0", "track.move", tr="t1", to=0, force=True)
        c.drain(1.0)
        step(c, "focus t0 again", "focus.set", tr="t0")
    step(c, "song.scrub", "song.scrub", beats=1.0)
    step(c, "song.seek", "song.seek", beats=4.0)
    step(c, "song.set tempo", "song.set", k="tempo", v=97.0)
    step(c, "undo", "song.undo")
    step(c, "redo", "song.redo")
    step(c, "take.next (records!)", "take.next", tr="t0")
    time.sleep(2.0)
    step(c, "song.stop", "song.stop")


def write_report(hello, info):
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    path = os.path.join(HERE, "..", "qa", "live_verify_%s.md" % ts)
    lines = ["# Live verify %s" % ts, "", "hello: `%s`" % hello, "", "sys.info: `%s`" % (str(info)[:400]), "",
             "| step | op | result | detail |", "|---|---|---|---|"]
    lines += ["| %s | %s | %s | %s |" % r for r in ROWS]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    for r in ROWS:
        print("%-44s %-18s %-8s %s" % r)
    print("report:", os.path.normpath(path))


if __name__ == "__main__":
    main()
