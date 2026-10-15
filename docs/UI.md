# UI language & layout rules

## Look (mirror Ableton Live 12 dark theme)
| Token | Hex | Use |
|---|---|---|
| bg | #1A1A1A | app background / gaps |
| panel | #2A2A2A | panels, strips |
| panel_hi | #363636 | raised controls, headers |
| line | #444444 | 1dp dividers |
| text | #C8C8C8 | primary text; text_dim #8A8A8A |
| accent | #FFA22B | selection, focus track, active toggles (Live orange) |
| play | #5FD35F | playing / play button |
| rec | #FF3B30 | record, arm |
| solo | #3D9FE0 | solo; mute-on uses accent-yellow #E8C547 |
Clip/track colors come from Live as RGB. Text on a clip color: black if luminance > 0.5 else white.
Type: system sans, 11–13sp (labels 10sp caps). Flat, square-ish corners (2dp), no shadows, no gradients.
Toggles look like Live buttons: small rectangles, lit when on.

## Orientation
Default = landscape (owner). Setting: Landscape / Portrait / Auto (Prefs.orientation, applied in MainActivity).
Phone landscape (~360–411dp tall) is the primary target: minimize vertical chrome so editors get height.

## Layout rules (hard requirements)
- Every screen must render with NO overlap/clipping at: 320x568dp (small phone), 360x800, 412x915,
  600x960 (small tablet/foldable), 840x1280 tablet, plus all of them in landscape, and fontScale 1.0 and 1.3.
- Size classes: Compact width → single pane + fixed tab row. Medium/Expanded → two panes
  (left: track list/clip list; right: editor). Never rely on horizontal page scrolling except inside editors
  (piano roll, arrangement lane, waveform), which pan/zoom within their own bounds.
- No sliding drawers, no animated bottom sheets, no marquee. Use fixed tab rows, inline expanding rows,
  and simple dialogs. Transitions: instant or ≤120ms fade.
- Touch targets ≥ 40dp in the dense layout (Live-like density, still tappable). Faders: vertical drag
  with fine mode (second finger or long-press) and double-tap to reset.
- Text never wraps into neighbors: single line + ellipsis for names.

## Screen inventory
1. **Connect** — discovered hosts (beacon), manual host:port, recents, "Mock" entry in debug.
2. **Main shell** — top transport bar (play/stop/rec/session-rec/overdub/loop/metronome/tempo/pos/undo/redo),
   track header (prev/next, name dropdown, arm/mute/solo/monitor, vol/pan mini faders, meter),
   tab row: Session · Arrange · Takes · Clip · Devices · Browser · Mix(focus sends/routing) · More (settings/update).
3. **Session** — the focused track's slot column: fire/stop/record per slot, long-press menu (dup/copy/move/delete/rename/color),
   big "Take ▶" button (take.next), scene launch column toggle.
4. **Arrange** — horizontal lane of this track (zoom/pan), loop brace, playhead, clip select → Clip tab.
5. **Takes** — take lanes for the track, per-take audition/promote/rename, ratings (stored on phone).
6. **Clip** — properties panel + MIDI piano roll (draw/select/move/resize/velocity lane/probability) or audio waveform
   (helper peaks) with loop/start markers and warp markers.
7. **Devices** — chain view (horizontal list of device cards, fold into vertical list on compact), on/off,
   macros as knobs, variations, full param list for opened device.
8. **Browser** — category roots → folders → items, load onto focused track, preview.
9. **Settings** — host, follow-Live-selection, haptics, update channel (stable/test), check for update.
