# TODO — workstation

Legend: `[ ]` todo · `[~]` in progress (who) · `[x]` done · `[!]` blocked (why)
Rule: update this file when starting/finishing anything. If interrupted, resume from "In progress".

## STATUS 2026-10-16 night: v0.1.1-test4 RELEASED (passed review; edge fix verified on emulator).
Unverified on real Live: track.move (rebuild), .asd waveform via clip.peaks inside Live, rec_env on real recording,
arr.move/trim, takes ops, cue.move → owner: restart Live, then `python tools/live_verify.py --mutate` on an empty set.
Minor open: clip-editor rec envelope unverified (mock), rename-dialog error text cut, tablet partly empty, Takes zoom.

## STATUS 2026-10-16 late: v0.1.1-test3 released after conformance review + re-checks (blocking clip-follow race
fixed & verified on emulator vs mock). Open nits (non-blocking, from qa/CONFORMANCE.md Re-check 2): rename-dialog
error text half-cut; tablet Session/Browser/Devices still partly empty; Takes opens too zoomed; demo "Add device"
inserts at chain start; demo lacks per-track clips for some tracks (Bass shows Drums clip in demo).
Unverified on real Live: arr.move/trim, takes promote/demote/move, cue.move, clip.peaks, device.insert names,
slot swap → run tools/live_verify.py --mutate with the owner.

