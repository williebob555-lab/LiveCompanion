import os
import sys
import threading
import time

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for p in ("remote_script", "mock", "tests"):
    sys.path.insert(0, os.path.join(ROOT, p))

from fake_lom import build_demo  # noqa: E402
from LiveCompanion.core.server import Core  # noqa: E402
from wsclient import WSClient  # noqa: E402


class Harness(object):
    """Runs the fake-LOM clock + Core.tick() on a background thread, like update_display in Live."""

    def __init__(self, **core_kw):
        self.song, self.app = build_demo()
        self.song.clip_trigger_quantization = 0         # immediate launches keep tests quick
        self.logs = []
        kw = dict(host="mock", port=0, bind_host="127.0.0.1", beacon=False, log=self.logs.append)
        kw.update(core_kw)
        self.core = Core(self.song, self.app, **kw)
        self.stop = False
        self.core.tick()                                # binds the port
        self.port = self.core.port
        self.clients = []
        self.t = threading.Thread(target=self._run, daemon=True)
        self.t.start()

    def _run(self):
        last = time.monotonic()
        while not self.stop:
            now = time.monotonic()
            self.song.advance(now - last)
            self.core.tick()
            last = now
            time.sleep(0.005)

    def client(self, hello=True, **kw):
        c = WSClient(self.port, **kw)
        self.clients.append(c)
        if hello:
            c.hello()
        return c

    def close(self):
        self.stop = True
        self.t.join(2)
        for c in self.clients:
            c.close()
        self.core.shutdown()


@pytest.fixture
def h():
    hh = Harness()
    yield hh
    hh.close()


@pytest.fixture
def c(h):
    cl = h.client()
    cl.wait(lambda x: "tracks" in x.sections and "session" in x.sections)
    return cl
