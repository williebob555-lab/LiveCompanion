# Owner-preference conformance checklist (release gate)

A reviewer agent fills this in for every release candidate, on a real emulator build, BEFORE tagging.
Each item: PASS / FAIL (+ screenshot + concrete fix). Any FAIL blocks the release unless the owner waived it.
Source of truth: docs/PREFERENCES.md (Core principles + every dated owner correction). Add a row whenever the
owner gives new feedback.

## Setup & access (principle 5)
- [ ] App opens without a Live connection; Settings & updates reachable from the Connect screen.
- [ ] Update check works offline-from-Live; offers the newest build; installs in place; resumes after permission.
- [ ] Demo mode lets every tab be explored without Live.
- [ ] Waveforms appear with NO helper running (WAV/AIFF).
- [ ] Windows setup is one double-click; Live needs only the one-time control-surface pick.
- [ ] App warns when Live's script is older than the app (banner after connect; features needing a missing op say "Needs script update").

- [ ] Disconnect/Cancel works instantly in every connection state (incl. while reconnecting).

## Feel (principle 3)
- [ ] Every drag (loop brace, markers, clips, trim, notes, velocity, faders, knobs, params, scrub) tracks the finger
      at frame rate and never snaps back after release (test with an artificial 300 ms server delay).
- [ ] Device added from browser / insert appears within one tick.
- [ ] With More -> Help -> Developer "Simulate 300 ms server delay" ON: loop brace, markers, clip move/trim, notes, velocity, warp markers, session tile drop, take drops, faders, knobs, macros, punch, scrub all follow the finger and never jump back.

## Direct manipulation (principle 2)
- [ ] Hold-drag works for: clips (session + arrangement), notes, devices, takes (promote / demote / move, in the track view AND the Takes tab
      Main lane row; target lane lights up), loop brace, markers, warp markers. Same hold delay everywhere.
- [ ] No action is reachable only via long-press menu; no submenus anywhere.
- [ ] Every gesture also has a visible button alternative.

## Visibility (principle 4)
- [ ] Nothing under the camera cutout or ROUNDED CORNERS (all four corners) in landscape and portrait.
- [ ] Edges/corners: no differently colored blocks - surfaces extend seamlessly to the screen edge (top bar, rail, info strip, bottom strips; only content is inset). Check shell_corners, shell_corners_right, shell_arrange_corners(_right) in portrait + both landscapes.
- [ ] No clipped/truncated important text at font 1.0 and 1.3; no overlap at every size in docs/UI.md.
- [ ] Every collapsible/hidden element has an obvious labeled affordance.

## Space & zoom (principle 6)
- [ ] Track lane fills the height; clip editor gets most of the screen; chrome ≤ ~18% height in phone landscape.
- [ ] Top-bar buttons ~40dp (owner asked for bigger).
- [ ] Zoom out: whole song in ~1/4 width (track view), clip in ~1/4 width (clip editor); zoom in deep.

## Tracks (principle 7)
- [ ] Track list wherever it appears (top-bar picker AND Tracks tab): rename (pencil), color, add, delete, duplicate.
- [ ] Track lists: tap row/name = switch to track; rename only via the pencil, never by tapping the name.
- [ ] Track reorder by hold-drag in picker + Tracks tab, with loss warning (one dialog listing what is lost; no dialog when nothing is lost); up/down buttons on the focused row; groups/returns/master have no grip and explain on tap.

## Clip editor hard minimums (owner, "ultra specific")
- [ ] Subdivision (adaptive + fixed + triplets) · time signature editable · waveform graphics · recording in RED.

## Look (principles 1, 8)
- [ ] Live-like: dark theme, light-blue accent, no orange chrome, subtle radii, consistent control sizes.
- [ ] Landscape default; fullscreen; screen stays on; info strip readable and fully visible.
- [ ] Each tab screenshot reviewed: does it look like Live and not empty/clumsy? (write one line per tab)


## Review — v0.1.1-test3 candidate (commit 6b9bd16)
Reviewer: owner-preference agent, pixel_7 emulator (2400x1080 landscape, 1080x2400 portrait, plus 1136x640 @320dpi = 568x320dp small phone), debug build, DEMO mode + mock on :9021 with "Simulate 300 ms server delay" ON. Screens: qa/screens/conf_*.png.
Not verifiable here: installing an APK (download needs approval), real Live, recording in red (mock does not simulate recording), Windows installer run, tablet size.

