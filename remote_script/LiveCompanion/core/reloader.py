"""Hot reload of the core modules without restarting Live (op `sys.reload`).

`Host` owns the Core; the thin Control Surface wrapper (and the mock) call host.tick() instead of core.tick().
This module is deliberately NOT reloaded itself, so it stays stable while everything else is swapped.
A failed reload (syntax error, import error, new Core cannot start) keeps / restores the previous code."""
import importlib
import os
import sys
import time
import traceback

ORDER = ("util", "ws", "beacon", "recenv", "asd", "peaks", "sections", "listeners", "arrange", "ops", "server")
SETTLE_TICKS = 2        # ticks between the op reply and the swap, so the reply is flushed to the phone


def _pkg():
    return __name__.rpartition(".")[0]          # e.g. "LiveCompanion.core"


def _precompile(pkg):
    """Syntax-check every module from disk before touching anything; returns an error string or ''."""
    for n in ORDER + ("__init__",):
        m = sys.modules.get(pkg if n == "__init__" else pkg + "." + n)
        f = getattr(m, "__file__", None)
        if not f or not os.path.isfile(f):
            continue
        try:
            with open(f, "rb") as fh:
                compile(fh.read(), f, "exec")
        except SyntaxError as e:
            return "%s:%s %s" % (os.path.basename(f), e.lineno, e.msg)
    return ""


def reload_modules():
    """importlib.reload the core modules in dependency order. Returns '' on success else an error text."""
    pkg = _pkg()
    err = _precompile(pkg)
    if err:
        return err
    importlib.invalidate_caches()
    try:
        for n in ORDER:
            m = sys.modules.get(pkg + "." + n)
            if m is not None:
                importlib.reload(m)
        importlib.reload(sys.modules[pkg])
        top = sys.modules.get(pkg.rpartition(".")[0] + ".probe")
        if top is not None:
            importlib.reload(top)
    except Exception:
        return traceback.format_exc()[-600:]
    return ""


class Host(object):
    """make_core(): builds a Core (called again after every successful module reload; must import Core freshly)."""

    def __init__(self, make_core, log=None):
        self.make_core = make_core
        self.log = log or (lambda m: None)
        self.core = None
        self.reloads = 0
        self.last_error = ""
        self._wait = 0
        self.core = make_core()

    def tick(self):
        core = self.core
        if core is None:
            return
        core.tick()
        if getattr(core, "reload_requested", False):
            self._wait += 1
            if self._wait >= SETTLE_TICKS:
                self._wait = 0
                core.reload_requested = False
                self.reload()

    def reload(self):
        old = self.core
        self.log("LiveCompanion: reloading script from disk")
        err = reload_modules()
        if err:
            self.last_error = err
            self.log("LiveCompanion reload FAILED, keeping previous code:\n" + err)
            return False
        try:
            old.shutdown()
        except Exception:
            self.log("LiveCompanion reload: old core shutdown error:\n" + traceback.format_exc())
        try:
            self.core = self.make_core()
        except Exception:
            self.last_error = traceback.format_exc()[-600:]
            self.log("LiveCompanion reload: new core failed, restoring previous:\n" + self.last_error)
            try:
                # type(old) still holds the previous class objects; build it the same way make_core would
                self.core = self._restore(old)
            except Exception:
                self.core = None
                self.log("LiveCompanion reload: restore failed:\n" + traceback.format_exc())
            return False
        self.reloads += 1
        self.last_error = ""
        self.log("LiveCompanion reloaded (#%d)" % self.reloads)
        return True

    def _restore(self, old):
        new = type(old)(old.song, old.app, host=old.host, live_version=old.live_version, port=old.ws.port,
                        bind_host=old.ws.host, beacon=old.beacon is not None, log=old.log, reloadable=True)
        return new

    def shutdown(self):
        if self.core is not None:
            self.core.shutdown()
