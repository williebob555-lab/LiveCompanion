# Wire protocol v1 (source of truth)

Transport: WebSocket (RFC 6455, text frames, JSON, UTF-8) on TCP **9010**, path `/`.
Discovery: remote script broadcasts UDP **9011** every 1 s:
`{"svc":"livecompanion","proto":1,"name":"<hostname>","ws":9010,"helper":9012,"set":"<song name or ''>"}`.
Helper HTTP on **9012** (see bottom). Multiple clients allowed; each has its own focus.

## Envelope
| Dir | Shape | Meaning |
|---|---|---|
| S→C | `{"t":"hello","proto":1,"live":"12.4.1","host":"win\|wine\|mock"}` | first message after connect |
| C→S | `{"t":"req","id":7,"op":"track.set","a":{...}}` | request; `id` unique per connection |
| S→C | `{"t":"res","id":7,"ok":true,"d":<any>}` / `{"t":"res","id":7,"ok":false,"err":"msg"}` | reply to every req. Usually immediate; **deferred** ops (`clip.peaks`) reply from a later tick, so replies may arrive out of order — match by `id` |
| S→C | `{"t":"sec","s":"<section>","d":<obj>}` | FULL replacement of a state section |
| S→C | `{"t":"rt","d":{...}}` | realtime frame, ≤20 Hz |
| S→C | `{"t":"log","lvl":"warn","msg":"..."}` | user-visible notice (e.g. op unsupported) |
| C→S | `{"t":"ping"}` → S→C `{"t":"pong"}` | keepalive (client every 3 s; drop after 10 s silence) |

On connect the server sends hello, then all global sections. Focus sections are sent after `focus.set`.
Client treats `hello` as "connected" (missing sections = empty states; a server that never answers JSON ping is kept alive via WS-level ping). `clip.set` rejects (ok:false, err) negative/absurd values and start_marker>=end_marker / loop_start>=loop_end.
Client must treat sections as authoritative; optimistic UI updates are allowed but reconciled by the next `sec`.

## References
- Track ref (string): `"t<N>"` = `song.tracks[N]`, `"r<N>"` = `song.return_tracks[N]`, `"m"` = master.
- Clip locator `loc` (object):
  - `{"w":"slot","tr":"t0","i":3}` — session clip in slot 3
  - `{"w":"arr","tr":"t0","i":2}` — `track.arrangement_clips[2]`
  - `{"w":"take","tr":"t0","lane":1,"i":0}` — `track.take_lanes[1].clips[0]`
- Device path (string): dot-separated indices from the track: `"2"` = devices[2]; `"2.c1.0"` = devices[2].chains[1].devices[0].
- Colors are 0xRRGGBB ints. Times are in beats (float). Parameter values are raw floats + `str` display string.

## Sections
**session** (global)
`{tempo, sig:[num,den], playing, pos, rec, session_rec, overdub, punch_in, punch_out, loop, loop_start, loop_len,
metronome, count_in, quant, rec_quant, can_undo, can_redo, back_to_arr, auto_arm, view:"session"|"arranger",
sel_track:<ref>, sel_scene:int, song_len, cues:[{t,n}] (locators), follow:<bool, this client follows Live's selected track>, cpu:<0..1 Live CPU load, application.average_process_usage>,
scale:{root:0-11,name,intervals:[0-11]}|null (song.root_note/scale_name/scale_intervals)}`

**tracks** (global) `{tracks:[T], returns:[T], master:T, scenes:[{i,name,color}]}` with
`T = {ref, name, color, kind:"audio"|"midi"|"group"|"return"|"master", armable, arm, mute, solo, monitor:0|1|2 (In/Auto/Off),
group:<ref|null>, folded, foldable, playing_slot:int(-1), fired_slot:int(-1), vol:P (returns/master only)}`

