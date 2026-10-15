# LiveCompanion — agent entry point

Android remote for Ableton Live 12 (Windows, or Fedora via Wine), focused on **one track at a time**.

## Before changing anything (mandatory)
1. Read `docs/PREFERENCES.md` — owner preferences, corrections, perf-sensitive areas, process rules. Overrides defaults.
2. Read `TODO.md` — the workstation. Resume from the "In progress" section if interrupted.
3. Read the doc(s) for the area you touch:
   - `docs/ARCHITECTURE.md` — components, repo layout, data flow, build/run
   - `docs/PROTOCOL.md` — wire protocol (source of truth for phone <-> Live)
   - `docs/LOM_NOTES.md` — verified facts about Live 12's Live Object Model
   - `docs/UI.md` — visual language, layout rules, screen inventory
4. QA findings live in `qa/REVIEW_LOG.md`; open items are mirrored into `TODO.md`.

## After changing anything
- Update TODO.md (move items, note decisions), and any doc whose facts changed.
- New owner preference/correction -> `docs/PREFERENCES.md` immediately.

## Gotchas
- NEVER rewrite files with Windows PowerShell `Get-Content`/`Set-Content` — it mangles UTF-8 (→ becomes â†’)
  and line endings. Use the Edit/Write tools or Python with encoding="utf-8", newline="".

## Layout
```
remote_script/LiveCompanion/   Python Control Surface that runs inside Live (stdlib only)
mock/                          Fake LOM + mock Live server (runs same handlers, no Live needed)
helper/                        Desktop helper: waveform peaks, launch Live (stdlib Python)
android/                       Kotlin + Jetpack Compose app
tools/                         Install/dev scripts
docs/  qa/  TODO.md
```
