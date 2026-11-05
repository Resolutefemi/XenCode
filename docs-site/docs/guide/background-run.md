# Running in the Background

"Settings → Running in the Background" centrally manages how the app behaves and notifies while in the background.

## Background keep-alive

When enabled, a foreground notification is shown so the process is not killed by the system when the app goes to the background. Even with no terminal sessions and an idle AI, the process stays alive; if the system reclaims and recreates the process, the persistent state is restored automatically. Tapping the notification returns to the app's home page.

Enable it before long tasks such as builds.

::: tip Keep-alive is automatic while the AI is working
Even with this toggle off, the same persistent notification is raised automatically when the AI starts a task and keeps the CPU awake (preventing replies from breaking off and tools from stalling after the screen turns off); it is dismissed automatically once tasks in all sessions finish. The point of turning the toggle on manually is "persistent even when idle": keep it on if you want background commands you run yourself in the terminal to go uninterrupted. While the toggle is on, the end of an AI task does not turn it back off.
:::

## Keep screen on

When enabled, the screen never times out as long as the app stays in the foreground — ideal for watching AI tasks or terminal output. It stops automatically when you switch apps or go home; it does not affect the system's auto-lock settings and needs no extra permission.

Keeping it on for a long time uses extra battery; it is best used while plugged in.

## Agent completion notifications

When enabled, a system notification pops up when the app is in the background and the AI finishes a round of conversation (titled "Task complete", with the message you sent as the body; if the round was triggered automatically by a background task, the task's title is shown instead). Tapping it returns to the app to see the result.

No notification is shown while the app is in the foreground, so it never interrupts the conversation you are reading.

::: tip Android 13 and later
Turning on the two toggles above for the first time requests the "Notifications" permission. If the system cannot show the grant dialog (the permission was previously disabled or denied), a popup explains it; tap "Go to settings" to open the system settings page and enable it manually. The toggles have no effect until the permission is granted.
:::

For system permissions (install unknown apps, storage, battery optimization, autostart, Shizuku) see [App Permissions](/guide/app-permissions).
