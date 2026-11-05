# Backup and Restore

"Settings → Backup and Restore" exports app configuration, chat history, credentials, and workspace files into an encrypted archive, and can restore from a backup as well.

## Export

At the top of the page, pick what to export: AI providers, Git credentials, connections and sync, chat history, MCP servers, authorization rules, app settings, workspace files. The checkboxes are remembered and reused next time.

**Workspace files are off by default**; turn them on manually when needed. This packs up all internal workspace files in local mode, including the `.git` directory, so repository history and unpushed commits are fully restored. External workspaces added via "Add local directory" are the original directories on your device and are not packed. Exclusions are each workspace's own `.gitignore` plus the sync feature's default ignore list (`node_modules`, `build`, `dist`, etc.); symlinks are not backed up.

::: tip Prefer Git for code backups
A Git repository already preserves code and history completely; the export here is better as an extra safety net than as your primary way of backing up code.
:::

Tapping "Export backup" opens a dialog to set a passphrase:

- **With a passphrase**: encrypted with AES-GCM.
- **Left empty**: outputs a plaintext tar.gz.

The exported archive contains credentials such as API keys and is saved wherever you choose via the system file picker.

::: danger Remember your passphrase
An encrypted backup cannot be decrypted without its passphrase; there is no backdoor or recovery path.
:::

## Import

Restores from a backup file using a **merge**; existing data is never wiped. Enter the matching passphrase; for an unencrypted backup, leave it empty.

If the backup contains workspace files, a "Restore workspaces" dialog first lists every workspace in the backup (name and file count) so you can check which ones to restore.

::: warning Restoring workspaces overwrites directly
Checked workspaces overwrite files in local workspaces of the same name, and this cannot be undone. If no local workspace has that name, one is created automatically before writing, so no data is lost when switching devices or reinstalling. Remote-mode workspaces (files on the server) do not participate in the restore.
:::

When the import finishes, a summary lists how many of each were restored: providers, Git credentials, remote connections, remote mounts, chat sessions, chat messages, to-dos, MCP servers, global authorization rules, workspace files.

Chat sessions are placed back into the workspace they belonged to when the backup was taken: restored directly if a local workspace of that name exists; otherwise an empty workspace is created automatically and the session attached (the same auto-create logic as "Restore workspaces"), so after switching devices or reinstalling, sessions are not all dumped into one place.

## Additional notes

The backup passphrase only protects the exported backup file itself. Sensitive configuration stored inside the app — API keys, connection passwords, SSH private keys — is separately encrypted with the system keystore (Android Keystore), independent of the backup passphrase; when switching devices, migrate via backup import, since copying the app data directory directly fails to decrypt due to key mismatch.
