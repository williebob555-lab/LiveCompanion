"""Arrangement clip editing: move and trim edges (start/end, shorter and longer, audio + MIDI).

Recipes VERIFIED on real Live 12.4 (see docs/LOM_NOTES.md "Arrangement editing"):
  * An arrangement clip's start_time/end_time can NOT be set. Setting start_marker/end_marker does not move edges.
  * While `looping` is OFF the clip is the region [loop_start, loop_end] (loop_start aliases start_marker):
        end_time = start_time + (loop_end - loop_start) / units_per_beat
    Setting loop_end moves the RIGHT edge. Setting loop_start moves the CONTENT start; start_time stays put.
  * A looped clip: looping=False, resize via the region, looping=True again. The loop brace is kept by Live.
  * The left edge (start_time) only changes by duplicate_clip_to_arrangement(clip, t) + delete_clip(original);
    the copy keeps name/colour/gain/pitch/warp/mute/envelopes/loop settings and, for MIDI, all notes.
  * Units: warped audio and MIDI = beats; unwarped audio = seconds (units_per_beat = 60 / song tempo).
  * Never toggle `warping` from here: Live raises a modal "keep timing?" dialog that blocks the whole script.
Pure Python (no Live imports) so the fake LOM runs the same code."""
from .util import OpError, BadArgs, Unsupported, g, num, seq, lane_clips

EPS = 1e-6
POS_TOL = 2e-3          # beats: clips whose start_time is this close are "the same position"
MIN_LEN = 0.0625        # 1/16 beat


def clips_of(cr):
    """The clip list the ClipRef lives in (main arrangement lane or one take lane)."""
    if cr.kind == "take":
        return list(lane_clips(cr.lane))
    return list(seq(g(cr.track, "arrangement_clips", ())))


def edges(clip):
    return num(g(clip, "start_time", 0.0)), num(g(clip, "end_time", 0.0))


def find_by_start(clips, start, tol=POS_TOL):
    best = None
    for k, x in enumerate(clips):
        d = abs(edges(x)[0] - start)
        if d <= tol and (best is None or d < best[0]):
            best = (d, k, x)
    return (best[1], best[2]) if best else (None, None)


def units_per_beat(song, clip):
    """Clip-time units per song beat: seconds-per-beat for unwarped audio, otherwise 1 (beats)."""
    if g(clip, "is_audio_clip", False) and not g(clip, "warping", True):
        return 60.0 / max(num(g(song, "tempo", 120.0), 120.0), 1.0)
    return 1.0


def audio_limits(song, clip):
    """(lo, hi) extent of the audio file in clip units, or None when unknown / not audio."""
    if not g(clip, "is_audio_clip", False):
        return None
    sr, frames = num(g(clip, "sample_rate", 0.0)), num(g(clip, "sample_length", 0.0))
    if sr <= 0 or frames <= 0:
        return None
    secs = frames / sr
    if not g(clip, "warping", True):
        return (0.0, secs)
    try:
        ms = [(num(g(m, "beat_time", 0.0)), num(g(m, "sample_time", 0.0))) for m in seq(g(clip, "warp_markers", ()))]
    except Exception:
        ms = []
    if not ms:
        return None
    ms.sort()
    tempo = max(num(g(song, "tempo", 120.0), 120.0), 1.0)

    def spb(i, j):      # seconds per beat between two markers (fallback: song tempo)
        db = ms[j][0] - ms[i][0]
        ds = ms[j][1] - ms[i][1]
        return ds / db if db > 1e-9 and ds > 1e-9 else 60.0 / tempo
    first = spb(0, 1) if len(ms) > 1 else 60.0 / tempo
    last = spb(-2, -1) if len(ms) > 1 else 60.0 / tempo
    hi = ms[-1][0] + (secs - ms[-1][1]) / last
    lo = ms[0][0] - ms[0][1] / first
    return (max(0.0, lo), hi)


def _set(clip, attr, val):
    try:
        setattr(clip, attr, val)
    except Exception as e:
        raise OpError("Live refused %s=%.4g (%s)" % (attr, val, e))


