# Live Object Model notes (Live 12.4.1)

Facts here must be VERIFIED (by the probe script `remote_script/LiveCompanion/probe.py` output against the
real Live, or by observed behavior). Mark unverified assumptions with `(?)`.

## Status
- Probe not yet run. Everything below is from prior knowledge — treat as `(?)` until verified.

## Expected API used (?)
- Song: tracks, return_tracks, master_track, scenes, view.selected_track/selected_scene/detail_clip, is_playing,
  start_playing/stop_playing/continue_playing, tempo, tap_tempo, signature_numerator/denominator, current_song_time,
  record_mode, session_record, arrangement_overdub, punch_in/out, loop, loop_start, loop_length, metronome,
  count_in_duration, clip_trigger_quantization, midi_recording_quantization, can_undo/undo, can_redo/redo,
  capture_midi, stop_all_clips, re_enable_automation, back_to_arranger, session_automation_record,
  jump_by, jump_to_next_cue/prev_cue, create_audio_track/midi_track/return_track, delete_track, duplicate_track,
  create_scene, song_length, move_device(?).
- Track: name, color, arm, can_be_armed, mute, solo, current_monitoring_state, clip_slots, arrangement_clips,
  take_lanes(?), create_take_lane(?), devices, mixer_device(volume,panning,sends), output_meter_left/right,
  input_routing_type/available_input_routing_types, input_routing_channel, output_routing_type,
  is_foldable, fold_state, group_track, playing_slot_index, fired_slot_index, stop_all_clips, delete_device,
  delete_clip(?), duplicate_clip_to_arrangement(?), create_midi_clip(start,len)(?), create_audio_clip(path,pos)(?).
- ClipSlot: has_clip, clip, fire, stop, create_clip(len), delete_clip, duplicate_clip_to(slot), has_stop_button,
  is_triggered, is_playing, is_recording.
- Clip: get_notes_extended, add_new_notes, apply_note_modifications, remove_notes_by_id(?), remove_notes_extended,
  quantize, duplicate_loop, crop, warp_markers, add_warp_marker/move_warp_marker/remove_warp_marker(?),
  file_path, sample_rate(?), sample_length.
- Browser (Application.browser): instruments, audio_effects, midi_effects, drums, sounds, samples, packs,
  user_library, plugins, clips, load_item, preview_item, stop_preview.
