# Chat Interface Tour

This page explains what each button and area of the main interface does.

When you start a new empty conversation, the welcome message appears in the upper-center of the page. After you tap the input box at the bottom to bring up the keyboard, the welcome message moves up with the input box, trying to stay between the top title bar and the input box; it returns to its original position once the keyboard is dismissed.

## Top Title Bar

- **Menu button** (top left): opens the sidebar for managing the session list and browsing files.
- **Session title and model name**: the top line is the current session name; the line below is the model currently in use.
- **New session**: starts a new conversation.
- **Git entry**: goes to the Git version management page.
- **Terminal entry**: goes to the terminal page.

In remote SSH mode, an extra line appears below the title bar: on the left, the SSH connection status (a dot plus text); on the right, the current session's cumulative tokens (up arrow for input, down arrow for output). Local mode doesn't show this line.

## Sidebar

Tap the menu button in the top-left corner, or swipe right from the left edge of the screen. At the top are two tabs: "Sessions" and "Files".

### Sessions Tab

Sessions are sorted from newest to oldest by the time of the last reply and grouped by time (Today / Yesterday / Last 7 days / Last 30 days / earlier by month). Pinned sessions go in a separate "Pinned" group at the very front, with light blue card backgrounds.

A short tap switches sessions; **long-press to open the action menu**:

- **Pin / Unpin**: pinned sessions stay fixed at the front.
- **Rename**: changes the session title without affecting the sort order.
- **Export**: exports this session.
- **Batch selection**: enters multi-select mode, where you can check multiple sessions, use one-tap "Select all", and "Batch delete" (with a confirmation). Tap × in the top-left corner or press the back key to exit multi-select mode.
- **Delete**: shows a confirmation dialog; once confirmed, the session and all its messages are deleted.

For sessions that have spawned subagents, the end of the row shows the subagent count and an expand arrow; tap it to view the subagents below (**since 1.11.0**). See [Subagents](/guide/subagent).

Below the sidebar tab bar is **search chat history** (**since 1.12.0**): type a keyword to search message content across all sessions in the current workspace, and matches appear in place in the list area (session title plus matching snippet, with the keyword highlighted). Tapping a result switches to that session and locates the message; tapping × to the right of the search box or clearing the input returns to the session list. Search covers the current workspace only and matches only the body text of you and the AI—not tool execution output.

### Files Tab

Browse the files of the current workspace; tapping a file opens the code editing page (**since 1.11.0**). See [File browsing & code editing](/guide/files).

At the bottom of the sidebar are three entries: "Browser" (**since 1.12.0**), "Network Request", and "Settings". For the network request page, see [Network requests](/guide/network-request).

## Toolbar Below the Input Box

From left to right:

- **Mode button**: cycles through BUILD, PLAN, and AUTO. See [Three modes](/guide/modes).
- **Model button**: shows the current model; tapping it opens the picker dialog. There is a search box at the top for fuzzy filtering, models are grouped by vendor, and tapping a group header collapses or expands it (the collapsed state is remembered and persists after reopening the dialog or restarting the app; during a search, matched groups expand automatically). Each row is labeled with capability tags (`Image`, `Tools`, input/output lengths); tap to switch.
- **Workspace button**: shows the current workspace name; tapping it opens the workspace management panel (see below).
- **File button**: uploads a file as an attachment whose contents the AI can read.
- **Image button**: uploads images as attachments. Tapping it opens the system gallery directly, and multiple selection is supported. Requires the current model to support image input.
- **Send button**: a normal send when the AI is idle; while the AI is working, it shows a stop button when the input box is empty, or an orange send button when there is content (messages are queued).

Once what you type grows beyond the height of the input box, an **expand button** appears in its top-right corner: tap it to edit the draft on a full screen; tap "Collapse" in the top-right corner or press the system back key to exit full screen. Edits made in full screen sync back to the input box in real time—both are the same draft, so just press send after collapsing.

