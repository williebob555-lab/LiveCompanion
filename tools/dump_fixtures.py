"""Dump real server messages from the in-process mock Core to android/app/src/test/resources/fixtures/*.json.

The Kotlin ContractTest decodes every fixture with the app's real Json config + models. Re-run this whenever
the protocol / serializers change:  python tools/dump_fixtures.py
Each file is the raw server message (`{"t":"sec"|"rt"|"res"|"hello", ...}`).
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for p in ("remote_script", "mock", "tests"):
    sys.path.insert(0, os.path.join(ROOT, p))

from conftest import Harness  # noqa: E402

OUT = os.path.join(ROOT, "android", "app", "src", "test", "resources", "fixtures")


def dump(out_dir=OUT):
    os.makedirs(out_dir, exist_ok=True)
    for f in os.listdir(out_dir):
        if f.endswith(".json"):
            os.remove(os.path.join(out_dir, f))
    written = []

    def save(name, msg):
        with open(os.path.join(out_dir, name + ".json"), "w", encoding="utf-8") as fh:
            json.dump(msg, fh, indent=1, sort_keys=True)
        written.append(name)

    h = Harness()
    try:
        c = h.client()
        hello = None
        for m in c.msgs:
            if m.get("t") == "hello":
                hello = m
        c.wait(lambda x: "tracks" in x.sections and "session" in x.sections)

        def last_sec(name):
            for m in reversed(c.msgs):
                if m.get("t") == "sec" and m.get("s") == name:
                    return m
            return {"t": "sec", "s": name, "d": c.sections[name]}

        def settle():
            c.drain(0.3)

        # hello arrived through take() in Harness.client(); synthesize from sys.info if we lost it
        info = c.ok("sys.info")
        save("hello", {"t": "hello", "proto": info["proto"], "live": info["live"], "host": "mock"})
        c.pump()
        for name in ("session", "tracks"):
            save("sec_" + name, {"t": "sec", "s": name, "d": c.sections[name]})

        def focus(tr, tag):
            c.forget("focus", "slots", "arr", "takes", "devices")
            c.ok("focus.set", tr=tr)
            c.wait(lambda x: all(k in x.sections for k in ("focus", "slots", "arr", "takes", "devices")))
            settle()
            for name in ("focus", "slots", "arr", "takes", "devices"):
                save("sec_%s_%s" % (name, tag), {"t": "sec", "s": name, "d": c.sections[name]})

        focus("t0", "midi")          # midi track with rack device
        focus("t3", "audio")         # audio track with take lanes
        focus("t5", "group")
        focus("t4", "audio2")
        focus("t2", "midi_rack")     # instrument rack with macros + variations
        c.ok("focus.set", tr="r0")
        c.wait(lambda x: x.sections.get("focus", {}).get("ref") == "r0")
        save("sec_focus_return", {"t": "sec", "s": "focus", "d": c.sections["focus"]})
        c.ok("focus.set", tr="m")
        c.wait(lambda x: x.sections.get("focus", {}).get("ref") == "m")
        save("sec_focus_master", {"t": "sec", "s": "focus", "d": c.sections["focus"]})

        c.ok("focus.set", tr="t3")
        c.wait(lambda x: x.sections.get("takes", {}).get("lanes"))
        settle()
        arr_audio = c.sections["arr"]
        takes = c.sections["takes"]

        def open_clip(loc, tag):
            c.forget("clip")
            c.ok("clip.open", loc=loc)
            c.wait(lambda x: x.sections.get("clip"))
            settle()
            save("sec_clip_" + tag, {"t": "sec", "s": "clip", "d": c.sections["clip"]})

        open_clip({"w": "slot", "tr": "t3", "i": 0}, "audio_slot")
        if arr_audio["clips"]:
            open_clip({"w": "arr", "tr": "t3", "i": 0}, "audio_arr")
        for li, lane in enumerate(takes["lanes"]):
            if lane["clips"]:
                open_clip({"w": "take", "tr": "t3", "lane": li, "i": 0}, "audio_take")
                break
        c.ok("focus.set", tr="t0")
        c.wait(lambda x: x.sections.get("focus", {}).get("ref") == "t0")
        open_clip({"w": "slot", "tr": "t0", "i": 0}, "midi_slot")
        if c.sections["arr"]["clips"]:
            open_clip({"w": "arr", "tr": "t0", "i": 0}, "midi_arr")

        c.forget("params")
        c.ok("device.open", tr="t0", path="0")
        c.wait(lambda x: x.sections.get("params"))
        save("sec_params_rack", {"t": "sec", "s": "params", "d": c.sections["params"]})

        c.ok("song.play")
        c.ok("scene.fire", i=0)
        c.msgs = []
        c.wait(lambda x: any(m.get("t") == "rt" and m["d"].get("playing") and m["d"].get("slot_pos") for m in x.msgs))
        rts = [m for m in c.msgs if m.get("t") == "rt" and m["d"].get("playing")]
        save("rt", rts[-1])
        with_slots = [m for m in rts if m["d"].get("slot_pos")]
        if with_slots:
            save("rt_slots", with_slots[-1])
        c.ok("song.stop")

        roots = c.req("browser.list", path=[])
        save("res_browser_roots", roots)
        d = roots["d"]
        folder = next(x for x in d if x.get("folder"))
        sub = c.req("browser.list", path=[folder.get("key") or folder["name"]])
        save("res_browser_sub", sub)
        # a deeper level, if there is one
        for x in sub["d"]:
            if x.get("folder"):
                deeper = c.req("browser.list", path=[folder.get("key") or folder["name"], x["name"]])
                save("res_browser_deep", deeper)
                break
        save("res_sys_info", c.req("sys.info"))
    finally:
        h.close()
    return written


if __name__ == "__main__":
    names = dump()
    print("wrote %d fixtures to %s" % (len(names), OUT))