**focus** `{ref, name, color, kind, armable, arm, mute, solo, monitor, vol:P, pan:P, sends:[P+{name}],
in_type, in_types:[str], in_ch, in_chs:[str], out_type, out_types:[str], playing_slot, fired_slot}`
where `P = {v, min, max, str}`.

**slots** `{tr, slots:[{i, has_clip, has_stop, status:"empty"|"stopped"|"playing"|"triggered"|"recording",
clip:{name,color,len,looping,audio,muted}|null}]}`

**arr** `{tr, clips:[{i,name,color,start,end,audio,muted,looping,start_marker,end_marker,loop_start,loop_end,
audio: file_path,warping,warp_first,warp_last; midi: pn:[[t,dur,pitch]..] (preview, thinned ~160/clip)}]}`

**takes** `{tr, supported:bool, lanes:[{i,name,clips:[{i,name,color,start,end}]}]}`

**devices** `{tr, devices:[D]}` with `D = {path,name,cls,on,type:"instrument"|"audio_fx"|"midi_fx"|"rack"|"other",
chains:[{i,name,devices:[D]}], macros:[{i,name,v,min,max,str}]|null, var:{count,sel}|null,
pp:[PP]}` where `PP = {i,name,v,min,max,str,quant,items,def}` are the first 8 parameters after Device On (empty for racks), shown inline in the phone's signal chain; `def` = Live default_value or null

**params** (the device opened with `device.open`) `{tr, path, name, params:[{i,name,v,min,max,str,quant:bool,items:[str]|null,def:number|null}]}`

**clip** (the clip opened with `clip.open`) `{loc, name,color,audio,len,looping,loop_start,loop_end,start_marker,end_marker,
start,end(arr only),signature:[n,d],rec:bool (clip.is_recording; loop_end/clip_pos grow while true), launch_mode,launch_quant,legato,muted,
audio-only: gain,gain_str,pitch_coarse,pitch_fine,warping,warp_mode,warp_modes:[str],file_path,sample_rate,sample_len,warp_markers:[{beat,sample_time}],
midi-only: notes:[N]}` where `N = {id,p,t,d,v,m(mute bool),prob,vd(velocity_deviation),rv(release_velocity)}`

**rt** frame `{pos, playing, beat_in_bar, cpu (0..1, same as session.cpu but refreshed every tick), meters:{focus:[l,r], master:[l,r]}, clip_pos:<float|null for opened clip>,
slot_pos:{<slot i>:<pos>} (playing slots on focused track)}`

