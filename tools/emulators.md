# Android emulators (QA surfaces)

Paths (Windows, Git Bash or PowerShell):
- SDK: `%LOCALAPPDATA%\Android\Sdk`
- adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- emulator: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe`
- sdkmanager / avdmanager: `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\12.0\bin\` (`.bat`)
- JAVA_HOME: `C:\Program Files\Android\Android Studio\jbr`

Image: `system-images;android-35;google_apis;x86_64`

## AVDs
| AVD | Device profile | Notes |
|---|---|---|
| `lc_phone_small` | `small_phone` | small phone, portrait and landscape checks |
| `lc_phone` | `pixel_7` | main phone |
| `lc_tablet` | `pixel_tablet` | tablet / expanded layouts |

## Start an AVD (headless, for QA agents)
```
"%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd lc_phone -no-window -no-audio -no-snapshot
```
Run it in the background. Wait for boot:
```
adb wait-for-device
adb shell getprop sys.boot_completed   # prints 1 when booted
```
Stop it: `adb emu kill`

To see the screen, drop `-no-window`.

## Install an APK
```
adb install -r path\to\LiveCompanion-debug.apk
```
Use `adb devices` to pick a target when more than one AVD is running (`adb -s emulator-5554 install ...`).

## Rotate
```
adb emu rotate
```
Toggles portrait and landscape.

## Screenshot
```
adb exec-out screencap -p > shot.png
```

## Font scale
```
adb shell settings put system font_scale 1.3
adb shell settings put system font_scale 1.0   # reset
```

## Create the AVDs (if missing)
```
avdmanager create avd -n lc_phone_small -k "system-images;android-35;google_apis;x86_64" -d small_phone
avdmanager create avd -n lc_phone -k "system-images;android-35;google_apis;x86_64" -d pixel_7
avdmanager create avd -n lc_tablet -k "system-images;android-35;google_apis;x86_64" -d pixel_tablet
```
Add `--force` to replace an existing AVD of the same name.

## Known gotchas
- NTKDaemon (Native Instruments) can hold TCP 5563, which the emulator needs for its console port. Start with
  `-port 5554 -no-window -gpu swiftshader_indirect` (e.g. `emulator.exe -avd lc_phone -port 5554 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect`).
- Hold-drag (long-press then move) via adb: `adb shell input draganddrop x1 y1 x2 y2 1500` (last arg = duration in ms).
