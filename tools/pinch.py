#!/usr/bin/env python3
"""Two-finger gestures on the emulator, which `adb shell input` cannot make: raw multi-touch events
sent through the emulator console (`adb emu event send`), in the touchscreen's 0–32767 range.
`sendevent` would need root, which a Play-image emulator refuses.

  tools/pinch.py pinch cx cy d0 d1 [steps]        # fingers d0 px apart → d1 px apart, about (cx, cy)
  tools/pinch.py pan x y dx dy [steps]            # two fingers 160 px apart, moved together by (dx, dy)
  tools/pinch.py hold-pinch cx cy d0 d1 [steps]   # as pinch, but leaves both fingers down (lift with `up`)
  tools/pinch.py up

Env: SERIAL (emulator-5554), SCREEN (1080x2400).
"""
import os, subprocess, sys

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
SERIAL = os.environ.get("SERIAL", "emulator-5554")
W, H = map(int, os.environ.get("SCREEN", "1080x2400").split("x"))

SLOT, TRACK, X, Y, PRESS = "ABS_MT_SLOT", "ABS_MT_TRACKING_ID", "ABS_MT_POSITION_X", "ABS_MT_POSITION_Y", "ABS_MT_PRESSURE"


def sx(x): return int(x * 32767 / (W - 1))
def sy(y): return int(y * 32767 / (H - 1))


def ev(c, v): return f"EV_ABS:{c}:{v}"
def syn(): return "EV_SYN:0:0"


def frame(pts, first=False):
    out = []
    for k, (x, y) in enumerate(pts):
        out.append(ev(SLOT, k))
        if first:
            out += [ev(TRACK, 100 + k), ev(PRESS, 200)]
        out += [ev(X, sx(x)), ev(Y, sy(y))]
    if first:
        out.append("EV_KEY:BTN_TOUCH:1")
    out.append(syn())
    return [out]


def up(n=2):
    out = []
    for k in range(n):
        out += [ev(SLOT, k), ev(TRACK, -1)]
    out += ["EV_KEY:BTN_TOUCH:0", syn()]
    return [out]


def run(frames):
    for f in frames:
        subprocess.run([ADB, "-s", SERIAL, "emu", "event", "send", *f], check=True, capture_output=True)


def main():
    cmd, a = sys.argv[1], [float(v) for v in sys.argv[2:]]
    if cmd in ("pinch", "hold-pinch"):
        cx, cy, d0, d1 = a[:4]
        steps = int(a[4]) if len(a) > 4 else 12
        lines = []
        for i in range(steps + 1):
            d = d0 + (d1 - d0) * i / steps
            lines += frame([(cx - d / 2, cy), (cx + d / 2, cy)], first=i == 0)
        if cmd == "pinch":
            lines += up()
        run(lines)
    elif cmd == "pan":
        x, y, dx, dy = a[:4]
        steps = int(a[4]) if len(a) > 4 else 12
        lines = []
        for i in range(steps + 1):
            ox, oy = dx * i / steps, dy * i / steps
            lines += frame([(x - 80 + ox, y + oy), (x + 80 + ox, y + oy)], first=i == 0)
        lines += up()
        run(lines)
    elif cmd == "up":
        run(up())


main()
