#!/usr/bin/env python3
"""LiveCompanion desktop helper: waveform peaks, Live launch, recent sets.

Stdlib only, Python 3.11+. Run: python helper/helper.py --port 9012
HTTP API is documented in docs/PROTOCOL.md ("Helper HTTP").
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import logging
import math
import os
import re
import shutil
import struct
import subprocess
import sys
from array import array
from bisect import bisect_right
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Iterator, NamedTuple
from urllib.parse import parse_qs, urlsplit

VERSION = "0.1.0"
DEFAULT_PORT = 9012
BLOCK_FRAMES = 65536          # frames decoded per block; keeps memory flat for long files
N_MIN, N_MAX, N_DEFAULT = 16, 8192, 512
SETS_LIMIT = 50
CACHE_DIR = Path(__file__).resolve().parent / ".cache"
LOG = logging.getLogger("helper")

_HOST_BIG = sys.byteorder == "big"
_WIN_DRIVE = re.compile(r"^([A-Za-z]):[\\/](.*)$")
_U8_TO_S8 = bytes(((i - 128) & 0xFF) for i in range(256))   # unsigned 8-bit WAV -> signed


class HelperError(Exception):
    def __init__(self, status: int, msg: str):
        super().__init__(msg)
        self.status = status
        self.msg = msg


class UnsupportedFormat(Exception):
    """The native decoder does not handle this file; the caller may try ffmpeg."""


class _Hdr(NamedTuple):
    sr: int
    nch: int
    frames: int
    bps: int          # bytes per sample
    kind: str         # "int" | "float"
    big: bool         # sample byte order on disk
    offset: int       # byte offset of the first sample frame
    unsigned8: bool   # WAV 8-bit is unsigned, AIFF 8-bit is signed


# ---------------------------------------------------------------- paths

def translate_path(p: str, platform: str | None = None, wineprefix: str | None = None) -> str:
    """Map a path as Live reports it to a local path. Only Linux (Wine) translates."""
    plat = platform or sys.platform
    if not plat.startswith("linux"):
        return p
    m = _WIN_DRIVE.match(p)
    if not m:
        return p                                   # already a unix path
    drive, rest = m.group(1).upper(), m.group(2).replace("\\", "/")
    if drive == "Z":
        return "/" + rest.lstrip("/")
    prefix = (wineprefix or os.environ.get("WINEPREFIX") or os.path.expanduser("~/.wine")).rstrip("/")
    if drive == "C":
        return prefix + "/drive_c/" + rest
    dosdev = f"{prefix}/dosdevices/{drive.lower()}:"
    if os.path.isdir(dosdev):
        return dosdev + "/" + rest
    return p


def _to_wine_path(unix_path: str) -> str:
    return "Z:" + unix_path.replace("/", "\\")


# ---------------------------------------------------------------- WAV / AIFF headers

def _parse_wav(f, size: int) -> _Hdr:
    fmt = None
    data_off = data_len = None
    pos = 12
    while pos + 8 <= size and (fmt is None or data_off is None):
        f.seek(pos)
        head = f.read(8)
        if len(head) < 8:
            break
        cid, csize = head[:4], struct.unpack("<I", head[4:])[0]
        body = pos + 8
        if cid == b"fmt ":
            raw = f.read(min(csize, 64))
            if len(raw) < 16:
                raise HelperError(422, "bad WAV fmt chunk")
            tag, nch, sr, _br, align, bits = struct.unpack("<HHIIHH", raw[:16])
            if tag == 0xFFFE and len(raw) >= 26:      # WAVE_FORMAT_EXTENSIBLE: real tag is in the GUID
                tag = struct.unpack("<H", raw[24:26])[0]
            fmt = (tag, nch, sr, align, bits)
        elif cid == b"data":
            data_off = body
            data_len = csize
            if data_len == 0xFFFFFFFF or body + data_len > size:   # streaming writers leave this wrong
                data_len = max(0, size - body)
        pos = body + csize + (csize & 1)
    if fmt is None or data_off is None:
        raise HelperError(422, "WAV has no fmt or data chunk")
    tag, nch, sr, align, bits = fmt
    if nch < 1 or sr < 1:
        raise HelperError(422, "bad WAV channel count or sample rate")
    bps = align // nch if align and align % nch == 0 else (bits + 7) // 8
    if tag == 1 and bps in (1, 2, 3, 4):
        kind = "int"
    elif tag == 3 and bps in (4, 8):
        kind = "float"
    else:
        raise UnsupportedFormat(f"WAV format tag {tag} / {bits} bit")
    frames = data_len // (bps * nch)
    return _Hdr(sr, nch, frames, bps, kind, False, data_off, unsigned8=(kind == "int" and bps == 1))


def _ieee80(b: bytes) -> float:
    """Decode an 80-bit IEEE extended float (AIFF sample rate)."""
    exp = ((b[0] & 0x7F) << 8) | b[1]
    sign = b[0] & 0x80
    mant = struct.unpack(">Q", b[2:10])[0]
    if exp == 0 and mant == 0:
        return 0.0
    val = mant * 2.0 ** (exp - 16383 - 63)
    return -val if sign else val


_AIFC_CODECS = {
    b"NONE": ("int", True), b"twos": ("int", True),      # big-endian PCM
    b"sowt": ("int", False),                               # little-endian PCM
    b"fl32": ("float", True), b"FL32": ("float", True),
    b"fl64": ("float", True), b"FL64": ("float", True),
}


def _parse_aiff(f, size: int) -> _Hdr:
    comm = None
    ssnd = None
    pos = 12
    while pos + 8 <= size and (comm is None or ssnd is None):
        f.seek(pos)
        head = f.read(8)
        if len(head) < 8:
            break
        cid, csize = head[:4], struct.unpack(">I", head[4:])[0]
        body = pos + 8
        if cid == b"COMM":
            raw = f.read(min(csize, 64))
            if len(raw) < 18:
                raise HelperError(422, "bad AIFF COMM chunk")
            nch, nframes, bits = struct.unpack(">hIh", raw[:8])
            sr = _ieee80(raw[8:18])
            ctype = raw[18:22] if len(raw) >= 22 else b"NONE"
            comm = (nch, nframes, bits, sr, ctype)
        elif cid == b"SSND":
            off, _blk = struct.unpack(">II", f.read(8))
            data_start = body + 8 + off
            data_len = max(0, min(csize - 8 - off, size - data_start))
            ssnd = (data_start, data_len)
        pos = body + csize + (csize & 1)
    if comm is None or ssnd is None:
        raise HelperError(422, "AIFF has no COMM or SSND chunk")
    nch, nframes, bits, sr, ctype = comm
    if ctype not in _AIFC_CODECS:
        raise UnsupportedFormat(f"AIFC compression {ctype!r}")
    kind, big = _AIFC_CODECS[ctype]
    if nch < 1 or sr < 1:
        raise HelperError(422, "bad AIFF channel count or sample rate")
    bps = (bits + 7) // 8
    if kind == "float" and bps not in (4, 8):
        raise UnsupportedFormat("AIFC float with unusual sample size")
    if kind == "int" and bps not in (1, 2, 3, 4):
        raise UnsupportedFormat("AIFF sample size")
    data_start, data_len = ssnd
    frames = min(nframes, data_len // (bps * nch))
    return _Hdr(sr, nch, frames, bps, kind, big, data_start, unsigned8=False)


# ---------------------------------------------------------------- sample decoding

def _unpack24(raw: bytes, big: bool) -> array:
    """24-bit samples -> int32 (value << 8), using a little-endian 4-byte layout."""
    n = len(raw) // 3
    buf = bytearray(4 * n)
    if big:
        buf[1::4] = raw[2::3]
        buf[2::4] = raw[1::3]
        buf[3::4] = raw[0::3]
    else:
        buf[1::4] = raw[0::3]
        buf[2::4] = raw[1::3]
        buf[3::4] = raw[2::3]
    s = array("i")
    s.frombytes(bytes(buf))
    if _HOST_BIG:
        s.byteswap()
    return s


def _decode_block(raw: bytes, h: _Hdr) -> list:
    if h.kind == "float":
        s = array("f" if h.bps == 4 else "d")
        s.frombytes(raw)
        if h.big != _HOST_BIG:
            s.byteswap()
    elif h.bps == 1:
        s = array("b")
        s.frombytes(raw.translate(_U8_TO_S8) if h.unsigned8 else raw)
    elif h.bps == 2:
        s = array("h")
        s.frombytes(raw)
        if h.big != _HOST_BIG:
            s.byteswap()
    elif h.bps == 3:
        s = _unpack24(raw, h.big)
    else:
        s = array("i")
        s.frombytes(raw)
        if h.big != _HOST_BIG:
            s.byteswap()
    return [s[c::h.nch] for c in range(h.nch)]


def _scale(h: _Hdr) -> float:
    if h.kind == "float":
        return 1.0
    return {1: 128.0, 2: 32768.0}.get(h.bps, 2147483648.0)


def _read_blocks(f, h: _Hdr) -> Iterator[tuple[list, int]]:
    fb = h.bps * h.nch
    f.seek(h.offset)
    left = h.frames
    while left > 0:
        m = min(BLOCK_FRAMES, left)
        raw = f.read(m * fb)
        got = len(raw) // fb
        if got == 0:
            break
        yield _decode_block(raw[: got * fb], h), got
        left -= got


# ---------------------------------------------------------------- bucketing

def _accumulate(blocks, frames: int, nch: int, n: int):
    """Stream (channel arrays, count) blocks into n min/max buckets per channel."""
    edges = [k * frames // n for k in range(n + 1)]
    edges[n] = sys.maxsize          # last bucket absorbs any overrun (ffmpeg frame estimates)
    mins = [[math.inf] * n for _ in range(nch)]
    maxs = [[-math.inf] * n for _ in range(nch)]
    pos = 0
    for chans, m in blocks:
        end = pos + m
        k = bisect_right(edges, pos) - 1
        while k < n and edges[k] < end:
            lo = max(edges[k], pos) - pos
            hi = min(edges[k + 1], end) - pos
            if hi > lo:
                for c in range(nch):
                    seg = chans[c][lo:hi]
                    mn, mx = min(seg), max(seg)
                    if mn < mins[c][k]:
                        mins[c][k] = mn
                    if mx > maxs[c][k]:
                        maxs[c][k] = mx
            k += 1
        pos = end
    return mins, maxs, pos


def _result(sr: int, nch: int, frames: int, n: int, mins, maxs, scale: float) -> dict:
    def clean(v: float) -> float:
        if math.isinf(v):           # empty bucket (fewer frames than buckets)
            return 0.0
        return round(max(-1.0, min(1.0, v / scale)), 4)

    return {
        "sr": sr,
        "ch": nch,
        "frames": frames,
        "n": n,
        "min": [[clean(v) for v in mins[c]] for c in range(nch)],
        "max": [[clean(v) for v in maxs[c]] for c in range(nch)],
    }


# ---------------------------------------------------------------- decoders

def _decode_native(path: str, n: int) -> dict:
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        head = f.read(12)
        if head[:4] == b"RIFF" and head[8:12] == b"WAVE":
            h = _parse_wav(f, size)
        elif head[:4] == b"FORM" and head[8:12] in (b"AIFF", b"AIFC"):
            h = _parse_aiff(f, size)
        else:
            raise UnsupportedFormat("not WAV or AIFF")
        mins, maxs, got = _accumulate(_read_blocks(f, h), h.frames, h.nch, n)
    return _result(h.sr, h.nch, got, n, mins, maxs, _scale(h))


_LAYOUTS = {"mono": 1, "stereo": 2, "5.1": 6, "5.1(side)": 6, "7.1": 8}


def _probe_ffmpeg(exe: str, path: str) -> tuple[int, int, int]:
    """Return (sr, channels, estimated frames) for the first audio stream."""
    ffprobe = shutil.which("ffprobe")
    if ffprobe:
        out = subprocess.run(
            [ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries",
             "stream=sample_rate,channels:format=duration", "-of", "json", path],
            capture_output=True, text=True)
        if out.returncode == 0:
            j = json.loads(out.stdout or "{}")
            st = (j.get("streams") or [{}])[0]
            if "sample_rate" in st and "channels" in st:
                sr, nch = int(st["sample_rate"]), int(st["channels"])
                dur = float((j.get("format") or {}).get("duration") or 0)
                return sr, nch, int(dur * sr)
    banner = subprocess.run([exe, "-hide_banner", "-i", path],
                            capture_output=True, text=True).stderr
    m = re.search(r"Audio:.*?(\d+) Hz, ([^,\n]+)", banner)
    if not m:
        raise HelperError(422, "ffmpeg found no audio stream")
    layout = m.group(2).strip()
    nch = _LAYOUTS.get(layout)
    if nch is None:
        mc = re.match(r"(\d+) channels", layout)
        nch = int(mc.group(1)) if mc else 2
    return int(m.group(1)), nch, 0


def _decode_ffmpeg(path: str, n: int) -> dict:
    exe = shutil.which("ffmpeg")
    if not exe:
        raise HelperError(415, "unsupported audio format (install ffmpeg for mp3/flac/ogg)")
    sr, nch, est_frames = _probe_ffmpeg(exe, path)
    cmd = [exe, "-v", "error", "-i", path, "-vn", "-map", "0:a:0",
           "-f", "f32le", "-acodec", "pcm_f32le", "-"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    fb = 4 * nch
    size = BLOCK_FRAMES * fb

    def blocks():
        while True:
            raw = proc.stdout.read(size)
            got = len(raw) // fb
            if got == 0:
                break
            s = array("f")
            s.frombytes(raw[: got * fb])
            if _HOST_BIG:
                s.byteswap()
            yield [s[c::nch] for c in range(nch)], got

    try:
        mins, maxs, got = _accumulate(blocks(), est_frames or n, nch, n)
    except Exception:
        proc.kill()
        raise
    finally:
        proc.stdout.close()
    rc = proc.wait()
    if rc != 0 and got == 0:
        raise HelperError(422, "ffmpeg could not decode the file")
    return _result(sr, nch, got, n, mins, maxs, 1.0)


# ---------------------------------------------------------------- peaks + cache

def _cache_path(local: str, mtime_ns: int, n: int) -> Path:
    key = hashlib.sha1(f"{local}|{mtime_ns}|{n}".encode("utf-8")).hexdigest()
    return CACHE_DIR / f"{key}.json"


def _cache_read(p: Path):
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _cache_write(p: Path, data: dict) -> None:
    try:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        tmp = p.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
        os.replace(tmp, p)
    except OSError as e:
        LOG.warning("cache write failed: %s", e)


def get_peaks(raw_path: str, n: int) -> dict:
    local = translate_path(raw_path)
    if not os.path.isfile(local):
        raise HelperError(404, f"no such file: {local}")
    cpath = _cache_path(local, os.stat(local).st_mtime_ns, n)
    cached = _cache_read(cpath)
    if cached is not None:
        LOG.info("peaks cache hit n=%d %s", n, os.path.basename(local))
        return cached
    try:
        res = _decode_native(local, n)
    except UnsupportedFormat:
        res = _decode_ffmpeg(local, n)
    LOG.info("peaks decoded n=%d frames=%d %s", n, res["frames"], os.path.basename(local))
    _cache_write(cpath, res)
    return res


# ---------------------------------------------------------------- sets + launch

def _set_roots() -> list[Path]:
    home = Path.home()
    return [home / "Documents" / "Ableton", home / "Music"]


def list_sets(limit: int = SETS_LIMIT) -> list[dict]:
    found: dict[str, tuple[str, str, float]] = {}
    for root in _set_roots():
        if not root.is_dir():
            continue
        for dirpath, dirnames, files in os.walk(root):
            dirnames[:] = [d for d in dirnames if d.lower() != "backup"]   # Live's autosave copies
            for fn in files:
                if not fn.lower().endswith(".als"):
                    continue
                p = os.path.join(dirpath, fn)
                try:
                    mt = os.stat(p).st_mtime
                except OSError:
                    continue
                found[os.path.normcase(os.path.abspath(p))] = (fn, p, mt)
    newest = sorted(found.values(), key=lambda t: t[2], reverse=True)[:limit]
    return [{"name": Path(fn).stem, "path": p, "mtime": int(mt)} for fn, p, mt in newest]


def _natural_key(s: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def find_live_exe(platform: str | None = None, wineprefix: str | None = None) -> str | None:
    plat = platform or sys.platform
    if plat.startswith("linux"):
        prefix = (wineprefix or os.environ.get("WINEPREFIX") or os.path.expanduser("~/.wine")).rstrip("/")
        pattern = os.path.join(prefix, "drive_c", "ProgramData", "Ableton", "Live 12*",
                               "Program", "Ableton Live 12*.exe")
    else:
        pattern = r"C:\ProgramData\Ableton\Live 12*\Program\Ableton Live 12*.exe"
    matches = glob.glob(pattern)
    return max(matches, key=_natural_key) if matches else None


def build_launch_cmd(exe: str, set_local: str | None, platform: str | None = None) -> list[str]:
    plat = platform or sys.platform
    args = [exe]
    if set_local:
        args.append(set_local if plat.startswith("win") else _to_wine_path(set_local))
    return ["wine", *args] if plat.startswith("linux") else args


def _spawn(cmd: list[str], cwd: str) -> subprocess.Popen:
    kw = {}
    if os.name == "nt":
        kw["creationflags"] = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    return subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.DEVNULL,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            close_fds=True, **kw)


def launch_live(set_raw: str | None) -> dict:
    set_local = None
    if set_raw:
        set_local = translate_path(set_raw)
        if not os.path.isfile(set_local):
            raise HelperError(404, f"no such set: {set_local}")
    exe = find_live_exe()
    if not exe:
        raise HelperError(404, "Ableton Live 12 not found under ProgramData\\Ableton or the Wine prefix")
    cmd = build_launch_cmd(exe, set_local)
    try:
        proc = _spawn(cmd, os.path.dirname(exe))
    except FileNotFoundError as e:
        raise HelperError(500, f"cannot start Live: {e}") from e
    LOG.info("launched Live pid=%s set=%s", proc.pid, set_local or "-")
    return {"ok": True, "pid": proc.pid, "exe": exe, "set": set_local}


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = f"LiveCompanionHelper/{VERSION}"

    def log_message(self, fmt, *args):          # one concise line per request via logging
        LOG.info("%s %s", self.client_address[0], fmt % args)

    def _send(self, status: int, obj) -> None:
        body = json.dumps(obj, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _fail(self, e: Exception) -> None:
        if isinstance(e, HelperError):
            self._send(e.status, {"ok": False, "err": e.msg})
        else:
            LOG.exception("unhandled error")
            self._send(500, {"ok": False, "err": f"{type(e).__name__}: {e}"})

    def do_GET(self):
        url = urlsplit(self.path)
        q = parse_qs(url.query)
        try:
            if url.path == "/health":
                self._send(200, {"ok": True, "version": VERSION})
            elif url.path == "/peaks":
                path = (q.get("path") or [""])[0]
                if not path:
                    raise HelperError(400, "missing path")
                self._send(200, get_peaks(path, _parse_n((q.get("n") or [None])[0])))
            elif url.path == "/sets":
                self._send(200, list_sets())
            else:
                raise HelperError(404, "not found")
        except Exception as e:
            self._fail(e)

    def do_POST(self):
        url = urlsplit(self.path)
        try:
            if url.path != "/launch":
                raise HelperError(404, "not found")
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            try:
                req = json.loads(body) if body.strip() else {}
            except ValueError as e:
                raise HelperError(400, "body must be JSON") from e
            if not isinstance(req, dict):
                raise HelperError(400, "body must be a JSON object")
            set_raw = req.get("set")
            if set_raw is not None and not isinstance(set_raw, str):
                raise HelperError(400, "set must be a string path")
            self._send(200, launch_live(set_raw))
        except Exception as e:
            self._fail(e)


def _parse_n(raw: str | None) -> int:
    if raw is None or raw == "":
        return N_DEFAULT
    try:
        n = int(raw)
    except ValueError as e:
        raise HelperError(400, "n must be an integer") from e
    return max(N_MIN, min(N_MAX, n))


def make_server(host: str = "0.0.0.0", port: int = DEFAULT_PORT) -> ThreadingHTTPServer:
    return ThreadingHTTPServer((host, port), Handler)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="LiveCompanion desktop helper")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%H:%M:%S")
    server = make_server(args.host, args.port)
    LOG.info("helper %s listening on %s:%d", VERSION, args.host, server.server_address[1])
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
