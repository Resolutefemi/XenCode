# Preferences

"Settings → General → Preferences" gathers the global preference toggles that are neither vendor-specific nor scoped to a particular purpose. Changes take effect immediately—nothing to save.

## Models

**Remove invalid models automatically after fetching** (on by default)

When on: on the [vendor edit page](/guide/providers), tap "Fetch Models"—as long as the server returns the model list successfully, models in the local list that the server no longer returns are removed automatically, with a notice telling you how many were removed.

- If the request fails or the server returns an empty list, the local list is untouched, preventing a network hiccup from wiping out the models you added.
- Manually added models that happen to be missing from the server's response are removed too. Turn the toggle off if that bothers you.
- When off, fetching only lists new models for you to check; the existing list is never modified.

**Auto-compression threshold** (default 90%; accepts an integer from 1 to 100)

When the next request is projected to use this percentage of the model's available input budget, context compression is triggered automatically (summarizing earlier conversation into a handoff summary). The budget accounts for the model's input limit and reserves room for the reply, so it isn't necessarily equal to the model's advertised total window. A larger value compresses later; a smaller value compresses earlier but summarizes more often. Even at 100%, the hard input limit cannot be bypassed.

## Sessions

**Session on startup** (default: "New session")

- **New session**: every app launch starts from a new session. If the current workspace still has an empty session with no messages sent, it is reused rather than creating another one.
- **Open most recent session**: goes straight into the most recently updated session in the current workspace and continues where you left off. If the workspace has no sessions yet, a new one is created. Pinned sessions get no priority—even with a pinned session, the most recently updated one still opens.

Workspace switches follow the same preference.

**Delete chat history for external workspaces** (off by default)

Controls how chat history is handled when you remove an external local workspace added via "Add local directory". When on, removing an external local workspace also deletes its sessions and messages; when off, it is only unlinked and the chat history is kept, so re-adding the same directory later still shows the history.

Deleting built-in workspaces and remote workspaces is unaffected by this toggle: when you delete those, the directory and the chat history are both deleted.

## Input

**Enter key sends the message** (off by default)

When on, the soft keyboard's Enter key becomes send—one press and it goes; when off, Enter inserts a newline and the send button does the sending. Keeping it off is recommended for multi-line input.

## Network

Both timeouts are in seconds—just enter a number in the input box; **0 means unlimited**. After saving, they apply immediately to subsequent requests, no restart needed.

**First-token timeout** (default 300 seconds)

The maximum wait for the first meaningful content (body, thinking, or a tool call) after each request attempt starts, including the time waiting for the server's response headers. When exceeded, the request is canceled and retried per the retry count setting; each retry restarts the timer. Increase it for slow-starting, long-thinking models. Once the first meaningful content arrives, this timeout no longer limits the rest of the reply.

**Inter-chunk timeout** (unlimited by default)

During a streaming reply, the longest allowed wait between two adjacent response chunks. With a concrete value set, stuck connections are cut off and retried earlier; keep it unlimited while your network is stable.

**Retry count** (default 6)

The app periodically probes HTTP/2 connections and, when one stops responding, drops it and handles it per the retry settings; HTTP/1.1 connections don't support this probe. When the UI shows "Retrying", it may be waiting out the backoff or may have already sent the next request and be waiting for content.

The number of exponential-backoff retries after a network request fails (excluding the first attempt; waits start at 0.5 seconds, double each time, and are capped at 10 seconds each). Even if a request has already produced partial output before disconnecting again, network retries keep accumulating rather than restarting the count. Enter 0 to fail immediately without retrying. This also interacts with multi-key switching—failures classified as key failures first trigger a switch to another key and a resend.

## Tools

**sendFile per-file limit** (default 100MB; accepts an integer greater than 0)

The maximum size of a single file when the AI sends files to the chat area with `sendFile`. Files over the limit are not sent; the AI receives the failure reason. Raising it allows bigger files, but each file is first copied into the app's private directory before being shown, so very large files may occupy considerable storage.