### Setup & access
- PASS  App opens without Live; "Settings & updates" on Connect (conf_01_connect, conf_02_settings).
- PASS  Update check works with no Live: Stable -> "No stable release yet" + hint; Test -> "Version 0.1.1-test2 available" + Download and install (conf_03, conf_04). Install-in-place / resume-after-permission NOT exercised.
  - FAIL (cosmetic) Release notes shown raw: "TODO: status / Co-Authored-By: Claude Opus 5.5 <noreply@...> / **Full Changelog**: https://..." (conf_04_update_test). Fix: publish a short human changelog in release.yml, or have UpdateCheck strip trailers, markdown and the compare link and cap to ~5 lines. -> fixed (pending re-check)
- PASS  Demo mode: every tab opens and shows content (conf_05 ... conf_t_*). Caveat: a demo clip move over other clips silently removes Beat B/C (conf_19), and the Takes tab has no take lanes in demo so take drags cannot be tried there. Fix: demo should overwrite/trim like Live and ship a take lane.
- PASS  Waveforms with no helper (audio track Vocals via mock, remote-script peaks): conf_50_audio_arr. (Mock waveform is synthetic; real WAV not checked.)
- PASS  Windows setup: SETUP.md = double-click "Install LiveCompanion.cmd" + one control-surface pick (read, not run).

### Feel (300 ms delay ON)
- PASS  Arrange clip hold-drag (conf_18 -> 20: moved, no snap-back). Loop-brace edge drag (conf_34 -> 36) stays where released. Ruler scrub (conf_37). Clip-editor note hold-drag (conf_40, conf_42) incl. velocity stem following. Session tile drop to empty slot (conf_33). Device hold-drag reorder (conf_49). Takes demote / promote / lane-to-lane move (conf_25 ... 31) against the remote script, no snap-back.
- PASS  Device added from the Add dialog is present in the first screenshot after the tap (conf_46); adb latency prevents resolving below ~300 ms.
- NOT TESTED  faders, knobs, macros, punch, warp markers, locators, clip trim, velocity precision (adb drags too coarse). Docs say they use Optimistic/SectionGuard.
- FAIL  Loop brace row is ~9dp tall with ~8dp-wide edge handles (conf_36, grey brace row at the top of the lane). Hitting an edge needs pixel accuracy. Fix: keep the visual, but make the touch area the whole ruler+brace band (>=40dp) with edge hit zones >=24dp. -> fixed (pending re-check)
- NOTE  Promote in the Takes tab leaves the take in its lane (conf_29: Beat B in main AND lane 1). Fine if that is Live's behaviour; say so in the hint.

### Direct manipulation
- PASS  Hold-drag works for clips (session + arrangement), notes, devices, takes (promote / demote / move, Takes-tab Main row), loop brace. Target-lane highlight during the drag could not be captured (adb drag blocks) - UNVERIFIED.
- NOT TESTED  hold-drag of locators/markers and warp markers; uniform hold delay.
- PASS  No long-press-only actions, no submenus; button alternatives exist (clip nudge < >, device Move, Promote).
- FAIL  Track-picker dialog: expanding "Edit" opens a 4x14 colour palette that fills the whole dialog; Rename / Duplicate / Delete sit BELOW it and appear only after scrolling (conf_15 vs conf_16). Hidden controls. Fix: one row Rename | Duplicate | Delete above the palette, or a "Color" chip that opens the palette. -> fixed (pending re-check)

### Visibility
- PASS  Rounded corners / cutout (pixel_7 landscape): top bar, rail, bottom strips, info strip, Settings (left cutout strip) are inset; nothing in the four corners (conf_05, conf_18, conf_36). Portrait OK (conf_p_*).
- FAIL  Truncated important text at font 1.0: scene names "Chor...", "Vers..." (two different scenes look identical, conf_06) and the track name "Renam..." in the top-bar picker (conf_21). Fix: widen the landscape scene column (~72dp; tiles are far wider than needed) or allow two lines; make the picker chip flex instead of the Mon AUTO button. -> fixed (pending re-check)
- FAIL  Font 1.3: position readout "1.1..." and track "Dru..." truncated in the top bar; scene names "Verse...", "Chor..." (conf_f13_Arrange, conf_f13_Session). Fix: size the position field to its content; shrink transport icon width when fontScale > 1.15. -> fixed (pending re-check)
- FAIL  Portrait Devices: Compressor panel cut at the right edge ("De", half chevron) with no scroll cue (conf_p_Devices). Fix: edge fade + ">" cue, or wrap the chain. -> fixed (pending re-check)
- PASS  No overlap at 2400x1080, 1080x2400 and 568x320dp.
- PASS  Collapsibles are labelled (^ PROPERTIES, Edit v, "Got it" tip).

