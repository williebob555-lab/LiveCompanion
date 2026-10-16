#!/usr/bin/env python3
"""Minimal LiveCompanion-protocol server for client robustness testing (stdlib only, RFC 6455 text frames).

Sends ONLY `hello` + a `session` section (docs/PROTOCOL.md says the real server also sends tracks/focus/...; a
client must treat missing sections as empty). Everything the client sends is read and ignored, JSON `ping`
included (the client must NOT drop a server that never answers JSON pings; WebSocket-level pings ARE answered).

    python tools/fake_min_server.py [--port 9020] [--pong] [--hostile]

--pong     answer JSON {"t":"ping"} with {"t":"pong"} like the real server
--hostile  after the session section also send NaN/Infinity/huge numbers, a 100 KB name, unknown sections,
           junk JSON and wrong-typed fields; the client must neither crash nor disconnect
"""
import argparse
import base64
import hashlib
import json
import socket
import struct
import threading

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def frame(opcode: int, payload: bytes) -> bytes:
    head = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        head += bytes([n])
    elif n < 65536:
        head += bytes([126]) + struct.pack(">H", n)
    else:
        head += bytes([127]) + struct.pack(">Q", n)
    return head + payload


def send_json(conn, obj):
    conn.sendall(frame(0x1, json.dumps(obj).encode()))  # json.dumps emits bare NaN / Infinity like some servers


def send_raw(conn, text: str):
    conn.sendall(frame(0x1, text.encode()))


def read_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf


def read_frame(conn):
    b0, b1 = read_exact(conn, 2)
    opcode = b0 & 0x0F
    n = b1 & 0x7F
    if n == 126:
        n = struct.unpack(">H", read_exact(conn, 2))[0]
    elif n == 127:
        n = struct.unpack(">Q", read_exact(conn, 8))[0]
    mask = read_exact(conn, 4) if b1 & 0x80 else b""
    data = read_exact(conn, n)
    if mask:
        data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
    return opcode, data


def handshake(conn):
    req = b""
    while b"\r\n\r\n" not in req:
        chunk = conn.recv(4096)
        if not chunk:
            raise EOFError
        req += chunk
    key = None
    for line in req.decode(errors="replace").split("\r\n"):
        if line.lower().startswith("sec-websocket-key:"):
            key = line.split(":", 1)[1].strip()
    if key is None:
        raise ValueError("not a websocket upgrade")
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    conn.sendall(
        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
         "Sec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())


def hostile(conn):
    send_json(conn, {"t": "sec", "s": "session", "d": {"tempo": float("nan"), "pos": float("inf"), "sig": [4, 4]}})
    send_json(conn, {"t": "sec", "s": "tracks", "d": {"tracks": [
        {"ref": "t0", "name": "X" * 100_000, "color": 1e30, "kind": "audio"},
        {"ref": "t1", "name": "ok", "color": -5, "kind": 12345},
    ], "scenes": "not a list"}})
    send_json(conn, {"t": "sec", "s": "from_the_future", "d": {"x": [1, 2, 3]}})
    send_raw(conn, "this is not json")
    send_raw(conn, '{"t":"sec","s":"slots","d":{"slots":[{"i":1e400,"status":"???"}]}}')
    send_json(conn, {"t": "sec", "s": "clip", "d": {"loc": None, "notes": [{"p": 1e20, "t": float("-inf"), "d": "x"}]}})
    send_json(conn, {"t": "rt", "d": {"pos": float("nan"), "meters": "no"}})
    send_json(conn, {"t": "sec", "s": "session", "d": {"tempo": 99.0}})  # a good one at the end


def serve(conn, args):
    try:
        handshake(conn)
        send_json(conn, {"t": "hello", "proto": 1, "live": "12.4.1", "host": "min"})
        send_json(conn, {"t": "sec", "s": "session", "d": {"tempo": 120.0, "sig": [4, 4], "playing": False}})
        if args.hostile:
            hostile(conn)
        while True:
            op, data = read_frame(conn)
            if op == 0x8:
                conn.sendall(frame(0x8, b""))
                break
            if op == 0x9:  # WebSocket ping -> pong
                conn.sendall(frame(0xA, data))
            elif op == 0x1 and args.pong:
                try:
                    if json.loads(data).get("t") == "ping":
                        send_json(conn, {"t": "pong"})
                except ValueError:
                    pass
    except (EOFError, OSError, ValueError):
        pass
    finally:
        conn.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9020)
    ap.add_argument("--pong", action="store_true")
    ap.add_argument("--hostile", action="store_true")
    args = ap.parse_args()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(5)
    print("fake_min_server on :%d (pong=%s hostile=%s)" % (args.port, args.pong, args.hostile))
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve, args=(conn, args), daemon=True).start()


if __name__ == "__main__":
    main()
