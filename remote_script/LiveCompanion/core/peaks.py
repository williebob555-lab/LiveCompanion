"""WAV/AIFF waveform peaks for the remote script. Stdlib only (Python 3.11, runs inside Live).

Decoding is block-streamed so memory stays flat. Pure file I/O: never touches the LOM, so it is safe on a
worker thread. `PeaksWorker` runs ONE background thread; results are collected by the main-thread tick.
Decoder code is shared with helper/helper.py (which keeps its own copy plus ffmpeg + HTTP)."""
from __future__ import annotations

import hashlib
import json
import math
import os
import queue
import struct
import sys
import tempfile
import threading
from array import array
from bisect import bisect_right
from collections import OrderedDict
from typing import Iterator, NamedTuple

BLOCK_FRAMES = 65536
N_MIN, N_MAX = 16, 8192
MEM_CACHE = 32
_HOST_BIG = sys.byteorder == "big"
_U8_TO_S8 = bytes(((i - 128) & 0xFF) for i in range(256))   # unsigned 8-bit WAV -> signed


class PeaksError(Exception):
    """File problem (missing / malformed); message goes to the client."""


class UnsupportedFormat(Exception):
    """Not WAV/AIFF PCM; the phone falls back to the helper."""


class _Hdr(NamedTuple):
    sr: int
    nch: int
    frames: int
    bps: int          # bytes per sample
    kind: str         # "int" | "float"
    big: bool         # sample byte order on disk
    offset: int       # byte offset of the first sample frame
    unsigned8: bool   # WAV 8-bit is unsigned, AIFF 8-bit is signed


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
                raise PeaksError("bad WAV fmt chunk")
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
        raise PeaksError("WAV has no fmt or data chunk")
    tag, nch, sr, align, bits = fmt
    if nch < 1 or sr < 1:
        raise PeaksError("bad WAV channel count or sample rate")
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
                raise PeaksError("bad AIFF COMM chunk")
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
        raise PeaksError("AIFF has no COMM or SSND chunk")
    nch, nframes, bits, sr, ctype = comm
    if ctype not in _AIFC_CODECS:
        raise UnsupportedFormat(f"AIFC compression {ctype!r}")
    kind, big = _AIFC_CODECS[ctype]
    if nch < 1 or sr < 1:
        raise PeaksError("bad AIFF channel count or sample rate")
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



def decode_peaks(path: str, n: int) -> dict:
    """{sr,ch,frames,n,min:[[..] per ch],max:[...]} values -1..1."""
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        head = f.read(12)
        if head[:4] == b"RIFF" and head[8:12] == b"WAVE":
            h = _parse_wav(f, size)
        elif head[:4] == b"FORM" and head[8:12] in (b"AIFF", b"AIFC"):
            h = _parse_aiff(f, size)
        else:
            raise UnsupportedFormat("unsupported format")
        mins, maxs, got = _accumulate(_read_blocks(f, h), h.frames, h.nch, n)
    return _result(h.sr, h.nch, got, n, mins, maxs, _scale(h))


def _cache_dir() -> str:
    return os.path.join(tempfile.gettempdir(), "LiveCompanion", "peaks")


class PeaksJob(object):
    def __init__(self, key, path, n, owner, rid):
        self.key, self.path, self.n, self.owner, self.rid = key, path, n, owner, rid
        self.cancelled = False
        self.ok = False
        self.data = None       # result dict or error string


class PeaksWorker(object):
    """One daemon thread; `submit()` from the main thread, `poll()` from the main thread each tick."""

    def __init__(self, disk_cache=True, cache_dir=None):
        self.q = queue.Queue()
        self.done = queue.Queue()
        self.mem = OrderedDict()
        self.disk = disk_cache
        self.dir = cache_dir or _cache_dir()
        self.latest = {}            # (owner, path) -> newest job; older queued jobs are skipped
        self.thread = None
        self.lock = threading.Lock()

    def submit(self, path, n, owner, rid):
        job = PeaksJob((path, n), path, n, owner, rid)
        with self.lock:
            prev = self.latest.get((owner, path))
            if prev is not None:
                prev.cancelled = True
            self.latest[(owner, path)] = job
            if self.thread is None:
                self.thread = threading.Thread(target=self._run, name="LiveCompanionPeaks", daemon=True)
                self.thread.start()
        self.q.put(job)
        return job

    def cancel_owner(self, owner):
        with self.lock:
            for (o, _p), j in list(self.latest.items()):
                if o == owner:
                    j.cancelled = True
                    del self.latest[(o, _p)]

    def poll(self):
        out = []
        while True:
            try:
                j = self.done.get_nowait()
            except queue.Empty:
                return out
            with self.lock:
                if self.latest.get((j.owner, j.path)) is j:
                    del self.latest[(j.owner, j.path)]
            if not j.cancelled:
                out.append(j)

    def stop(self):
        self.q.put(None)

    def _run(self):
        while True:
            job = self.q.get()
            if job is None:
                return
            if job.cancelled:
                continue
            try:
                job.data = self._compute(job.path, job.n)
                job.ok = True
            except UnsupportedFormat as e:
                job.data = str(e) or "unsupported format"
            except (PeaksError, OSError, struct.error, ValueError, MemoryError) as e:
                job.data = "cannot read audio file: %s" % (e,)
            except Exception as e:
                job.data = "error: %s: %s" % (type(e).__name__, e)
            self.done.put(job)

    def _compute(self, path, n):
        mtime = os.stat(path).st_mtime_ns
        key = (path, mtime, n)
        hit = self.mem.get(key)
        if hit is not None:
            self.mem.move_to_end(key)
            return hit
        cp = os.path.join(self.dir, hashlib.sha1(("%s|%s|%s" % key).encode("utf-8")).hexdigest() + ".json")
        if self.disk:
            try:
                with open(cp, "r", encoding="utf-8") as f:
                    res = json.load(f)
                self._remember(key, res)
                return res
            except (OSError, ValueError):
                pass
        res = decode_peaks(path, n)
        self._remember(key, res)
        if self.disk:
            try:
                os.makedirs(self.dir, exist_ok=True)
                tmp = cp + ".tmp"
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(res, f, separators=(",", ":"))
                os.replace(tmp, cp)
            except OSError:
                pass
        return res

    def _remember(self, key, res):
        self.mem[key] = res
        while len(self.mem) > MEM_CACHE:
            self.mem.popitem(last=False)