## Ops (`a` = args). Unsupported → `ok:false, err:"unsupported: ..."`.
Song/transport: `song.play`, `song.stop`, `song.continue`, `song.tap_tempo`, `song.undo`, `song.redo`,
`song.capture_midi`, `song.stop_all_clips`, `song.re_enable_automation`, `song.jump {beats}` (relative),
`song.seek {beats}` (absolute), `song.scrub {beats}` (relative, LOM scrub_by),  (relative, LOM scrub_by), `song.cue {dir:1|-1}`, `cue.move {i, time}` (move locator i of `session.cues` to `time`; best effort: sets CuePoint.time if settable, else jumps to the cue, `song.set_or_delete_cue()` to delete, re-creates at `time`, restores name, playhead and play state; clear `err` if impossible or another locator is there),
`song.set {k,v}` k ∈ tempo, sig, metronome, loop, loop_start, loop_len, punch_in, punch_out, rec, session_rec,
overdub, count_in, quant, rec_quant, auto_arm, back_to_arr, view.
Scenes/tracks: `scene.fire {i}`, `scene.create {i}`, `track.create {kind:"audio"|"midi"|"return", i}`, `track.delete {tr}`,
`track.duplicate {tr}`,
`track.move_check {tr}` -> `{ok_to_move, losses:[str]}` (losses ⊂ "arrangement automation" | "group membership" | "frozen" | "take lanes"; refuses groups / returns / master with `err`),
`track.move {tr, to, force?}` (REBUILD-MOVE, `to` = FINAL index in the track list, returns `{tr}` = the track's new ref; focus of the caller follows it, other clients' focus is remapped):
if `track_move_check` has losses and `force` is not true -> `ok:false, err:"needs_confirm", d:{losses:[...]}` and nothing is touched. Otherwise: create a same-type track at the target position, `song.move_device` every device across in order,
`ClipSlot.duplicate_clip_to` every session clip, `duplicate_clip_to_arrangement` every arrangement clip, copy name/colour/mute/solo/arm/monitoring, volume/pan/sends and routing (by display name), delete the original. One tick; on any error the new
track is deleted again (devices moved back) and `err:"move failed, nothing changed: ..."`. Open clips of all clients are closed (indices shift). UNVERIFIED on real Live.
Focus: `focus.set {tr}`, `focus.follow {on}`, `view.select {tr}` (select track in Live).
Track: `track.set {tr,k,v}` k ∈ name,color,arm,mute,solo,monitor,folded,vol,pan,send(+`i`),in_type,in_ch,out_type;
`track.stop {tr}`.
Slots: `slot.fire {tr,i}`, `slot.create {tr,i,len}`, `slot.delete {tr,i}`, `slot.duplicate {tr,i}` (to the next FREE slot below, new scene if none),
`slot.copy {tr,i,to_tr,to_i,replace?}`, `slot.move {tr,i,to_tr,to_i,replace?|swap?}` — an OCCUPIED target is REFUSED (`err` contains "occupied") unless `replace:true` (overwrite) or, for move only, `swap:true` (exchange both clips via a scratch slot),
`take.next {tr}` — take loop: arms track, fires first empty slot at/after last recorded one (creates scene if none).
Names the new clip "Take N" (N = highest existing "Take n" on the track + 1; a newly created scene gets the same name). Returns `{i, name}`.
Clips: `clip.fire {loc}`, `clip.stop {loc}`, `clip.scrub {loc,beat}` / `clip.stop_scrub {loc}` (LOM Clip.scrub, session clips; `unsupported` if the API is missing; the clip editor ruler scrub uses `song.seek` for arrangement clips and these for session clips), `clip.open {loc}`, `clip.close`, `clip.set {loc,k,v}`
k ∈ name,color,sig_num (1-99),sig_den (1,2,4,8,16),looping,loop_start,loop_end,start_marker,end_marker,gain,gain_db (dB in displayed units; server converts and refines against gain_display_string),pitch_coarse,pitch_fine,warping,warp_mode,
launch_mode,launch_quant,legato,muted; `clip.delete {loc}`, `clip.duplicate_loop {loc}`, `clip.crop {loc}`,
`clip.quantize {loc,grid,amount}`, `clip.to_arr {loc,time}`, `arr.create_midi {tr,start,len}`,
`arr.move {loc,start}` (duplicate_clip_to_arrangement + delete original; returns `{i}` = new index),
`arr.trim {loc, start?, end?}` (absolute beats; refuses a result shorter than 1/16 beat; edits clip markers, verifies start_time/end_time, reverts + unsupported err if Live ignores it; unverified on real Live).
Notes (opened MIDI clip): `notes.add {notes:[N without id]}`, `notes.modify {notes:[N]}`, `notes.remove {ids}`,
`notes.clear`, `notes.transpose {ids,semis}`, `notes.duplicate {ids,offset}`.
Warp (opened audio clip): `warp.add {beat,sample_time?}`, `warp.move {beat,to_beat}`, `warp.remove {beat}`.
Takes: `takes.create_lane {tr}`, `takes.rename {tr,lane,name}`, `takes.promote {loc}` (copy take clip to main lane, same time),
`takes.demote {loc, lane}` (loc = `arr` clip; main-lane clip -> take lane `lane` at the same time: `TakeLane.create_audio_clip(file_path, start)` / `create_midi_clip(start, len)` + notes copied, name/colour/gain best effort, THEN the original is deleted; returns `{lane, i}` = where the copy is. If the lane API is missing: `ok:false, err:"unsupported: take-lane clip creation (...)"` and nothing was changed),
`takes.move {loc, lane}` (loc = `take` clip; copy into another take lane at the same time + delete original; same lane = no-op ok; same errors/return as demote). The phone drives all three from hold-drag (track view take lanes + Takes tab Main lane row).
Devices: `device.open {tr,path}` / `device.close`, `device.toggle {tr,path}`, `device.param {tr,path,i,v}`,
`device.delete {tr,path}`, `device.select {tr,path}`,
`device.move {tr,path,to,into?}` (to = FINAL index in destination; `into` = rack chain path like `"2.c1"` to move into a chain, absent = same list; server converts to Live's pre-removal insertion index for `song.move_device`),
`device.insert {tr,name,i?,into?}` (`track.insert_device(name,i)`, native device by display name, i default = end, `into` = chain path),
`device.available {}` -> `{instruments:[name], audio_effects:[name], midi_effects:[name]}` (top-level device items of those browser roots = the native devices of the user's Live edition; cached per session; the phone's "+" list uses it). An unavailable name in `device.insert` answers `err: "<name> isn't available in your Live edition"`,
`device.duplicate {tr,path}` (`duplicate_device`, copy lands right after the original). All three (and `device.delete`, `browser.load`) mark `devices` dirty immediately and keep re-checking it for ~4 s (Live applies loads late), so the phone sees the new tree within one tick of Live changing it. `device.var {tr,path,act:"store"|"recall"|"select"|"delete",i?}`,
`chain.select {tr,path,i}`.
Browser: `browser.list {path:[str]}` → `[{name,folder,loadable,uri?}]` (path `[]` = roots), `browser.load {tr,path}`,
`browser.preview {path}`, `browser.preview_stop`.
Meta: `sys.info` → `{live, proto, host, script_version:int, ops:[str]}`.
`script_version` (core `VERSION`, bumped whenever ops change; now 2) + `ops` let the phone detect an OLD script still loaded in Live: after `hello` the app sends `sys.info` and compares `ops` with its `REQUIRED_OPS`
(clip.peaks, track.move, track.move_check, cue.move, takes.demote, takes.move, arr.move, arr.trim, clip.scrub, device.move) and `script_version` with `MIN_SCRIPT_VERSION`. If outdated it shows a compact banner in the shell
("LiveCompanion script in Live is outdated - run the installer (or tools/install) and restart Live"); an op unknown to the script (`err:"unknown op: x"`) is shown as "Needs script update (x)" instead of a bare error (e.g. in place of a waveform).
An error `res` may carry `d` (e.g. `needs_confirm` losses).

## Clarifications (binding)
- `track.set` monitor value is int 0=In,1=Auto,2=Off. vol/pan/send `v` are raw parameter values.
- `slot.create.len` and all lengths/times are beats.
- After a successful `focus.set`, server sends fresh `focus, slots, arr, takes, devices` sections.
- `clip.stop {loc}`: slot → stops that slot; arr/take → no-op ok.
- `take.next {tr}` only. Play toggle = `song.play`/`song.stop`.
- `rt.slot_pos` keys are slot indices as strings (JSON object keys).
- Scene list length == slot count for every track.
- `clip.close`/`device.close` (or the opened object disappearing) → `sec clip`/`sec params` with `d:null`.
- `clip` section adds `notes_total`; notes capped at 5000 per section.
- `quant`, `rec_quant`, `launch_quant`, `clip.quantize.grid` are Live's raw enum ints (passed through).
- `auto_arm` ↔ `song.exclusive_arm`. `rt.beat_in_bar` is 0-based. A req without `id` gets `res` with `id:null`.
- `browser.list []` roots include `key` (e.g. "instruments"); path elements accept key or display name.

## clip.peaks (waveforms without the helper)
`clip.peaks {loc, n}` (loc: slot/arr/take; n 16..8192) -> `d = {sr,ch,frames,n,min:[[..] per ch],max:[...]}` (-1..1, 3 decimals).
Deferred reply: the file path is read on Live's main thread, decoding runs on ONE background thread (pure file I/O,
never the LOM), the `res` is sent by a later tick. Newer request for the same clip supersedes queued older ones
(superseded/disconnected jobs are skipped, no reply). Cached in memory (LRU 32, path+mtime+n) and on disk in
`<tmp>/LiveCompanion/peaks`. WAV/AIFF only; errors `ok:false`: `not an audio clip`, `no audio file`, `unsupported format`
(mp3/flac/ogg -> app falls back to the helper), `cannot read audio file: ...`, bad loc/n.
**Locked recordings (Live 12.4.2 Windows):** audio recorded in the open set can't be read (exclusive lock). Order tried:
1. the wav; 2. on PermissionError/OSError the analysis file `<path>.asd` (`core/asd.py`, exact min/max at 128-frame
resolution; reply has `"src":"asd"`, `sr` = the clip's `sample_rate` when the LOM reports it else 0);
3. the meter envelope recorded while the clip was recording (`core/recenv.py`): coarse, reply `"approx": true`
(`sr:1000, ch:1, frames` = clip milliseconds, `min` = -max); 4. `err: "audio file locked by Live (save the set to unlock)"`.
The app never caches failures or `approx` results (cache key = path + clip length + n) and keeps asking (now, +1.5 s, +3 s, then every 5 s) until a real result arrives; it re-asks immediately when recording stops.

## rt.rec_env (growing waveform while recording)
While a clip records on the FOCUSED track, `rt` frames carry `rec_env: {"k":"s<slot>"|"arr","step":0.0625,"f":F,"i":I,"v":[0..100]}`:
meter peaks (max of output/input meters, sampled every tick; grid points skipped between ticks are linearly ramped, so no stair steps) on an absolute song-time grid, `step` beats per
point (1/16 beat), point index = floor(song_beat/step). `f` = index of the first point of this recording (a change of `k` or `f`
= new recording, reset), `i` = index of `v[0]`, `v` = newest <= 160 points. The app draws a smooth mirrored filled envelope (linear interpolation) and fades the real waveform in when it arrives. Absent when nothing records. Arrangement
recording key is `arr` (needs `record_mode` + armed track + playing). The app merges frames (`RecEnvBuf`) and draws the shape
inside the red region (clip editor: axis unit -> song beat via the current playhead; track view: beat = song time).

## Helper HTTP (:9012) (optional: exotic-format peaks via ffmpeg, launch Live, sets)
- `GET /health` → `{ok:true,version}`
- `GET /peaks?path=<url-encoded clip file_path>&n=<buckets>` → `{sr,ch,frames,n,min:[[..]...per ch],max:[...]}`
  (values -1..1). Cached by path+mtime. Wine paths (`Z:\home\x`, `C:\users\x`) are translated on Linux.
- `GET /sets` → `[{name (file stem), path (helper-native), mtime (epoch seconds)}]`, newest 50 from
  `~/Documents/Ableton` and `~/Music` (skips `Backup/`).
- `POST /launch` body `{set?:path}` → `{ok,pid,exe,set}`; starts Live, opening `set` if given.
- `n` is clamped to 16..8192 (default 512). `path` is a helper-native path (Windows or Wine form).
- Errors: `{ok:false, err:"..."}` with 400 (bad args), 404 (no file / Live not found), 415 (format needs ffmpeg),
  422 (malformed file), 500.
- Peaks: WAV (PCM 8/16/24/32, float32/64, EXTENSIBLE) and AIFF/AIFC (NONE/twos/sowt/fl32/fl64) are native;
  other formats need `ffmpeg` + `ffprobe` on PATH.