### Space & zoom
- PASS  Lane fills the height; clip editor gets nearly everything (conf_09). Chrome above the lane on pixel_7 landscape ~17% (top bar + ruler/brace, conf_18).
- FAIL  Small phone landscape (568x320dp): chrome above the lane ~32% (two transport rows + ruler), lane ~58% of height (conf_sm_Arrange). Fix: below 700dp collapse row 2 to a 28dp strip (track + arm/M/S) and move vol/pan to Mix. -> fixed (pending re-check)
- FAIL  Small-phone Tracks tab: the focused track row is crushed to a sliver (conf_sm_Tracks; "Vocals" shows only a red button; Guitar / FX Group not visible; pinned Master eats the height). Fix: pin Master only if >= 5 rows fit, else keep it inside the scroll. -> fixed (pending re-check)
- PASS  Top-bar buttons ~40dp, icons 18dp.
- PASS  Clip editor zoom-out: clip is ~1/4 of the width after 8x H- (conf_10: 417 of 1670 px). Track-view zoom-out not measured.

### Tracks
- PASS  Rename / add / delete / duplicate / colour exist in the Tracks tab and in the top-bar picker (conf_07, conf_14, conf_15, conf_16); reorder limitation explained, no fake handles. (Hidden-controls FAIL in the picker is listed above.)

### Clip editor hard minimums
- PASS  Grid: Auto + Triplet + 1/1..1/32 (conf_11). Minor: no "Off" like Live - add it.
- PASS  Signature dialog (beats + unit 1/1..1/16 with live "Result: 4/4") (conf_12); ruler and grid follow.
- PASS  Waveform graphics (conf_50). Recording in RED: UNVERIFIED (mock cannot record; code exists in TrackLane/PianoRoll). Verify on real Live or add recording to the mock/demo.

### Look
- PASS  Dark Live theme, light-blue accent, no orange chrome (orange only as clip colours), small radii, consistent 40dp bars.
- PASS  Landscape default, fullscreen (no status bar), FLAG_KEEP_SCREEN_ON in MainActivity, info strip readable (POS / BPM / SIG / CPU).

### Tab look review
- Arrange: Live-like clip blocks with header bar, good. Flat brown slabs with tiny note ticks make sparse drum clips look heavy/empty; scale the pitch range so notes fill more of the block.
- Session: compact bars but ~1000px wide with a lot of dead orange; scene names truncated; reads as a list more than Live's grid, acceptable for one track.
- Tracks: clear, tall rows, empty middle in each row; fine.
- Clip: the editor is excellent and Live-like; with no clip open the tab is a plain list ("Open a clip on Drums") - auto-open the selected/first clip.
- Devices: Live-like signal chain; ~60% of the screen empty below, generic "Param 1..6" labels in demo/mock; scale knobs up with the free height.
- Browser: first open is empty ("Pick a category above"); auto-open Instruments.
- Mix: clear; bottom third empty; send rows are big dark bars at -inf dB.
- Takes: without lanes it is an almost empty screen with one sentence; with lanes it works (Main row + lanes + hint) but the lower 50% is empty and its flat clip blocks differ from Arrange's header-bar style.
- More: clean, updates first; Developer section needs two scrolls.

### Principles not covered by the checklist
- Principle 4: after normal taps in demo a notice "Demo: not simulated (device.close)" appeared; unsupported demo ops should be silent or disabled, not look like errors.
- Principle 6: Devices / Mix / Takes leave large empty areas while controls stay small; scale with free space.
- Principle 8: clip-editor ruler shows "1.2 1.3 1.4 2" with no "1" at the bar start (hidden under the loop flag) - minor.
- QA hazard: a stale mock with an older remote script was already listening on :9020 and answered "unknown op: takes.demote". run_mock.py should detect/report an occupied port loudly (it only logged "cannot bind").
- Settings shows "0.1.0 (build 1) - debug build" for this candidate (version stamping only in release.yml - expected).

