@echo off
rem Starts the LiveCompanion test phone emulator. Uses port 5554 explicitly because Native Instruments'
rem NTKDaemon holds TCP 5563, which breaks the emulator's automatic port choice.
rem Usage: double-click (phone), or:  "Start Emulator.cmd" lc_tablet   /   "Start Emulator.cmd" lc_phone_small
set AVD=%1
if "%AVD%"=="" set AVD=lc_phone
start "" "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd %AVD% -port 5554 -no-snapshot-load
