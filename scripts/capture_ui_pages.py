#!/usr/bin/env python3
"""Captures XenCode Desktop UI-tour pages under Xvfb.

Watches <dir>/marker.txt (written by the app's XENCODE_UI_TOUR mode) and
grabs the X11 root window via python-xlib whenever the marker changes.
Usage: capture_ui_pages.py <tour_dir> [display]
"""
import os
import sys
import time

from PIL import Image
from Xlib import display, X


def main() -> int:
    tour_dir = sys.argv[1]
    dpy_name = sys.argv[2] if len(sys.argv) > 2 else ":99"
    d = display.Display(dpy_name)
    root = d.screen().root
    geo = root.get_geometry()
    w, h = geo.width, geo.height
    marker_path = os.path.join(tour_dir, "marker.txt")
    last = None
    deadline = time.time() + 180  # hard stop
    print(f"watching {marker_path} on {dpy_name} ({w}x{h})", flush=True)
    while time.time() < deadline:
        marker = None
        if os.path.exists(marker_path):
            with open(marker_path) as f:
                marker = f.read().strip()
        if marker == "DONE":
            print("tour finished", flush=True)
            return 0
        if marker and marker != last:
            time.sleep(0.7)  # let the new page render fully
            raw = root.get_image(0, 0, w, h, X.ZPixmap, 0xFFFFFFFF)
            img = Image.frombytes("RGB", (w, h), raw.data, "raw", "BGRX")
            out = os.path.join(tour_dir, f"{marker}.png")
            img.save(out)
            print(f"captured {out}", flush=True)
            last = marker
        time.sleep(0.12)
    print("timed out", flush=True)
    return 1


if __name__ == "__main__":
    sys.exit(main())
