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

## Direct manipulation (principle 2)
- [ ] Hold-drag works for: clips (session + arrangement), notes, devices, takes (promote/demote/move), loop brace,
      markers, warp markers. Same hold delay everywhere.
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