Uploaded attachments appear as preview cards above the input box and can be removed before sending. Uploading large files takes a moment; while it runs, a notice shows above the input box and turns into a preview card automatically when done. Tap an image thumbnail to [view it fullscreen](#fullscreen-image-viewer).

Unsent drafts are saved per session: type something in the current session, switch to another one, and come back—the draft is still there. Drafts are persisted locally and survive a forced close and reopen of the app (process restart).

When you scroll the message list, the input area fades to 40% opacity to reduce distraction and recovers once scrolling stops.

## Workspace Management

In the panel that pops up when you tap the workspace button, you can:

- **Switch workspace**: tap to switch. If there is a running AI session or terminal tab, a confirmation dialog appears first—switching will stop them.
- **New workspace**: create one by entering a name; it corresponds to a standalone folder on the phone.
- **Add local directory** (local mode): use an existing directory on the device as a workspace. The first tap shows a risk confirmation dialog (the AI will read and write your real files directly), and you can check "Don't ask again". Only folders from "Internal storage" or "SD card" can be selected; other file providers such as cloud drives are not supported.
- **Delete workspace**: internal workspaces are deleted along with their files; external workspaces added via "Add local directory" are only unlinked—the directory contents are **not deleted**.

The workspace is the project root the AI works in; the AI's code read/write and command execution are confined to the current workspace.

## Slash Commands

Typing `/` in the input box opens the command menu. **Built-in commands come first (alphabetically), followed by skills (also alphabetically)**.

Built-in commands:

- `/compress`: manually triggers context compression for the current session.
- `/init`: has the AI analyze the current workspace codebase and generate or improve the project rules file `AGENTS.md` (if the project root already has `CLAUDE.md` but no `AGENTS.md`, `CLAUDE.md` is improved instead). Arguments can specify a focus, e.g. `/init focus on testing and release process`.
- `/usage`: view today's and cumulative call counts, token usage, and estimated cost (global statistics, across sessions).

Skills:

- Every enabled skill can be triggered directly with `/skill-name`; the AI executes that skill's body as the instruction for the turn. In the menu, skills carry a "Skill" tag on the right.
- Skills can take arguments, e.g. `/pdf-report a4`: arguments replace the `$ARGUMENTS` placeholder in the skill body; if the body has no placeholder, the arguments are appended at the end.

Commands are queued while the AI is busy and run automatically once it is idle. The command text appears as a user message in the conversation and enters the context.

## Context Usage and Compression

The ring around the send button reflects the share of the model's available input budget in use, not cumulative consumption. Before sending, the added usage from new input, replies, and tool results is estimated, then calibrated with the actual figures once the model responds; the screen-reader description distinguishes estimated usage. After switching models, compressing, or rewinding, the old usage baseline no longer applies—subsequent requests are recalculated.

When the context approaches the threshold, earlier conversation is turned into a handoff summary while the most recent messages are kept. When the compression model's window is small, processing is done in chunks, which may increase waiting time and call costs; if the summary is empty, truncated, or unable to free enough space, the original history is not replaced. An explicit context overflow triggers at most one automatic compression-and-retry; if it still fails, shorten your input, use fewer tools, or check the model window settings.

## Message Queue

The input box stays available while the AI is working. After typing, tapping the orange send button doesn't interrupt the current task; the message joins the **send queue**:

- The queue panel appears above the input box, listing the order and a summary of each queued message; tap the header to collapse/expand, and it collapses automatically when a dialog or the keyboard opens.
- When the current round finishes, the next queued message is sent automatically; multiple messages run in order.
- **Drag to reorder**: hold the drag handle (☰) on the left of a row and drag up or down to change the execution order of the queued messages.
- **Edit**: tap the pencil icon on a row to modify that message; it updates after confirmation.
- **Insert now**: tapping the lightning icon on a row sends that message to the working AI immediately—instead of waiting for the current round to end, it is injected directly into the tool result the AI is working on, so the AI adjusts to the new content within the same round. If the AI isn't calling tools this round (plain text output), it is sent as the next message when the round ends.
- Tapping "Stop" skips the current round and immediately executes the next item in the queue.
- The × on the right of each message removes it from the queue.

::: warning The queue lives in memory only
When you switch workspaces or the process is killed by the system, the queue is cleared; don't keep important content only in the queue.
:::

## Replies and Tool Cards

As the AI generates a reply, it appears character by character with a **typewriter effect**, and the speed adapts to the model's output rate: when the model outputs quickly, the display speeds up to match, lagging only a short stretch behind and never falling further behind. The thinking preview line and tool execution output keep updating in real time, unaffected by the typewriter; when generation ends, the remaining short stretch completes quickly (a fraction of a second) instead of the whole block suddenly appearing.

Leave midway and come back (switch to the terminal, the settings page, or another session that is also outputting): everything already output is shown directly up to the current progress rather than being retyped from scratch; newly produced content continues to appear character by character.

The message area uses a **flat document flow** layout: what you send is a light bubble on the right, annotated with the send time below; the AI's replies are laid directly onto the page without an extra bordered card.

Every AI reply is wrapped in a **task collapse** (**since 1.13.0**) that gathers the whole round's process:

- The collapse header shows the round's status: "Running" while it is working, changing to "Completed in Xs" once done (X is the round's duration); tap it to expand or collapse.
- While running, the body, thinking, and tool calls appear in the order they occurred—explanatory text before a tool call is never moved after the tool; only after the task ends is the final answer kept separately below the process.
- **Auto-collapses when the task completes**, leaving only the final answer; expand it to review the round's process (thinking, tool calls, intermediate transitions).
- After you manually expand or collapse it, your choice is preserved (even across session switches or scrolling off screen and back); it only auto-collapses if you never touched it before the task completed.
- If the AI answers in a single step with no thinking and no tool calls, no collapse header is shown, same as before.

