# TODO — workstation

Legend: `[ ]` todo · `[~]` in progress (who) · `[x]` done · `[!]` blocked (why)
Rule: update this file when starting/finishing anything. If interrupted, resume from "In progress".

## In progress
- [~] M1.1 python-core agent (sonnet) — remote script, core, fake LOM, mock, tests
- [~] M1.3 android-core agent (sonnet) — scaffold, net, state, theme, shell, Session, Settings/update, Roborazzi
- [~] M1.2 + M1.4 + emulators agent (haiku) — helper, CI/release workflows, AVDs
- Next when android-core lands: parallel screen agents (Arrange+Takes, Clip editor, Devices+Browser+Mix)

## Milestone 1 — "Broad strokes" (everything wired end-to-end, mock-tested) → owner QA
### M1.0 Foundations (orchestrator)
- [x] Docs system (CLAUDE.md, docs/*, qa/REVIEW_LOG.md, this file)
- [ ] git init + public repo williebob555-lab/LiveCompanion + .gitignore
- [ ] Toolchain: JDK (Android Studio JBR) on PATH for builds, Gradle wrapper, emulator system image + AVDs
### M1.1 Remote script + core + mock (agent: python-core)
- [ ] `core/` protocol: WebSocket server (non-blocking, stdlib), JSON envelope, ping/pong, multi-client
- [ ] Sections serializers: session, tracks, focus, slots, arr, takes, devices, params, clip, rt
- [ ] Listener manager: global cheap listeners + focused-track detail listeners, dirty flags, per-tick flush
- [ ] All ops in PROTOCOL.md (graceful `unsupported` where LOM lacks it)
- [ ] UDP discovery beacon
- [ ] ControlSurface wrapper `__init__.py` + install script `tools/install_remote_script.ps1` (+ .sh for Wine)
- [ ] `probe.py`: dumps dir() of key LOM objects to Log.txt / a JSON file → fills docs/LOM_NOTES.md
- [ ] Fake LOM (`mock/fake_lom.py`): realistic demo set (8 tracks, audio+midi, slots, arr clips, take lanes, racks, browser tree), simulated playback clock + recording
- [ ] `mock/run_mock.py` server using same core; pytest suite in `tests/`
### M1.2 Helper (agent: helper)
- [ ] `/health`, `/peaks` (wav/aiff stdlib; ffmpeg fallback), cache, Wine path translation, `/sets`, `/launch`
### M1.3 Android app (agents: android-core, then android-screens in parallel)
- [x] Gradle project, Compose, theme tokens (docs/UI.md), WindowSizeClass shell
- [x] Connection: beacon discovery, manual connect, WebSocket client, req/res, reconnect + full resync
- [x] State store: per-section StateFlows, optimistic updates
- [ ] Screens (DONE: Connect, Shell, Session, Settings/More; placeholders for Arrange, Takes, Clip, Devices, Browser, Mix): Connect, Shell (transport bar, track header, tabs), Session, Arrange, Takes, Clip (props, piano roll, waveform), Devices, Browser, Mix, Settings
- [x] In-app update (mirror InkSlate UpdateCheck), stable/test channels
- [x] Screenshot matrix tests (Roborazzi) across sizes/orientations/font scales
### M1.4 CI/release
- [ ] GitHub Actions: build debug APK + run python tests on push; release workflow on tag `v*` attaches APK + remote script zip
### M1.5 Verification
- [ ] Run probe against real Live 12.4.1, reconcile LOM_NOTES + fix ops
- [ ] QA agent rounds R1–R4 (mock + emulators, then real Live)
- [ ] Milestone report + APK to owner

## Later / ideas
- Signing keystore via repo secrets (owner will say when).
- Take ratings sync to Live clip names? (decide after owner QA)

## QA follow-ups
- (none yet)

## Done log
- 2026-10-15 Docs system created; process questions answered (public repo, local+CI builds, mock+emulator+real Live QA, milestone reports).
