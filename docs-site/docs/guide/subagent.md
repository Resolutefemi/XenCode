# Subagents

::: tip Version note
Subagents are available since 1.11.0.
:::

A subagent is an independent working session spawned by the main session. For time-consuming research, parallel multi-module review, architecture comparisons and other complex tasks, you can have the AI dispatch subagents to work in parallel **without blocking the main conversation**.

A subagent has its own context and tool capabilities. Once dispatched it works independently in the background — you can stay in the main session and keep discussing, or tap into the sub-session anytime to check live progress, add questions, or pause it manually.

Subagents come in two kinds:
- **Default general subagent**: automatically inherits the parent session's model configuration and all available tools;
- **Custom subagent**: freely specify the model, tool allowlist/blacklist and a dedicated prompt.

## How to use

Just ask the AI in the main session; for example:

- "Dispatch a subagent to review `app/build.gradle.kts` and list potential issues"
- "Dispatch two subagents at once: one to research database migration plans, one to research UI component library choices"
- "Dispatch a read-only research subagent to map the current login and authentication flow"

Before dispatching, the AI pops a dialog to confirm authorization; once agreed, the subagent starts in the background and the main session immediately goes idle, ready for your next instructions.

## Placement & management in the sidebar

The sidebar session list clearly shows the parent-child relationship between main sessions and subagents:

- **Expand & collapse**: sessions that have spawned subagents show the subagent count and a chevron at the end of the row. Tap the chevron to expand and see all of its subagents;
- **Status indicator**: running subagents show a green running light;
- **Independent interaction**: tap a subagent to switch into its session page — identical to a regular session, with its own chat, pause and regenerate;
- **Session operations**: long-press a subagent entry to rename, export or delete; deleting a main session cleans up all subagents under it along with it.

## Context & information passing

::: tip Context isolation with two-way sync
A subagent's context is fully isolated from the main session — it does not auto-load the main session's history, staying lightweight and focused:

- **Task dispatch**: when dispatching, the AI automatically distills the task's explicit goals, key file paths, verified background and constraints, and packs them as the initial instruction for the subagent. If you instruct the dispatch manually in the main session, it is just as well to spell out the key background and expected output;
- **Result roll-up**: when the subagent finishes, the main session is notified automatically and reads its final output to summarize for you; if the main session is busy calling another tool, a hint under the card notes the subagent is ready and follows up automatically after the current operation;
- **Full traceability**: the main session usually reads only the subagent's final conclusion; to inspect its complete tool-call logs, reasoning trail or intermediate output, just tap into the sub-session in the sidebar for every detail.
:::

## Two-way messaging with the main session

Subagents and the main session support two-way async messaging without blocking each other:

- **Main session → subagent**: the main session can append messages to a subagent anytime (extra instructions, corrections, or follow-ups on existing results) and **send repeatedly**. A running subagent receives the message in its next turn; a finished or paused one is woken up and continues working;
- **Subagent → main session**: while running, a subagent can proactively report key progress, ask for help, or raise items needing coordination or decisions. The main session gets a system notification and can reply directly.

These messages appear as slim hint bars, not regular chat bubbles; when the main session is busy they are followed up automatically after the current operation, and when idle they immediately trigger a new turn.

## Auto-dispatch and manual selection

The main-session AI knows every available subagent and what each is good at:

- **Auto-match**: when your request matches a subagent's purpose description (`description`), the AI proactively picks that dedicated subagent;
- **Manual selection**: you can also name it explicitly, e.g. "Use the Explore subagent to find every network request entry point".

## Built-in subagent: Explore

On first launch the built-in **Explore** read-only subagent is automatically released into the global directory (at `~/.xencode/agents/explore.md`), ready out of the box.

Its core use is read-only code base exploration and logic untangling:
- **Read-only safe**: only code and file retrieval tools are exposed (`readFile`, `list`, `search`, `websearch`, `webfetch`, `loadSkill`) — **no file-writing or terminal-executing tools at all**; it cannot touch the project and needs no frequent authorization dialogs;
- **Custom model**: inherits the current session's model by default. To give it a more economical model, just fill in `provider` and `model` in `explore.md`;
- **Persisted**: the file is only released when it does not yet exist; your personal modifications are fully preserved across app upgrades.

## Custom subagents

There are two ways to create a custom subagent, fully equivalent (saving in-app writes the very same `.md` file):

- **Create in-app** (recommended): "+" at the top-right of "Settings → Subagents", fill in the form — the model can be picked straight from your configured vendors;
- **Hand-write the definition file**: drop a Markdown file into the directory yourself — good for reusing definitions from other projects or managing them with git.

Two directories to choose from:

