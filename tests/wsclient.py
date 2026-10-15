"""Tiny blocking WebSocket test client (RFC 6455 client side) for driving the Core in tests."""
import base64
import json
import os
import socket
import struct
import time


class WSClient(object):
    def __init__(self, port, host="127.0.0.1", timeout=5.0, handshake=True):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(0.05)
        self.buf = bytearray()
        self.next_id = 1
        self.msgs = []          # received json messages not yet consumed
        self.sections = {}      # latest `d` per section
        self.sec_count = {}
        self.closed = False
        self.last_close = None
        self.got_pong = None
        if handshake:
            self.handshake()

    # ---- handshake
    def handshake(self, key=None):
        key = key or base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % key).encode())
        end = time.time() + 5
        while b"\r\n\r\n" not in self.buf:
            self._recv_some()
            if time.time() > end:
                raise TimeoutError("handshake")
        i = self.buf.find(b"\r\n\r\n")
        self.http_head = bytes(self.buf[:i]).decode()
        del self.buf[:i + 4]
        assert " 101 " in self.http_head.split("\r\n")[0], self.http_head
        return key

    def _recv_some(self):
        try:
            d = self.sock.recv(65536)
            if not d:
                self.closed = True
            else:
                self.buf += d
        except socket.timeout:
            pass
        except OSError:
            self.closed = True

    # ---- frames
    @staticmethod
    def frame(op, payload=b"", fin=True, mask=True):
        b0 = (0x80 if fin else 0) | op
        n = len(payload)
        mbit = 0x80 if mask else 0
        if n < 126:
            head = struct.pack("!BB", b0, mbit | n)
        elif n < 65536:
            head = struct.pack("!BBH", b0, mbit | 126, n)
        else:
            head = struct.pack("!BBQ", b0, mbit | 127, n)
        if mask:
            m = os.urandom(4)
            return head + m + bytes(b ^ m[i % 4] for i, b in enumerate(payload))
        return head + payload

    def send_raw(self, data):
        self.sock.sendall(data)

    def send_text(self, text):
        self.send_raw(self.frame(1, text.encode()))

    def send_json(self, obj):
        self.send_text(json.dumps(obj))

    def send_fragmented(self, text, chunk=7, split_tcp=True):
        data = text.encode()
        parts = [data[i:i + chunk] for i in range(0, len(data), chunk)] or [b""]
        for k, p in enumerate(parts):
            f = self.frame(1 if k == 0 else 0, p, fin=(k == len(parts) - 1))
            if split_tcp:
                for j in range(0, len(f), 3):
                    self.send_raw(f[j:j + 3])
                    time.sleep(0.001)
            else:
                self.send_raw(f)

    def _parse(self):
        out = []
        while len(self.buf) >= 2:
            b0, b1 = self.buf[0], self.buf[1]
            op, ln, pos = b0 & 0xF, b1 & 0x7F, 2
            if ln == 126:
                if len(self.buf) < 4:
                    break
                ln, pos = struct.unpack("!H", bytes(self.buf[2:4]))[0], 4
            elif ln == 127:
                if len(self.buf) < 10:
                    break
                ln, pos = struct.unpack("!Q", bytes(self.buf[2:10]))[0], 10
            if len(self.buf) < pos + ln:
                break
            payload = bytes(self.buf[pos:pos + ln])
            del self.buf[:pos + ln]
            out.append((op, payload))
        return out

    def pump(self, wait=0.05):
        """Read whatever is available for up to `wait` seconds."""
        self._recv_some()
        for op, payload in self._parse():
            if op == 1:
                m = json.loads(payload.decode())
                self.msgs.append(m)
                if m.get("t") == "sec":
                    self.sections[m["s"]] = m["d"]
                    self.sec_count[m["s"]] = self.sec_count.get(m["s"], 0) + 1
            elif op == 9:
                self.send_raw(self.frame(0xA, payload))
            elif op == 8:
                self.closed = True
                self.last_close = payload
            elif op == 0xA:
                self.got_pong = payload

    def wait(self, pred, timeout=5.0):
        end = time.time() + timeout
        while time.time() < end:
            self.pump()
            if pred(self):
                return True
            if self.closed:
                break
        self.pump()
        return bool(pred(self))

    def take(self, pred, timeout=5.0):
        """Wait for and remove the first queued message matching pred."""
        end = time.time() + timeout
        while time.time() < end:
            for i, m in enumerate(self.msgs):
                if pred(m):
                    return self.msgs.pop(i)
            self.pump()
            if self.closed and not self.buf:
                break
        for i, m in enumerate(self.msgs):
            if pred(m):
                return self.msgs.pop(i)
        raise TimeoutError("no matching message; last=%r" % (self.msgs[-4:],))

    def hello(self):
        return self.take(lambda m: m.get("t") == "hello")

    def req(self, op, timeout=5.0, **a):
        rid = self.next_id
        self.next_id += 1
        self.send_json({"t": "req", "id": rid, "op": op, "a": a})
        return self.take(lambda m: m.get("t") == "res" and m.get("id") == rid, timeout)

    def ok(self, op, **a):
        r = self.req(op, **a)
        assert r.get("ok"), r
        return r.get("d")

    def sec(self, name, timeout=5.0):
        """Wait for a fresh `sec` message of this name, consume it, return its `d`."""
        return self.take(lambda m: m.get("t") == "sec" and m.get("s") == name, timeout)["d"]

    def until(self, name, pred=None, timeout=5.0):
        """Wait until the LATEST section `name` satisfies pred(d); returns d."""
        ok = self.wait(lambda x: name in x.sections and (pred is None or pred(x.sections[name])), timeout)
        assert ok, "section %s never satisfied predicate; last=%r" % (name, str(self.sections.get(name))[:300])
        return self.sections[name]

    def forget(self, *names):
        for n in names:
            self.sections.pop(n, None)

    def drain(self, wait=0.2):
        end = time.time() + wait
        while time.time() < end:
            self.pump()
        self.msgs = []

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass
