# Three Modes: Build / Plan / Auto

Tapping the mode button to the left of the input box cycles through them: BUILD → PLAN → AUTO → BUILD. What differs across the three modes is the AI's **permission scope**.

## BUILD (build mode)

The default mode, with full capabilities. The AI can write files, edit code and run build commands, but every operation needing authorization pops a dialog to ask you.

Use this mode for everyday development. For high-risk commands blocked by the built-in guard (such as `rm` on the root directory), the AI can retry with `elevate: true`, turning it into a one-time authorization from you (see [Tool authorization](/guide/permissions)).

## PLAN (plan mode)

In this mode the AI's write permissions are disabled by an interception at a lower layer (blocked at the tool layer, not merely by prompt instruction). The AI can only read code, search files and devise plans — it cannot modify any file or run commands that write.

Good fits:

- You are not yet sure how to change things and want the AI to explore the code base and propose a plan first.
- The change spans multiple files or an architectural decision, and you want to see the plan before committing.
- You only want to ask questions and understand the existing implementation, without it touching anything.

Once the plan is settled, switch back to BUILD to execute.

## AUTO (auto mode)

All permissions are granted. Every tool call (writing files, running commands, terminal operations) is auto-approved — no authorization dialogs at all.

::: danger This mode grants huge permissions
Only catastrophic deletions (such as `rm` on the root or system directories) and destructive-looking commands that cannot be statically judged are still blocked — everything else goes through (you can remove this last-resort layer by enabling "Disable safety interception" under "Settings → Tool authorization"; the AI can also retry with `elevate: true`, which then pops a one-time authorization dialog). Use it when you clearly understand the risks and want the AI to run batch tasks fully unattended. Not recommended for unfamiliar projects or when you are unsure what the AI will execute.
:::

## The AI can also request a switch

Besides tapping the button yourself, the AI can also request to enter or leave PLAN mode, and you approve with one tap in the UI. For example, after finishing a plan in PLAN mode it will ask to exit PLAN and start implementing — after exit it automatically returns to the mode it was in before entering PLAN.

There is a one-way restriction: **AUTO mode can only be entered by you manually** — the AI cannot switch itself into AUTO. In the other direction, while in AUTO the AI may switch itself to PLAN — the only path by which the AI can leave AUTO.

## Exiting PLAN returns to the previous mode

PLAN is a temporary state: the mode at entry is remembered and automatically restored on exit. Entered from AUTO, after finishing the plan it returns to AUTO; entered from BUILD, it returns to BUILD. It no longer always falls back to BUILD.

Note: exiting PLAN still needs your approval on the plan review panel (tap "Approve & implement"); only after approval is the mode actually restored.

## Switching mid-run

The mode button also works while the AI is executing a task. If you switch modes mid-task, it takes effect **immediately in the current turn**: the AI receives a mode-switch notification and the task in hand is handled under the new mode from that moment on (switch to AUTO midway and subsequent operations stop popping dialogs one by one; switch to PLAN and subsequent writes are blocked) — no need to wait for the turn to finish.
