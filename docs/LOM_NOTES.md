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

## Verified ABSENT
- No `move_track` / track reordering anywhere in the API. Track order cannot be changed remotely.

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
