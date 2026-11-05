# Remote SSH Mode

After selecting a "Remote SSH" container, XenCode enters remote workspace mode: the AI's **file reads/writes, command execution and terminal all happen on the remote server**, with `~/workspace` mapped to a designated directory on the server — the phone is just the control surface.

It fits cases where the server already has a full toolchain, or where you want the AI to edit a remote project in place.

## Prerequisite: create an SFTP channel first

Remote SSH reuses the connection info (host, port, username, password) of an **SFTP** channel from the "Connection & Sync" page — set up an SFTP connection there first; see [Workspace sync](/guide/sync).

FTP channels cannot run commands and never appear in the picker list.

## Creating a remote SSH container

In "Settings → Container & Images" tap + at the top-right and choose "Remote SSH":

- **Name**: an alias for this configuration.
- **SSH connection channel**: pick a configured SFTP channel from the dropdown. When none is available the UI tells you to configure one on the "Connection & Sync" page first, and the save button is disabled.
- **Remote workspace path**: the directory on the server that the AI's `~/workspace` maps to. When a channel is selected it pre-fills the default `~/.xencode/workspaces` (resolved against that user's real home on the server, e.g. `/root/.xencode/workspaces` for root), and you can change it.

After saving, select it in the list and it takes effect.

Authentication follows the chosen SFTP channel's configuration, supporting **password** and **SSH private key** auth (including passphrase-protected keys).

## Connection behavior

- **Auto-connect**: if the app starts in remote mode it connects automatically in the background; a failed connect never blocks the UI and is retried on the first command.
- **Keep-alive & auto-reconnect**: after connecting, a heartbeat is sent every 30 seconds to guard against idle disconnects; on disconnection the background reconnects automatically (backoff from 5 up to 30 seconds), and it retries immediately once more when the app returns to the foreground.
- **Status display**: the top of the chat page shows "SSH: connected / connecting / failed / not connected". When not connected, the message area shows a waiting hint.
- **Two independent connections**: file I/O goes over SFTP while the terminal and commands go over exec — two separate SSH connections that never interfere; if the file channel breaks it is rebuilt automatically without interrupting running commands.
- **Path bootstrapping**: a missing remote workspace path is created automatically; if the directory has no subdirectories, a workspace named `default` is created so the AI always has a usable `~/workspace`.

## Local data vs data that follows the execution environment

In remote mode, commands, files, the terminal and the workspace all live on the server. Most configuration still stays on the phone; a few "execution-environment resources" follow the environment and are kept remotely:

| Item | Description |
| --- | --- |
| Chat history, model & vendor configuration, API keys, app settings | All local |
| Memory | Local. Project-level memory is isolated by "project name + server identity", so same-named workspaces on different servers never mix |
| Global skills, subagents and dashboard scripts | **Follow the execution environment**: in remote mode they read and write `~/.xencode/skills/`, `~/.xencode/agents/`, `~/.xencode/scripts/` on the **server**, independent of the local-mode copy |
| Project-level skills and subagents | Read and write `<workspace>/.xencode/skills/`, `agents/` directly in the **server workspace**; can be committed to git with the code base |
| Project-level configuration (tool authorization rules / MCP / skill & subagent disable lists) | On the phone. In remote mode it is isolated under the app's private directory by "project name + server identity" and never written to the server |
| MCP servers | HTTP ones are connected directly by the app; stdio ones run on the "default container" (see below), not on the remote server |
| Logs and tool output | Local |

Once connected, XenCode syncs the built-in documentation to `~/.xencode/docs/` on the server, so in remote mode the AI can consult these docs too.

## Default container

Some features still need to run locally in remote mode — stdio-type MCP servers, for example. They run on the **default container**, which defaults to the built-in Alpine and can be switched to any local container in the "Default container" group below the container list.

After switching containers (including to remote SSH), all stdio MCP servers disconnect and reconnect on the new container; HTTP MCP does not depend on containers and is unaffected.

## Where Git identity and credentials go

The **identity** saved on the Git page is written to the current execution environment — in remote mode, the server's git config.

**Credentials** take the phone's local credentials file as the single source of truth. In remote mode you can enable "Auto-inject credentials" on the credentials page (remembered per server, asked on first entry), which syncs credentials to the server — effective only for repositories under the workspace directory, leaving repositories elsewhere on the server and the server user's global git config untouched.
