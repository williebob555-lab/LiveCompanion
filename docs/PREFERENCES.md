# Owner preferences, corrections & sensitive areas

Single user (the owner). Read this before every change. Newest entries at the bottom of each section.

## CORE PRINCIPLES (distilled from all owner feedback — apply them to cases the owner hasn't mentioned yet)
1. **It's Ableton on a phone.** When unsure how something should look or behave, do what Live does.
2. **Direct manipulation.** Anything you can see you can grab: hold-drag to move/reorder/promote/trim, drag to
   set values. Menus/dialogs only for rare actions. No submenus.
3. **Instant feel.** Every touch responds at frame rate locally; the network never shows as lag or snap-back.
4. **Everything visible, nothing clipped or hidden.** No content under cutouts/rounded corners; no hidden gestures
   or undiscoverable toggles; controls big enough to hit; text never truncated where it matters.
5. **Zero friction setup and use.** No extra programs required for core features (waveforms etc.), one-click
   install, updates from anywhere in the app, app usable without a Live connection (settings, updates, demo).
6. **Space goes to the work.** Editors (track view, clip editor) get the room; chrome is compact but comfortable;
   zoom ranges generous both ways.
7. **One track at a time**, but the track itself is fully editable from wherever its list appears.
8. **Looks good.** Live's dark look, light-blue accent, subtle roundness, consistent sizes; if a screen looks
   empty, clumsy or "not Live", it's a bug.
Every release must pass qa/CONFORMANCE.md (a reviewer agent audits each screen against these principles).

## Product preferences
- Core focus: ONE track at a time — see and manipulate the clips on that track (session slots, arrangement lane, takes).
- Target ONLY the latest Live (currently 12.4.1, Standard edition; no Max for Live). No back-compat shims.
- Hosts: Windows native, and Fedora via Wine. Nothing in the remote script may be Windows-specific.
- OUT of scope (owner decision): phone audio in/out, MIDI over the phone, plugin GUI mirroring.
- IN scope: everything else technically possible — recording, multi-takes/take lanes, MIDI note editing,
  clip/warp editing, devices/macros/variations, browser loading, waveforms, transport, undo.
- Style: mirror Ableton Live's look (dark grey panels, flat, small type, Live's clip color palette). Dense.
- UI must work on ALL screen sizes and both orientations. Never overlapping elements. No obnoxious
  sliding/animated components (no gratuitous swipe drawers, bouncing sheets, marquee text).
- Claude owns feature breadth and correctness; owner gives direction after QA rounds.

## Process preferences
- Files local in C:\LiveCompanion; public GitHub repo (williebob555-lab/LiveCompanion) for builds/releases.
  Working software first; signing keys / release polish later.
- In-app updates must work natively like the owner's other apps: mirror
  `C:\PDF Draw App\core\src\main\kotlin\com\inkslate\core\UpdateCheck.kt` (GitHub Releases API, stable
  channel = latest, opt-in test channel = prereleases, download APK + system installer).
  2026-10-16: release signing SET UP (owner approved). Keystore backup + passwords: %USERPROFILE%\LiveCompanion-signing
  (outside repo — never commit). GitHub secrets: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
  release.yml builds assembleRelease signed with it and stamps versionName from the tag, versionCode = run number.
  Installed test builds always follow the test channel. Owner reported: update check didn't work and install
  failed (causes: versionName always 0.1.0; per-build debug keys) — fixed from v0.1.0-test5 on.
- 2026-10-16 (owner): the app must be usable WITHOUT a Live connection — settings/updates reachable from the
  Connect screen, plus a demo mode to explore the UI. Emulator must be easy to open (tools\Start Emulator.cmd,
  -port 5554 because NTKDaemon holds 5563).
