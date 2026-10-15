"""Minimal non-blocking RFC 6455 WebSocket server (text frames only). stdlib only.

Everything is driven by poll(); nothing ever blocks. Partial reads/writes are buffered."""
import base64
import hashlib
import os
import select
import socket
import struct
import time

GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_MSG = 8 * 1024 * 1024
MAX_WBUF = 16 * 1024 * 1024
MAX_HEAD = 16 * 1024


def _unmask(data, mask):
    n = len(data)
    if not n:
        return bytes(data)
    m = (mask * (n // 4 + 1))[:n]
    return (int.from_bytes(data, "big") ^ int.from_bytes(m, "big")).to_bytes(n, "big")


def encode_frame(op, payload=b""):
    n = len(payload)
    if n < 126:
        head = struct.pack("!BB", 0x80 | op, n)
    elif n < 65536:
        head = struct.pack("!BBH", 0x80 | op, 126, n)
    else:
        head = struct.pack("!BBQ", 0x80 | op, 127, n)
    return head + payload


class Conn(object):
    def __init__(self, sock, addr):
        sock.setblocking(False)
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except Exception:
            pass
        self.sock = sock
        self.addr = addr
        self.rbuf = bytearray()
        self.wbuf = bytearray()
        self.open = False          # handshake done
        self.closed = False
        self.close_after_flush = False
        self.frag = None
        self.inbox = []
        self.last_rx = time.monotonic()
        self.user = None           # owner (Core) data


class WSServer(object):
    def __init__(self, host="0.0.0.0", port=9010, log=None):
        self.host, self.port = host, port
        self.log = log or (lambda m: None)
        self.lsock = None
        self.conns = []

    # ------------------------------------------------------------ lifecycle
    def start(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        if os.name != "nt":
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind((self.host, self.port))
            s.listen(16)
            s.setblocking(False)
        except Exception:
            s.close()
            raise
        self.lsock = s
        if self.port == 0:
            self.port = s.getsockname()[1]

    def stop(self):
        for c in list(self.conns):
            self._drop(c)
        self.conns = []
        if self.lsock is not None:
            try:
                self.lsock.close()
            except Exception:
                pass
            self.lsock = None

    # ------------------------------------------------------------ io
    def poll(self):
        """Returns (new_open_conns, closed_conns). Decoded messages land in conn.inbox."""
        new, gone = [], []
        if self.lsock is None:
            return new, gone
        socks = [c.sock for c in self.conns if not c.closed]
        wl = [c.sock for c in self.conns if not c.closed and c.wbuf]
        try:
            r, w, _ = select.select([self.lsock] + socks, wl, [], 0)
        except Exception:
            r, w = [], []
            for c in list(self.conns):       # find the bad socket
                try:
                    select.select([c.sock], [], [], 0)
                except Exception:
                    self._drop(c)
        by_sock = dict((c.sock, c) for c in self.conns)
        for s in r:
            if s is self.lsock:
                for _ in range(8):
                    try:
                        cs, addr = self.lsock.accept()
                    except Exception:
                        break
                    self.conns.append(Conn(cs, addr))
                continue
            c = by_sock.get(s)
            if c is not None and not c.closed:
                self._read(c, new)
        for s in w:
            c = by_sock.get(s)
            if c is not None and not c.closed:
                self._flush(c)
        for c in list(self.conns):
            if c.closed:
                self.conns.remove(c)
                gone.append(c)
        return new, gone

    def _read(self, c, new):
        try:
            for _ in range(16):
                data = c.sock.recv(65536)
                if not data:
                    self._drop(c)
                    return
                c.rbuf += data
                c.last_rx = time.monotonic()
                if len(c.rbuf) > MAX_MSG + MAX_HEAD:
                    break
        except (BlockingIOError, InterruptedError):
            pass
        except Exception:
            self._drop(c)
            return
        try:
            was_open = c.open
            self._process(c)
            if c.open and not was_open and not c.closed:
                new.append(c)
        except Exception as e:
            self.log("ws protocol error: %r" % (e,))
            self._drop(c)

    def _flush(self, c):
        if c.closed:
            return
        try:
            while c.wbuf:
                n = c.sock.send(c.wbuf[:262144])
                if n <= 0:
                    break
                del c.wbuf[:n]
        except (BlockingIOError, InterruptedError):
            pass
        except Exception:
            self._drop(c)
            return
        if not c.wbuf and c.close_after_flush:
            self._drop(c)

    def _drop(self, c):
        if c.closed:
            return
        c.closed = True
        try:
            c.sock.close()
        except Exception:
            pass

    # ------------------------------------------------------------ api
    def send_text(self, c, text):
        if c.closed or not c.open or c.close_after_flush:
            return False
        if len(c.wbuf) > MAX_WBUF:
            self.log("client too slow, dropping")
            self._drop(c)
            return False
        c.wbuf += encode_frame(0x1, text.encode("utf-8"))
        self._flush(c)
        return True

    def close(self, c, code=1000):
        if c.closed:
            return
        if c.open and not c.close_after_flush:
            c.wbuf += encode_frame(0x8, struct.pack("!H", code))
            c.close_after_flush = True
            self._flush(c)
        else:
            self._drop(c)

    # ------------------------------------------------------------ parsing
    def _process(self, c):
        if c.closed:
            return
        if not c.open:
            if not self._handshake(c):
                return
        buf = c.rbuf
        while not c.closed and not c.close_after_flush:
            if len(buf) < 2:
                return
            b0, b1 = buf[0], buf[1]
            fin = b0 & 0x80
            op = b0 & 0x0F
            masked = b1 & 0x80
            ln = b1 & 0x7F
            pos = 2
            if ln == 126:
                if len(buf) < 4:
                    return
                ln = struct.unpack("!H", bytes(buf[2:4]))[0]
                pos = 4
            elif ln == 127:
                if len(buf) < 10:
                    return
                ln = struct.unpack("!Q", bytes(buf[2:10]))[0]
                pos = 10
            if ln > MAX_MSG:
                self.close(c, 1009)
                return
            mask = b""
            if masked:
                if len(buf) < pos + 4:
                    return
                mask = bytes(buf[pos:pos + 4])
                pos += 4
            if len(buf) < pos + ln:
                return
            payload = bytes(buf[pos:pos + ln])
            del buf[:pos + ln]
            if masked:
                payload = _unmask(payload, mask)
            self._frame(c, fin, op, payload)

    def _frame(self, c, fin, op, payload):
        if op >= 0x8:                      # control
            if not fin or len(payload) > 125:
                self.close(c, 1002)
            elif op == 0x8:
                self.close(c, 1000)
            elif op == 0x9:
                c.wbuf += encode_frame(0xA, payload)
                self._flush(c)
            return                         # pong: ignore
        if op == 0x1 or op == 0x2:
            if c.frag is not None:
                self.close(c, 1002)
                return
            is_text = (op == 0x1)
            if fin:
                if is_text:
                    self._deliver(c, payload)
            else:
                c.frag = (is_text, bytearray(payload if is_text else b""))
        elif op == 0x0:
            if c.frag is None:
                self.close(c, 1002)
                return
            is_text, data = c.frag
            if is_text:
                data += payload
                if len(data) > MAX_MSG:
                    self.close(c, 1009)
                    return
            if fin:
                c.frag = None
                if is_text:
                    self._deliver(c, bytes(data))
        else:
            self.close(c, 1002)

    def _deliver(self, c, payload):
        try:
            c.inbox.append(payload.decode("utf-8"))
        except UnicodeDecodeError:
            self.close(c, 1007)

    def _handshake(self, c):
        i = c.rbuf.find(b"\r\n\r\n")
        if i < 0:
            if len(c.rbuf) > MAX_HEAD:
                self._drop(c)
            return False
        head = bytes(c.rbuf[:i]).decode("latin-1")
        del c.rbuf[:i + 4]
        lines = head.split("\r\n")
        hdr = {}
        for ln in lines[1:]:
            if ":" in ln:
                k, v = ln.split(":", 1)
                hdr[k.strip().lower()] = v.strip()
        key = hdr.get("sec-websocket-key")
        if (not lines[0].startswith("GET ") or "websocket" not in hdr.get("upgrade", "").lower()
                or not key):
            body = b"LiveCompanion websocket endpoint\n"
            c.wbuf += (b"HTTP/1.1 426 Upgrade Required\r\nUpgrade: websocket\r\nConnection: close\r\n"
                       b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n") + body
            c.close_after_flush = True
            self._flush(c)
            return False
        accept = base64.b64encode(hashlib.sha1(key.encode("ascii") + GUID).digest())
        c.wbuf += (b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                   b"Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + b"\r\n\r\n")
        c.open = True
        self._flush(c)
        return True
