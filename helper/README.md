# Helper

Desktop companion for the phone app: waveform peaks, recent sets, launch Live. Stdlib Python 3.11+.

- **Windows:** `python helper\helper.py --port 9012` (allow Python through the firewall on Private networks).
- **Fedora:** `python3 helper/helper.py --port 9012`. Live runs under Wine, so set `WINEPREFIX` if it is not `~/.wine`.
- **Optional:** install `ffmpeg` (with `ffprobe`) for mp3/flac/ogg peaks. WAV and AIFF need nothing extra.
- Check it is up: `curl http://localhost:9012/health` returns `{"ok":true,"version":"0.1.0"}`.
- Peaks are cached as JSON in `helper/.cache/` (keyed by path, mtime and bucket count); delete the folder to reset.
- Tests: `python -m pytest tests/test_helper.py -q` (needs `pip install pytest`).
- Endpoints and payloads: `docs/PROTOCOL.md`, section "Helper HTTP".