def _set_flag(clip, attr, val):
    try:
        setattr(clip, attr, val)
    except Exception as e:
        raise OpError("Live refused %s (%s)" % (attr, e))
    if bool(g(clip, attr, val)) != val:
        raise OpError("Live ignored %s=%s" % (attr, val))


def set_region(clip, p, q):
    """Set the unlooped region [p, q] in an order that never grows the clip beyond max(old, new) length
    (growing would make Live overwrite neighbours)."""
    a, b = num(g(clip, "loop_start", 0.0)), num(g(clip, "loop_end", 0.0))
    cap = max(b - a, q - p) + EPS
    if q > a and q - a <= cap:
        order = ("loop_end", "loop_start")
    elif p < b and b - p <= cap:
        order = ("loop_start", "loop_end")
    elif q > a:
        order = ("loop_end", "loop_start")
    else:
        order = ("loop_start", "loop_end")
    vals = {"loop_start": p, "loop_end": q}
    cur = {"loop_start": a, "loop_end": b}
    for k in order:
        if abs(cur[k] - vals[k]) > EPS:
            _set(clip, k, vals[k])


def set_brace(clip, ls, le):
    """Restore a loop brace on a looping clip without ever passing through start >= end."""
    ce = num(g(clip, "loop_end", 0.0))
    order = ("loop_end", "loop_start") if ls >= ce else ("loop_start", "loop_end")
    want = {"loop_start": ls, "loop_end": le}
    for k in order:
        if abs(num(g(clip, k, 0.0)) - want[k]) > EPS:
            _set(clip, k, want[k])


def move_clip(cr, new_start):
    """Move an arrangement clip: duplicate at new_start, delete the original, clean up any remnant.
    Returns (index, clip) of the moved clip in the (re-read) clip list."""
    track = cr.track
    if cr.kind == "take" or not hasattr(track, "duplicate_clip_to_arrangement") or not hasattr(track, "delete_clip"):
        raise Unsupported("moving this clip")
    clip = cr.clip
    old_s, old_e = edges(clip)
    others = set((round(edges(x)[0], 3), round(edges(x)[1], 3)) for x in clips_of(cr))
    others.discard((round(old_s, 3), round(old_e, 3)))
    try:
        track.duplicate_clip_to_arrangement(clip, float(new_start))
    except Exception as e:
        raise OpError("Live could not move the clip (%s)" % (e,))
    try:
        track.delete_clip(clip)      # may already be gone: the copy overwrites an overlapping original
    except Exception:
        pass
    clips = clips_of(cr)
    idx, new = find_by_start(clips, float(new_start))
    if new is None:
        raise OpError("the moved clip did not appear at the new position")
    # a remnant of the original (when the copy only partly overwrote it) lies inside the old footprint
    for k, x in enumerate(list(clips)):
        if k == idx:
            continue
        s, e = edges(x)
        if s >= old_s - POS_TOL and e <= old_e + POS_TOL and (round(s, 3), round(e, 3)) not in others:
            try:
                track.delete_clip(x)
            except Exception:
                pass
    return find_by_start(clips_of(cr), float(new_start))


def result(cr, clip, extra=None):
    s, e = edges(clip)
    idx, _ = find_by_start(clips_of(cr), s)
    out = {"i": idx, "start": s, "end": e}
    if extra:
        out.update(extra)
    return out


def move(cr, start):
    if cr.kind == "take":
        raise BadArgs("take-lane clips move with takes.move")
    clip = cr.clip
    if g(clip, "is_recording", False):
        raise BadArgs("this clip is still recording")
    s, _e = edges(clip)
    if abs(s - start) < POS_TOL:
        return result(cr, clip)
    _idx, new = move_clip(cr, start)
    return result(cr, new)