### Totals
PASS 21 / FAIL 9 / UNVERIFIED or NOT TESTED 6.
FAILs: raw release notes; loop-brace hit target; track-picker Rename/Duplicate/Delete hidden below palette; truncated scene/track names @1.0; truncated pos/track/scene @1.3; portrait Devices clipped panel; small-phone chrome 32%; small-phone Tracks focused row crushed.


### Fix pass (post test3 review) - all FAILs fixed (pending re-check)
Release notes cleaned (UpdateCheck.cleanNotes, 6 lines + More...); loop brace hit band >= 40dp with 24dp edge zones + grips (track view, clip editor, Takes); track picker: Rename/Duplicate/Delete row above a collapsible Color chip, row scrolls to top; scene column measured to the longest name; top bar = one 40dp row with a flexible name and fixed-width tempo/pos (min width for 999.4.4), secondary controls in a bottom strip on small phones; info strip text fixed at 10dp; Devices chain edge fade + chevron; small phone Tracks: Master pinned only if 5 rows fit; tab rail items fit; clip editor ruler scrub; demo clip moves overlap instead of deleting, demo ships a take lane; Takes empty state with action; Browser opens on Instruments.


## Re-check - v0.1.1-test3 candidate (commit 8005c06)
Reviewer: owner-preference agent. lc_phone (pixel_7, 2400x1080 land / 1080x2400 port), lc_phone_small (640x360dp land), lc_tablet (2560x1600); debug build 8005c06; DEMO + mock on :9021 with "Simulate 300 ms server delay" ON (lc_phone). Screens: qa/screens/conf2_*.png.

### Previous FAILs
- PASS  Update screen notes: More -> Check shows "TODO: status" only (conf2_04); trailers/markdown/compare link gone. NOTE: the published test2 release body itself is the placeholder "TODO: status" - write a real changelog in the release.
- PASS  Loop brace: grips visible, edge grabbed from 9 px and from 25 px inside the edge, at y=140 and y=165, 300 ms delay on, no snap-back (conf2_05, 06).
- PASS  Track-picker: Rename | Duplicate | Delete | Color chip in one row directly under the track, no scroll (pixel_7 conf2_12, small phone conf2_67).
- PASS  Truncation @1.0: scene names (Chorus 2, Verse 2 full), long track name flexes ("Renamed Drum TrackDrums") (conf2_07, 13). @1.3: position "1.1.1", scene names, track name all complete (conf2_08, 09, 14). Portrait keeps an ellipsis only for a 22-char name (acceptable).
- PASS  Portrait Devices: edge fade + small ">" chevron at the right of the Compressor (conf2_15). Weak: chevron is tiny and sits on "Param 4"; make it 24dp.
- PASS (borderline) Small phone chrome: above lane = top bar 11% + ruler/brace 8% = 19% (was 32%); lane 61% of height (was 58%); one 40dp row + 24dp strip + 40dp bottom bar below the lane (conf2_62). Target "<= ~18%" missed by ~1 point; acceptable, but the bottom bar + strip still eat 27%.
- PASS  Small-phone Tracks: six rows visible, Master not pinned (conf2_63). Rail "More" fully visible at 360dp (y 662-687 of 720).
- PASS  Clip-editor ruler scrub: playhead tick follows the finger end (conf2_54).

### Previously not verified
- PASS  Red recording: clip editor red region + red "REC" + red slot row (conf2_26, 27); track view red wash + "REC" + red ruler bar while arrangement-recording (conf2_28). Session tile of the recording slot stays orange with only the slot button red (minor: tint the tile red).
- PARTIAL TAKE: transport plays, but in the Takes tab nothing shows recording (no red region, conf2_31); mock cannot create a take lane from TAKE. Treat as unverified on real Live.
- PASS  Take-drop target: lane gets a blue outline/tint + "Demote to this take lane" label during the drag; drop persists (conf2_33, 34). NIT: the dragged ghost overlaps the label ("Demo|e to this take lane").
- PASS  Faders (volume strip 300 ms delay: follows to the release point, identical 1.5 s later) and knobs (Param 1 0.50 -> 0.67 at once, stays) (conf2_40, 41).
- PASS  Warp markers: Warp+ then tap adds, drag moves with waveform stretching, stays after 1.5 s (conf2_50-52).
- FAIL  Locators cannot be moved: dragging the "Verse" flag just scrubs the playhead (conf2_44). Code has no locator drag (TrackLane only draws cues). Checklist says markers hold-drag. Fix: hit-test cue flags (24dp) in TrackLane, hold-drag with optimistic position + a locator move/set op, or demote the checklist row if the API cannot do it.
- PASS  Tablet quick pass: Arrange, Session, Tracks, Clip, Devices, Browser, Mix, Takes, More all render without overlap; top bar merges transport + mixer in one row (conf2_70-72).

