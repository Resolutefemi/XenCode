# Logs & Troubleshooting

When something goes wrong with the app, logs are the most direct clue. You can view them in-app under "Settings → Logs".

## Viewing logs in-app

It opens on today's log by default. On this page you can:

- **Log level**: the settings row at the top shows the current level; tap it to pick from a bottom panel (Verbose / Debug / Info / Warn / Error / None). When chasing a bug, set it to `Debug`; for daily use keep `Info`. Release builds default to `Info`, filtering out debug noise.
- **Switch files**: tap the "Log file" row for a bottom sheet of dates, with today selected by default.
- **Search & copy**: keywords filter in real time; the button at the top-right copies the current filtered results in one tap.
- **Color-coded levels**: Verbose gray, Debug blue, Info green, Warn orange, Error red.
- **Copy a single entry**: tap any row to copy that complete log entry (time, level and source included); long-press for a menu choosing what to copy — this entry, this entry plus the stack trace, or just the message text.
- **Live scrolling**: on by default, refreshing every 3 seconds and following the newest entries; turn it off to stop refreshing and keep your manual browsing position.

To keep large logs from stuttering, the in-app view only shows the tail of the current file under the active filter; open the full file from the file system.

## Where the log files are

```
/storage/emulated/0/Android/data/com.xencode/files/logs/
```

No root required — your phone's stock file manager can open it.

Workspace project files and AI configuration live in the app's private directory, invisible to ordinary file managers; see [File browsing & code editing](/guide/files#where-workspace-files-are-stored-on-the-phone) for how to access them.

## Filtering by MCP name

When troubleshooting an MCP server that will not connect, go to "Settings → MCP Servers", edit a server and tap the log button at its top-right — it jumps to the logs page pre-filtered by that server's name (a filter chip at the top of the page clears it in one tap). The log button at the top-right of the list page shows all logs instead.

## How crashes are handled

On an uncaught exception the app **does not just vanish** — it shows a full-screen crash error page:

- The page shows the version, device, time and full stack trace at the moment of the crash.
- Tap "Copy error info" to copy the full crash report (stack trace and log file paths included) and paste it straight to the developers — no need to dig through the logs yourself.
- Choose "Restart app" to return to the main screen, or "Exit app" to quit entirely.

Crash logs are written to that same `logs/` directory.

## What to do when long tasks get killed in the background

If long AI tasks (builds, deep code reviews) or time-consuming terminal commands are often killed when you switch to the background, go to "Settings → System Permissions" and enable **background keep-alive**, **ignore battery optimization** and **auto-start management**; see [System permissions](/guide/app-permissions).
