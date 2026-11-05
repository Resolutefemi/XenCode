# App Permissions

"Settings → App Permissions" centrally manages the system-level permissions the app needs. Permission states refresh automatically when the page resumes (including returning from system settings); already-granted items do nothing when tapped.

The three toggles for background keep-alive, keep screen on, and AI completion notifications live on the [Running in the Background](/guide/background-run) page.

## Install unknown apps

Shows whether it is currently allowed. If not allowed, tapping the row opens the system settings page for this app's "Install unknown apps" switch, which must be enabled before APKs can be installed.

## Access storage

Shows whether it is currently granted. When not granted, tapping pops up the system permission dialog; it is used to read and write workspace files and container images.

If the permission was permanently denied (the system no longer shows the dialog), a popup explains it; tap "Go to settings" to enable it manually.

## Ignore battery optimizations

Shows whether the app is currently exempt. When not exempt, tapping opens the system's battery optimization request page to exempt this app from power-saving policies, reducing the chance of the background process being killed.

## Autostart management

Tapping jumps to your device vendor's autostart management settings (Xiaomi, Huawei, OPPO, vivo, Meizu, etc.), letting the app start itself after boot or after being terminated by the system. If the vendor page will not open or the vendor cannot be matched, it falls back to this app's details page.

Combined with "Background keep-alive" and "Ignore battery optimizations", this further reduces the chance of being killed in the background — Chinese OEM ROMs are fairly aggressive about background restrictions, so if long tasks keep getting interrupted, enable all three.

## Shizuku authorization

Shizuku lets the app run system commands as adb shell (uid 2000) and is a prerequisite for the AI's `Shizuku` tool. The row shows the current state:

- **Not installed / service not running**: tapping opens the Shizuku app; follow its instructions to install it or start the service.
- **Not authorized**: tapping requests authorization; choose "Allow" in the dialog Shizuku shows.
- **Ready**: authorization is complete and the AI can call the `Shizuku` tool.

The state refreshes automatically when you return to this page from the Shizuku app. For usage and security notes see the [Shizuku tool](/guide/shizuku).