### NEW violations
- FAIL (principle 4) Tapping a track NAME in the picker dialog starts inline rename; with the keyboard up in landscape the dialog is squeezed to ~10 px and the field is unreadable/unusable (conf2_45). Fix: rename in a small separate dialog or make the dialog scroll/shrink with IME padding, and keep the field >= 48dp.
- FAIL (principle 8, minor) Clip-editor ruler labels collide when zoomed out on MIDI clips ("10.210.310.411", conf2_27); the audio ruler thins labels correctly. Fix: apply the same label-thinning to the piano-roll ruler.
- FAIL (principle 4) Notice "Demo: not simulated (device.close)" appeared with no user action on Devices (conf2_15) and persists; unsupported demo ops must be silent.
- FAIL (principle 6/8) Tablet: Session/Mix/Devices/Browser/Clip leave 50-70% empty with phone-sized controls (conf2_72); Clip tab still shows "Open a clip on Drums" list instead of auto-opening. Fix: scale knobs/faders/tiles with free space, auto-open selected/first clip.
- NIT: info-strip track name at 10dp is cut to "Rename" for a long name (conf2_14). Takes tab on tablet opens zoomed so Beat A/C are cut at the edges (conf2_71_tab_Takes). Fresh installs start with "Simulate 300 ms delay: off" (correct).

### Totals (re-check)
Previous FAILs: 8 PASS / 0 FAIL (2 borderline/weak). Not-verified items: 6 PASS, 1 PARTIAL (TAKE), 1 FAIL (locators). New: 4 FAIL (rename-with-keyboard, MIDI ruler labels, demo notice, tablet emptiness/Clip auto-open).

### Fix pass 2 (post test3 re-check) - fixed (pending re-check)
- Locators: fixed (pending re-check) - hold-drag a flag (24dp zone, wins over ruler scrub) moves it optimistically; tap = jump; server op `cue.move {i,time}` (sets CuePoint.time, else delete + re-create fallback, clear err); fake LOM + pytest + PROTOCOL.md. Real-Live unverified.
- Rename in picker with keyboard: fixed (pending re-check) - picker uses a small NameDialog (IME-padded, scrolling body, pinned buttons, 48dp field, autofocus, inline error).
- Piano-roll ruler labels: fixed (pending re-check) - bar labels thinned 2/4/8/16/32 by px, finer labels only where they fit.
- Demo notice: fixed (pending re-check) - unsupported demo ops are silent (logcat only).
- Tablet (>= 840dp): fixed (pending re-check) - Session slot list | controls | selected-clip detail; Mix vertical send faders + routing, returns/master vertical strips; Devices scale up to 2.2x and 6 columns; Browser folders | items; Clip tab auto-opens playing/first slot clip, else first arrangement clip (phones too). Screens: build/outputs/roborazzi/tablet_*.png.
- Borderline: fixed (pending re-check) - small-phone ruler 11dp + brace 3dp (threshold <= 380dp tall); Devices scroll chevron 24dp accent.


