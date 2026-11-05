# Wirelessly debugging other devices with adb

Once adb is installed in XenCode's Linux container, you can use wireless debugging (Android 11 and up) to connect another Android phone, or the very phone XenCode runs on — no computer needed at any point.

## When to use this

- **Debugging another phone**: install apps, capture logs, take screenshots, simulate input, upload and download files.
- **Debugging the local device**: perform the same operations on the phone you are currently using, from inside XenCode.
- Operations that require root are out of scope: adb runs as `shell` (uid 2000).

## Environment baseline

| Item | Value |
| --- | --- |
| OS | Debian GNU/Linux 12 (bookworm) aarch64 (PRoot container) |
| adb | 34.0.5 (Debian backports; the repo's default version is too old and lacks the `pair` command) |
| Target device | Android 11 and up (verified on Android 15 and Android 16 for this guide) |
| Network | Debugging another phone requires both devices on the same LAN; debugging the local device goes through `127.0.0.1` and needs no LAN |
| Permissions | shell (uid 2000), not root |

## 1. Install adb

The adb version shipped in the container's repo is too old and lacks the `pair` command required for wireless debugging, so install it from backports:

```bash
apt update
apt install -y -t bookworm-backports adb
adb version
# should print Version 34.x
```

**Note**: adb is installed in the container's system directories; after reinstalling the container you must install it again, and the pairing record is lost along with it.

## 2. Pairing (one time only)

First, on the target phone:

1. Settings → About phone → tap "Build number" 7 times to enable Developer options;
2. Developer options → turn on "Wireless debugging";
3. Tap "Pair device with pairing code"; the screen shows the **IP address & port** and a **6-digit pairing code**.

It is best to split-screen the "Wireless debugging" settings page with XenCode so you can act while reading it — once the pairing dialog is switched away or closed, the port and code expire immediately. The pairing port can also be auto-discovered with the script at the end of this page; only the 6-digit code must be read off the screen.

Back in the XenCode terminal, replace the port and pairing code with the values shown on screen:

```bash
adb pair 192.168.1.23:37123 123456
# on success it prints Successfully paired to ...
```

**Note**: The pairing port and code live only briefly; closing the dialog invalidates them immediately and you must reopen it. After a successful pairing the device remembers this host, so you never need to pair again.

## 3. Connecting

```bash
adb connect 192.168.1.23:40001
adb devices -l
```

`192.168.1.23:40001` is the "IP address & port" shown on the wireless debugging main page; note that it is not the same port used for pairing.

The connect port changes every time wireless debugging is re-enabled; check the phone screen again, or auto-discover it with the script at the end of this page.

## 4. Debugging the local device

When debugging the phone XenCode runs on, use `127.0.0.1` as the address (the container shares the phone's network, no LAN needed):

```bash
adb pair 127.0.0.1:37123 123456
adb connect 127.0.0.1:40001
adb devices
```

**Note**: The phone's LAN IP works too, but the same device then appears twice (one `127.0.0.1` entry and one LAN IP entry); use `adb -s <serial>` to pick a device, or `adb disconnect` to drop the extra entry.

The value of debugging the local device: Android restricts ordinary apps from invoking system commands directly (`screencap`, `pm`, `dumpsys` and the like get refused), while running them as `shell` through adb is not subject to that restriction.

## Common operations

```bash
adb shell getprop ro.product.model      # device model
adb shell pm list packages              # installed apps
adb shell logcat -d                     # dump current logs
adb exec-out screencap -p > screen.png  # screenshot
adb push local.txt /sdcard/             # upload a file
adb pull /sdcard/photo.jpg ./           # download a file
adb shell input keyevent KEYCODE_HOME   # simulate a keypress
```

Install apps with `adb install app.apk`; on some devices you must also allow "Install apps via USB" in Developer options.

These commands all run as the `shell` user: they can read and write `/sdcard`, but cannot access restricted directories such as `/data/data`.

## Troubleshooting

**Error `protocol fault (couldn't read status message)`**

The most common cause is an expired pairing port (the dialog was closed or reopened). Reopen the dialog and pair again with the fresh port and code. If the port is definitely current and the error persists, just retry the same command — some adb versions have this known issue, and the second attempt usually succeeds.

**Error `failed to connect to ...: Connection refused`**

The connect port expired, or the device is not paired yet. Check the port on the wireless debugging main page again, and re-pair if necessary.

**Every new terminal needs to reconnect**

The adb server exits when the terminal session ends, so a new session needs `adb connect` run again. Just run `adb start-server`, `adb connect`, and the following commands in the same terminal.

**`adb mdns services` lists nothing**

The mDNS discovery bundled with adb inside the container does not work — the 5353 port it needs is already taken by the phone's own mDNS service. Manual connections are unaffected; for auto-discovery use the script below.

## Appendix: auto-discovering ports

With wireless debugging on, the device broadcasts `_adb-tls-pairing._tcp` (the pairing service, only while the pairing dialog is open) and `_adb-tls-connect._tcp` (the connect service) on the LAN. Save the script below as `discover_adb.py`; running it lists the "IP:port" of each:

```python
#!/usr/bin/env python3
"""Scan the LAN for adb wireless debugging endpoints"""
import socket
import struct
import time

SERVICES = {
    'pairing port': '_adb-tls-pairing._tcp.local',
    'connect port': '_adb-tls-connect._tcp.local',
}


def encode(name):
    out = b''
    for part in name.split('.'):
        out += bytes([len(part)]) + part.encode()
    return out + b'\x00'


def read_name(data, off):
    labels, jumped, start = [], False, off
    while True:
        length = data[off]
        if length == 0:
            off += 1
            break
        if length & 0xC0 == 0xC0:
            ptr = struct.unpack('!H', data[off:off + 2])[0] & 0x3FFF
            if not jumped:
                start = off + 2
            off, jumped = ptr, True
            continue
        labels.append(data[off + 1:off + 1 + length].decode())
        off += 1 + length
    return '.'.join(labels), (start if jumped else off)


def scan(service, seconds=6):
    # Setting the high bit of class requests a unicast response: the pairing service only replies multicast to ordinary queries,
    # which cannot be received without binding 5353; the connect service replies unicast
    query = (struct.pack('!HHHHHH', 0, 0, 1, 0, 0, 0)
             + encode(service)
             + struct.pack('!HH', 12, 0x8001))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                    struct.pack('4s4s', socket.inet_aton('224.0.0.251'),
                                socket.inet_aton('0.0.0.0')))
    sock.bind(('', 0))
    sock.settimeout(0.3)
    endpoints = set()
    deadline = time.time() + seconds
    while time.time() < deadline:
        sock.sendto(query, ('224.0.0.251', 5353))
        try:
            data, addr = sock.recvfrom(9000)
        except socket.timeout:
            continue
        qd, an, ns, ar = struct.unpack('!HHHH', data[4:12])
        off = 12
        for _ in range(qd):
            _, off = read_name(data, off)
            off += 4
        for _ in range(an + ns + ar):
            name, off = read_name(data, off)
            rtype, _, _, rdlen = struct.unpack('!HHIH', data[off:off + 10])
            off += 10
            rdata = data[off:off + rdlen]
            off += rdlen
            if rtype == 33 and service.split('.')[0] in name:
                port = struct.unpack('!HHH', rdata[:6])[2]
                endpoints.add(f'{addr[0]}:{port}')
    sock.close()
    return endpoints


for label, service in SERVICES.items():
    for endpoint in sorted(scan(service)):
        print(f'{label}  {endpoint}')
```

Usage:

```bash
python3 discover_adb.py
# pairing port  192.168.1.23:37123
# connect port  192.168.1.23:40001
adb pair 192.168.1.23:37123 123456
adb connect 192.168.1.23:40001
```

**Note**: The pairing service only broadcasts while the "Pair device with pairing code" dialog is open, so run the script with the dialog open; the 6-digit pairing code always has to be read from the phone screen. The list may contain several results (including stale ports from before a device reboot) — just try `adb connect` on each one.
