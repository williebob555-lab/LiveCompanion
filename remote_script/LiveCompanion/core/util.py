"""Shared helpers: errors, defensive getters, reference resolution. No Live imports."""
import math


class OpError(Exception):
    """Operation failed; message goes to the client as `err`."""


class Deferred(object):
    """Returned by an op whose `res` is sent later (from a tick) by Core; the worker finishes off-thread."""


class BadArgs(OpError):
    pass


class Unsupported(OpError):
    def __init__(self, what):
        OpError.__init__(self, "unsupported: " + str(what))


def g(obj, name, default=None):
    """getattr that also swallows exceptions raised by LOM property access."""
    try:
        return getattr(obj, name, default)
    except Exception:
        return default


def num(x, default=0.0):
    try:
        f = float(x)
    except Exception:
        return default
    if math.isnan(f) or math.isinf(f):
        return default
    return f


def intv(x, default=0):
    try:
        return int(x)
    except Exception:
        return default


def need(a, key, types=None):
    if key not in a:
        raise BadArgs("missing arg: " + key)
    v = a[key]
    if types is not None and not isinstance(v, types):
        raise BadArgs("bad arg type: " + key)
    if isinstance(v, bool) and types is not None:
        ts = types if isinstance(types, tuple) else (types,)
        if bool not in ts and object not in ts:
            raise BadArgs("bad arg type: " + key)
    return v


def need_num(a, key):
    v = need(a, key)
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise BadArgs("bad arg type: " + key)
    if math.isnan(v) or math.isinf(v):
        raise BadArgs("bad arg value: " + key)
    return v


def need_int(a, key):
    v = need_num(a, key)
    if int(v) != v:
        raise BadArgs("expected integer: " + key)
    return int(v)


def seq(x):
    try:
        return list(x)
    except Exception:
        return []


# ---------------------------------------------------------------- references

def parse_ref(ref):
    """'t3' -> ('t',3); 'r1' -> ('r',1); 'm' -> ('m',0)."""
    if not isinstance(ref, str) or not ref:
        raise BadArgs("bad track ref")
    k = ref[0]
    if k == "m" and len(ref) == 1:
        return "m", 0
    if k in ("t", "r") and ref[1:].isdigit():
        return k, int(ref[1:])
    raise BadArgs("bad track ref: " + ref)


def resolve_track(song, ref):
    k, i = parse_ref(ref)
    if k == "m":
        return song.master_track
    lst = song.tracks if k == "t" else song.return_tracks
    try:
        n = len(lst)
    except Exception:
        n = 0
    if i >= n:
        raise BadArgs("no such track: " + ref)
    return lst[i]


def track_ref(song, track):
    """Reverse lookup (identity/equality scan). Returns None if not found."""
    try:
        if track == song.master_track:
            return "m"
    except Exception:
        pass
    for prefix, lst in (("t", song.tracks), ("r", song.return_tracks)):
        for i, t in enumerate(lst):
            if t is track or t == track:
                return "%s%d" % (prefix, i)
    return None


def slots_of(track):
    return seq(g(track, "clip_slots", ()))


def is_int(i):
    return isinstance(i, int) and not isinstance(i, bool)


def resolve_slot(song, tr, i):
    track = resolve_track(song, tr)
    slots = slots_of(track)
    if not is_int(i) or i < 0 or i >= len(slots):
        raise BadArgs("no such slot: %s/%s" % (tr, i))
    return track, slots[i]


def lane_clips(lane):
    c = g(lane, "arrangement_clips", None)
    if c is None:
        c = g(lane, "clips", ())
    return seq(c)


class ClipRef(object):
    __slots__ = ("clip", "track", "kind", "slot", "index", "lane")

    def __init__(self, clip, track, kind, slot=None, index=0, lane=None):
        self.clip, self.track, self.kind = clip, track, kind
        self.slot, self.index, self.lane = slot, index, lane


def resolve_clip(song, loc):
    if not isinstance(loc, dict):
        raise BadArgs("bad loc")
    w = loc.get("w")
    tr = loc.get("tr")
    i = loc.get("i")
    if not is_int(i) or i < 0:
        raise BadArgs("bad loc index")
    if w == "slot":
        track, slot = resolve_slot(song, tr, i)
        if not g(slot, "has_clip", False):
            raise BadArgs("no clip in slot")
        return ClipRef(slot.clip, track, "slot", slot, i)
    track = resolve_track(song, tr)
    if w == "arr":
        clips = seq(g(track, "arrangement_clips", ()))
        if i >= len(clips):
            raise BadArgs("no such arrangement clip")
        return ClipRef(clips[i], track, "arr", None, i)
    if w == "take":
        lane_i = loc.get("lane")
        lanes = seq(g(track, "take_lanes", ()))
        if not is_int(lane_i) or not 0 <= lane_i < len(lanes):
            raise BadArgs("no such take lane")
        clips = lane_clips(lanes[lane_i])
        if i >= len(clips):
            raise BadArgs("no such take")
        return ClipRef(clips[i], track, "take", None, i, lanes[lane_i])
    raise BadArgs("bad loc.w")


def loc_key(loc):
    try:
        return (loc.get("w"), loc.get("tr"), loc.get("lane"), loc.get("i"))
    except Exception:
        return None


def resolve_device(song, tr, path):
    """-> (device, owner, index). `owner` is the track or chain holding it (has delete_device)."""
    track = resolve_track(song, tr)
    if not isinstance(path, str) or not path:
        raise BadArgs("bad device path")
    toks = path.split(".")
    owner = track
    devs = seq(g(track, "devices", ()))
    dev = None
    idx = None
    pos = 0
    while pos < len(toks):
        t = toks[pos]
        if not t.isdigit():
            raise BadArgs("bad device path")
        idx = int(t)
        if idx >= len(devs):
            raise BadArgs("no such device")
        dev = devs[idx]
        pos += 1
        if pos < len(toks):
            c = toks[pos]
            if not (c[:1] == "c" and c[1:].isdigit()):
                raise BadArgs("bad device path")
            chains = seq(g(dev, "chains", ()))
            ci = int(c[1:])
            if ci >= len(chains):
                raise BadArgs("no such chain")
            owner = chains[ci]
            devs = seq(g(owner, "devices", ()))
            pos += 1
            if pos >= len(toks):
                raise BadArgs("bad device path")
    return dev, owner, idx
