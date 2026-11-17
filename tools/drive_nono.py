#!/usr/bin/env python3
"""Drives the debug build's Play screen on an emulator with `adb shell input`, aimed by the grid
geometry the board logs (tag NonoGeo) and the solution AppModel logs (tag NonoSol). Debug builds only.

  tools/drive_nono.py geo                         # where the grid is
  tools/drive_nono.py stroke r0 c0 r1 c1 [ms]     # one swipe from cell to cell (0-based)
  tools/drive_nono.py tap r c
  tools/drive_nono.py solve [ms] [rows|cols] [limit]   # fill every run of the solution, one stroke per run
  tools/drive_nono.py board                       # the saved board, next to the solution
  tools/drive_nono.py check r0 c0 r1 c1           # which cells of a straight run are not filled on the saved board

Env: SERIAL (default emulator-5554).
"""
import os, re, subprocess, sys, time, json

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
SERIAL = os.environ.get("SERIAL", "emulator-5554")
PKG = "app.materialnonogram.debug"


def adb(*a, capture=True):
    return subprocess.run([ADB, "-s", SERIAL, *a], capture_output=capture, text=True).stdout


def last(tag):
    out = adb("logcat", "-d", "-s", tag)
    lines = [l for l in out.splitlines() if tag in l and "=" in l]
    if not lines:
        sys.exit(f"no {tag} line in logcat")
    return dict(re.findall(r"(\w+)=(\S+)", lines[-1].split(": ", 1)[1]))


def geo():
    g = last("NonoGeo")
    f = {k: float(g[k]) for k in ("x0", "y0", "c", "minor", "major")}
    f["w"], f["h"] = int(g["w"]), int(g["h"])
    f["view"] = [float(v) for v in g["view"].split(",")]
    f["zoomed"] = g["zoomed"] == "true"
    return f


def pos(g, k):
    return k * (g["c"] + g["minor"]) + (k // 5) * (g["major"] - g["minor"])


def centre(g, r, c):
    return g["x0"] + pos(g, c) + g["c"] / 2, g["y0"] + pos(g, r) + g["c"] / 2


def swipe(g, r0, c0, r1, c1, ms):
    x0, y0 = centre(g, r0, c0)
    x1, y1 = centre(g, r1, c1)
    adb("shell", "input", "swipe", str(int(x0)), str(int(y0)), str(int(x1)), str(int(y1)), str(ms))


def tap(g, r, c):
    x, y = centre(g, r, c)
    adb("shell", "input", "tap", str(int(x)), str(int(y)))


def visible(g, r, c):
    x, y = centre(g, r, c)
    v = g["view"]
    return v[0] <= x <= v[2] and v[1] <= y <= v[3]


def solution():
    s = last("NonoSol")
    w, h, bits = int(s["w"]), int(s["h"]), s["bits"]
    return s["id"], w, h, bits


def saved():
    """The game in progress as saved (the autosave lags a stroke by ~250 ms), with its solution bits."""
    gid = adb("shell", "run-as", PKG, "cat", "files/nonograms/current.txt").strip()
    if not gid:
        gid = solution()[0]
    o = json.loads(adb("shell", "run-as", PKG, "cat", f"files/nonograms/{gid}.json"))
    return o, o["w"], o["h"], o["bits"]


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "geo"
    a = sys.argv[2:]
    if cmd == "geo":
        print(geo())
    elif cmd == "stroke":
        g = geo()
        r0, c0, r1, c1 = map(int, a[:4])
        swipe(g, r0, c0, r1, c1, int(a[4]) if len(a) > 4 else 300)
    elif cmd == "tap":
        g = geo(); tap(g, int(a[0]), int(a[1]))
    elif cmd == "solve":
        g = geo()
        ms = int(a[0]) if a else 120
        axis = a[1] if len(a) > 1 else "rows"
        limit = int(a[2]) if len(a) > 2 else 10 ** 9
        _, w, h, bits = saved()
        n = 0
        lines = range(h) if axis == "rows" else range(w)
        for L in lines:
            cells = [(L, k) for k in range(w)] if axis == "rows" else [(k, L) for k in range(h)]
            run = []
            for (r, c) in cells + [(-1, -1)]:
                on = r >= 0 and bits[r * w + c] == "1"
                if on:
                    run.append((r, c)); continue
                if run:
                    if all(visible(g, *p) for p in (run[0], run[-1])):
                        if len(run) == 1: tap(g, *run[0])
                        else: swipe(g, *run[0], *run[-1], ms)
                        time.sleep(0.25)
                        n += 1
                        if n >= limit: return
                    run = []
    elif cmd == "board":
        o, w, h, bits = saved()
        b = o["board"]
        for r in range(h):
            print(" ".join(".#x"[int(b[r * w + c])] for c in range(w)), "   ", " ".join("#" if bits[r * w + c] == "1" else "." for c in range(w)))
        print("status", o["status"], "lives", o["livesLeft"], "events", len(o["events"]))
    elif cmd == "check":
        o, w, h, bits = saved()
        b = o["board"]
        r0, c0, r1, c1 = map(int, a[:4])
        if r0 == r1:
            cells = [(r0, c) for c in range(min(c0, c1), max(c0, c1) + 1)]
        else:
            cells = [(r, c0) for r in range(min(r0, r1), max(r0, r1) + 1)]
        missing = [(r, c) for (r, c) in cells if b[r * w + c] != "1"]
        print("filled", len(cells) - len(missing), "of", len(cells), "missing", missing)


main()
