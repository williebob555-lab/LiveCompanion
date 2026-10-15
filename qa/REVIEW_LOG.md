# QA review log

Written by the QA agent(s). One section per round. Each finding:
`- [ID] [severity: blocker|major|minor|nit] [area] what happened → expected. Repro: ... Status: open|fixed(<commit>)|wontfix`
Rounds: R1 intended use · R2 odd combinations · R3 unintended use · R4 try to break it.
Open findings are mirrored into TODO.md under "QA follow-ups".

## R0 — orchestrator smoke test (emulator lc_phone 1080x2400, mock + helper, APK @ bf985cd)
- [S1] [major] [shell] After connect no track is focused → "No track selected", empty session. Expected: auto-focus Live's selected track (or first track) on connect. Status: open
- [S2] [nit] [header] Pan readout "C" is drawn over the pan center line. Status: open
- OK: connect via 10.0.2.2 shortcut, sections arrive, next-track focuses Drums, slots/scenes/vol/pan render, no overlap at 1080x2400.
