# LiveCompanion setup (about 2 minutes)

## Windows (one click)
1. From the latest GitHub release download **LiveCompanion-Windows-setup.zip**, unzip it anywhere,
   double-click **Install LiveCompanion.cmd**. It:
   - installs the Live script into the User Library Live is configured to use (found automatically),
   - installs the waveform helper and starts it at login (needs Python 3.11+; skipped otherwise),
   - asks once for admin to open the firewall for LiveCompanion on **private** networks.
2. Restart Live → Settings → Link, Tempo & MIDI → Control Surface → **LiveCompanion** (Input/Output: None). Once only.
3. Phone: install the APK from the same release, open it on the same Wi-Fi, tap your PC under "Found on this network".
   Later updates install from inside the app (More → Check for updates).

## Fedora / Wine
Run `tools/install_remote_script.sh` (uses `$WINEPREFIX`, default `~/.wine`), then step 2 above; optional helper:
`python3 helper/helper.py`.

## Trouble
- Phone can't connect: make sure nothing else uses port 9010 (e.g. a running mock), and that Live is allowed on
  private networks (TCP 9010, UDP 9011; helper TCP 9012). Or type the PC's IP under Manual.
- Upgrading from a v0.1.0-test1…test4 build: uninstall it once (those were signed with throwaway keys).