**Every message you send** has the send time and "Copy / Rewind / More" buttons pinned below, so you can copy or rewind any of them at any time. **The AI reply's buttons attach only to the latest reply of the whole session** (in one round the AI may take multiple steps with tool calls in between; the buttons attach only to the last step, and they appear only when the round completes normally—not when paused manually or on error). Each round's usage, cache hit rate, and duration are totaled per round and shown below the round's last reply; see [Usage and timing under replies](#usage-and-timing-under-replies).

The AI's thinking process occupies its own line, **collapsed by default**; when collapsed it is a single line: a thinking icon at the start plus a one-line preview—while thinking is in progress it shows the line currently being written (scrolling with the content), and once thinking ends it is fixed to the first line of the thinking, truncated with `…` if it doesn't fit; tap the line to expand the full text at any time, and tap again to collapse. When the body starts outputting or a tool call is being prepared, the thinking state becomes "Thinking complete" and the duration stops increasing, excluding subsequent body generation time. The expanded thinking content is scrolled inside a fixed-height window (it doesn't stretch the whole page), with a vertical line on the left marking it as thinking.

While waiting for the model's response, the bottom of the message area shows a status line: a white highlight sweeps back and forth across the text, followed by three bouncing dots (the dots are not part of the highlight). When the model is preparing a tool call (the tool name arrives first while parameters are still being transferred, which can take several seconds), the line states the action about to run instead of showing "Thinking" the whole time—e.g. `Editing file`, `Running command`, `Searching the web`.

Tool calls are collapsed into one line as "consecutive batches":

- **`N tool calls`**: consecutive tool calls within a round are merged into this line, **collapsed by default**—it doesn't auto-expand even while running; tap it yourself if you want the details. As long as any tool in the batch is still running, **the line itself** carries a highlight sweeping back and forth. Tap the line to expand or collapse it manually at any time; **after a manual choice it always displays your way**—automatic rules never override it, and scrolling off screen or switching to the terminal or settings and back never reverts it to the default state.
- Once expanded, each call takes a row and the whole group is **indented one level**, making it obvious at a glance that these rows belong to the group above rather than sitting at the same level as the group header.
- For the tool currently running, the end of the row shows **what it is doing** (visible even when collapsed) instead of a generic "Running": `Running command`, `Editing file`, `Reading file`, `Searching the web`, `Generating image`, `Organizing todos`, `Starting subagent`, `Reading/writing memory`; tools whose specific action can't be recognized (MCP tools, etc.) show `Calling tool`.
- Tap a single row to view its "instruction" and "result"; when the content is long, it scrolls inside a fixed-height window, with a fade at the bottom hinting there is more below. A single row's expanded/collapsed state is likewise remembered and won't auto-close when you scroll away and back. While a tool is still outputting, scrolling inside the window temporarily pins the content being viewed, preventing new output from replacing the text and causing jumps; real-time updates resume once you scroll back to the bottom and stop. While you browse, the tool keeps running, and the outer chat doesn't auto-scroll to steal focus. Inside the tool and thinking windows, dragging past the top or bottom continues to drag the outer chat list; after a quick flick and release, the window's momentum scrolling stops at the boundary and doesn't drive the outer list.
- File-modifying tools (write file, edit file) are **collapsed by default like other tools**; tapping to expand shows a **diff card**: the file path in the top-left corner, a "Copy" button in the top-right to copy this diff, the body a numbered `+ / −` colored diff, and a footer summarizing the change (e.g. `+3 −0 · 1 file`).

If a tool card's footer shows a "Background task / subagent finished" notice (green dot for success, red dot for failure or manual termination), the background task or subagent has completed within that round and been captured by the current session; the AI picks it up and continues automatically.

## Fullscreen Image Viewer

Any image in the chat can be tapped to view full size; it works the same in four places:

- image attachments waiting to be sent above the input box;
- image attachments inside sent message bubbles;
- images shown in the AI reply body (charts or screenshots the AI generated, or images it read from the workspace);
- images the AI sends you with the send-file tool (or image generation).

Once fullscreen: pinch with two fingers to zoom, drag with one finger to inspect details after zooming in, and double-tap to toggle between original size and zoomed. Tap the image, press the back key, or tap × in the top-right corner to close (tapping the image doesn't close it while zoomed, to avoid accidental closing while inspecting details). On tablets, whether the chat area takes half the screen or the full screen, the image fills the entire screen.

An image file that has been moved or deleted cannot be opened; a "Failed to load image" message appears. GIFs show only the first frame.

## Files Sent by the AI

Files the AI sends with `sendFile` (and images it generates) appear in the chat area **one file per row**: a thumbnail or type icon on the left, the file name and "size · path" in the middle, and a `›` hint on the right showing it is tappable.

- Tap an image row → view it fullscreen in the app; tap other types → open with the corresponding system app (installers first confirm "Allow installing unknown apps").
- If the file has been moved or deleted, tapping it shows "File does not exist or has been moved"; if the location doesn't support sharing to other apps, a corresponding note appears. Files in external workspaces added via "Add local directory" and directories mounted from custom images can be opened the same way.
- Each row sits at **the position of the tool call that sent the file**, so when scrolling the history you can see "at which step the AI sent this file"; tools that carry files **do not participate in the "N tool calls" collapse**—whether the group is collapsed or not doesn't affect their display.
- One call sends at most 10 files, and the default per-file size limit is 100MB (adjustable in "Preferences → Tools"); if even one file is missing or over the limit, the whole batch is not sent—the AI receives the failure reason, corrects it, and retries.

## Usage and Timing Under Replies

Below the AI reply body there is a line of statistics, **computed as a "total for this round" and attached only below the last reply of each round** (the buttons are arranged "Copy → Stats → More options", with the `···` actions entry at the end of the row):

- **Token usage**: `↑` is this round's input tokens, `↓` is output tokens. A model may be called several times in one round (each tool call makes another request); this is the sum of all requests in the round, not the figure for a single step.
- **Cache hit rate** (**since 1.11.0**): the percentage next to the database icon—the share of this round's input that hit the server-side cache. A higher hit rate means cheaper and faster. Not shown when nothing hit the cache or the vendor doesn't return cache data. This field has been recorded since 1.11.0; older messages won't show it.
- **Total round duration** (**since 1.11.0**): the time next to the clock icon, attached only below the last reply of a round. It counts from when you press send until the AI finishes, including tool execution and the time waiting for you to approve authorizations. Under 1 minute it shows as `12s`; longer shows as `2:05` (m:s) or `1:02:05` (h:m:s). It doesn't appear while the task is running, only after it completes.

For cumulative usage and cache hit rate across a whole session or vendor, see [Token usage statistics](/guide/token-stats).
