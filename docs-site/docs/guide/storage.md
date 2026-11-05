# Storage

"Settings → Storage" shows, by category, how much space XenCode uses on this device, with an entry for cleaning up temporary data.

## Overview

At the top of the page are the app's total usage, a composition bar and the device's free space. The bar's full width represents total usage, and its colors correspond one-to-one with the color dots of the categories below. Pinned at the bottom is the "Clear selected" button together with the size of the selected data — still reachable while scrolling the list.

Counting requires walking files directory by directory — containers and workspaces are usually the slowest. So the page is never left blank: each category appears as soon as it is computed, pending ones show "—", and a "Computing…" hint sits at the top. To recount, tap the refresh icon at the top-right.

## Usage breakdown

| Category | What it contains |
| --- | --- |
| Chats | Database files: sessions, messages, tool results, call records, checkpoint indexes |
| Containers | Each container's root filesystem. Expand to see every container, plus the largest directories inside the biggest one |
| Container image archives | Downloaded or imported image archives. No longer needed once the container is set up, but deleting one means that container can never be extracted again |
| Workspaces | Project files inside the app's built-in workspaces (including `.git`). Expand to see each project; the subtitle marks its chat attachments |
| AI configuration & artifacts | Skills, memory, subagents, MCP configuration, prompts, docs, plus tool-output archives, image-recognition session caches and network request history |
| Checkpoints | Snapshots taken before the AI modifies files, used for undo |
| Logs | App runtime logs and AI request logs |
| Cache | The system cache directory; clearable anytime |
| Other data | Scattered data: settings, wallpapers, terminal fonts, SSH private keys, etc. |
| App package | The APK itself; not clearable |

Categories whose name carries an arrow expand into details on tap. "AI configuration & artifacts" only expands to show the clearable items — tool-output archives, image-recognition session caches and network request history; non-clearable content like skills, memory and prompts is no longer itemized but still counts toward the category total. The clearable sub-items shown may therefore add up to less than the category's total size.

::: tip What is not counted
External workspaces added via "Add local directory" live in your device's shared storage — they are not app usage, so they are not counted. The same goes for files on the server in remote SSH mode.
:::

The expanded view of Chats lists the biggest sessions. It is an estimate from message bodies, smaller than the database files themselves — the database also carries index and page-alignment overhead. To shrink this item, delete unneeded sessions from the session list; to clear only call records, reset at the top-right of the "Token stats" page.

Containers and workspaces cannot be deleted here — reset or delete containers on the "Container & Images" page and delete workspaces in the workspace switcher, to avoid deleting an environment in use. Deleting a workspace deletes its chat history along with it; whether deleting an external local workspace also deletes its chats is decided by a toggle in "Preferences".

## Reclaimable space

The cleanup entry sits together with the usage breakdown rather than as a separate group. The five clearable items are below — none of them touch chats, code or configuration, but some clears affect viewing or following up on history:

- **Cache files**: check the "Cache" category. Temporary files are rebuilt later anyway.
- **Runtime logs**: check the "Logs" category. Covers app logs and AI request logs; new logs are written as usual after clearing, and tapping the category still expands to show each kind's usage.
- **Tool-output archives**: expand "AI configuration & artifacts" and check the matching detail. Overly long AI tool output is written to disk as a full copy. After clearing, output paths mentioned in history can no longer be read.
- **Image-recognition session cache**: expand "AI configuration & artifacts" and check the matching detail. This is the history used for follow-up questions on images; after clearing, old images can no longer be queried further.
- **Network request history**: expand "AI configuration & artifacts" and check the matching detail. A JSON snapshot dropped on every send from the Network Request page; after clearing, the history list is empty. These three items appear only when they use space.

Logs and cache are checked by default; tool output, image-recognition cache and network request history are not. After checking, the bottom button updates the total size of the selection; collapsing a category does not deselect it. Items with no usage cannot be checked, and the button is disabled when no clearable data is selected.

After tapping "Clear selected", the confirmation dialog lists the targets, total size and per-item impact. Confirming clears only the selected items; when done the page recounts automatically and shows how much space was freed.

The cleanup scope is this device's app cache, logs and global-directory data; tool output and image-recognition cache may have been produced by several workspaces, not just the current one. It never clears the workspaces' own files, attachments, build caches, or data on the remote server.

::: warning Clearing is irreversible
Deleted files cannot be recovered. Although these items contain no chats, code or configuration, old tool-output copies and image-recognition session history cannot be restored by using the app again.
:::

## What to do when usage is large

- **Containers in the gigabytes**: package-manager caches inside the container are usually the bulk (the cache directories of Gradle, npm, pip, etc.). Expand the Containers category to find the largest directory, then clean it in the terminal, e.g. `rm -rf ~/.gradle/caches`. Containers no longer in use can be deleted on the "Container & Images" page.
- **Workspaces on the large side**: build artifacts like `node_modules`, `build`, `.gradle` take most of it — delete them and rebuild to restore.
- **Chats on the large side**: tool output in long sessions takes the most space — delete early sessions you no longer need.
- **Image archives still around**: once the container is set up, delete the downloaded file on the "Container & Images → Download images" page.
