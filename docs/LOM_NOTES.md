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