def trim(song, cr, ns=None, ne=None):
    """Trim to absolute song beats [ns, ne] keeping the content under every song time. Returns {i,start,end[,clamped]}."""
    clip = cr.clip
    if g(clip, "is_recording", False):
        raise BadArgs("this clip is still recording")
    for attr in ("loop_start", "loop_end", "looping"):
        if not hasattr(clip, attr):
            raise Unsupported("trimming this clip")
    cs, ce = edges(clip)
    ns = cs if ns is None else float(ns)
    ne = ce if ne is None else float(ne)
    if ne - ns < MIN_LEN - 1e-9:
        raise BadArgs("clip would be shorter than 1/16 beat")
    if cr.kind == "take" and abs(ns - cs) > POS_TOL:
        raise BadArgs("take-lane clips can only be trimmed at the end")
    u = units_per_beat(song, clip)
    was_loop = bool(g(clip, "looping", False))
    sm0 = num(g(clip, "start_marker", 0.0))
    brace = (num(g(clip, "loop_start", 0.0)), num(g(clip, "loop_end", 0.0)))
    if was_loop:
        _set_flag(clip, "looping", False)
    cur = clip
    clamped = False
    try:
        a, b = num(g(clip, "loop_start", 0.0)), num(g(clip, "loop_end", 0.0))
        # Un-looping a looped clip resizes it to its marker region, so measure the model AFTER the toggle
        cs1, ce1 = edges(clip)
        if abs((b - a) - (ce1 - cs1) * u) > 0.02 * max(b - a, 1.0) + 1e-3:
            raise OpError("this clip does not follow the expected arrangement model (region %.3g vs length %.3g)"
                          % (b - a, (ce1 - cs1) * u))
        lim = audio_limits(song, clip)
        if lim is not None and lim[1] < b - 0.01 * max(1.0, b):
            lim = None                  # our idea of the file length contradicts the clip: do not clamp
        d = ns - cs
        p = a + d * u
        if was_loop and abs(d) > POS_TOL:
            ls_l, le_l = brace
            ln = le_l - ls_l
            p = sm0 + d * u
            if ln > 1e-9 and (p >= le_l - 1e-9 or (p < ls_l and sm0 >= ls_l - 1e-9)):
                p = ls_l + (p - ls_l) % ln          # same loop phase as the original at this song time
            elif p < 0:
                p = 0.0
        lo = lim[0] if lim else 0.0
        if p < lo - 1e-9:
            p = lo
            clamped = True
            ns = cs + (p - a) / u
        q = p + (ne - ns) * u
        if lim is not None and not was_loop and q > lim[1] + 1e-9:
            q = lim[1]
            clamped = True
        if q - p < MIN_LEN * u - 1e-9:
            raise BadArgs("nothing left of the audio there")
        ne = ns + (q - p) / u
        dd = ns - cs
        if dd > POS_TOL:                 # later start: shrink in place first, then move right
            set_region(clip, p, q)
            _i, cur = move_clip(cr, ns)
        elif dd < -POS_TOL:              # earlier start: move left first, then extend the content start
            _i, cur = move_clip(cr, ns)
            set_region(cur, p, q)
        else:
            set_region(clip, p, q)
        # read back; correct once if Live's tempo ratio differs from ours (unwarped audio)
        s2, e2 = edges(cur)
        ra, rb = num(g(cur, "loop_start", 0.0)), num(g(cur, "loop_end", 0.0))
        if abs(e2 - ne) > 2e-3 and rb - ra > EPS:
            r = (e2 - s2) / (rb - ra)
            if r > 1e-9:
                set_region(cur, ra, ra + (ne - s2) / r)
    except Exception:
        if was_loop:                    # failed half-way: put the clip's length back before re-looping
            try:
                a0, b0 = num(g(cur, "loop_start", 0.0)), num(g(cur, "loop_end", 0.0))
                cs0, ce0 = edges(cur)
                if abs(ce0 - ce) > 2e-3 and abs(cs0 - cs) < 2e-3:
                    set_region(cur, a0, a0 + (ce - cs0) * u)
            except Exception:
                pass
        raise
    finally:
        if was_loop:
            try:
                _set_flag(cur, "looping", True)
                set_brace(cur, brace[0], brace[1])
            except Exception:
                pass
    return result(cr, cur, {"clamped": True} if clamped else None)
