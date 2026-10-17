# Live Object Model notes (Live 12.4.1)

VERIFIED from the real probe (`~/LiveCompanion_probe.json`, written by `probe.py` on script load, 2026-10-15).
Re-run: reload the control surface in Live; the file is rewritten. Unverified items marked `(?)`.

## Verified present
- Song: `move_device`, `scrub_by`, `jump_by`, `jump_to_next_cue/prev_cue`, `nudge_up/down`, `capture_and_insert_scene`,
  cue_points, scale info (root_note, scale_name, scale_intervals, scale_mode), groove/swing, tempo_follower,
  listeners for tracks/scenes/current_song_time/is_playing/etc.
- Track (audio/midi/return/master share the class): `arrangement_clips`, `clip_slots`, `take_lanes`,
  `create_take_lane`, `create_audio_clip`, `create_midi_clip`, `delete_clip`, `duplicate_clip_slot`,
  `duplicate_clip_to_arrangement`, `insert_device` (native devices by name), `delete_device`, `duplicate_device`,
  `jump_in_running_session_clip`, `is_frozen`, input/output meters (`input_meter_level`, `output_meter_left/right`),
  `devices` listener (add_devices_listener) — use it so browser loads appear immediately.
- ClipSlot: `create_audio_clip`, `delete_clip`, `duplicate_clip_to`, playing_status, controls_other_clips.
- Device: parameters listener, `latency_in_ms`, view.is_collapsed. Application: average/peak process usage (CPU).
- song.view: selected_chain, selected_parameter, draw_mode, follow_song, detail_clip.

## Verified on Windows (2026-10-16, Live 12.4.2)
- Audio recorded in the CURRENTLY OPEN set is locked by Live (no share-read): open() → Errno 13, even from the
  script inside Live. Older/saved samples are readable. The sidecar "<file>.wav.asd" IS readable → waveform source
  (decoded by core/asd.py, see next section). Last resort while a clip records: the meter envelope (core/recenv.py).

## .asd overview format (reverse-engineered)
**Confidence: HIGH for WAV recordings** - verified on 110+ owner files (mono/stereo, 44.1/48 kHz, 24/32-bit): header
fields matched the wav in every pair; bins vs the true wav min/max: mean abs error 0.002, median ~0, p99 0.04 (outliers
= a transient sitting in a 128-frame bin that straddles a bucket edge, max ~0.5 at n=256). Live 12.4.2 file version.
Re-verify any time with `python tools/asd_probe.py [dir]` (read-only; prints mismatches). Layout (all little-endian):
- `06 49 | u32 n | n x u32` = seek table into the preprocessed (warp) data. **Last entry == total sample frames.**
  Then 4 unknown bytes, `u32 0`, `u32 100`, **`u32 2*channels`** (offset `6+4*(n+1)+8`), then a typed object stream
  (type names are length-prefixed; strings UTF-16LE) with warp markers / onsets / tempo estimate. Sample rate is NOT stored.
- Overview = 3 records `0x13 "SampleOverViewLevel"` + `u32 level` + `u32 count` + `count x float16`. (The same type name
  also appears earlier as schema text with level=1,count=18 - skip until the first level==0 record.)
- Level k holds **min,max pairs per bin, channels interleaved per bin** `[ch0 min, ch0 max, ch1 min, ch1 max, ...]`,
  bins = ceil(frames / 128^(k+1))  -> level 0 = 128 frames/bin, level 1 = 16384, level 2 = 2097152 (whole file).
  Values are IEEE half floats, already -1..1 (24-bit sources are normalised). No abs-peak-only data: true min AND max.
- File is written when Live analyses the clip (present while the set is open even if the wav is locked). Unknown: files
  from mp3/flac (not tested), very old Live versions. A missing/odd .asd -> `AsdError` -> next fallback.

## Verified ABSENT
- No `move_track` / track reordering anywhere in the API. `track.move` is therefore a REBUILD-MOVE (create track at index, `song.move_device`, clip copies, delete original) - **track.move = rebuild-move, unverified on real Live** (assumes `create_*_track(index)` returns/places the track at `index`, `Track.duplicate_clip_to_arrangement(clip_of_other_track, t)` works, `parameter.automation_state` exists, `Track.is_frozen`). Automation envelopes, group membership, frozen state and take lanes are NOT carried (server reports them as `losses`).

## Track view assumptions (unverified on real Live)
- arr.trim sets end_marker (and loop_end when growing a looping clip) / start_marker and reads start_time/end_time back; if Live does not move the clip edge the change is reverted and an error returned.
- arr.move =  duplicate_clip_to_arrangement then deletes the original (delete failure ignored when the copy overwrote it).

## Not yet probed (no clip existed in the probed set)
- Clip members (notes API, warp markers, start_time settability for moving arrangement clips) `(?)`.
  Moving an arrangement clip = duplicate_clip_to_arrangement at new time + delete_clip (works per API presence).

