# Remote Environments

"Settings → Remote Environments" connects cloud Linux machines so the AI can run heavy jobs outside the phone's container, one-off sandboxes, or remote desktops. The currently available provider is **InstaBox**; **E2B** is a placeholder and not yet usable.

## InstaBox

InstaBox provides a persistent cloud Debian VM (default template `agent-browser`, home directory `/data/home`). The `linux_machine` tool only appears in conversations after you enable it and save an API key.

### First-time setup

1. Open **Settings → Remote Environments → InstaBox**.
2. Turn on the enable switch and enter your InstaBox API key (`ibk_…`). The API URL defaults to `https://api.instabox.sh` and the template to `agent-browser` — usually no need to change either.
3. Optional: enter the id of a machine to reuse (`m_…`). Left empty, the AI creates a machine or reuses one this app has tagged before.
4. Tap **Save**.
5. Switch to the **Arsenal** page at the bottom, tap "Test connection" to confirm the key works, and check the tool capability list.

The key is stored encrypted in the app's private directory via the system keystore and never appears in conversation content.

### How the AI uses it

- Use `linux_machine` when the user explicitly asks for a remote computer, the task is too heavy for the phone/container, or a one-off sandbox is needed right away.
- The same session reuses the pinned machine, so there is no need to pass `machineId` every time.
- In Build mode, write operations (create / run commands / write files / desktop control, etc.) pop a confirmation dialog.
- Plan / Ask modes allow only read-only actions: `info`, `list`, `files_list`, `files_read`, `files_exists`, `list_ports`, `desktop_status`, `metrics`.

After the switch is turned off or the key cleared, `linux_machine` disappears from the current turn's tool list.

### Common failures

| Message | Cause and fix |
| --- | --- |
| Tool does not appear | Not enabled or key not saved; check the switch and key on the InstaBox page |
| `DISABLED` / `NO_KEY` | Same as above; save first, then send another message |
| Connection test failed | Invalid key, network unreachable, or wrong API URL; verify against the InstaBox console |
