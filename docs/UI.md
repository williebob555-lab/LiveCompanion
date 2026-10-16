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
1. **Connect** — top row: title + "Settings & updates" button (opens SettingsScreen: updater, orientation, help; needs no Live); discovered hosts (beacon), manual host:port, recents, emulator shortcut, **"Try demo (no Live needed)"** = DemoClient (net/DemoClient.kt): in-app fake Live from assets/demo/*.json fixtures; transport/track/slot/clip/note ops applied locally, playhead advances via a ticker, unsupported ops answer ok + "Demo: not simulated" notice. The shell is labelled "DEMO" (info strip + thin line at the bottom); Disconnect/back leaves the demo.
2. **Main shell** — top transport bar (play/stop/rec/session-rec/overdub/loop/metronome/tempo/pos/undo/redo),
   track header (prev/next, name dropdown, arm/mute/solo/monitor, vol/pan mini faders, meter),
   tab row: Arrange (default) · Session · Tracks · Clip · Devices · Browser · Mix(focus sends/routing) · Takes (dim) · More (settings/update).
3. **Session** — the focused track's slot column as compact Live-like clip tiles (30dp tall, max ~380dp wide; launch square with play triangle in the clip colour, coloured name cell, small stop + ⋯ buttons; scene column 46dp (landscape) / 64dp (portrait) so names fit; tiles max 560dp (landscape) / 460dp centred (portrait)). Hold-and-drag a tile onto another slot to move it (Replace/Swap/Cancel dialog if occupied). Landscape >= 560dp: tiles on the left, a 150dp side column with TAKE (take.next), Stop, Scenes toggle, + Scene; narrower/portrait: the same controls in a row above.
4. **Arrange** — horizontal lane of this track (zoom/pan), loop brace (+ separate red punch IN/OUT flags), playhead, clip select → Clip tab. Chrome above the lane is only the top bar + a 16dp bar-number ruler + 9dp loop-brace row (~15% of 360dp); ALL controls sit in slim 24dp strips BELOW the lane: pos readout (tap = locate), `<` `>` step, `Snap x` (tap cycles Off/1/16/1/8/Beat/Bar), Follow, In/Out, Fit, Takes; a selected clip adds a strip with `◀ ▶` nudge-by-snap (non-gesture alternative to hold-drag move), Edit/Dup/Mute/Rename/Color/Delete. Clip blocks are Live-like: header bar with name, darker body tint, MIDI = note bars (see below), audio = waveform; block fills the lane height (no cap); MIDI notes are solid rounded bars in a light tint of the clip colour with a darker outline, row height = body height / pitch span (used range +-1, min 1 octave) clamped 2-10dp and centred in the body; the SELECTED clip is drawn above overlapping neighbours, double-tap empty lane (also below a clip) = new MIDI clip. Locators = small light flags with a name pill; bar numbers under a pill are skipped. Zoom limits: zoom-out until the whole song (max of song length, last clip end, loop end, + 8 bars) takes ~1/4 of the width (never below 2px per bar; ruler labels thin out 1/2/4/8/16/32 bars), max = 1/64 note per ~24dp. A drag is cancelled when the focused track changes or the dragged clip disappears. The first-run tip is shown once ever (Prefs.arrangeTipSeen); the same text lives in More -> Help.
5. **Takes** — a MAIN lane row (the track's arrangement clips) on top, then the take lanes; per-take audition/promote/rename, ratings (stored on phone). Hold + drag (300 ms, haptic) a take UP onto the Main lane = PROMOTE, a Main-lane clip DOWN onto a take lane = DEMOTE, a take onto another take lane = MOVE; the clip keeps its time, the target lane lights up (accent fill + outline + label "Promote to main lane" / "Demote to take lane" / "Move to this lane") and a ghost of the clip follows the finger. The same gesture works in the track view (Takes strip shown): hold a take and drag up into the main lane, or hold a main-lane clip and drag it down into a take lane. All three are optimistic (`ui/track/TakeOps.kt`); a refused op (`unsupported`) shows the notice and re-requests the server state. Buttons: selected take -> "Promote" / "To main lane".
6. **Clip** — full-width L->R editor (ui/clip/*): toolbar = signature chip (tap to edit) + ONE grid picker chip ("Auto 1/16 v", popup grid) + Snap | Draw/Select icon toggles | Fold, Scale (only when the song has a scale) | Vel|Prob lane switch | icons Select-all / Duplicate / Delete (content descriptions for accessibility). Signature and grid appear ONLY in the toolbar (the ruler corner is blank). Single row at >= 500dp, two rows below. Default vertical zoom shows 12-16 pitch rows. Note dragged onto an identical pitch+start: both outlined red during the drag, replace on drop (Live's rule). Velocity/probability drag edits the nearest stem to the touch (selected note wins on coincident stems). Zoom limits (horizontal): zoom-out until the clip takes ~1/4 of the width, max = 1/64 note per ~24dp; ruler with loop-brace row (drag body/edges, tap toggles loop), bar/beat/subdivision ticks following the clip signature, start/end flags (drag); floating H-/H+/V-/V+/Fit zoom pad + two-finger pinch/pan. MIDI: note-name keys, Fold, Scale highlight (song scale), Draw/Select, drag to move/resize, box select, Vel/Prob lane. Audio: gain-scaled helper-peaks waveform, seconds or beats ruler, warp markers (Warp+ tap to add, drag to move, Del warp). Recording = growing RED region + red playhead. Playhead interpolated per frame. Properties = thin collapsible handle strip at the bottom (^ PROPERTIES); expanded, they are a right-side column in landscape (editor narrows) or a bottom panel capped at 40% height in portrait.
   (helper peaks) with loop/start markers and warp markers.
7. **Devices** — Live-style horizontal SIGNAL CHAIN: panels left to right joined by flow arrows (title bar = power, name, collapse chevron),
   inline knobs (rows of 4, as many as the panel height allows of the first 16 params; macros for racks; quantized params as stepped selectors; double-tap resets to default, value strings shown),
   rack chains selectable -> the chain's devices appear inline to the right. Hold a title (300ms, same hold as Session/Arrange) and drag to reorder (device.move; a neighbour is passed when the leading edge crosses its centre). Knob cells scale with font scale; on >= 600dp screens knobs/cells/rows scale up to 1.7x with the height, Dup/Del(confirm) buttons,
   Panels are sized to their content (height too); tap a title to SELECT a device: it shows `◀ Move ▶` buttons (same reorder as hold-drag, non-gesture). Quantized parameters are Live-style dropdown chips (value + chevron; tap opens a list). "+" at the end is a compact tile; it opens the native-device quick list (device.insert) with a Browser link. "Params" opens the full list. Browser Load / insert show a
   dim "loading..." panel until the devices section catches up (ui/devices/DevicePending).
8. **Mix** — sends (with return names), routing, and the returns + master strip (two columns when wide). Name / arm / mute / solo / monitor (and vol+pan outside the 700..900dp band) are NOT repeated: they live in the top bar; track duplicate/delete/add live in Tracks.
9. **Browser** — category roots (one scrolling chip row with a right-edge fade + ">" when more chips exist) → folders → items, load onto focused track, preview.
10. **Tracks** (the SAME editor is the top-bar track picker dialog; tapping a row there focuses and closes) — all tracks/returns/master (rows flow into 2-3 columns on wide screens, master pinned below the list; type label scales with font): color bar, tap name = inline rename, arm/M/S, tap row = focus, "Edit" chevron expands color palette / duplicate / delete (confirm); add audio/MIDI/return. No reorder (LOM has no move_track; a note says "To reorder, drag tracks in Live").
11. **Settings** (More tab = SettingsContent + Host/Follow sections; SettingsContent alone is the Connect-screen settings) — host, follow-Live-selection, haptics, update channel (stable/test), check for update (result shown directly under the button; the update section is first on the page). A test build shows "Stable" disabled and "Test (automatic for test builds)". If "Install unknown apps" is needed, the asset is remembered and the download resumes on return. Installed shows versionName + build number (+ "debug build"). Stable with only pre-releases -> "No stable release yet" + hint to switch to Test; switching channel clears the old result; a test build always follows the Test channel (UpdateCheck).

## Cross-cutting (2026-10-16)
- Top bar rows are 40dp (icons 18dp). Rounded display corners: the shell reads WindowInsets rounded corners (Corners, API 31+) and keeps the info strip, rail and top/bottom bars out of the arcs (`cornersOverride` for screenshots).
- Instant feel (principle 3) is implemented in three layers, all with the same rule "render local at frame rate, throttled sends (33 ms, latest wins), settle after release until the server matches or 600 ms, never snap back":
  1. `ui/components/OptimisticDrag.kt` (`Optimistic`, `DragThrottle`): faders, knobs, device params + macros.
  2. `state/SectionGuard.kt` via `LiveStore.editClip/editSlots/editArr/editTakes/editSession`: an optimistic edit is re-applied on top of every incoming section until the server shows it (or 600 ms). Used by clip markers, loop brace, start/end flags, warp markers, signature/props (clip editor), session slot move/copy/swap, take promote/demote/move, punch In/Out, loop toggle/brace, seek.
  3. Gesture-local state: track view clip move/trim (`TrackLane` DragEdit, kept until a clip with the new span arrives or 600 ms), loop brace (`loopPrev`), playhead scrub (`PlayheadClock` holds 600 ms), `Timeline` loop edges (Takes tab) and `NoteEditor` (notes move/resize/draw/velocity: a server list that does not show the local edit is held back 600 ms, then accepted).
  Testing aid: More -> Help -> Developer "Simulate 300 ms server delay" (`net/SimDelay`, shown in debuggable and test-channel builds; honoured by LiveClient and DemoClient). With it on, every drag above must still follow the finger and land without a snap-back.
- Rounded corners: `Corners` (real radii, API 31+) -> `BodyCorners` (CompositionLocal `LocalBodyCorners`) = how much of the bottom-left/right arc is NOT already covered by the rail / info strip. The Arrange bottom strips (control row, tip, selection strip) and the Clip tab handle row (Properties / Close) are inset by it; list-like tabs take half of it as side padding; the velocity lane no longer draws its max label in the bottom-left corner. Button labels never wrap (`LiveTextButton` is `softWrap=false`, e.g. "Fit" at portrait 412).

## Names
Every rename (clip in Arrange/Session/Takes lane, track in Mix/Tracks, clip name field in Clip properties) rejects an empty name with an inline message (`components/Dense.kt: nameError / NameDialog`); nothing blank is ever sent to Live.


## Position text
Every bar.beat.sixteenth readout/parser uses `ui/track/Bbt.kt` (signature-aware, 1-based; a sixteenth is always 0.25 quarter, so 3/8 beats have 2). Typed values out of range are rejected with an inline message, never wrapped.

## Conformance fix pass (2026-10-16)
- Landscape top chrome is ONE 40dp row (`CompactTopBar`): fixed-width buttons, tempo/position sized for "999.99"/"999.4.4" at the current font scale, the track name takes the rest. When the full set does not fit (small phones), session-rec/overdub/loop/metronome/undo/redo/monitor/vol/pan/meter move to a 40dp bottom strip (`CompactSecondaryBar`). Screens < 360dp tall: arrange ruler 12dp + brace 4dp (chrome ~18%).
- Loop brace: invisible >= 40dp grab band (ruler + top of lane), 24dp edge zones, two-tick grips (track view, clip editor, Takes). Clip editor: dragging the ruler (not on a marker/loop edge) scrubs the playhead (arrangement clip: song.seek; session clip: clip.scrub).
- Track list Edit panel: Rename | Duplicate | Delete | Color chip (palette collapsed). Session scene column widens to the longest scene name (cap 120/130dp); long-press shows the full name. Devices chain shows edge fade + chevron when it scrolls. Tracks: Master pinned only when >= 5 rows fit. Release notes: cleaned, first 6 lines + More... Browser opens on Instruments; Takes without lanes shows an explanation + Create lane.
