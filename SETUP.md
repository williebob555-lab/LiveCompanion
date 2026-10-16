# LiveCompanion setup (about 2 minutes)

1. **Phone:** install `LiveCompanion-<version>.apk` from the GitHub release (allow "install unknown apps" once).
2. **PC (Windows):** download `LiveCompanion-remote-script.zip` from the same release and unzip the
   `LiveCompanion` folder into `Documents\Ableton\User Library\Remote Scripts\`.
   (From a clone of the repo instead: run `tools\install_remote_script.ps1`.)
   **Fedora/Wine:** same folder inside the Wine prefix, or run `tools/install_remote_script.sh`.
3. **Live:** Settings → Link, Tempo & MIDI → Control Surface → pick **LiveCompanion** (Input/Output: None).
   One time only — Live remembers it.
4. **Phone:** open the app on the same Wi-Fi; your PC appears under "Found on this network". Tap it.
5. Optional, for waveforms and launching Live from the phone: run `python helper/helper.py` on the PC
   (from the `LiveCompanion-helper.zip`).

Trouble: allow Live through the firewall (TCP 9010, UDP 9011; helper TCP 9012), or type the PC's IP manually.
