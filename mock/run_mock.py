"""Run the real LiveCompanion core against the fake LOM: python mock/run_mock.py [--port 9010]"""
import argparse
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "remote_script"))
sys.path.insert(0, HERE)

from fake_lom import build_demo  # noqa: E402
from LiveCompanion.core.server import Core  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9010)
    ap.add_argument("--beacon-port", type=int, default=9011)
    ap.add_argument("--no-beacon", action="store_true")
    ap.add_argument("--interval", type=float, default=0.05)
    args = ap.parse_args(argv)
    song, app = build_demo()
    core = Core(song, app, host="mock", live_version="12.4.1", port=args.port, beacon=not args.no_beacon,
                beacon_port=args.beacon_port, log=lambda m: print(m, flush=True))
    print("LiveCompanion mock on ws://0.0.0.0:%d (Ctrl+C to stop)" % args.port, flush=True)
    last = time.monotonic()
    try:
        while True:
            now = time.monotonic()
            song.advance(now - last)
            last = now
            core.tick()
            time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        core.shutdown()


if __name__ == "__main__":
    main()
