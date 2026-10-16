# UI language & layout rules

## Look (mirror Ableton Live 12 dark theme)
| Token | Hex | Use |
|---|---|---|
| bg | #1A1A1A | app background / gaps |
| panel | #2A2A2A | panels, strips |
| panel_hi | #363636 | raised controls, headers |
| line | #444444 | 1dp dividers |
| text | #C8C8C8 | primary text; text_dim #8A8A8A |
| accent | #5FBEEB | selection, focus track, active toggles (Ableton light blue — owner, replaces orange) |
| warn | #FFA22B | only for warnings / Live's own orange-coded states (e.g. clip launch armed), sparingly |
| play | #5FD35F | playing / play button |
| rec | #FF3B30 | record, arm |
| solo | #3D9FE0 | solo; mute-on uses accent-yellow #E8C547 |
Clip/track colors come from Live as RGB. Text on a clip color: black if luminance > 0.5 else white.
Type: system sans, 11–13sp (labels 10sp caps). Flat, subtle roundness (3–4dp radii like Live 12), no shadows, no gradients.
Fullscreen immersive + keep-screen-on. Landscape cutout side strip = read-only info (position, tempo, CPU, meters, link).
Interaction: drag-first (hold+drag moves/reorders/trims); menus only for rare actions; no nested submenus.
Toggles look like Live buttons: small rectangles, lit when on.

## Orientation
Default = landscape (owner). Setting: Landscape / Portrait / Auto (Prefs.orientation, applied in MainActivity).
Phone landscape (~360–411dp tall) is the primary target: minimize vertical chrome so editors get height.

## Layout rules (hard requirements)
- Every screen must render with NO overlap/clipping at: 320x568dp (small phone), 360x800, 412x915,
  600x960 (small tablet/foldable), 840x1280 tablet, plus all of them in landscape, and fontScale 1.0 and 1.3.
- Landscape (width > height) or width >= 600dp: slim TAB RAIL on the left (52dp, full labels, always visible - no collapse) + ONE compact top bar (transport + focused track; vol/pan only when >= 900dp, two 30dp rows below 700dp). Portrait compact: transport + track header + tab row (two lines, never abbreviated).
- There is NO permanent side pane: Arrange (track view) is the front tab, Session is a normal tab. Takes is a dimmed, last-but-one tab.
- Fullscreen immersive (swipe reveals bars) + keep-screen-on (MainActivity). Cutout mode = shortEdges. In landscape the cutout-side inset (>= 28dp, left OR right) is a read-only `InfoStrip` (connection dot, position, BPM, signature, CPU, focused-track meter); the other side keeps its inset as padding. Nothing interactive in the strip.
- Any collapsible element needs a visible, labeled affordance (e.g. "Edit v" chevron buttons); no hidden gestures.
- No sliding drawers, no animated bottom sheets, no marquee. Use fixed tab rows, inline expanding rows,
  and simple dialogs. Transitions: instant or ≤120ms fade.
- Touch targets ≥ 40dp in the dense layout (Live-like density, still tappable). Faders: vertical drag
  with fine mode (second finger or long-press) and double-tap to reset.
- Text never wraps into neighbors: single line + ellipsis for names.

## Screen inventory
1. **Connect** — discovered hosts (beacon), manual host:port, recents, "Mock" entry in debug.
2. **Main shell** — top transport bar (play/stop/rec/session-rec/overdub/loop/metronome/tempo/pos/undo/redo),
   track header (prev/next, name dropdown, arm/mute/solo/monitor, vol/pan mini faders, meter),
   tab row: Arrange (default) · Session · Tracks · Clip · Devices · Browser · Mix(focus sends/routing) · Takes (dim) · More (settings/update).
3. **Session** — the focused track's slot column: fire/stop/record per slot, hold-and-drag a clip onto another slot to move it, visible ⋯ button for rare actions (dup/copy/move/delete/rename/create),
   big "Take ▶" button (take.next), scene launch column toggle.
4. **Arrange** — horizontal lane of this track (zoom/pan), loop brace, playhead, clip select → Clip tab.
5. **Takes** — take lanes for the track, per-take audition/promote/rename, ratings (stored on phone).
6. **Clip** — full-width L->R editor (ui/clip/*): toolbar = signature (tap to edit) + GRID (Auto adaptive | 1/1..1/32 | Trip) + Snap; ruler with loop-brace row (drag body/edges, tap toggles loop), bar/beat/subdivision ticks following the clip signature, start/end flags (drag); floating H-/H+/V-/V+/Fit zoom pad + two-finger pinch/pan. MIDI: note-name keys, Fold, Scale highlight (song scale), Draw/Select, drag to move/resize, box select, Vel/Prob lane. Audio: gain-scaled helper-peaks waveform, seconds or beats ruler, warp markers (Warp+ tap to add, drag to move, Del warp). Recording = growing RED region + red playhead. Playhead interpolated per frame. Properties = thin collapsible handle strip at the bottom (^ PROPERTIES).
   (helper peaks) with loop/start markers and warp markers.
7. **Devices** — Live-style horizontal SIGNAL CHAIN: panels left to right joined by flow arrows (title bar = power, name, collapse chevron),
   inline knobs (first 8 params; macros for racks; quantized params as stepped selectors; double-tap resets to default, value strings shown),
   rack chains selectable -> the chain's devices appear inline to the right. Hold a title and drag to reorder (device.move), Dup/Del(confirm) buttons,
   "+" at the end opens the native-device quick list (device.insert) with a Browser link. "Params" opens the full list. Browser Load / insert show a
   dim "loading..." panel until the devices section catches up (ui/devices/DevicePending).
8. **Browser** — category roots → folders → items, load onto focused track, preview.
9. **Tracks** — all tracks/returns/master: color bar, tap name = inline rename, arm/M/S, tap row = focus, "Edit" chevron expands color palette / duplicate / delete (confirm); add audio/MIDI/return. No reorder (LOM has no move_track; a note says so).
10. **Settings** — host, follow-Live-selection, haptics, update channel (stable/test), check for update.
