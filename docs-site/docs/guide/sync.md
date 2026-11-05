# Workspace Sync

Sync workspace directories on your phone to a remote server. The entry point is under "Settings → Runtime → Connection & Sync"; the page has four tabs at the bottom.

::: tip How this page divides work with "Containers & Images"
This page only covers **connection channels and file sync**. If you want the AI to use the remote server directly as its execution environment (commands, files, and terminal all running on the server), that is a separate feature — see [Remote SSH Mode](/guide/remote-ssh), which reuses the SFTP channel set up here.
:::

## Connection Configuration

Tap + in the top-right corner to create a connection channel:

- **Protocol type**: `SFTP` (default port 22) or `FTP` (default port 21).
- **Connection name**: An alias.
- **Host address**: IP or domain name.
- **Port / Username / Auth method**: Required. The auth method is either **Password** or **Key** (choose from private keys already added on the "SSH Keys" page; the passphrase of an encrypted private key is entered on the key editing page and is not asked for again here).
- **Test connectivity**: You can test before saving.

### SSH Keys and Host Fingerprints

Tap the key icon in the top bar to open it; there are two tabs at the bottom:

**Keys**: Manage the private keys used for login. Tap + in the top-right corner for two ways to add one: **Import from file** (pick a private key file in the file picker) or **Paste key** (opens the same form as the editing page — paste the private key content and fill in the name and passphrase; leave the name empty for automatic naming). Private keys are copied encrypted into a private directory and their public key fingerprint is extracted; encrypted private keys are labeled "passphrase required". Tap a key to open its editing page: you can change the name and passphrase (the passphrase of the encrypted private key); the private key content is read-only (hidden by default — tap the eye on the right to view it). The passphrase is set on the key itself and is supplied automatically when a connection uses that key. Swipe left to delete.

**Fingerprints**: Manage trusted server host fingerprints. The first time you connect to a server, a dialog shows the key type and `SHA256:` fingerprint; confirming "Trust and connect" saves it, and later connections are allowed straight through. If the server fingerprint changes, confirmation is requested again — this is the man-in-the-middle defense, so be careful when a fingerprint suddenly changes. After swiping left to delete, the next connection asks for confirmation again.

## Mounted Directories

A connection channel only answers "can we connect"; you also need a mapping of which local workspace corresponds to which remote directory. Tap + in the top-right corner:

- **Linked connection channel**: Pick an existing connection from the dropdown.
- **Remote target directory**: The folder icon on the right opens a remote directory browser so you can drill down and pick one.
- **Map to local workspace**: Pick a local workspace from the dropdown (create one in the sidebar first).
- **Auto-connect on app start**: When enabled, it follows the current workspace — on app launch or workspace switch, mounts mapped to the current workspace connect automatically and start watching for local changes, while other workspaces' mounts are disconnected. Failed connections keep retrying at backoff intervals until the toggle is turned off.

When saving, if the same "channel + remote directory + workspace" combination already exists, the app reports that the mount already exists and blocks it.

Once connected, the card offers several actions: Disconnect, Upload all / Sync all, Download all, and Connect and sync. Swipe a list row left to delete it.

::: danger Sync is one-way
Automatic sync only **pushes local changes made after connecting to the remote** (it watches local file additions, deletions, and edits); changes on the remote side are **not** pulled down automatically. Existing files are not uploaded on connect — tap "Upload all" manually when needed.

To pull remote files down to local, tap "Download all" manually — but note this is a **full-overwrite** pull: local files with the same names are overwritten (extra local files are not deleted), and unsaved local changes may be lost. Be sure that is what you want before using it.
:::

The connection timeout is 15 seconds, so an unreachable host fails fast instead of hanging. Connected mounts are probed every 15 seconds; on disconnect the card shows "Reconnecting" and reconnects automatically at 5s, 10s, 20s, 40s, and 60s intervals, resuming sync once reconnected.

## Built-in FTP Server

Run an FTP server on your phone so you can manage its projects from a computer.

The phone and the computer must be on the same WiFi. Once enabled, the screen shows the FTP address (e.g. `ftp://192.168.1.100:2121` — tap the address to copy it). Open it from your computer's file explorer or FileZilla to manage everything under `projects/`.

Configurable options include the listen port, login username, login password, whether anonymous access is allowed, and whether to start on boot; tap "Save config" at the bottom after making changes.

::: warning Security note
Allowing anonymous access means any device on the same network can read and write your project files. Do not enable anonymous access on public WiFi, and turn the server off once you are done.
:::

## Sync Settings

- **Follow .gitignore rules**: When enabled, the `.gitignore` in the project root is read automatically to filter which files get synced.
- **Max sync queue size per round**: Prevents server request overload or network congestion when a large number of files change at once; excess operations are queued and executed in batches.

The sync engine also always ignores common build and cache directories (`node_modules`, `.git`, `build`, `dist`, etc.), so no manual configuration is needed.

Tap "Save settings" at the bottom to apply your changes.
