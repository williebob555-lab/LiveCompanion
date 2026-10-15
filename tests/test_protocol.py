import json
import socket
import time

from conftest import Harness
from wsclient import WSClient


def test_hello(h):
    c = h.client(hello=False)
    m = c.hello()
    assert m == {"t": "hello", "proto": 1, "live": "12.4.1", "host": "mock"}


def test_global_sections_on_connect(h):
    c = h.client()
    assert c.wait(lambda x: "session" in x.sections and "tracks" in x.sections)
    s = c.sections["session"]
    for k in ("tempo", "sig", "playing", "pos", "rec", "session_rec", "overdub", "punch_in", "punch_out", "loop",
              "loop_start", "loop_len", "metronome", "count_in", "quant", "rec_quant", "can_undo", "can_redo",
              "back_to_arr", "auto_arm", "view", "sel_track", "sel_scene", "song_len", "follow"):
        assert k in s, k
    assert s["tempo"] == 120.0 and s["sig"] == [4, 4] and s["view"] == "session" and s["sel_track"] == "t0"
    t = c.sections["tracks"]
    assert len(t["tracks"]) == 8 and len(t["returns"]) == 2 and t["master"]["kind"] == "master"
    assert len(t["scenes"]) == 8 and t["scenes"][3]["name"] == "Chorus"
    kinds = [x["kind"] for x in t["tracks"]]
    assert kinds == ["midi", "midi", "midi", "audio", "audio", "group", "audio", "midi"]
    assert t["tracks"][6]["group"] == "t5" and t["tracks"][7]["group"] == "t5" and t["tracks"][0]["group"] is None
    assert t["tracks"][5]["foldable"] is True and t["returns"][0]["kind"] == "return"
    assert t["tracks"][0]["armable"] and not t["returns"][0]["armable"]
    # no focus-bound sections before focus.set
    c.pump()
    assert not any(k in c.sections for k in ("focus", "slots", "arr", "takes", "devices", "clip", "params"))


def test_focus_set_sends_sections(c):
    c.ok("focus.set", tr="t3")
    f = c.until("focus", lambda d: d["ref"] == "t3")
    assert f["name"] == "Vocals" and f["kind"] == "audio" and f["vol"]["max"] == 1.0
    assert len(f["sends"]) == 2 and f["sends"][0]["name"] == "A Reverb"
    assert "Ext. In" in f["in_types"] and f["out_type"] == "Master"
    sl = c.until("slots", lambda d: d["tr"] == "t3")
    assert len(sl["slots"]) == 8 and sl["slots"][0]["clip"]["audio"] is True and sl["slots"][1]["status"] == "empty"
    arr = c.until("arr", lambda d: d["tr"] == "t3")
    assert [x["name"] for x in arr["clips"]] == ["Vox Main", "Vox Main 2"]
    tk = c.until("takes", lambda d: d["tr"] == "t3")
    assert tk["supported"] and len(tk["lanes"]) == 3 and len(tk["lanes"][0]["clips"]) == 2
    dv = c.until("devices", lambda d: d["tr"] == "t3")
    assert [d["name"] for d in dv["devices"]] == ["EQ Eight", "Compressor", "Reverb"]


def test_focus_rack_devices_and_unsupported_takes(c):
    c.ok("focus.set", tr="t2")
    dv = c.until("devices", lambda d: d["tr"] == "t2")
    rack = dv["devices"][1]
    assert rack["type"] == "rack" and len(rack["chains"]) == 3 and len(rack["macros"]) == 8
    assert rack["var"] == {"count": 3, "sel": 1}
    assert rack["chains"][0]["devices"][0]["path"] == "1.c0.0"
    c.ok("focus.set", tr="t5")             # group track: no take lanes in this LOM -> degrade
    tk = c.until("takes", lambda d: d["tr"] == "t5")
    assert tk == {"tr": "t5", "supported": False, "lanes": []}
    c.ok("focus.set", tr="m")
    assert c.until("focus", lambda d: d["ref"] == "m")["kind"] == "master"


def test_focus_set_bad(c):
    for bad in ("t99", "x", "", None, 5, "r9"):
        r = c.req("focus.set", tr=bad)
        assert r["ok"] is False and r["err"]


def test_ping_pong(c):
    c.send_json({"t": "ping"})
    c.take(lambda m: m.get("t") == "pong")
    c.send_raw(c.frame(9, b"hi"))
    assert c.wait(lambda x: x.got_pong == b"hi")