## Take-lane clip creation (UNVERIFIED on real Live 12.4.1)
`takes.demote` / `takes.move` assume `TakeLane.create_audio_clip(file_path, start_time)` and `TakeLane.create_midi_clip(start_time, length)`
exist (mirrors Track.create_audio_clip/create_midi_clip) and that `Track.delete_clip(clip)` also deletes a take-lane clip. The ops check
`hasattr` first and answer `unsupported: take-lane clip creation (TakeLane.create_*_clip missing in this Live)` without touching anything; a
delete failure after a successful copy is reported as `copied, but could not remove the original`. Verify with the next probe (tools/live_verify.py --mutate).

## Edition-dependent devices
`track.insert_device(name, i)` raises `ValueError: Device <name> not available.` for devices the edition lacks (Live 12.4.2 Standard has no Amp, Cabinet, Roar, ...). The installed set = top-level `is_device` items under `application.browser.instruments / audio_effects / midi_effects` (server op `device.available`); never hard-code a device list. `device.insert` maps the ValueError to a friendly `err` and logs one line.

## Arrangement editing - verified recipes, Live 12.4.x (owner's real Live, Windows, 2026-10-16/17)
Experiments ran against the owner's open (disposable) set through the existing ops (clip.set / clip.to_arr / notes.add),
waiting >= 1 s and re-reading after each step. Remote-script implementation: `core/arrange.py` (same code runs on the fake LOM).
- `start_time` / `end_time` are READ-ONLY. Writing `start_marker` / `end_marker` never moves a clip edge (this is why the
  old arr.trim "failed"). `clip.crop` did not resize either. A clip's left edge only changes by `duplicate_clip_to_arrangement`.
- Model for an UNLOOPED arrangement clip (looping False): it is the region [loop_start, loop_end] where `loop_start`
  ALIASES `start_marker` (writing it sets the content start, `start_time` stays; writing `start_marker` while unlooped is inert).
  `end_time = start_time + (loop_end - loop_start) / units_per_beat`. Writing `loop_end` moves the RIGHT edge (shorter and longer).
  `end_marker` is independent (writes do nothing visible). Examples: clip 100..104, loop 0..4 -> loop_end=3 gives end 103;
  loop_end=6 gives 106; loop_start=1 gives start_marker 1, end 103 (start_time still 100); loop_start=0 restores.
- LOOPED clip: edges are fixed; `loop_start`/`loop_end` edit only the brace. To resize: `looping=False` (region :=
  [start_marker, end_marker], loop values then alias the region), set the region, `looping=True` again: Live KEEPS the stored
  loop brace, the clip keeps the resized length and `end_marker` becomes the region end. Verified: 120..127.47 clip with brace 1..4
  -> unloop, loop_end=5, reloop -> 120..125, brace still 1..4.
- Units: warped audio and MIDI are in beats; UNWARPED audio is in seconds (loop_start 0.5 beat -> 0.25 at 120 bpm file tempo) and
  `end_time` uses the SONG tempo (1.75 s -> 3.118 beats at the owner's tempo). Unwarped `loop_end` clamps at the file length
  (2.0 s file: 3.0 -> 2.0). WARPED clips accept far more than the file (loop_end 100 on a 4-beat file -> clamped to 20 beats),
  so the script clamps to the file itself (beat of the last warp marker + remaining seconds at the last segment's rate).
- MIDI: same model; notes outside the region are KEPT (hidden): shortening to 3 then extending to 8 shows all notes again.
- `duplicate_clip_to_arrangement(clip, t)` copies EVERYTHING: name, colour, gain, pitch, warp mode, mute, loop/markers, notes
  (verified). It returns None in the real API (so identify the new clip by `start_time`). It overwrites what it lands on, including
  part of the source when the target overlaps it; delete_clip(original handle) afterwards removes the remnant (or raises when the
  original was fully overwritten - ignore). Unverified: automation envelopes (assumed copied with the clip).
- RECIPES (all keep the content under the same song time, like Live's own edge drag):
  * move: duplicate at the new start, delete original, delete any remnant inside the old footprint, find new clip by start.
  * trim END (any state): [unloop] set loop_end = loop_start + (newEnd-start)*units [reloop].
  * trim START later by d: shrink in place first (loop_start += d*units, clip is now [s, e-d]) then MOVE the clip right by d.
  * trim START earlier by d: MOVE the clip left by d first, then loop_start -= d*units (end grows back to e inside the old footprint,
    so no neighbour is overwritten beyond the original footprint). Clamped at the audio file start (content start 0).
  * looped clips: content start p = start_marker + d with the loop phase kept (p wraps into the brace when past loop_end).
  * Setting order for loop_start/loop_end never grows the clip beyond max(old,new) length (growth would overwrite neighbours).
- DANGER (cost an hour): setting `warping` back to True on an unwarped clip makes Live open a MODAL dialog "Would you like to keep
  the current timing of the clip(s)?" - the whole remote script (single main thread) stops answering until someone clicks
  Yes/No/Cancel in Live. NEVER toggle warping from scripts/tests on a real set. (Turning warping OFF is silent.)
- Gotchas seen: clip.set gain_db=-6 on the real clip produced gain 1.0 (+24 dB): the dB refinement loop in ops.clip_set diverges - TODO.
- Re-run on the reloaded script: `tools/live_edit_check.py` (the whole matrix) - see TODO.
