# Copies the LiveCompanion remote script into Live's User Library (Windows).
$ErrorActionPreference = "Stop"
$src = Join-Path (Split-Path -Parent $PSScriptRoot) "remote_script\LiveCompanion"
$dst = Join-Path $env:USERPROFILE "Documents\Ableton\User Library\Remote Scripts\LiveCompanion"
if (-not (Test-Path $src)) { throw "Source not found: $src" }
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item -Recurse -Force (Join-Path $src "*") $dst
Get-ChildItem -Path $dst -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force
Write-Host "Installed to $dst"
Write-Host "In Live: Settings > Link/Tempo/MIDI > Control Surface = LiveCompanion (Input/Output: None)."