- 2026-10-16 (owner): "I want to be able to zoom out much more" — zoom-out limits must be generous (track view:
  whole song in ~1/4 width; clip editor: clip in ~1/4 width). Owner also still couldn't edit the track list: the
  track picker (top-bar dropdown) must itself support rename/add/delete/color, not only the Tracks tab.
  Owner couldn't see waveforms (helper not running) → waveforms must work WITHOUT the helper (remote script
  computes peaks in a background thread; helper only a fallback).
  Dragging the loop brace ("repeat heads") felt laggy → ALL drags must be optimistic (render local state at frame
  rate, throttled sends, settle after release, never snap back). Perf-sensitive area.
  Nothing may be clipped by ROUNDED DISPLAY CORNERS (owner's phone clipped the info strip and three corners) —
  respect rounded-corner insets everywhere.
  Takes: hold-and-drag a take from a take lane up to the main lane = PROMOTE; drag a main-lane clip down into a
  take lane = DEMOTE; drag between take lanes = move. (Owner request; real-Live API for take-lane clip creation
  unverified — implement best-effort with clear errors.)
  Top-bar buttons should be a bit BIGGER (owner accepts losing some clip-view height for it) — ~40dp row.
- Release tags: builds ≤ v0.1.0-test10 compare pre-release text lexically ("test10" < "test9"), so from now on
  bump the base version instead of growing the test number past 9 (v0.1.1-test1, v0.1.1-test2 … v0.1.2-test1).
  Natural ordering is fixed from v0.1.1-test1 on.
- App icon: owner said the first icon looked like "a weird claw" with a too-small C — L and C must be equal
  cap height and stroke weight.
- Docs must be human-digestible AND efficient for an agent to re-read. Keep them current.
- TODO.md is the workstation: plan, in progress, done, next. Return point after interruptions.
- Parallel agents allowed: use cheaper models (haiku/sonnet) for grunt work, keep total usage moderate.
- Testing = dedicated QA agent working in rounds: (1) use as intended, (2) odd/interesting combos,
  (3) use not as intended, (4) try to break it. Results -> qa/REVIEW_LOG.md. Orchestrator must push the
  agent if shallow and rein it in if wasting time.
- QA surfaces: mock Live server, Android emulators (multiple sizes/orientations), and real Live on this PC.
- Milestone reports: work autonomously through TODO phases; stop at milestones with summary + APK.

## Corrections from owner
- 2026-10-15: Default orientation is LANDSCAPE (more natural clip editor). App locks to sensorLandscape by
  default; Settings offers Landscape / Portrait / Auto. Phone-landscape (~360dp tall) is the PRIMARY layout to
  optimize — keep vertical chrome minimal there so editors get height. Portrait must still work.
- 2026-10-15 (owner QA of v0.1.0-test2, real Live) — BINDING UI DIRECTION:
  - DIRECT MANIPULATION over menus: hold-and-drag to move things everywhere (clips, notes, markers, devices,
    track order). Owner HATES submenus/long-press menus as the main way to act. Menus only for rare actions.
  - TRACK VIEW front and center (the focused track's arrangement lane / clips). Takes are rarely used — owner
    records over the spots to redo. Takes must not take prominent space; demote to a secondary mode.
  - Playhead: dragging must be SMOOTH (interpolated, continuous scrub) and offer precise placement inside a bar
    (snap choices incl. off/1/16/beat/bar, nudge, typed bar.beat.sixteenth).
  - Clip editor: full width, left-to-right, as much detail as Live's own clip view (ruler, loop brace, markers,
    note names, grid, velocity, etc.). Not "simple and tiny".
  - Clip editor HARD MINIMUMS (owner, "ultra specific"): visible + adjustable grid SUBDIVISION (adaptive and
    fixed 1/1…1/32, triplets), TIME SIGNATURE changes (clip signature editable; ruler follows it), real WAVEFORM
    graphics for audio clips (helper peaks; also drawn inside clip blocks in the track view), and RECORDING
    PROGRESSION drawn in RED (growing red region while a clip records, in clip editor and track view).
  - Devices: horizontal SIGNAL CHAIN like Live (devices left→right with their parameters), drag to reorder.
  - Track list: drag to reorder (LOM has NO move_track — see LOM_NOTES; explain/offer alternative), add,
    remove, rename, color inline.
  - Keep screen on while app is open. Fullscreen/immersive (no status bar clock/battery).
  - Use the camera-cutout side strip in landscape for read-only info (position, tempo, meters, connection).
  - Accent color: Ableton LIGHT BLUE, not yellow-orange. Subtle roundness (Live-like small radii).
  - Collapsible UI must be obvious/intuitive (the rail collapse wasn't discoverable).
  - App icon/branding consistent with the owner's LiveSample (C:\Sampler App) and LivePatch (C:\LivePatch) apps.
  - Responsiveness: device added from browser took ~10 s to appear — changes must reflect within one tick.
  - Overall: "make it look good and make sense".
- 2026-10-16 DESIGN RULES derived by Claude from QA pass 3's critique (NOT owner-stated; implements the owner's
  "look good / Live-like / drag-first" direction — owner may override): no orange chrome anywhere (playhead = near-white, locators = small light flags; orange only as Live's own clip colours); Arrange = Live-like clip blocks (header bar, darker body, thin note lines over the clip's pitch range) with ALL controls in slim strips below the lane (<= ~15% chrome above it in 800x360); Session = compact clip tiles, not full-width bars; Clip toolbar = icons, signature/grid shown once; Devices = content-sized panels with dropdown chips; Mix must not repeat top-bar controls; every gesture needs a visible non-gesture alternative (device move buttons, clip nudge buttons); empty names are always rejected with an inline error.
- 2026-10-15: Ableton-side setup must be EASY and QUICK. Keep SETUP.md ≤ a handful of steps; aim for a
  one-click Windows installer (script + optional helper autostart) and zero config beyond picking the
  control surface once.

## Machine limits (owner's laptop, 15 GB RAM)
- 2026-10-16: laptop restarted mid-QA (likely RAM exhaustion: 2 emulators + Gradle daemons + agents).
  Rules: max ONE emulator at a time; never run emulator QA and Gradle-heavy agents concurrently; run
  `gradlew --stop` after build-heavy phases; kill emulators when a QA round ends.

## Performance-sensitive areas
- Remote script runs on Live's main thread. All socket I/O is non-blocking and done in the ~100 ms
  `update_display` tick (plus schedule_message). Never block, never sleep, never do file I/O per tick.
- Listeners: only attach detailed listeners for the FOCUSED track (+ its selected clip). Song-level
  summary listeners are cheap ones only (name/color/arm/mute/solo). Remove listeners on focus change.
- Coalesce: listener callbacks only mark sections dirty; serialization happens once per tick.
- Meters/playhead: realtime channel at <= 20 Hz, only while a client is connected.
- MIDI notes: send only for the clip open in the editor; cap note count per message (chunk if needed).
- Waveform peaks: computed by the helper (not Live), cached on disk keyed by path+mtime.
- Android: Compose recomposition — state flows per section; avoid recomposing whole screen on meter ticks.