def test_rt_frames_rate_and_content(c):
    c.ok("focus.set", tr="t0")
    c.ok("song.play")
    c.drain(0.3)
    t0 = time.time()
    n = 0
    while time.time() - t0 < 1.0:
        c.pump()
    rts = [m for m in c.msgs if m.get("t") == "rt"]
    assert 4 <= len(rts) <= 22, len(rts)
    d = rts[-1]["d"]
    assert d["playing"] is True and d["pos"] > 0 and set(d["meters"]) == {"focus", "master"}
    assert len(d["meters"]["focus"]) == 2 and "beat_in_bar" in d and d["clip_pos"] is None


def test_rt_clip_and_slot_pos(c):
    c.ok("focus.set", tr="t0")
    c.ok("slot.fire", tr="t0", i=0)
    c.ok("clip.open", loc={"w": "slot", "tr": "t0", "i": 0})
    assert c.wait(lambda x: any(m.get("t") == "rt" and m["d"]["clip_pos"] is not None and "0" in m["d"]["slot_pos"]
                                for m in x.msgs))


def test_malformed_and_garbage_do_not_crash(h, c):
    c.send_text("this is not json")
    c.send_text("[1,2,3]")
    c.send_text("{}")
    c.send_text('{"t":"req"}')
    c.send_text('{"t":"nope"}')
    c.send_text('{"t":"req","id":5,"op":"does.not.exist","a":{}}')
    r = c.take(lambda m: m.get("t") == "res" and m.get("id") == 5)
    assert r["ok"] is False and "unknown op" in r["err"]
    c.send_text('{"t":"req","id":6,"op":"song.set","a":[1]}')
    assert c.take(lambda m: m.get("id") == 6)["ok"] is False
    c.send_text('{"t":"req","id":7,"op":123}')
    assert c.take(lambda m: m.get("id") == 7)["ok"] is False
    c.send_text('{"t":"req","op":"sys.info"}')           # no id: still answered, id null
    assert c.take(lambda m: m.get("t") == "res" and m.get("id") is None and m.get("ok") is True)
    c.send_raw(c.frame(2, b"\x00\x01binary"))           # binary frames ignored
    logs = [m for m in c.msgs if m.get("t") == "log"]
    assert logs
    assert c.ok("sys.info")["host"] == "mock"
    assert h.t.is_alive()


def test_bad_arg_shapes(c):
    cases = [("song.set", {}), ("song.set", {"k": "tempo"}), ("song.set", {"k": "tempo", "v": "fast"}),
             ("song.set", {"k": "tempo", "v": 5}), ("song.set", {"k": "sig", "v": [3, 3]}),
             ("song.set", {"k": "nope", "v": 1}), ("scene.fire", {"i": 99}), ("scene.fire", {"i": "a"}),
             ("track.set", {"tr": "t0", "k": "vol"}), ("track.set", {"tr": "zz", "k": "vol", "v": 1}),
             ("track.set", {"tr": "t0", "k": "color", "v": -5}), ("slot.fire", {"tr": "t0", "i": 99}),
             ("slot.fire", {"tr": "t0", "i": True}), ("clip.open", {"loc": {"w": "slot", "tr": "t0", "i": 7}}),
             ("clip.open", {"loc": "x"}), ("clip.set", {"loc": {"w": "slot", "tr": "t0", "i": 0}, "k": "zzz", "v": 1}),
             ("device.param", {"tr": "t0", "path": "0", "i": 999, "v": 0.5}),
             ("device.open", {"tr": "t0", "path": "9.c9.9"}), ("device.open", {"tr": "t0", "path": "a"}),
             ("browser.list", {"path": "x"}), ("browser.list", {"path": ["nope"]}),
             ("notes.add", {"notes": []}), ("track.create", {"kind": "nope"}), ("song.cue", {"dir": 5})]
    for op, a in cases:
        r = c.req(op, **a)
        assert r["ok"] is False and r["err"], (op, a, r)
    assert c.ok("sys.info")


