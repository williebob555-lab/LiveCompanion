# Copies the LiveCompanion remote script into Live's User Library (Windows).
# Finds the User Library Live is actually configured to use (Library.cfg of the newest Live version),
# falling back to Documents\Ableton\User Library.
$ErrorActionPreference = "Stop"
$src = Join-Path (Split-Path -Parent $PSScriptRoot) "remote_script\LiveCompanion"
if (-not (Test-Path $src)) { throw "Source not found: $src" }

$userLib = Join-Path $env:USERPROFILE "Documents\Ableton\User Library"
$prefsRoot = Join-Path $env:APPDATA "Ableton"
$cfg = Get-ChildItem $prefsRoot -Directory -Filter "Live *" -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName "Preferences\Library.cfg") } |
    Sort-Object { $v = ($_.Name -replace '[^0-9.]', ''); if ($v -notmatch '\.') { $v += '.0' }; [version]$v } -Descending |
    Select-Object -First 1
if ($cfg) {
    $xml = Get-Content (Join-Path $cfg.FullName "Preferences\Library.cfg") -Raw
    $name = [regex]::Match($xml, '<UserLibrary>[\s\S]*?<ProjectName Value="([^"]*)"').Groups[1].Value
    $path = [regex]::Match($xml, '<UserLibrary>[\s\S]*?<ProjectPath Value="([^"]*)"').Groups[1].Value
    if ($name -and $path) { $userLib = Join-Path ($path -replace '/', '\') $name }
    Write-Host "Live settings: $($cfg.Name) -> User Library: $userLib"
}

$dst = Join-Path $userLib "Remote Scripts\LiveCompanion"
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item -Recurse -Force (Join-Path $src "*") $dst
Get-ChildItem -Path $dst -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force
Write-Host "Installed to $dst"
Write-Host "Restart Live, then: Settings > Link, Tempo & MIDI > Control Surface = LiveCompanion (Input/Output: None)."