## Re-check 2 — v0.1.1-test3 candidate (commit c92fa0f)
Reviewer: owner-preference agent; lc_phone (land, font 1.0/1.3), lc_phone_small (1280x720), lc_tablet (2560x1600); DEMO + mock :9021. Screens: qa/screens/conf3_*.png.
- PASS  Locators: hold-drag of the Verse flag moves it (drop snapped to a beat), persisted on the server (relaunch showed the new position); tap on a flag jumps the playhead (conf3_01, 02).
- PASS (nit) Rename with IME up in landscape: small NameDialog, 48dp+ field, OK/Cancel visible (conf3_12). NIT: the inline error "Name can't be empty" is cut in half vertically (conf3_13).
- PASS  Piano-roll ruler zoomed out: no label collisions (conf3_21/22). NIT: label "2.4" touches the loop-end flag at max zoom-out.
- PASS  Demo: no "not simulated" notice while visiting every tab and using device Del/Params/Add, Browser Load, Mix, Takes.
- FAIL  Clip tab does NOT auto-open when the track changes while on the Clip tab: switching Drums->Bass->Keys leaves "Open a clip on Keys" with 4 slot clips + 1 arrangement clip (conf3_44), and on the first Bass visit from Arrange it also showed the picker (a second entry worked). Likely race in ClipScreen: after "stale -> clip.close", `if (clip.loc != null) { followFor = null; return }` cancels the follow before the close arrives. Fix: ignore clip.loc whose tr != followFor (treat as closed) and open the new clip anyway.
- PASS  Small phone chrome: top bar + ruler/brace = 15.5% (lane 65%) (conf3_61). Devices chevron: 24dp accent chevron + fade at overflow (conf3_66). NIT: demo "Add device" inserts at the chain start.
- PARTIAL Tablet: Mix (vertical faders), Clip, Devices (scaled knobs) use the space; Session still ~65% empty below 8 short tiles and a mostly empty detail pane; Browser 75% empty (little content); Devices ~60% empty (conf3_70_*).
- PASS  Regression sweep lc_phone land: Arrange/Session/Devices/Mix/Takes/Browser/More render, font 1.3 top bar and Arrange intact. NIT: Takes tab opens zoomed to bars 1-3 (Beat B cut).
Totals: 1 blocking FAIL (Clip auto-open on track switch), rest PASS/nits.


## Review — v0.1.1-test4 candidate (commit e7974e4)
Reviewer: owner-preference agent; lc_phone (pixel_7 2400x1080 land, 1080x2400 port), debug build e7974e4, mock :9021 (+ a scratchpad "old script" mock on :9022 with clip.peaks/track.move/takes.demote removed, no product code touched). Screens: qa/screens/c4_*.png.
Not testable here: reverse landscape (emulator ignored user_rotation 3), font 1.3, small phone, tablet, install-in-place, real Live, red recording with a live envelope (see below).

### New owner items
- PASS  Picker: tap name/row = switch track and picker closes (c4_02, c4_03). Rename only via the pencil; Tracks tab pencil opens inline field (c4_10).
- PASS  Hold-drag reorder in the picker on the mock: Keys moved to the top, no dialog, devices (Arpeggiator, Layer Pad, EQ Eight), slot clips (Chords 1,2,3,5), name and colour preserved (c4_04..06). Up/down buttons on the focused row work (Tracks tab).
- PASS  Loss dialog only when losses exist: group child FX Audio -> "will lose: group membership" (c4_08), Vocals -> "take lanes" (c4_16); Cancel restores order (c4_09). Groups/returns/master have no grip.
- NIT   Groups/returns/master: tapping the row only switches track; no "why can't I move this" explanation is shown (checklist says explain on tap).
- PASS  Disconnect while "Connection lost - retrying": returns to Connect immediately, no further attempts after 8 s (c4_11, c4_12).
- PASS  Outdated script: banner "LiveCompanion script in Live is outdated - run the installer ... restart Live" appears after connect (c4_13); reorder says "Needs script update (track.move)" (c4_17).
- FAIL (minor) Waveform placeholder on an old script says "Waveform unavailable" (c4_15) instead of "Needs script update"; banner is visible so not silent. Fix: map unknown-op error from clip.peaks to the same "Needs script update (clip.peaks)" text.
- PASS  Waveforms with no helper: Vocals/Guitar audio clips show waveforms in Arrange blocks and in the clip editor (c4_22, c4_23, c4_43).
- UNVERIFIED Recording shows red region that grows (arrangement record, c4_30, c4_33: red wash + REC + red ruler bar) but NO envelope shape is visible, and I could not get the mock into a session-recording state with a growing clip (the slot record dot produced a stopped empty "Audio" clip, c4_36/42). The mock only drives meters for session recordings, so envelope-while-recording needs real Live or a mock fix.
- FAIL  Corners, landscape (2400x1080): the top bar surface (54) stops ~50 px before the right edge; the strip beyond it is the darker window colour (42) while the ruler below extends to the edge (c4_01, c4_51_tr_crop). Bottom bar, rail and info strip are seamless. Fix: paint the top bar background to the right screen edge and inset only the buttons.
- FAIL  Corners, portrait (1080x2400): the top ~135 px inset is the darker page colour (26) above bars/tabs that are lighter (42/54), with 5 px darker slivers at the left/right of the bar rows (c4_80, c4_81). This is the "inset area is a different colour" the owner complained about. Fix: draw the top bar/tab surface behind the status inset (extend it to y=0) and pad content only.
- PASS  Content clear of corners in both orientations (nothing in the four corners).

