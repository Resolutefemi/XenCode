# File Browsing & Code Editing

::: tip Version note
The file tree and code editing page are available since 1.11.0.
:::

The "Files" tab at the top of the sidebar browses the current workspace; tapping a file opens the full-screen code editing page by default (images and installer packages excepted—see "Images and installers" below). Both local and remote SSH workspaces are supported, with reads and writes handled automatically according to the current mode.

## Browsing Files

The "Files" page is an indented file tree expanded in place from the workspace root.

- The first row is the workspace root `workspace`; it can be collapsed and expanded too (expanded by default), with a refresh button on the right.
- Tap a directory to expand or collapse it in place; tap a file to open the editing page directly (images and installers: see "Images and installers" below)—no drilling in and out level by level.
- Directories come before files; within a group, entries sort by name.
- Files show different icons by type (code, scripts, configuration, documents, images, audio/video, archives, etc.), using the same monochrome linear icons as the rest of the app, tinted with the theme color.
- The coloring rules are similar to VS Code: hidden files starting with `.` are shown dimmed, and entries matched by the workspace root's `.gitignore` are orange (only the root `.gitignore` is consulted; ones in subdirectories are not parsed).
- Deep paths and long file names are no problem: the whole tree shares one horizontal scroll and names are never truncated into ellipses—swipe left and right to see everything.
- Directories you expand or collapse are **remembered per workspace**: closing and reopening the app, or switching away and back to the same workspace, restores the previous expansion state.
- Browsing is confined to the workspace; you can't go above the workspace root.

## Images and Installers

**Images** in the file tree (png / jpg / jpeg / gif / webp / bmp) open the in-app fullscreen viewer, with pinch-to-zoom and double-tap zoom, same as viewing images in the chat area; **APK / APKS / XAPK** files invoke the system installer directly (the first time, it walks you through enabling "Allow installing unknown apps"). Neither type enters the code editor; every other file still opens the editing page as usual.

## Opening Files from the Conversation

