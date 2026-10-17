# One-click LiveCompanion setup for Windows.
#  1. Copies the remote script into the User Library Live is actually configured to use.
#  2. Optionally installs the desktop helper (waveforms, launch Live) and starts it at login.
#  3. Opens the Windows Firewall for Live's ports on PRIVATE networks only.
# Run from an unzipped release (folders LiveCompanion\ and helper\ next to this file) or from the repo.
param([switch]$NoHelper, [switch]$NoFirewall)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$script = @((Join-Path $here "LiveCompanion"), (Join-Path (Split-Path $here) "remote_script\LiveCompanion")) |
    Where-Object { Test-Path (Join-Path $_ "__init__.py") } | Select-Object -First 1
if (-not $script) { throw "Could not find the LiveCompanion script folder next to this installer." }

# --- 1. Remote script -> Live's configured User Library
$userLib = Join-Path $env:USERPROFILE "Documents\Ableton\User Library"
$cfg = Get-ChildItem (Join-Path $env:APPDATA "Ableton") -Directory -Filter "Live *" -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName "Preferences\Library.cfg") } |
    Sort-Object { $v = ($_.Name -replace '[^0-9.]', ''); if ($v -notmatch '\.') { $v += '.0' }; [version]$v } -Descending |
    Select-Object -First 1
if ($cfg) {
    $xml = Get-Content (Join-Path $cfg.FullName "Preferences\Library.cfg") -Raw
    $name = [regex]::Match($xml, '<UserLibrary>[\s\S]*?<ProjectName Value="([^"]*)"').Groups[1].Value
    $path = [regex]::Match($xml, '<UserLibrary>[\s\S]*?<ProjectPath Value="([^"]*)"').Groups[1].Value
    if ($name -and $path) { $userLib = Join-Path ($path -replace '/', '\') $name }
}
$dst = Join-Path $userLib "Remote Scripts\LiveCompanion"
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
New-Item -ItemType Directory -Force $dst | Out-Null
Copy-Item -Recurse -Force (Join-Path $script "*") $dst
Get-ChildItem $dst -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force
Write-Host "[1/3] Live script installed to $dst"

# --- 2. Helper (needs Python 3.11+ on PATH)
if (-not $NoHelper) {
    $helperSrc = @((Join-Path $here "helper"), (Join-Path (Split-Path $here) "helper")) |
        Where-Object { Test-Path (Join-Path $_ "helper.py") } | Select-Object -First 1
    $py = (Get-Command pythonw.exe -ErrorAction SilentlyContinue).Source
    if ($helperSrc -and $py) {
        $helperDst = Join-Path $env:LOCALAPPDATA "LiveCompanion\helper"
        New-Item -ItemType Directory -Force $helperDst | Out-Null
        Copy-Item -Recurse -Force (Join-Path $helperSrc "*") $helperDst
        $lnk = Join-Path ([Environment]::GetFolderPath("Startup")) "LiveCompanion helper.lnk"
        $sh = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk)
        $sh.TargetPath = $py
        $sh.Arguments = "`"$(Join-Path $helperDst 'helper.py')`""
        $sh.WorkingDirectory = $helperDst
        $sh.Save()
        Start-Process $py -ArgumentList "`"$(Join-Path $helperDst 'helper.py')`"" -WorkingDirectory $helperDst
        Write-Host "[2/3] Helper installed, running now and at every login."
    } else {
        Write-Host "[2/3] Helper skipped (needs Python 3.11+ from python.org). Waveforms will show a placeholder."
    }
} else { Write-Host "[2/3] Helper skipped (-NoHelper)." }

# --- 3. Firewall (private networks only; needs admin, so ask once)
if (-not $NoFirewall) {
    $rules = 'New-NetFirewallRule -DisplayName "LiveCompanion (TCP 9010,9012)" -Direction Inbound -Protocol TCP -LocalPort 9010,9012 -Profile Private -Action Allow -ErrorAction SilentlyContinue; ' +
             'New-NetFirewallRule -DisplayName "LiveCompanion (UDP 9011)" -Direction Inbound -Protocol UDP -LocalPort 9011 -Profile Private -Action Allow -ErrorAction SilentlyContinue'
    $exists = Get-NetFirewallRule -DisplayName "LiveCompanion*" -ErrorAction SilentlyContinue
    if (-not $exists) {
        try { Start-Process powershell -Verb RunAs -Wait -ArgumentList "-NoProfile -Command $rules"; Write-Host "[3/3] Firewall opened for LiveCompanion on private networks." }
        catch { Write-Host "[3/3] Firewall step skipped (admin prompt declined). If the phone can't connect, allow Live on Private networks." }
    } else { Write-Host "[3/3] Firewall rules already present." }
}

# --- 4. Hot reload: if Live already runs a LiveCompanion script that supports it, swap in the new code now.
try {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $cts = New-Object System.Threading.CancellationTokenSource 3000
    $ws.ConnectAsync([Uri]"ws://127.0.0.1:9010", $cts.Token).Wait()
    $msg = [Text.Encoding]::UTF8.GetBytes('{"t":"req","id":1,"op":"sys.reload","a":{}}')
    $ws.SendAsync([ArraySegment[byte]]$msg, [Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).Wait()
    Start-Sleep -Milliseconds 800
    $ws.Dispose()
    Write-Host "[4/4] Asked the running script to reload (if it supports it; otherwise restart Live once)."
} catch { Write-Host "[4/4] Live not reachable on 127.0.0.1:9010 - the new script loads when Live starts." }

Write-Host ""
Write-Host "Last step, once: restart Live, then Settings > Link, Tempo & MIDI > Control Surface = LiveCompanion."
