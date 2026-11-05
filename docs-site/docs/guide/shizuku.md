# Shizuku Tool

Shizuku lets ordinary apps run commands as adb shell (uid 2000) **without root**. Once connected, the AI gains a tool named `Shizuku` that can run `pm`, `am`, `cmd` and other commands directly on the phone's system and read/write `/sdcard`, covering what the local container (PRoot) lacks — system-level permissions.

Shizuku runs as the shell user and **cannot read other apps' private data directories** (that requires root).

## Prerequisite: install and start Shizuku

1. Install Shizuku from [its website](https://shizuku.rikka.app/download/) or an app store.
2. Open Shizuku and start the service following the in-app guide:
   - Android 11 and above: after enabling "Wireless debugging" it can be started right on the phone;
   - Android 10 and below: connect to a computer and start it with adb.

## Authorizing in XenCode

Open "Settings → System Permissions → Shizuku"; the page shows the current status:

- **Not installed**: tap to go install it.
- **Service not running**: tap to open Shizuku and start the service.
- **Not authorized**: tap to request authorization; Shizuku shows a dialog — choose "Allow".
- **Ready**: authorization complete — the AI can now use the `Shizuku` tool.

Returning from the Shizuku app to XenCode refreshes the status automatically.

## Usage & security

`Shizuku` operates on the host Android system directly as adb shell — the permissions are high-risk, so it is treated as a **high-risk tool** for authorization:

- **Every call pops an authorization confirmation**: including read-only commands like `ls`; it bypasses no built-in allowlist and is never auto-allowed by an existing rule.
- **"Always allow" is not supported**: the "Always allow" button in the dialog is disabled (with the reason shown) — each call is allowed once only and no remembered rule is created.
- **AUTO (auto) mode still pops dialogs**: AUTO's "allow all" has no effect on it; only after enabling "Settings → Tool authorization → Disable safety interception" does AUTO restore automatic approval (BUILD mode still asks every time and cannot be remembered).
- Under **PLAN (plan) mode**, the tool is blocked like any other write operation.
- **Deny rules** in project permission rules still apply to it (a `deny` hit is rejected outright).

## FAQ

**A command reports "Shizuku not ready"**

Follow the settings page and confirm in order: installed → service started → authorized. After a device reboot the Shizuku service usually needs starting again (on Android 11+ you can re-enable wireless debugging inside Shizuku to start it).

**The `Shizuku` tool fails without an obvious error**

Some commands need specific system permissions that the shell identity does not have (e.g. accessing other apps' private directories). Compare with `Bash` in the terminal to tell whether it is the command itself or a permission issue.
