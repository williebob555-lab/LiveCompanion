"""Tiny emulator driver for QA: tap by visible text, screenshot, list texts.

Usage:
  python tools/adbui.py [-s SERIAL] texts                 # list visible text/desc with bounds
  python tools/adbui.py [-s SERIAL] tap "Connect"         # tap first element whose text/desc contains it
  python tools/adbui.py [-s SERIAL] tapxy 100 200
  python tools/adbui.py [-s SERIAL] longpress "Bass A"
  python tools/adbui.py [-s SERIAL] swipe x1 y1 x2 y2 [ms]
  python tools/adbui.py [-s SERIAL] type "hello"
  python tools/adbui.py [-s SERIAL] shot qa/screens/name.png
  python tools/adbui.py [-s SERIAL] rotate land|port
  python tools/adbui.py [-s SERIAL] font 1.3
"""
import os
import re
import subprocess
import sys
import time

ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = "adb"


def adb(serial, *args, binary=False):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    out = subprocess.run(cmd, capture_output=True, timeout=60)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def nodes(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb(serial, "shell", "cat", "/sdcard/ui.xml")
    res = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n).group(1) if ' text="' in n else ""
        d = re.search(r'content-desc="([^"]*)"', n)
        d = d.group(1) if d else ""
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if (t or d) and b:
            x1, y1, x2, y2 = map(int, b.groups())
            res.append((t or d, (x1 + x2) // 2, (y1 + y2) // 2, (x1, y1, x2, y2)))
    return res


def find(serial, label):
    ns = nodes(serial)
    for exact in (True, False):
        for t, x, y, b in ns:
            if (t == label) if exact else (label.lower() in t.lower()):
                return x, y
    raise SystemExit("not found: %r (visible: %s)" % (label, [n[0] for n in ns][:40]))


def main(argv):
    serial = None
    if argv[:1] == ["-s"]:
        serial, argv = argv[1], argv[2:]
    cmd, rest = argv[0], argv[1:]
    if cmd == "texts":
        for t, x, y, b in nodes(serial):
            print("%-40s %s" % (t[:40], b))
    elif cmd == "tap":
        x, y = find(serial, rest[0]); adb(serial, "shell", "input", "tap", str(x), str(y)); time.sleep(0.6)
    elif cmd == "tapxy":
        adb(serial, "shell", "input", "tap", rest[0], rest[1]); time.sleep(0.6)
    elif cmd == "longpress":
        x, y = find(serial, rest[0])
        adb(serial, "shell", "input", "swipe", str(x), str(y), str(x), str(y), "900"); time.sleep(0.6)
    elif cmd == "swipe":
        adb(serial, "shell", "input", "swipe", *rest[:4], rest[4] if len(rest) > 4 else "300"); time.sleep(0.6)
    elif cmd == "type":
        adb(serial, "shell", "input", "text", rest[0].replace(" ", "%s"))
    elif cmd == "shot":
        data = adb(serial, "exec-out", "screencap", "-p", binary=True)
        os.makedirs(os.path.dirname(rest[0]) or ".", exist_ok=True)
        with open(rest[0], "wb") as f:
            f.write(data)
        print(rest[0], len(data))
    elif cmd == "rotate":
        adb(serial, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb(serial, "shell", "settings", "put", "system", "user_rotation", "1" if rest[0] == "land" else "0")
        time.sleep(1.5)
    elif cmd == "font":
        adb(serial, "shell", "settings", "put", "system", "font_scale", rest[0]); time.sleep(1.5)
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