When the AI references code in the conversation, the path is written as a clickable link (an underlined `file path:line number`). Tapping such a link opens the editing page directly; with a line number it also scrolls to that line and tries to **keep it near the middle of the screen** (the target line ends up flush with the edge when it's close to the beginning or end of the file, limited by the scroll range). Once you scroll, auto-positioning hands control back to you and never drags the view back to the target line.

Files inside the workspace, the AI configuration directory (`~/.xencode/...`), and container system files (`/etc/...`, `/root/...`) are supported, and they can be opened under remote SSH workspaces as well. Links pointing to ordinary URLs are unaffected and still open in the system browser.

Tapping a nonexistent path, a directory, or a file over the size limit shows the corresponding failure message in the editor.

## List Refresh

In local workspaces, file changes **refresh automatically**—not just the AI's: changes made by running commands in the terminal or by other apps are picked up as well.

It watches the workspace root and all currently **expanded** directories—no recursive walk of the whole tree—so even large repositories don't consume much in the way of system resources; once a directory is collapsed, it is no longer watched.

Two other refresh moments: the sidebar re-reads every time it appears (with a persistent sidebar on wide screens, every return to the chat page from another page counts), and the refresh button to the right of the workspace root row re-reads manually at any time.

Remote SSH workspaces cannot watch the remote filesystem for changes and **can only** refresh at these two moments—tap the refresh button if the list looks out of sync. In remote mode, reading a directory goes over the network and may briefly show a loading state; on failure, the specific reason is shown in place of the list.

## Create, import, rename, delete

**Long-press a folder** (including the workspace root) and choose **New file** or **New folder**. The new item is created in that folder. If the folder is collapsed, it expands so you can see the result.

Names must be a single entry: `/`, `\`, `.`, or `..` disable the confirm button so nothing is created outside the folder. If the name already exists or the folder is not writable, the app reports failure and does not overwrite.

The same menu also imports from the device:

- **Import files**: pick one or more files; they are copied into that folder.
- **Import folder**: pick a device folder; the whole tree is copied in. Folder import only works for directories the app can resolve to a real path on shared storage.
- Same-name items get a numeric suffix (`readme-1.md`) and are not overwritten.

**Copy path** is available on files and folders and copies the workspace path to the clipboard.

**Long-press a file or subfolder** to rename or delete it (the workspace root can only create or import):

- **Rename**: rename in the same folder only; this does not move across folders.
- **Delete**: files are deleted immediately; folders are deleted recursively after a confirmation prompt.

::: danger Deletion is irreversible
Deletion bypasses the recycle bin and is not protected by checkpoints. Think it through before confirming, especially when deleting folders.
:::

## Copy, Cut, and Paste

Long-press a file or subfolder and choose "Copy" or "Cut", then long-press the destination folder and choose "Paste here". If an entry with the same name already exists at the destination, you are asked whether to overwrite; overwriting replaces the existing entry and its contents and cannot be undone.

You can't paste over the source entry in its original directory, nor copy or move a folder into itself or its subfolders. Such operations are rejected and the source files stay unchanged.

## Code Editor Page

After you tap a file, the sidebar collapses and the full-screen editing page opens.

At the top left is the **hamburger button**, which opens the "Project Files tree" standard navigation drawer on the left (content is pushed to the right, not covered over) containing the same complete file tree as the sidebar; tapping a file in the tree smoothly closes the drawer and opens or switches to that file. While the drawer is open, the hamburger button animates with a rotation into a **close button**—tap it again to close the drawer. The drawer **opens and closes only through this button; swiping in from the screen edge is not supported**, to avoid conflicting with editing gestures. The old "Back" is handled by the system back key: pressing back first closes the drawer, then exits the editing page—when you entered from the sidebar file tree, the drawer automatically reopens after exiting.

There is no tab bar under the drawer: tapping a file in the tree **directly replaces the current editing content** with that file, and picking a previously opened file again opens it just as directly (unsaved drafts from this session are kept—nothing is lost when you switch back). Unsaved drafts live in memory only: they survive leaving the editing page, but are discarded when the process ends.

- **Undo steps follow input pauses**: rapid consecutive typing counts as one step; typing again after a pause of about half a second starts a new step. Once you have undone all the way back to how the file looked when opened, the asterisk before the file name disappears and the save button grays out; exiting then no longer prompts about saving.
- **Pressing the back key to exit with unsaved changes first shows a confirmation dialog**, where you can choose "Discard" or "Save and exit". The hamburger drawer is also supported when there is a large-screen right pane.
- When a Markdown file is open, an extra **preview toggle** button appears to the left of the undo button; tap it to switch to the rendered view, tap again to switch back to the source. Preview and source share the same editing state—switching never loses unsaved edits.
- The preview is full Markdown rendering: **HTML embedded in the document (centered headings, images, table layouts, badges, etc.) displays as usual**, code blocks are syntax highlighted, and the color scheme follows the app's light/dark theme. Relative-path images in the document (e.g. `docs/a.png`) are read from the current workspace, so they display in remote workspaces too; web images (badges, online charts, etc.) need a network connection to load. Tapping links inside the preview doesn't navigate, to avoid leaving the current document.
- The preview has no LaTeX formula rendering; switch back to the source view for documents containing formulas.
- The **bottom quick-symbol bar** scrolls horizontally: leftmost are cursor left/right movement and indent (Tab), followed by symbols commonly used in programming (brackets, quotes, operators, `;` `:` `|` `&`, etc.); tapping inserts at the cursor. When the soft keyboard is up, the symbol bar floats above it.
- With unsaved changes, pressing the system back key first shows a confirmation dialog where you can choose "Discard" or "Save and exit".
- **You can scroll past the end of the file by another half screen**: the last few lines don't stick to the bottom edge, making it easier to edit at the end of the file.

### Syntax Highlighting

These languages are built in: Kotlin, Java, Python, JavaScript/JSX, TypeScript/TSX, JSON/JSONC, YAML, Shell, Go, Rust, C/C++, CSS, XML, HTML, Markdown, PHP, Lua. `.kts` is highlighted as Kotlin. Unrecognized types display as plain text; editing is unaffected.

The color scheme uses the VS Code default themes (Dark+ for dark / Light+ for light), switching automatically with the app theme; changing the theme while editing never loses unsaved content.

### Editor Settings

Tap the settings button in the top-right corner to open the standalone settings page; changes take effect immediately and are remembered for the next time you open it:

- **Font size**: adjust with the slider.
- **Word wrap**: when on, long lines wrap instead of scrolling horizontally.
- **Show indent guides**: on by default.
- **Show word-wrap arrows**: draws indicator arrows at wrap points when word wrap is on; off by default.
- **Show whitespace**: visualizes spaces, tabs, and line breaks; off by default.

### Saving and Concurrency

Saving is a **whole-file overwrite write-back**; a "Saved" notice appears on success.

::: warning No external change detection
AI tools or the terminal may be modifying the same file at the same time, and saving directly overwrites it with the editor's contents without warning that the file was changed externally. If you and the AI might touch the same file, let one side finish before the other starts, to avoid clobbering each other's work.
:::

### Limitations

- **File size limit is 2 MB**. Files over the limit don't open—the content has to be read into memory whole, and anything bigger would visibly stutter or even crash.
- **Binary files are not supported**. The beginning of the file is sniffed on open; anything identified as binary (images, executables, archives, etc.) is rejected with a message rather than loading gibberish as text.

## Where Workspace Files Are Stored on the Phone

The "Files" page browses workspace content, but workspace files and the AI configuration all live in the app's private directory, which ordinary file managers can't see by default. When you want to open or back them up with external tools, there are a few options:

**Mount with a file manager (recommended)**: the app implements a standard DocumentsProvider, so you can use the phone's built-in "Files" manager or a third-party one (MT Manager, Solid Explorer, etc.) to find and mount the app's private root. Once mounted, you can see:

- `projects/`: workspace projects. External workspaces added via "Add local directory" are not here—they live in the device directory you chose yourself.
- `xencode/`: corresponds to `~/.xencode` inside the container, containing `skills/` (skills), `docs/` (documents), `mcp.json` (MCP configuration), and more.

Mounting steps using MT Manager: open MT Manager → tap the menu in the top-left corner (three horizontal lines) → the three dots at the top right of the sidebar → "Add local storage" → when the system file picker opens, tap the three dots at the top left and choose "XenCode" → tap "Use this folder" at the bottom.

**Built-in terminal**: switch to the terminal page, `cd ~/.xencode` to look around, and use `vi` or `cat` to view and edit.

**Let the AI handle it**: the AI runs in the container; you can say "print `~/.xencode/mcp.json` so I can see it" or "write a new skill under `~/.xencode/skills/`".

**Rooted devices**: go straight to `/data/data/com.xencode/files/` (on some systems the equivalent path is `/data/user/0/com.xencode/files/`). There, `projects/` is the workspace root, `xencode/` corresponds to `~/.xencode`, and `rootfs/` is the container system. Note that debug builds use the package name `com.xencode.debug`; the two private directories are fully isolated.
