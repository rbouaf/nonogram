#!/usr/bin/env python3
"""Regenerates the adaptive launcher icon's foreground (and an SVG preview in /tmp/nono-icon.svg).

A 5×5 nonogram block drawn the way Play's "Blocks" board draws it: empty wells faint, and every
filled run fused into one rounded shape, so the thing a clue counts (a run) is one object. The
pattern is a smoothed blob, never a picture (D3). Everything sits in a 46 dp square centred on the
108 dp canvas, inside the 66 dp safe circle every launcher mask keeps (a 46 dp square has its corners
32.5 dp from centre, just under the 33 dp radius).
Usage: python3 tools/gen_launcher_icon.py
"""
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
INK = "#593400"            # onPrimaryContainer of the Amber seed, on the #FFB156 background
N, BOX = 5, 46.0
GAP = 1.6
TILE = (BOX - GAP * (N - 1)) / N
O = (108 - BOX) / 2        # 31
R_WELL, R_RUN = 1.6, 3.2
PATTERN = [
    "##...",
    "####.",
    "#####",
    ".####",
    "..##.",
]


def filled(r, c):
    return 0 <= r < N and 0 <= c < N and PATTERN[r][c] == "#"


def cell(x, y, w, h, tl, tr, br, bl):
    """A rectangle with its own radius per corner (0 = square)."""
    d = f"M{x + tl:.3f},{y:.3f}H{x + w - tr:.3f}"
    if tr: d += f"A{tr},{tr} 0 0 1 {x + w:.3f},{y + tr:.3f}"
    d += f"V{y + h - br:.3f}"
    if br: d += f"A{br},{br} 0 0 1 {x + w - br:.3f},{y + h:.3f}"
    d += f"H{x + bl:.3f}"
    if bl: d += f"A{bl},{bl} 0 0 1 {x:.3f},{y + h - bl:.3f}"
    d += f"V{y + tl:.3f}"
    if tl: d += f"A{tl},{tl} 0 0 1 {x + tl:.3f},{y:.3f}"
    return d + "Z"


def rr(x, y, w, h, r):
    return cell(x, y, w, h, r, r, r, r)


shapes = []  # (alpha, path)
# Wells first, then fills fused across the gaps to filled neighbours, the way Play's Blocks board fuses a
# run; a corner is round only where it is convex (both its orthogonal neighbours empty).
for r in range(N):
    for c in range(N):
        x = O + c * (TILE + GAP); y = O + r * (TILE + GAP)
        if not filled(r, c):
            shapes.append(("0.20", rr(x, y, TILE, TILE, R_WELL)))
for r in range(N):
    for c in range(N):
        if not filled(r, c):
            continue
        x = O + c * (TILE + GAP); y = O + r * (TILE + GAP)
        R, D = filled(r, c + 1), filled(r + 1, c)
        w = TILE + (GAP if R else 0); h = TILE + (GAP if D else 0)
        k = lambda a, b: 0 if (a or b) else R_RUN
        tl = k(filled(r, c - 1), filled(r - 1, c)); tr = k(R, filled(r - 1, c))
        br = k(R, D); bl = k(filled(r, c - 1), D)
        shapes.append(("1.0", cell(x, y, w, h, tl, tr, br, bl)))
        # Two fused runs side by side leave a hole where the gaps cross: close it only inside a 2×2 of fills.
        if R and D and filled(r + 1, c + 1):
            shapes.append(("1.0", cell(x + TILE, y + TILE, GAP, GAP, 0, 0, 0, 0)))

paths = [f'    <path android:fillColor="{INK}" android:fillAlpha="{a}" android:pathData="{d}"/>' for a, d in shapes]
out = ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
out.write_text('<?xml version="1.0" encoding="utf-8"?>\n<!-- Written by tools/gen_launcher_icon.py: a 5×5 block, filled runs fused, inside the 66 dp safe zone. -->\n'
               '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
               '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
               + "\n".join(paths) + "\n</vector>\n")
svg = "".join(f'<path fill="{INK}" fill-opacity="{a}" d="{d}"/>' for a, d in shapes)
pathlib.Path("/tmp/nono-icon.svg").write_text(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">'
    '<defs><clipPath id="m"><circle cx="54" cy="54" r="54"/></clipPath></defs>'
    '<g clip-path="url(#m)"><rect width="108" height="108" fill="#FFB156"/>' + svg +
    '<circle cx="54" cy="54" r="33" fill="none" stroke="#0005" stroke-dasharray="2 2" stroke-width="0.4"/></g></svg>')
print("wrote", out)
