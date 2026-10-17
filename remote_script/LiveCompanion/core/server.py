"""Core: protocol server driven by tick(). Pure Python; takes `song` and `application` objects
(real LOM or mock/fake_lom). Never blocks, never raises out of tick()."""
import json
import time
import traceback

from . import sections as S
from .beacon import Beacon
from .listeners import Listeners
from .ops import Ops
from .peaks import PeaksWorker
from .recenv import RecEnv
from .util import Deferred, OpError, BadArgs, resolve_track, resolve_clip, resolve_device, loc_key, g
from .ws import WSServer

PROTO = 1
TRACK_SECTIONS = ("focus", "slots", "arr", "takes", "devices")

# which dirty keys an op marks after running (belt and braces on top of listeners)
OP_MARKS = {
    "track.set": ("focus",),
    "track.stop": ("slots",),
    "slot": ("slots", "clip"),
    "take": ("slots",),
    "clip": ("slots", "arr", "takes", "clip"),
    "notes": ("clip",),
    "warp": ("clip",),
    "arr": ("arr", "takes"),
    "takes": ("arr", "takes"),
    "device": ("devices", "params"),
    "chain": ("devices", "params"),
    "browser.load": ("devices",),
}


class Client(object):
    def __init__(self, conn, cid):
        self.conn = conn
        self.id = cid
        self.focus = None
        self.clip = None
        self.device = None
        self.follow = False
        self.last_sel = None      # Live's selected track as last seen by follow_check (react to changes only)
        self.sent = {}
        self.pending = set()      # sections to (re)send unconditionally next flush
        self.last_rt = 0.0


