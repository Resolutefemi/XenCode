#!/bin/bash
# Runs XenCode Desktop's UI tour under Xvfb and screenshots every page.
set -u
export PATH="$HOME/.cargo/bin:$PATH"
BIN=/home/z/my-project/repos/XenCode/rust/target/debug/xencode-desktop
TOUR_DIR=/tmp/ui-tour
CAP=/home/z/my-project/scripts/capture_ui_pages.py

rm -rf "$TOUR_DIR"; mkdir -p "$TOUR_DIR"
export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export XDG_RUNTIME_DIR=/tmp/xdgr; mkdir -p "$XDG_RUNTIME_DIR"
export LD_LIBRARY_PATH=/home/z/my-project/xlibs/usr/lib/x86_64-linux-gnu:/home/z/local-libs/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}
export LIBGL_DRIVERS_PATH=/usr/lib/x86_64-linux-gnu/dri:/home/z/local-libs/usr/lib/x86_64-linux-gnu/dri

pkill -x Xvfb 2>/dev/null; sleep 0.5
Xvfb :99 -screen 0 1280x860x24 +extension GLX +render -noreset >/tmp/xvfb.log 2>&1 &
XVFB_PID=$!
sleep 2

cd /home/z/my-project/repos/XenCode
XENCODE_UI_TOUR="$TOUR_DIR" "$BIN" >/tmp/desktop.log 2>&1 &
APP_PID=$!

python3 "$CAP" "$TOUR_DIR" :99 > /tmp/capture.log 2>&1
CAP_RC=$?

kill $APP_PID 2>/dev/null; sleep 1
pkill -x Xvfb 2>/dev/null
echo "capture rc=$CAP_RC"
ls -la "$TOUR_DIR"/*.png 2>/dev/null | awk '{print $5, $9}'
echo "--- desktop.log tail ---"
tail -5 /tmp/desktop.log
echo "--- capture.log tail ---"
tail -5 /tmp/capture.log