def test_fragmented_frames(c):
    c.send_fragmented(json.dumps({"t": "req", "id": 900, "op": "sys.info", "a": {}}), chunk=5)
    r = c.take(lambda m: m.get("id") == 900)
    assert r["ok"] and r["d"]["proto"] == 1
    # fragmented + interleaved ping (control frames may appear between fragments)
    msg = json.dumps({"t": "req", "id": 901, "op": "song.set", "a": {"k": "tempo", "v": 133}}).encode()
    c.send_raw(c.frame(1, msg[:10], fin=False))
    c.send_raw(c.frame(9, b"mid"))
    c.send_raw(c.frame(0, msg[10:], fin=True))
    assert c.take(lambda m: m.get("id") == 901)["ok"]
    assert c.wait(lambda x: x.got_pong == b"mid")
    c.until("session", lambda d: d["tempo"] == 133)


def test_large_message(c):
    name = "x" * 70000
    c.ok("track.set", tr="t0", k="name", v=name)
    c.until("tracks", lambda d: d["tracks"][0]["name"] == name)


def test_non_websocket_http(h):
    s = socket.create_connection(("127.0.0.1", h.port), timeout=3)
    s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
    data = b""
    s.settimeout(3)
    while b"\r\n\r\n" not in data:
        data += s.recv(1024)
    assert b" 426 " in data.split(b"\r\n")[0]
    s.close()
    assert h.client().ok("sys.info")          # server still fine


def test_bad_frames_close_connection_only(h):
    c = h.client()
    c.send_raw(b"\x83\x00")             # reserved opcode
    assert c.wait(lambda x: x.closed)
    c2 = h.client()
    assert c2.ok("sys.info")


def test_close_frame(h):
    c = h.client()
    c.send_raw(c.frame(8, b"\x03\xe8"))
    assert c.wait(lambda x: x.closed)


def test_abrupt_disconnect_cleans_up(h):
    c = h.client()
    c.wait(lambda x: "tracks" in x.sections)
    c.ok("focus.set", tr="t0")
    c.sock.close()
    t = time.time()
    while time.time() - t < 3 and h.core.clients:
        time.sleep(0.02)
    assert not h.core.clients
    time.sleep(0.2)
    assert h.song.listener_count() == 0
    assert h.song.tracks[0].mixer_device.volume.listener_count() == 0


def test_ping_timeout():
    hh = Harness(ping_timeout=0.4)
    try:
        c = hh.client()
        assert c.wait(lambda x: x.closed, timeout=4)
        c2 = hh.client()
        for _ in range(6):
            c2.send_json({"t": "ping"})
            time.sleep(0.15)
        assert not c2.closed
    finally:
        hh.close()


def test_multi_client_isolation(h):
    a, b = h.client(), h.client()
    a.wait(lambda x: "tracks" in x.sections)
    b.wait(lambda x: "tracks" in x.sections)
    a.ok("focus.set", tr="t0")
    b.ok("focus.set", tr="t3")
    assert a.until("focus")["ref"] == "t0" and b.until("focus")["ref"] == "t3"
    a.ok("clip.open", loc={"w": "slot", "tr": "t0", "i": 0})
    a.until("clip", lambda d: d and d["name"] == "Beat 1")
    b.pump()
    assert "clip" not in b.sections
    # a changes vol on t3: only b (focused on t3) gets a focus update; a's focus stays t0
    a.forget("focus")
    b.ok("track.set", tr="t3", k="vol", v=0.5)
    b.until("focus", lambda d: abs(d["vol"]["v"] - 0.5) < 1e-6)
    a.drain(0.3)
    assert "focus" not in a.sections or a.sections["focus"]["ref"] == "t0"
    # per-client follow flag is per client
    a.ok("focus.follow", on=True)
    a.until("session", lambda d: d["follow"] is True)
    b.drain(0.3)
    assert b.sections["session"]["follow"] is False
    h.song.view.selected_track = h.song.tracks[4]
    a.until("focus", lambda d: d["ref"] == "t4")
    b.drain(0.3)
    assert b.sections["focus"]["ref"] == "t3"
    # global change reaches both
    a.ok("song.set", k="tempo", v=100)
    b.until("session", lambda d: d["tempo"] == 100)
    # opened clip/device are independent
    b.ok("device.open", tr="t3", path="0")
    b.until("params", lambda d: d and d["name"] == "EQ Eight")
    a.drain(0.2)
    assert "params" not in a.sections


def test_follow_focus_on_selected_track(c, h):
    c.ok("focus.follow", on=True)
    c.until("focus", lambda d: d["ref"] == "t0")
    c.ok("view.select", tr="t2")
    c.until("focus", lambda d: d["ref"] == "t2")
    assert h.song.view.selected_track is h.song.tracks[2]
