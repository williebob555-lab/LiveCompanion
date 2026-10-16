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
- [ ] No clipped/truncated important text at font 1.0 and 1.3; no overlap at every size in docs/UI.md.
- [ ] Every collapsible/hidden element has an obvious labeled affordance.

## Space & zoom (principle 6)
- [ ] Track lane fills the height; clip editor gets most of the screen; chrome ≤ ~18% height in phone landscape.
- [ ] Top-bar buttons ~40dp (owner asked for bigger).
- [ ] Zoom out: whole song in ~1/4 width (track view), clip in ~1/4 width (clip editor); zoom in deep.

## Tracks (principle 7)
- [ ] Track list wherever it appears (top-bar picker AND Tracks tab): rename, color, add, delete, duplicate;
      reorder limitation explained (no fake handles).

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
