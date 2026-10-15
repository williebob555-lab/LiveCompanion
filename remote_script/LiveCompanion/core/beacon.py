"""UDP discovery beacon (PROTOCOL.md). Non-blocking; all errors ignored."""
import json
import socket


class Beacon(object):
    def __init__(self, ws_port=9010, helper_port=9012, port=9011, targets=None, name=None, interval=1.0):
        self.ws_port, self.helper_port, self.port = ws_port, helper_port, port
        self.interval = interval
        self.targets = targets if targets is not None else [("255.255.255.255", port)]
        try:
            self.name = name or socket.gethostname()
        except Exception:
            self.name = "live"
        self.sock = None
        self.last = 0.0
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            s.setblocking(False)
            self.sock = s
        except Exception:
            self.sock = None

    def payload(self, set_name=""):
        return json.dumps({"svc": "livecompanion", "proto": 1, "name": self.name, "ws": self.ws_port,
                           "helper": self.helper_port, "set": set_name or ""},
                          separators=(",", ":")).encode("utf-8")

    def tick(self, now, set_name_fn=None):
        if self.sock is None or now - self.last < self.interval:
            return
        self.last = now
        try:
            name = set_name_fn() if set_name_fn else ""
        except Exception:
            name = ""
        data = self.payload(name)
        for t in self.targets:
            try:
                self.sock.sendto(data, t)
            except Exception:
                pass

    def close(self):
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass
        self.sock = None