class Core(object):
    def __init__(self, song, application, host="win", live_version="12.4.1", port=9010, bind_host="0.0.0.0",
                 beacon=True, beacon_port=9011, beacon_targets=None, helper_port=9012, log=None,
                 probe_path=None, ping_timeout=10.0, rt_interval=0.05, poll_interval=2.0, peaks_disk_cache=True,
                 reloadable=False):
        self.song, self.app = song, application
        self.reloadable = reloadable          # True when a reloader.Host drives this Core (op sys.reload)
        self.reload_requested = False
        self.host, self.live_version = host, live_version
        self.log = log or (lambda m: None)
        self.probe_path = probe_path
        self.ping_timeout, self.rt_interval, self.poll_interval = ping_timeout, rt_interval, poll_interval
        self.ws = WSServer(bind_host, port, self.log)
        self.beacon = Beacon(port, helper_port, beacon_port, beacon_targets) if beacon else None
        self.peaks = PeaksWorker(disk_cache=peaks_disk_cache)
        self.recenv = RecEnv()    # meter envelope of clips being recorded on focused tracks
        self.ops = Ops(self)
        self.clients = []
        self.dirty = set()
        self.listeners_dirty = False
        self.dev_burst = {}       # tr -> deadline (monotonic) to re-mark devices dirty (Live applies loads late)
        self._rebuild = False
        self._cur_rid = None
        self._cid = 0
        self._next_start = 0.0
        self._last_poll = 0.0
        self._err_seen = {}
        self._cache = {}
        self.listeners = Listeners(song, application, self.dirty.add, self._resync, self.log)

    # ------------------------------------------------------------ public
    @property
    def port(self):
        return self.ws.port

    def tick(self):
        try:
            self._tick()
        except Exception:
            self._err("tick")

    def shutdown(self):
        try:
            self.listeners.detach_all()
        except Exception:
            pass
        try:
            for c in list(self.clients):
                self.ws.close(c.conn, 1001)
            self.ws.stop()
        except Exception:
            pass
        try:
            if self.beacon:
                self.beacon.close()
        except Exception:
            pass
        self.clients = []
        try:
            self.peaks.stop()
        except Exception:
            pass

    def set_name(self):
        for fn in (lambda: self.song.name, lambda: self.app.get_document_title()):
            try:
                v = fn()
                if isinstance(v, str) and v:
                    return v
            except Exception:
                pass
        return ""

    def run_probe(self):
        try:
            from .. import probe
        except Exception:
            raise OpError("unsupported: probe module unavailable")
        path = probe.run_probe(self.song, self.app, self.probe_path, self.live_version)
        return {"path": path}

    # ------------------------------------------------------------ internals
    def _err(self, where):
        msg = traceback.format_exc()
        key = (where, msg[-200:])
        n = self._err_seen.get(key, 0)
        self._err_seen[key] = n + 1
        if n < 3 and len(self._err_seen) < 200:
            self.log("LiveCompanion error in %s: %s" % (where, msg))

    def mark_devices(self, tr, secs=4.0):
        """Device tree changed (load/insert/move/delete): resend `devices` now, re-check for ~4 s
        (dedup in _push makes repeats free), and rebuild device listeners."""
        self.dirty.add(("devices", tr))
        self.dev_burst[tr] = time.monotonic() + secs
        self._resync()

    def _resync(self):
        self.listeners_dirty = True
        self._rebuild = True

    def _tick(self):
        now = time.monotonic()
        if self.ws.lsock is None and now >= self._next_start:
            try:
                self.ws.start()
                self.log("LiveCompanion listening on %s:%s" % (self.ws.host, self.ws.port))
            except Exception as e:
                self._next_start = now + 5.0
                self.log("LiveCompanion cannot bind port %s: %r" % (self.ws.port, e))
        new, gone = self.ws.poll()
        for conn in gone:
            cl = conn.user
            if cl in self.clients:
                self.clients.remove(cl)
                self.peaks.cancel_owner(cl.id)
                self.listeners_dirty = True
        for conn in new:
            self._on_new(conn)
        for cl in list(self.clients):
            inbox, cl.conn.inbox = cl.conn.inbox, []
            for text in inbox[:200]:
                try:
                    self._handle(cl, text)
                except Exception:
                    self._err("handle")
            if len(inbox) > 200:
                self._send(cl, {"t": "log", "lvl": "warn", "msg": "too many messages; dropped some"})
        if self.clients and now - self._last_poll >= self.poll_interval:
            self._last_poll = now
            self.dirty.update((("session",), ("tracks",)))
            for cl in self.clients:
                if cl.focus:
                    self.dirty.update((("focus", cl.focus), ("slots", cl.focus)))
        if self.dev_burst:
            for tr in list(self.dev_burst):
                self.dirty.add(("devices", tr))
                if now >= self.dev_burst[tr]:
                    del self.dev_burst[tr]
                    self._rebuild = True
                    self.listeners_dirty = True
        self._send_deferred()
        if self.clients:
            try:
                self.recenv.sample(self.song, set(c.focus for c in self.clients if c.focus))
            except Exception:
                self._err("recenv")
        if self.listeners_dirty:
            self._sync_listeners()        # attach before sending state so no change slips between the two
        if self.clients:
            self._flush()
        if self.listeners_dirty:
            self._sync_listeners()
        if self.clients:
            self._send_rt(now)
            for cl in list(self.clients):
                if now - cl.conn.last_rx > self.ping_timeout:
                    self.log("client %d timed out" % cl.id)
                    self.ws.close(cl.conn, 1001)
        if self.beacon:
            self.beacon.tick(now, self.set_name)

    def _on_new(self, conn):
        self._cid += 1
        cl = Client(conn, self._cid)
        conn.user = cl
        self.clients.append(cl)
        self._send(cl, {"t": "hello", "proto": PROTO, "live": self.live_version, "host": self.host})
        cl.pending.update(("session", "tracks"))
        self.listeners_dirty = True

    def _sync_listeners(self):
        self.listeners_dirty = False
        rebuild, self._rebuild = self._rebuild, False
        refs = set(c.focus for c in self.clients if c.focus)
        clips = dict((loc_key(c.clip), c.clip) for c in self.clients if c.clip)
        devs = set(c.device for c in self.clients if c.device)
        self.listeners.sync(bool(self.clients), refs, clips, devs, rebuild)

    # ------------------------------------------------------------ messaging
    def _send(self, cl, obj):
        return self._send_raw(cl, json.dumps(obj, separators=(",", ":")))

    def _send_raw(self, cl, text):
        try:
            return self.ws.send_text(cl.conn, text)
        except Exception:
            return False

    def _handle(self, cl, text):
        try:
            m = json.loads(text)
        except Exception:
            m = None
        if not isinstance(m, dict):
            self._send(cl, {"t": "log", "lvl": "warn", "msg": "malformed message ignored"})
            return
        t = m.get("t")
        if t == "ping":
            self._send(cl, {"t": "pong"})
        elif t == "req":
            self._req(cl, m)
        else:
            self._send(cl, {"t": "log", "lvl": "warn", "msg": "unknown message type"})

    def _req(self, cl, m):
        rid = m.get("id")
        op = m.get("op")
        a = m.get("a")
        if a is None:
            a = {}
        res = {"t": "res", "id": rid}
        self._cur_rid = rid
        try:
            if not isinstance(op, str):
                raise BadArgs("missing op")
            if not isinstance(a, dict):
                raise BadArgs("a must be an object")
            d = self.ops.run(cl, op, a)
            if isinstance(d, Deferred):
                return            # the reply is sent from a later tick (see _send_deferred)
            json.dumps(d)
            res["ok"] = True
            res["d"] = d
        except OpError as e:
            res = {"t": "res", "id": rid, "ok": False, "err": str(e)}
            if getattr(e, "data", None) is not None:
                res["d"] = e.data
        except AttributeError as e:      # LOM object lacks something we expected
            self._err("op " + str(op))
            res = {"t": "res", "id": rid, "ok": False, "err": "unsupported: %s" % (e,)}
        except Exception as e:
            self._err("op " + str(op))
            res = {"t": "res", "id": rid, "ok": False, "err": "error: %s: %s" % (type(e).__name__, e)}
        self._send(cl, res)
        if isinstance(op, str) and isinstance(a, dict):
            try:
                self._after_op(cl, op, a)
            except Exception:
                self._err("after_op")

    def defer_peaks(self, cl, path, n, sr=0, ek=None):
        d = Deferred()
        d.rid = self._cur_rid
        job = self.peaks.submit(path, n, cl.id, d.rid, sr)
        job.ek = ek
        return d

    def _send_deferred(self):
        for job in self.peaks.poll():
            cl = next((c for c in self.clients if c.id == job.owner), None)
            if cl is None:
                continue
            if job.ok:
                self._send(cl, {"t": "res", "id": job.rid, "ok": True, "d": job.data})
            elif job.locked and job.ek and self._env_reply(cl, job):
                pass
            else:
                self._send(cl, {"t": "res", "id": job.rid, "ok": False, "err": job.data})

    def _env_reply(self, cl, job):
        """Locked wav, no .asd: answer with the coarse meter envelope recorded while the clip was recording."""
        try:
            d = self.recenv.approx_peaks(job.ek, job.n)
        except Exception:
            self._err("env_peaks")
            return False
        if d is None:
            return False
        self._send(cl, {"t": "res", "id": job.rid, "ok": True, "d": d})
        return True

    def _after_op(self, cl, op, a):
        d = self.dirty
        if op == "clip.peaks":
            return
        if op.startswith("sys.") or op.startswith("browser.") and op != "browser.load" or op.startswith("focus.") \
                or op.startswith("view."):
            return
        d.add(("session",))
        d.add(("tracks",))
        trs = set()
        for k in ("tr", "to_tr"):
            v = a.get(k)
            if isinstance(v, str):
                trs.add(v)
        loc = a.get("loc")
        if isinstance(loc, dict) and isinstance(loc.get("tr"), str):
            trs.add(loc["tr"])
        if cl.clip and isinstance(cl.clip.get("tr"), str) and op.split(".")[0] in ("notes", "warp", "clip"):
            trs.add(cl.clip["tr"])
        marks = OP_MARKS.get(op) or OP_MARKS.get(op.split(".")[0])
        if marks is None:        # song.*, scene.*, track.create/delete/duplicate ...
            marks = TRACK_SECTIONS + ("clip", "params")
            for c in self.clients:
                if c.focus:
                    trs.add(c.focus)
        for name in marks:
            if name in ("clip", "params"):
                d.add((name,))
            else:
                for tr in trs:
                    d.add((name, tr))

    # ------------------------------------------------------------ focus
    def set_focus(self, cl, tr):
        cl.focus = tr
        for n in TRACK_SECTIONS:
            cl.sent.pop(n, None)
        cl.pending.update(TRACK_SECTIONS)
        self.listeners_dirty = True

    def follow_check(self, cl):
        try:
            sel = S.session(self.song, self.app)["sel_track"]
        except Exception:
            sel = None
        # Only react when Live's selection actually CHANGES (session is dirty every tick while playing, since it
        # carries the playhead); otherwise the phone's own track choice would be overridden constantly.
        if sel == getattr(cl, "last_sel", None):
            return
        cl.last_sel = sel
        if sel and sel != cl.focus:
            self.set_focus(cl, sel)

    # ------------------------------------------------------------ flush
    def _validate_clients(self):
        for cl in self.clients:
            if cl.focus:
                try:
                    resolve_track(self.song, cl.focus)
                except Exception:
                    cl.focus = None
                    self._send(cl, {"t": "log", "lvl": "warn", "msg": "focused track no longer exists"})
            if cl.clip:
                try:
                    resolve_clip(self.song, cl.clip)
                except Exception:
                    cl.clip = None
                    cl.pending.add("clip")
            if cl.device:
                try:
                    resolve_device(self.song, cl.device[0], cl.device[1])
                except Exception:
                    cl.device = None
                    cl.pending.add("params")

    def _flush(self):
        dirty = set(self.dirty)       # keep self.dirty identity: listener callbacks hold its .add
        self.dirty.clear()
        struct = ("struct",) in dirty
        if struct:
            dirty.update((("session",), ("tracks",)))
            self._validate_clients()
        else:
            for cl in self.clients:
                if ("clip",) in dirty and cl.clip or ("params",) in dirty and cl.device:
                    self._validate_clients()
                    break
        self._cache = {}
        for cl in list(self.clients):
            force, cl.pending = cl.pending, set()
            if cl.follow and (("session",) in dirty or struct):
                self.follow_check(cl)
                force |= cl.pending
                cl.pending = set()
            names = []
            if ("session",) in dirty or "session" in force:
                names.append("session")
            if ("tracks",) in dirty or "tracks" in force:
                names.append("tracks")
            if cl.focus:
                for n in TRACK_SECTIONS:
                    if struct or (n, cl.focus) in dirty or n in force:
                        names.append(n)
            if cl.clip is not None or "clip" in force:
                if ("clip",) in dirty or "clip" in force or struct:
                    names.append("clip")
            if cl.device is not None or "params" in force:
                if ("params",) in dirty or "params" in force or struct:
                    names.append("params")
            for n in names:
                self._push(cl, n, n in force)

    def _ser(self, name, cl):
        song, app = self.song, self.app
        if name == "session":
            return json.dumps(S.session(song, app, cl.follow), separators=(",", ":"))
        if name == "tracks":
            key = ("tracks",)
        elif name == "clip":
            key = ("clip", loc_key(cl.clip))
        elif name == "params":
            key = ("params", cl.device)
        else:
            key = (name, cl.focus)
        hit = self._cache.get(key)
        if hit is not None:
            return hit
        if name == "tracks":
            d = S.tracks(song, app)
        elif name == "clip":
            d = S.clip(song, app, cl.clip) if cl.clip else None
        elif name == "params":
            d = S.params(song, app, cl.device[0], cl.device[1]) if cl.device else None
        else:
            d = getattr(S, name)(song, app, cl.focus)
        js = json.dumps(d, separators=(",", ":"))
        self._cache[key] = js
        return js

    def _push(self, cl, name, force):
        try:
            js = self._ser(name, cl)
        except Exception as e:
            if name in ("clip", "params"):
                if name == "clip":
                    cl.clip = None
                else:
                    cl.device = None
                js = "null"
            else:
                self._err("section " + name)
                return
        if not force and cl.sent.get(name) == js:
            return
        cl.sent[name] = js
        self._send_raw(cl, '{"t":"sec","s":"%s","d":%s}' % (name, js))

    # ------------------------------------------------------------ realtime
    def _send_rt(self, now):
        cache = {}
        for cl in self.clients:
            if now - cl.last_rt < self.rt_interval:
                continue
            cl.last_rt = now
            key = (cl.focus, loc_key(cl.clip) if cl.clip else None)
            js = cache.get(key)
            if js is None:
                try:
                    d = S.rt(self.song, self.app, cl.focus, cl.clip)
                    re_ = self.recenv.rt_payload(cl.focus)
                    if re_ is not None:
                        d["rec_env"] = re_
                    js = '{"t":"rt","d":%s}' % json.dumps(d, separators=(",", ":"))
                except Exception:
                    self._err("rt")
                    continue
                cache[key] = js
            self._send_raw(cl, js)