## NOW (2026-10-16, owner testing on phone)
- [x] Track reorder (owner request): server `track.move_check` / `track.move` (rebuild-move, ops.py + fake LOM + pytest), app hold-drag grip + up/down buttons in picker and Tracks tab, loss-confirm dialog, "Moving...", DemoClient. Real-Live UNVERIFIED -> run `tools/live_verify.py --mutate` (new step "track.move"). Tap name = switch track, rename via pencil.
- [x] Locked-recording waveforms (owner: Live holds new recordings exclusively): clip.peaks falls back wav -> `<wav>.asd` overview (core/asd.py, format in LOM_NOTES, tools/asd_probe.py) -> meter envelope (core/recenv.py, `approx`) -> clear error; `rt.rec_env` draws a growing waveform in the red region (app: RecEnvBuf, dim approx peaks). Needs owner re-test on real Live (restart Live to load the new script).
- [x] Script-version check: sys.info `script_version` (core VERSION=2) + app REQUIRED_OPS -> outdated banner; unknown op -> "Needs script update".
- [x] Seamless edges: bar/rail/strip backgrounds extend into the rounded-corner insets (shell_corners*, shell_arrange_corners*).
- [x] Disconnect while reconnecting did nothing: the connection strip's touch blocker swallowed the button's taps; fixed + MinimalServerTest.disconnectDuringBackoffIsInstantAndFinal.
- [x] Conformance review #1 fixes (see qa/CONFORMANCE.md fix pass) - uncommitted, gate green; needs reviewer re-check on emulator. New: clip.scrub/clip.stop_scrub ops (real-Live unverified), compact top bar + CompactSecondaryBar.
- [x] Agent 1: Connect settings/updates, demo mode (DemoClient), editable track picker, zoom-out limits, 40dp top bar, rounded-corner insets (committed 2a7c643).
- [x] Agent 2: waveforms without helper (clip.peaks in remote script, background thread) (committed 2a7c643; real-Live check of Wine paths / large AIFF still pending).
- [x] Pass 3 (uncommitted, gate green): INSTANT FEEL everywhere - SectionGuard (state/SectionGuard.kt) + LiveStore.edit* for clip markers/loop/warp/props, slot move, arr/takes, session (punch, loop, seek); NoteEditor settle; TrackLane drag settles on match (no reset on any clips section); Timeline loop edge settle; playhead hold 600 ms; debug "Simulate 300 ms server delay" (More -> Help -> Developer; SimDelay honoured by LiveClient + DemoClient).
- [x] Pass 3: BodyCorners/LocalBodyCorners (Arrange bottom strips, Clip handle row, list tabs half), velocity "127" label out of the corner, LiveTextButton softWrap=false ("Fit" never wraps).
- [x] Pass 3: TAKES hold-drag (promote / demote / move) in track view + Takes tab (new Main lane row), server `takes.demote` + `takes.move` (ops.py, fake LOM TakeLane.create_*_clip, 3 new pytest tests), PROTOCOL.md/LOM_NOTES.md, DemoClient simulates takes.*, arr.move/trim/create_midi, clip.delete/to_arr, slot.move/copy/duplicate.
- [x] Pass 3: demo waveforms (assets/demo/peaks_sine_a.json, peaks_pad_chord.json; DemoClient clip.peaks resamples to the requested n).
- [ ] Open / known gaps: (a) TakeLane.create_audio_clip/create_midi_clip + delete_clip-on-lane are UNVERIFIED on real Live (ops answer `unsupported` cleanly; verify with tools/live_verify.py --mutate); (b) the clip editor has no playhead scrub (Live's clip view has none either); (c) take hold-drag in the track view needs the Takes strip shown (a mid-drag layout change would be worse); (d) promote/demote keep the clip's time (vertical drag only); (e) device param value STRINGS (not the knob) still follow the server echo; (f) not yet run on a phone/emulator with the delay toggle on.
- [ ] Then: CONFORMANCE REVIEW (reviewer agent fills qa/CONFORMANCE.md on emulator; FAILs fixed) -> release v0.1.1-test3 -> reinstall remote script.
- Process rule (owner, 2026-10-16): "do more reviewing with my obvious preferences" - every release gated on
  qa/CONFORMANCE.md; derive and apply Core principles proactively.

2026-10-16 waveforms without helper: `clip.peaks` op (remote_script core/peaks.py, background thread, deferred res) + Android PeaksClient WS-first/helper-fallback DONE, uncommitted. Pytest green; real-Live check pending (Wine path, large AIFF).
## RESUME HERE — Milestone 2 "UI lock-in" (owner feedback recorded in PREFERENCES 2026-10-15; owner asleep)
2026-10-16 P4 fix pass DONE, uncommitted (see qa/REVIEW_LOG P4 "fixed(pending commit)"): note-bar MIDI clips, selected-on-top, updater resume + test-channel UI, icon gap/scale, 3-row portrait chrome, browser chip fade, tracks multi-column + pinned master, device cell scaling + tablet scale, shared 300ms hold-drag. Next: commit, release test8, emulator re-check (icon, updater resume, device drag).
2026-10-16 night: QA passes 4–6 + fixes → test8, test9, test10; updater verified end-to-end incl. resume after
permission. Found updater lexical ordering bug ("test10" < "test9") → fixed; naming rule in PREFERENCES; released
v0.1.1-test1. One-click Windows setup (tools/setup_windows.ps1 + "Install LiveCompanion.cmd") added to releases
from v0.1.1-test2 on (helper autostart + firewall parts untested on this PC by design — owner runs them).
Remaining: owner's real-Live run of tools/live_verify.py --mutate; owner QA feedback.
2026-10-16 evening: test6 (pass-3 fixes + design polish) and test7 (full-height lane, one-row toolbar, nits)
RELEASED, signed. [~] QA pass 4 running: updater end-to-end test5→test7, icon verdict, portrait/tablet/small matrix.
Still pending owner: real-Live run of tools/live_verify.py --mutate (empty set).
2026-10-16 later: release signing set up (secrets + backup in %USERPROFILE%\LiveCompanion-signing), update check
fixed, icon redrawn (equal L/C) → RELEASED v0.1.0-test5 (versionCode 5, release-signed, verified). From test5 on,
in-app updates install in place. Icon not yet visually verified on a launcher — check on emulator next QA.
2026-10-16 latest: QA pass 2 fixes committed 241e85f, RELEASED v0.1.0-test4, remote script reinstalled.
2026-10-16 latest++: Track view fills the lane height (no 120dp clip cap; takes capped at 35% of lane area; MIDI preview = thin note lines over pitch range, min 1 octave); clip toolbar is ONE row at >=400dp panes (compact: icon-only Draw/Select, Vel/Prob cycle button); ALL open P3 nits fixed (browser, connect, tracks, info strip, monitor label, warp grip handles) - uncommitted, gate green.
2026-10-16 latest+: QA pass 3 fix pass DONE, uncommitted (all P3-1-*/2-*/3-*/4-* marked "fixed(pending commit)" in qa/REVIEW_LOG.md,
    nits left open: P3-2-4/5, P3-3-5/8/9, P3-4-5, warp-marker handles). Design critique applied: Arrange (Live-like clips, controls below the lane,
    nudge buttons, light locators/playhead, no orange), Session tiles + side control column, Clip icon toolbar + zoom limits, Devices content-sized
    panels + dropdown chips + move buttons, Mix de-duplicated, update UI (No stable release yet). Gate green (assembleDebug, unit tests, roborazzi, pytest).
    Next: owner review of screenshots / test6 release; emulator re-check of arrange drag cancel + nudge on a real device; real-Live verify arr.move overlap.
[~] QA pass 3 (emulator + mock only) running. Launching Live unattended was BLOCKED by the permission
    classifier → real-Live mutating verification (arr.trim/arr.move/swap/device.insert names/etc.) needs the owner
    present or explicit approval; prepare a scripted checklist (tools/live_verify.py) for that session.
Next: owner QA of test4 on real Live; open nits P2-1-12/13/14/17/18, P2-2-4/7; verify arr.trim/arr.move/swap on real Live.
2026-10-16: laptop restarted mid-QA (RAM) → resource rules in PREFERENCES. P2-R1 fixes committed fb66daa,
RELEASED v0.1.0-test3; remote script reinstalled to owner's User Library (Live restart needed to load it).
QA pass 2 continuation agent running (R1b/R2/R3/R4 + read-only P2-LIVE). Then: fix pass → test4.
Status: A–D DONE, committed 367edb2 (full gate green). QA pass 2 agent running (mock + read-only real Live).
Orchestrator-noted: clip toolbar overflows at 800x360 ("Del" cut); Arrange hint text row wastes height;
device panels mostly empty vertically (show more params when tall). Then fix pass → tag v0.1.0-test3.
Plan: 4 parallel sonnet agents, then QA pass + fix pass:
- [x] A Shell/visual (DONE, uncommitted; reorder note shown in Tracks tab): light-blue accent, 3–4dp radii, immersive fullscreen, keep-screen-on, cutout info strip,
      TRACK VIEW (Arrange) as default/front, Session as tab (not permanent half pane), Takes demoted,
      obvious collapse affordances, track list (add/remove/rename/color inline; reorder impossible → explain),
      app icon matching C:\Sampler App + C:\LivePatch branding.
- [x] B (done, uncommitted; see ui/track/*, ArrangeScreen.kt; arr.trim/song.scrub ops; real-Live verification of trim pending) Track view/playhead: smooth interpolated playhead + continuous scrub (scrub_by/song.seek throttled),
      snap off/1/16/beat/bar, nudge, typed locate; drag clips to move (dup_to_arr+delete), drag edges to trim.
- [x] C (DONE, uncommitted; screenshots clip_*.png, ClipGridTests) Clip editor rewrite: full-width L→R, Live-level detail (ruler, loop brace, markers drag, note names,
      fold, scale highlight, velocity lane, drag everything; props in a thin collapsible strip).
- [x] D Devices (done: signal chain UI ui/devices/*, device.move/insert/duplicate, pp inline params, devices burst re-mark, pending placeholder): horizontal signal chain with params inline, drag reorder (song.move_device), insert_device,
      server devices listener so browser loads appear within one tick (owner saw ~10 s lag).
- Then: re-run tools/dump_fixtures.py, QA pass 2 on new build + real Live, fix, tag v0.1.0-test3.
Port note: stop any mock on 9010 before using real Live (it blocked the script once).

### P2-R1 fix pass (uncommitted, awaiting gate review)
- [x] P2-1-1..6 fixed(pending commit): shared signature-aware Bbt (ui/track/Bbt.kt; BbtTests) used by transport, info strip,
  arrange, clip props and clip ruler; locate dialog validates inline (max = song length + 1000 bars); loop+punch drawn as
  loop brace + separate red IN/OUT flag strip; orientation migration (Prefs flag orientation_chosen_v2 -> landscape until chosen);
  clip props = right-side column in landscape (bottom panel capped at 40% in portrait).
- [x] Clip toolbar: one "1/16 v" grid picker popup; single row at >= 640dp wide. Arrange hint row removed (dismissible
  first-run tip per process + More -> Help). Devices: server preview params 8 -> 16, panels fill rows of 4 by height.
- Next: owner review of screenshots, then commit and tag v0.1.0-test3.


### P2-R1b/R2/R3 fix pass (uncommitted)
- [x] fixed(pending commit): P2-1-8 (slot.move/copy refuse occupied unless replace:true / swap:true; slot.duplicate uses next FREE slot; app asks Replace/Swap/Cancel, drag preview red), P2-1-9, P2-1-10, P2-1-11, P2-1-15, P2-1-16, P2-2-1, P2-2-2, P2-2-3, P2-2-5; arr.trim refuses < 1/16 beat.
- Still open (nits): P2-1-12 (new track colour/scroll-to), P2-1-13 (arrange initial fit), P2-1-14 (ruler label overlap), P2-1-17 (keyboard push), P2-1-18, P2-2-4 (pos rounding), P2-2-7 (tablet device panels).
- Gate run: assembleDebug, testDebugUnitTest, recordRoborazziDebug, pytest (71) green; gradle stopped. Real-Live check of swap/arr.trim pending.

## Earlier resume notes
- Released v0.1.0-test1 then v0.1.0-test2 (prerelease, tag on 8ca7296) with APK + remote-script zip + helper zip. SETUP.md written.
- QA R3/R4 + session-pane toggle fixed and released as v0.1.0-test2 (CI-built; not yet re-QA'd on emulator).
- Next: (1) easy setup: one-click Windows installer (tools/setup_windows.ps1: install script + helper
  autostart shortcut), helper bundled; (2) real-Live probe once owner selects control surface
  (~/LiveCompanion_probe.json → docs/LOM_NOTES.md); (3) QA pass 2; (4) owner QA feedback.

## In progress
- [x] QA pass 1 (R1–R4) done; R1/R2 fixed in 493f60d.
- [~] Fix agent: QA R3/R4 + collapsible session pane + tempo ellipsis.
- Next: QA pass 2 (regression of all Q* on new build + real Live once probe runs), then milestone report + test release v0.1.0-test1.
- [!] Real-Live probe blocked on owner selecting LiveCompanion control surface in Live.
- [~] M1.1 python-core agent (sonnet) — remote script, core, fake LOM, mock, tests
- [~] screens agent A (sonnet): Arrange + Takes (+ ui/components/Timeline.kt)
- [~] screens agent B (sonnet): Clip editor (props, piano roll, waveform via helper)
- [~] screens agent C (sonnet): Devices + Browser + Mix
- [x] screens agent C done (Devices/Browser/Mix, Dense.kt, ColorPalette.kt) — uncommitted until build green
- [x] screens agent A done (Arrange/Takes, Timeline.kt) — uncommitted until build green
- [x] Shell tab hook (LocalTabNavigator), Open in Clip (Session menu/Arrange/Takes), Takes ruler fixed (TimelineVScroll). [ ] arr clip drag-move still open
- [x] Protocol gap DONE (server+Models+Mix return/master faders) (vol on returns/master): tracks section T lacks vol for returns/master → add `vol:P` to T (server + Models + Mix return strip faders)
- [x] Density pass done (32dp rows, single-line tabs, 11-12sp); contract test (tools/dump_fixtures.py + ContractTest) done; S1 auto-focus, S2 pan label bg done.
- Next (was): density pass on Shell/Session (orchestrator noted rows/buttons ~48dp, too chunky vs "dense" pref),
  then emulator smoke against mock, then QA agent rounds.
- Done this phase: android-core (a7447d5), helper+CI (90a6b45), AVDs lc_phone_small/lc_phone/lc_tablet created.

## Milestone 1 — "Broad strokes" (everything wired end-to-end, mock-tested) → owner QA
### M1.0 Foundations (orchestrator)
- [x] Docs system (CLAUDE.md, docs/*, qa/REVIEW_LOG.md, this file)
- [x] git init + public repo williebob555-lab/LiveCompanion + .gitignore
- [x] Toolchain: JDK (Android Studio JBR) on PATH for builds, Gradle wrapper, emulator system image + AVDs
### M1.1 Remote script + core + mock (agent: python-core)
- [x] `core/` protocol: WebSocket server (non-blocking, stdlib), JSON envelope, ping/pong, multi-client
- [x] Sections serializers: session, tracks, focus, slots, arr, takes, devices, params, clip, rt
- [x] Listener manager: global cheap listeners + focused-track detail listeners, dirty flags, per-tick flush
- [x] All ops in PROTOCOL.md (graceful `unsupported` where LOM lacks it)
- [x] UDP discovery beacon
- [x] ControlSurface wrapper `__init__.py` + install script `tools/install_remote_script.ps1` (+ .sh for Wine)
- [x] `probe.py`: dumps dir() of key LOM objects to Log.txt / a JSON file → fills docs/LOM_NOTES.md
- [x] Fake LOM (`mock/fake_lom.py`): realistic demo set (8 tracks, audio+midi, slots, arr clips, take lanes, racks, browser tree), simulated playback clock + recording
- [x] `mock/run_mock.py` server using same core; pytest suite in `tests/` (65 tests green)
### M1.2 Helper (agent: helper)
- [x] `/health`, `/peaks` (wav/aiff stdlib; ffmpeg fallback), cache, Wine path translation, `/sets`, `/launch`
### M1.3 Android app (agents: android-core, then android-screens in parallel)
- [x] Gradle project, Compose, theme tokens (docs/UI.md), WindowSizeClass shell
- [x] Connection: beacon discovery, manual connect, WebSocket client, req/res, reconnect + full resync
- [x] State store: per-section StateFlows, optimistic updates
- [ ] Screens (DONE: Connect, Shell, Session, Settings/More; placeholders for Arrange, Takes, Clip, Devices, Browser, Mix): Connect, Shell (transport bar, track header, tabs), Session, Arrange, Takes, Clip (props, piano roll, waveform), Devices, Browser, Mix, Settings
- [x] In-app update (mirror InkSlate UpdateCheck), stable/test channels
- [x] Screenshot matrix tests (Roborazzi) across sizes/orientations/font scales
### M1.4 CI/release
- [x] GitHub Actions (not yet run on GitHub): build debug APK + run python tests on push; release workflow on tag `v*` attaches APK + remote script zip
### M1.5 Verification
- [ ] Run probe against real Live 12.4.1, reconcile LOM_NOTES + fix ops
- [ ] QA agent rounds R1–R4 (mock + emulators, then real Live)
- [ ] Milestone report + APK to owner

## Later / ideas
- Signing keystore via repo secrets (owner will say when).
- Take ratings sync to Live clip names? (decide after owner QA)

## QA follow-ups
- [x] (orch) (fixed, pending commit) Two-pane: allow collapsing the left Session pane (fixed toggle on the rail, no slide) so editors get full width.
- [x] (orch) (fixed, pending commit) Tempo/pos readout ellipsized at 568x320 f1.3.
- [x] R3 Q3-1..6 + R4 Q4-1..4 fixed (pending commit): connect stays on Connect until hello (error/Cancel there, recents only after hello), host:port validation, dim+block UI while disconnected with in-bar status strip (no layout push), Back -> Session first, numeric validation (client + core/ops.py marker checks), IME-scrollable Connect, tablet lanes/devices/rail, >50 note delete confirm, client robust to minimal/hostile servers (tools/fake_min_server.py, RobustnessTest), collapsible Session pane (Prefs.sessionPaneHidden).
- [x] R1 Q1-1..Q1-10 fixed (not committed): landscape/wide shell = left tab rail + one compact top bar (CompactTopBar.kt), props side column/toggle in Clip, symmetric safe insets, vel/prob lane >=40dp, note dedupe on move/draw, clip.set gain_db (server+PROTOCOL), take.next names "Take N" + TAKE state/count/elapsed bars, Takes tab explains workflows + "To arr", Mix horizontal faders + aligned returns/master + Delete separated, device Delete moved + confirm.
- [x] R2 Q2-1 (null clip/params sections + fixtures + ContractTest), Q2-2 TAKE disabled when not armable, Q2-3 scene column scales with font, Q2-4 waveform snap + gesture exclusion, Q2-5 auto-reconnect (Cancel on banner) + Back confirm, Q2-6 mock exclusive arm.
- [x] S1 auto-focus a track on connect (qa/REVIEW_LOG R0)
- [x] S2 pan readout overlaps center line

## Done log
- 2026-10-15 Docs system created; process questions answered (public repo, local+CI builds, mock+emulator+real Live QA, milestone reports).

- QA pass 5 follow-ups P5-3-1, P5-4-1, P5-5-1, P5-6-1: fixed (pending commit; unit/roborazzi/pytest green).
- [x] Conformance fix pass 2 (uncommitted, gate green): locator hold-drag + cue.move, picker rename dialog, piano-roll ruler thinning, silent demo, tablet layouts, Clip auto-open, small-phone chrome, chevron. Needs reviewer re-check; cue.move unverified on real Live.
- 2026-10-16 waveform fix: arrange peaks loop gave up after any error (e.g. .asd not yet written at record end) -> blank forever; now PeaksClient.await retries (0/1.5/3/5 s) until real peaks, cache key path+len+n, rec_env step 1/16 beat + ramped + smooth filled envelope.
