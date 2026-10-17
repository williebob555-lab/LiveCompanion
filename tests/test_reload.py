"""sys.reload: hot-swap the core modules without restarting Live (reloader.Host)."""
import os
import threading
import time

from conftest import ROOT  # noqa: F401  (sets sys.path)
from fake_lom import build_demo
from LiveCompanion.core import reloader
from wsclient import WSClient


class HostHarness(object):
    def __init__(self, fail_make=False):
        self.song, self.app = build_demo()
        self.logs = []
        self.fail_make = [False]

        def make_core():
            if self.fail_make[0]:
                raise RuntimeError("boom")
            from LiveCompanion.core.server import Core
            return Core(self.song, self.app, host="mock", port=self.port, bind_host="127.0.0.1", beacon=False,
                        log=self.logs.append, reloadable=True)
        self.port = 0
        self.host = reloader.Host(make_core, self.logs.append)
        self.host.tick()
        self.port = self.host.core.port
        self.stop = False
        self.t = threading.Thread(target=self._run, daemon=True)
        self.t.start()

    def _run(self):
        while not self.stop:
            self.host.tick()
            time.sleep(0.005)

    def close(self):
        self.stop = True
        self.t.join(2)
        self.host.shutdown()


def test_reload_swaps_core_and_clients_reconnect():
    hh = HostHarness()
    try:
        c = WSClient(hh.port)
        c.hello()
        info = c.ok("sys.info")
        assert "sys.reload" in info["ops"]
        old = hh.host.core
        assert c.ok("sys.reload") == {"reloading": True}
        end = time.time() + 5
        while hh.host.reloads < 1 and time.time() < end:
            time.sleep(0.02)
        assert hh.host.reloads == 1 and hh.host.core is not old
        c.close()
        c2 = WSClient(hh.port)
        c2.hello()
        assert c2.ok("sys.info")["script_version"] >= 3
        c2.wait(lambda x: "tracks" in x.sections)
        c2.close()
    finally:
        hh.close()


def test_reload_with_syntax_error_keeps_old_code(tmp_path, monkeypatch):
    hh = HostHarness()
    try:
        c = WSClient(hh.port)
        c.hello()
        old = hh.host.core
        monkeypatch.setattr(reloader, "_precompile", lambda pkg: "ops.py:1 invalid syntax")
        c.ok("sys.reload")
        end = time.time() + 3
        while not hh.host.last_error and time.time() < end:
            time.sleep(0.02)
        assert "invalid syntax" in hh.host.last_error
        assert hh.host.core is old and hh.host.reloads == 0
        assert c.ok("sys.info")["live"]       # still serving on the same connection
        c.close()
    finally:
        hh.close()


def test_reload_new_core_failure_restores_previous():
    hh = HostHarness()
    try:
        c = WSClient(hh.port)
        c.hello()
        hh.fail_make[0] = True
        c.ok("sys.reload")
        end = time.time() + 3
        while not hh.host.last_error and time.time() < end:
            time.sleep(0.02)
        assert "boom" in hh.host.last_error
        assert hh.host.core is not None
        time.sleep(0.3)
        c.close()
        c2 = WSClient(hh.port)
        c2.hello()
        assert c2.ok("sys.info")["live"]
        c2.close()
    finally:
        hh.close()


def test_sys_reload_absent_without_host(h):
    c = h.client()
    assert "sys.reload" not in c.ok("sys.info")["ops"]
    r = c.req("sys.reload")
    assert not r["ok"]
