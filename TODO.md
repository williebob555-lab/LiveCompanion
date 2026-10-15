# TODO â€” workstation

Legend: `[ ]` todo Â· `[~]` in progress (who) Â· `[x]` done Â· `[!]` blocked (why)
Rule: update this file when starting/finishing anything. If interrupted, resume from "In progress".

## In progress
- [~] M1.1 python-core agent (sonnet) â€” remote script, core, fake LOM, mock, tests
- [~] screens agent A (sonnet): Arrange + Takes (+ ui/components/Timeline.kt)
- [~] screens agent B (sonnet): Clip editor (props, piano roll, waveform via helper)
- [~] screens agent C (sonnet): Devices + Browser + Mix
- Next: density pass on Shell/Session (orchestrator noted rows/buttons ~48dp, too chunky vs "dense" pref),
  then emulator smoke against mock, then QA agent rounds.
- Done this phase: android-core (a7447d5), helper+CI (90a6b45), AVDs lc_phone_small/lc_phone/lc_tablet created.

## Milestone 1 â€” "Broad strokes" (everything wired end-to-end, mock-tested) â†’ owner QA
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
- [x] `probe.py`: dumps dir() of key LOM objects to Log.txt / a JSON file â†’ fills docs/LOM_NOTES.md
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
- [ ] QA agent rounds R1â€“R4 (mock + emulators, then real Live)
- [ ] Milestone report + APK to owner

## Later / ideas
- Signing keystore via repo secrets (owner will say when).
- Take ratings sync to Live clip names? (decide after owner QA)

## QA follow-ups
- (none yet)

## Done log
- 2026-10-15 Docs system created; process questions answered (public repo, local+CI builds, mock+emulator+real Live QA, milestone reports).