- **Global**: `~/.xencode/agents/<name>.md`, shared across projects and never lost on upgrade. It follows the execution environment: in local mode it is `/root/.xencode/agents/` inside the container (mapped to the app's private directory on the phone); in remote mode it means `~/.xencode/agents/` on the **server**, with reads and writes over SSH;
- **Project-level**: `<project workspace>/.xencode/agents/<name>.md`, effective only in the current workspace; commit it to version control along with the code base. In remote mode this means the workspace **on the server**, with reads and writes over SSH.

For same-named definitions, project-level wins. A file consists of YAML frontmatter at the top and the prompt body:

```markdown
---
name: researcher
description: Read-only code and technical research: search code broadly, consult web resources and summarize conclusions, without modifying files
provider: DeepSeek
model: deepseek-reasoner
reasoningEffort: high
mode: plan
tools: [readFile, list, search, websearch, webfetch]
disallowedTools: [Bash, terminal]
inject: [base, projectRules]
---
You are a senior code research expert. First use search to locate entry points, then readFile to verify in depth.
When outputting conclusions, always cite exact `file path:line number` references; mark unverified inferences honestly.
```

### Field reference

| Field | Required | Description |
| --- | --- | --- |
| `name` | No | The subagent's identifier; defaults to the file name when omitted |
| `description` | Recommended | Briefly states use cases and strengths; the main-session AI uses it to decide when to pick this subagent |
| `provider` | No | AI vendor name; inherits the parent session's configuration when omitted |
| `model` | No | Model name; inherits the parent session's configuration when omitted |
| `reasoningEffort` | No | Thinking effort: `none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max` |
| `mode` | No | Default run mode: `build` / `plan` / `auto`; inherits the parent session's current mode when omitted |
| `tools` | No | Tool allowlist. **Omitted means all available tools are exposed** |
| `disallowedTools` | No | Tool blacklist, higher priority than the allowlist; supports the `mcp__*` prefix wildcard |
| `inject` | No | System rule fragments to inject; see the table below |

### inject rule fragments

| Value | Meaning |
| --- | --- |
| `base` | A lightweight baseline for subagents: tool-usage rules and path conventions, light on tokens |
| `mainRules` | The full general rules: behavioral guidelines and rule baseline fully identical to the main session |
| `skills` | Skill support: injects the available-skills list so the subagent can load and run project skills |
| `memory` | Memory support: injects global and project long-term memory summaries; see [Memory & project rules](/guide/memory) |
| `projectRules` | Project rules: injects the workspace's `AGENTS.md` or `CLAUDE.md` constraints; see [Memory & project rules](/guide/memory) |

`base` and `mainRules` are the light and full versions of the same rule set — **normally pick just one**. Enabling both is not an error, but injects the overlapping rule text twice, wasting context.

Omitting `inject` defaults to `[base, skills, memory, projectRules]`. Setting `inject: [none]` uses only the prompt you write in the Markdown body.

## Managing in settings

Open "Settings → Subagents" to browse all recognized subagents grouped by "Current project / Global".

- **New**: tap "+" at the top-right. The form covers name and purpose description, scope (current project / global), **model and thinking effort** picked from configured vendors, tool allowlist and blacklist checkboxes, prompt-fragment injection toggles, and finally the agent prompt body;
- **Edit**: tap into a subagent's details page, then the pencil icon at the top-right. Renaming renames its definition file too;
- **Enable / disable**: the toggle at the top of the details page. Disabled, it disappears from the main-session AI's dispatch list and can no longer be summoned by name — but the definition file is kept and already-running sub-sessions are unaffected;
- **Delete**: swipe an entry left in the list; deletes the matching `.md` definition file, unrecoverably.

Enabled/disabled state is kept in `agents.json`, following the same convention as the skills disable list: the global list lives at `~/.xencode/agents.json` in the current execution environment — in local mode stored in the app's private directory on the phone, in remote mode stored on the server; the two are independent and never migrated or synced automatically. The project-level list is at `<workspace>/.xencode/agents.json` in local mode; in remote mode it still lives in the app's private directory on the phone, isolated per server and per workspace. The two levels are unioned — disabled in either scope means disabled.

Toggling in-app refreshes the list. In local mode, hand-edited definitions or lists refresh within seconds; in remote mode changes on the server are not watched — after hand edits, leave and re-enter the settings page to refresh.

## Subagent market

The **download icon** at the top-right of the subagents page opens the online catalog: ready-made definitions for architecture planning, writing tests, dependency health checks, deep troubleshooting and more — pick one and install it into the current project with a tap.

List and details interactions match the skill market: auto-paging near the bottom, full refresh at the top-right; the details sheet shows name, author, version, plus one extra line — the subagent's **behavior summary** (run mode · thinking effort · injected fragments). Tap **Install** to write it as the current project's `agents/<name>.md` definition file; back on the list, the new subagent is there.

Install rules:

- **Always installed into the current project scope** (in local mode the in-container workspace's `.xencode/agents/`; in remote mode the matching directory on the server), never touching global subagents; on a name clash an index is appended automatically (e.g. `Plan Architect 2`) — nothing is overwritten.
- The entry's own `mode` (build/plan/ask), thinking effort, tool allowlist/blacklist and `inject` fragments are written into the definition file verbatim; unspecified fields fall back to "inherit from parent session", same semantics as hand-created ones.
- An installed subagent is fully equivalent to a hand-made one — keep editing it, toggle it, or swipe left to delete.

## Runtime mechanics & limits

- **Concurrency cap**: at most **5 subagents running at once**; beyond that, wait for tasks to finish or pause some manually;
- **No nesting**: a subagent cannot spawn sub-subagents;
- **Two-way messaging**: the main session can append messages anytime to running or finished subagents (sendable repeatedly), and a running subagent can proactively report or ask for help; messages arrive asynchronously without blocking either side.
- **Default mode**: a subagent inherits the main session's mode at dispatch time. Dispatched under PLAN it is likewise read-only; under AUTO it stops popping dialogs per call, just like the main session. A custom subagent can override this with the `mode` field — e.g. pin a read-only subagent to `mode: plan` so it stays read-only no matter when it is dispatched;
- **Authorization control**: subagents follow the same tool-authorization safety mechanism as the main session. If one attempts to write files or run system commands, the confirmation dialog pops just the same (clearly labeled with which subagent it came from);
- **Manual stops are reported too**: stopping from inside the sub-session, or outright deleting a running subagent, sends the main session a "manually terminated" notice (not a failure), so the AI does not re-dispatch on its own; the concurrency slot is released immediately;
- **Lifecycle**: deleting a parent session cascades a cleanup of all its subagent sessions;
- **When definition changes take effect**: after adding, renaming or editing a subagent definition, already-open sessions keep the list from their start — **open a new session** for the AI to see the change.
