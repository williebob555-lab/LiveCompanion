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
| warn | #E8D36A | pale-yellow status/warning text. NO orange chrome anywhere (locators, playhead, flags); orange only appears as Live's own clip colours |
| playhead | #F4F4F4 | playhead + locator flags (Live-like near-white); red while recording |
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
- Landscape (width > height) or width >= 600dp: slim TAB RAIL on the left (52dp, full labels, always visible - no collapse) + ONE compact top bar (transport + focused track; vol/pan only when >= 900dp, two 30dp rows below 700dp). Portrait phone (>= 340dp*fontScale wide): PortraitTopBar = 3 x 28dp rows (transport+undo / tempo+pos+track nav / arm M S mon + vol/pan/meter) + 28dp two-line tab row (~16% of 915dp); narrower or larger fonts keep TransportBar + TrackHeader.
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
3. **Session** — the focused track's slot column as compact Live-like clip tiles (30dp tall, max ~380dp wide; launch square with play triangle in the clip colour, coloured name cell, small stop + ⋯ buttons; scene column 46dp (landscape) / 64dp (portrait) so names fit; tiles max 560dp (landscape) / 460dp centred (portrait)). Hold-and-drag a tile onto another slot to move it (Replace/Swap/Cancel dialog if occupied). Landscape >= 560dp: tiles on the left, a 150dp side column with TAKE (take.next), Stop, Scenes toggle, + Scene; narrower/portrait: the same controls in a row above.
4. **Arrange** — horizontal lane of this track (zoom/pan), loop brace (+ separate red punch IN/OUT flags), playhead, clip select → Clip tab. Chrome above the lane is only the top bar + a 16dp bar-number ruler + 9dp loop-brace row (~15% of 360dp); ALL controls sit in slim 24dp strips BELOW the lane: pos readout (tap = locate), `<` `>` step, `Snap x` (tap cycles Off/1/16/1/8/Beat/Bar), Follow, In/Out, Fit, Takes; a selected clip adds a strip with `◀ ▶` nudge-by-snap (non-gesture alternative to hold-drag move), Edit/Dup/Mute/Rename/Color/Delete. Clip blocks are Live-like: header bar with name, darker body tint, MIDI = note bars (see below), audio = waveform; block fills the lane height (no cap); MIDI notes are solid rounded bars in a light tint of the clip colour with a darker outline, row height = body height / pitch span (used range +-1, min 1 octave) clamped 2-10dp and centred in the body; the SELECTED clip is drawn above overlapping neighbours, double-tap empty lane (also below a clip) = new MIDI clip. Locators = small light flags with a name pill; bar numbers under a pill are skipped. Zoom limits: min = whole arrangement + margin fills the width, max = 1/64 note per ~24dp. A drag is cancelled when the focused track changes or the dragged clip disappears. The first-run tip is shown once ever (Prefs.arrangeTipSeen); the same text lives in More -> Help.
5. **Takes** — take lanes for the track, per-take audition/promote/rename, ratings (stored on phone).
6. **Clip** — full-width L->R editor (ui/clip/*): toolbar = signature chip (tap to edit) + ONE grid picker chip ("Auto 1/16 v", popup grid) + Snap | Draw/Select icon toggles | Fold, Scale (only when the song has a scale) | Vel|Prob lane switch | icons Select-all / Duplicate / Delete (content descriptions for accessibility). Signature and grid appear ONLY in the toolbar (the ruler corner is blank). Single row at >= 500dp, two rows below. Default vertical zoom shows 12-16 pitch rows. Note dragged onto an identical pitch+start: both outlined red during the drag, replace on drop (Live's rule). Velocity/probability drag edits the nearest stem to the touch (selected note wins on coincident stems). Zoom limits (both axes of editors: horizontal): min = whole clip + margin fills the width, max = 1/64 note per ~24dp; ruler with loop-brace row (drag body/edges, tap toggles loop), bar/beat/subdivision ticks following the clip signature, start/end flags (drag); floating H-/H+/V-/V+/Fit zoom pad + two-finger pinch/pan. MIDI: note-name keys, Fold, Scale highlight (song scale), Draw/Select, drag to move/resize, box select, Vel/Prob lane. Audio: gain-scaled helper-peaks waveform, seconds or beats ruler, warp markers (Warp+ tap to add, drag to move, Del warp). Recording = growing RED region + red playhead. Playhead interpolated per frame. Properties = thin collapsible handle strip at the bottom (^ PROPERTIES); expanded, they are a right-side column in landscape (editor narrows) or a bottom panel capped at 40% height in portrait.
   (helper peaks) with loop/start markers and warp markers.
7. **Devices** — Live-style horizontal SIGNAL CHAIN: panels left to right joined by flow arrows (title bar = power, name, collapse chevron),
   inline knobs (rows of 4, as many as the panel height allows of the first 16 params; macros for racks; quantized params as stepped selectors; double-tap resets to default, value strings shown),
   rack chains selectable -> the chain's devices appear inline to the right. Hold a title (300ms, same hold as Session/Arrange) and drag to reorder (device.move; a neighbour is passed when the leading edge crosses its centre). Knob cells scale with font scale; on >= 600dp screens knobs/cells/rows scale up to 1.7x with the height, Dup/Del(confirm) buttons,
   Panels are sized to their content (height too); tap a title to SELECT a device: it shows `◀ Move ▶` buttons (same reorder as hold-drag, non-gesture). Quantized parameters are Live-style dropdown chips (value + chevron; tap opens a list). "+" at the end is a compact tile; it opens the native-device quick list (device.insert) with a Browser link. "Params" opens the full list. Browser Load / insert show a
   dim "loading..." panel until the devices section catches up (ui/devices/DevicePending).
8. **Mix** — sends (with return names), routing, and the returns + master strip (two columns when wide). Name / arm / mute / solo / monitor (and vol+pan outside the 700..900dp band) are NOT repeated: they live in the top bar; track duplicate/delete/add live in Tracks.
9. **Browser** — category roots (one scrolling chip row with a right-edge fade + ">" when more chips exist) → folders → items, load onto focused track, preview.
10. **Tracks** — all tracks/returns/master (rows flow into 2-3 columns on wide screens, master pinned below the list; type label scales with font): color bar, tap name = inline rename, arm/M/S, tap row = focus, "Edit" chevron expands color palette / duplicate / delete (confirm); add audio/MIDI/return. No reorder (LOM has no move_track; a note says so).
11. **Settings** — host, follow-Live-selection, haptics, update channel (stable/test), check for update (result shown directly under the button; the update section is first on the page). A test build shows "Stable" disabled and "Test (automatic for test builds)". If "Install unknown apps" is needed, the asset is remembered and the download resumes on return. Installed shows versionName + build number (+ "debug build"). Stable with only pre-releases -> "No stable release yet" + hint to switch to Test; switching channel clears the old result; a test build always follows the Test channel (UpdateCheck).

## Names
Every rename (clip in Arrange/Session/Takes lane, track in Mix/Tracks, clip name field in Clip properties) rejects an empty name with an inline message (`components/Dense.kt: nameError / NameDialog`); nothing blank is ever sent to Live.


## Position text
Every bar.beat.sixteenth readout/parser uses `ui/track/Bbt.kt` (signature-aware, 1-based; a sixteenth is always 0.25 quarter, so 3/8 beats have 2). Typed values out of range are rejected with an inline message, never wrapped.