### Regression sweep (landscape, mock)
PASS  Arrange, Session, Tracks, Clip, Devices, Browser, Mix, Takes, More render, no overlap/truncation at font 1.0 (c4_6x_*, c4_montage_0/1). Not re-run: delay-simulation drags, small phone, tablet, font 1.3, update install (unchanged since test3 re-check 2).
- Note: a stale mock from another session still listens on :9020 (not touched).

### Tab look review
- Arrange: Live-like blocks, waveform fills clip, good. Session: compact rows, lots of empty space under 8 scenes. Tracks: clear rows, group children indented, Master pinned at bottom (large gap in portrait). Clip: Live-like; audio waveform strong. Devices: signal chain fine, large empty lower half. Browser: tabs + list, mostly empty. Mix: readable. Takes: lanes + hint, lower half empty. More: clean.

### Totals / verdict
Blocking FAILs: 2 corner/edge colour (landscape top bar right edge, portrait top inset) — owner rule 2026-10-16. Minor: peaks placeholder text, group-tap explanation. Unverified: recording envelope.

### Fix pass after test4 (pending re-check)
- Corners landscape top bar right edge + portrait top inset: FIXED (pending re-check). MainShell root no longer pads; every surface (top bar, tab row, rail, strips, notice bar) paints its background edge-to-edge first, then pads content. New pixel test EdgeSeamTest (portrait, land-left cutout, land-right cutout; 6px edge bands never app-background where a chrome surface sits) - failed on the old shell (3/3), passes now.
- Peaks placeholder on outdated script: now "Needs script update (clip.peaks) ..." (pending re-check).
- Group/return/master grip slot: shows a faint dot; tapping shows the reason inline in the track list (the picker covers the shell notice bar) and as a notice (pending re-check).
- Mock: meters now driven for ARRANGEMENT recording on an armed track (record_mode + playing) so the red envelope can be checked (pending re-check).

## Re-check — test4 candidate (commit 7fbc777)
Reviewer: release-gate agent; lc_phone (-port 5554), debug APK built from 7fbc777, mock :9021. Screens: qa/screens/c5_*.png. Pixel values read with PIL.
- FAIL  Landscape top bar (2400x1080): bar surface (54) still ends at x~2345; the ~55 px strip to the right edge is 42 (rows y 5-80), and below it the page strip is 26 / ruler ends ~5 px short (c5_01_start, c5_02_land_tr_crop). Owner rule 2026-10-16 (inset areas must not be a different colour) still violated at the top-right. Bottom-right (c5_03) uses 42 bar surface to the edge.
- PASS  Portrait (1080x2400): top inset (y 0-135) is 42, same as the bar-row surface below; no darker slivers at the sides of bar/tab rows (x 0..8 = 42); bottom corners = page colour 26 to the edge (c5_10_portrait).
- PASS  Recording envelope in track view: arm Drums + arrangement record + play ~6 s: red region grows from the start with a clearly varying (non-flat) envelope shape, REC label, red ruler bar (c5_11_rec_arr).
- UNVERIFIED  Envelope in the clip editor: the Clip tab shows the existing MIDI clip (Drums) / Vox A (Vocals), not a growing recording clip (c5_12, c5_17); the mock creates no recording clip in arrangement record.
- PASS  Group-track grip tap shows "Group tracks can't be moved here - move them in Live" inline in the list and as a notice (c5_14). Return/master not separately captured (same code path assumed).
- NOT TESTED  Outdated-script placeholder text (needs old-script mock).
Verdict: NOT RELEASE OK - blocking FAIL: landscape top bar does not reach the right screen edge.
