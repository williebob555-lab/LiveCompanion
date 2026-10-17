"""LiveCompanion Control Surface (Ableton Live 12). Thin wrapper: all logic is in core/.

Pure stdlib; nothing platform specific (same file runs on Windows and under Wine)."""
import os
import traceback

try:  # only available inside Live; the mock/tests import the package without it
    from _Framework.ControlSurface import ControlSurface
except Exception:  # pragma: no cover
    ControlSurface = None

PORT = 9010
PROBE_AFTER_TICKS = 30      # ~3 s after load, once


def _detect_host():
    try:
        import ctypes
        getattr(ctypes.windll.ntdll, "wine_get_version")   # AttributeError on real Windows
        return "wine"
    except Exception:
        pass
    if os.environ.get("WINELOADER") or os.environ.get("WINEPREFIX"):
        return "wine"
    return "win"


def _live_version(app):
    try:
        return "%d.%d.%d" % (app.get_major_version(), app.get_minor_version(), app.get_bugfix_version())
    except Exception:
        return "12.4.1"


if ControlSurface is not None:
    class LiveCompanion(ControlSurface):
        def __init__(self, c_instance):
            ControlSurface.__init__(self, c_instance)
            self._lc_core = None
            self._lc_host = None
            self._lc_ticks = 0
            self._lc_probed = False
            try:
                from .core.reloader import Host
                try:
                    import Live
                    app = Live.Application.get_application()
                except Exception:
                    app = self.application()
                song = self.song()
                ver = _live_version(app)
                hostname = _detect_host()

                def make_core():
                    from .core.server import Core       # re-imported fresh after every hot reload
                    return Core(song, app, host=hostname, live_version=ver, port=PORT, log=self.log_message,
                                reloadable=True)

                self._lc_host = Host(make_core, self.log_message)
                self._lc_core = self._lc_host.core
                self.log_message("LiveCompanion loaded (port %d)" % PORT)
            except Exception:
                self.log_message("LiveCompanion failed to start:\n" + traceback.format_exc())

        def update_display(self):
            try:
                ControlSurface.update_display(self)
            except Exception:
                pass
            host = self._lc_host
            if host is None or host.core is None:
                return
            try:
                host.tick()
                core = host.core
                self._lc_ticks += 1
                if not self._lc_probed and self._lc_ticks >= PROBE_AFTER_TICKS:
                    self._lc_probed = True
                    from . import probe
                    p = probe.run_probe(self.song(), core.app, None, core.live_version)
                    self.log_message("LiveCompanion probe written to " + str(p))
            except Exception:
                self._lc_probed = True
                self.log_message("LiveCompanion tick error:\n" + traceback.format_exc())

        def disconnect(self):
            host, self._lc_host = self._lc_host, None
            self._lc_core = None
            try:
                if host is not None:
                    host.shutdown()
            except Exception:
                self.log_message("LiveCompanion shutdown error:\n" + traceback.format_exc())
            try:
                ControlSurface.disconnect(self)
            except Exception:
                pass


def create_instance(c_instance):
    if ControlSurface is None:
        raise RuntimeError("LiveCompanion must run inside Ableton Live")
    return LiveCompanion(c_instance)
