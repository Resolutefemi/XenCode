# Custom Prompts

XenCode's system prompt can be modified. The default prompts are bundled with the app and update automatically on upgrade; just put the pieces you want to change or add into the custom directory to override or extend them—no need to modify the app itself.

::: tip Since v1.12.0
The fragment naming rules in this document (`<two-digit number>-<name>.md`), adding fragments by number, the `.no-builtin` full-disable switch, and the <span v-pre>`{{AICODE_*}}`</span> variables are supported since **v1.12.0**. Earlier versions could only override by placing files with **exactly the same name** as the default fragment; adding by number, `.no-builtin`, and variables were not supported.
:::

## Managing in the App

You can also leave the filesystem alone and manage everything under **Settings → Prompts**:

- The page lists every fragment that is **actually in effect**, by number, with a badge on the right indicating which layer the content comes from:
  - **Project**: the current workspace's `.xencode/prompts.custom/`
  - **Global**: `~/.xencode/prompts.custom/`
  - **Local**: `~/.xencode/prompts/` (the copy released from the built-ins at startup)
  - **Built-in**: the app's bundled `assets/prompts/`
- For the same number, only the highest-priority copy takes effect—priority is **Project > Global > Local > Built-in**; the rest are shadowed.
- Tap a row to edit its content. Saves go by default to the layer currently in effect for that number; "Local/Built-in" are not writable, so after editing they land in the Global layer by default, and the edit page lets you switch the scope to the Project layer (when a workspace is open).
- Every fragment row can be swiped left; Project/Global fragments can have their override deleted, after which the next layer takes over automatically; Local/Built-in fragments have a grayed-out delete button and cannot be deleted.
- The "+" in the top-right corner adds a fragment under an unused number. When editing, the summary and the body are filled in separately; the summary shows in the list and preview page and is never injected into the AI. When writing files by hand, a leading `<!-- summary -->` comment is likewise recognized as the summary, and the body preview hides this comment.
- On first entry a usage notice pops up automatically; the Info button in the top-right corner reopens it at any time.

Changes made in the app are fully equivalent to placing files by hand; the two approaches can be mixed.

## Directory Structure

Prompts live under the AI configuration directory `~/.xencode/` (inside the local-mode container it is `/root/.xencode/`; in remote mode it is `.xencode/` under the server user's home, with reads and writes going to the remote side):

```
~/.xencode/
├── prompts/          Default prompts (fully released at app startup; overwritten automatically on upgrade)
│   ├── 00-identity.md
│   ├── 10-communication.md
│   ├── ...           Top level holds the "static baseline" fragments, sorted by two-digit numeric prefix (the last built-in one is 99-runtime-context.md)
│   └── agent/        On-demand fragments (not part of the static baseline; read only when used)
│       ├── plan-mode.md           PLAN mode reminder
│       ├── auto-mode.md           AUTO mode reminder
│       ├── subagent-base.md       Subagent base operating rules
│       ├── subagent-context.md    Subagent runtime context (identity/workspace/time)
│       ├── compact-summary.md     Long-conversation context compression
│       ├── title-generator.md     Session title generation
│       └── init.md                Instruction body of the /init command
├── prompts.custom/   Your custom fragments (overrides or additions; untouched by upgrades)
│   ├── 50-my-safety-rules.md
│   ├── 25-extra-requirements.md
│   └── agent/
│       └── plan-mode.md           Override an on-demand fragment
├── skills/
└── docs/
```

Fragments come in two kinds with different naming rules:

- **Static baseline** (top level of `prompts/`): `<two-digit number>-<name>.md`; the number is its identity and determines its place in the system prompt.
- **On-demand fragments** (`prompts/agent/`): no number; read only when used (e.g. switching modes, compressing context, dispatching subagents, generating titles).

## Naming Rules

Custom fragments go in `prompts.custom/`:

- **Top-level files** must be `<two-digit number>-<name>.md` (e.g. `05-opening.md`, `50-my-safety-rules.md`). The number is the identity:
  - A number matching a built-in static baseline fragment (built-in numbers are `00`, `10`, `15`, `20`, `30`, `40`, `50`, `60`, `70`, `99`) → **overrides** that fragment; the trailing name can be anything;
  - A number not in that set → treated as an **added fragment**, inserted into the corresponding spot in the baseline by number (e.g. `05-opening.md` sits between `00` and `10`).
- Files that don't match this format (e.g. `notes.md`, `2024-notes.md`) are ignored.
- When multiple files share a number, only the one that sorts first by filename is used and the rest are ignored—keep numbers unique.
- Fragments in the **`agent/` subdirectory** have no numeric identity and are overridden by **exact same name** (e.g. `prompts.custom/agent/plan-mode.md`).

## Loading Priority

Top-level numbered fragments (`<two-digit number>-<name>.md`) are looked up across four layers; for the same number only the highest-priority copy takes effect, and the search stops at the first hit:

1. **Project**: `<workspace>/.xencode/prompts.custom/` (in remote mode, the matching project directory inside the app's private storage)
2. **Global**: `~/.xencode/prompts.custom/`
3. **Local**: `~/.xencode/prompts/<same-name file>` — the copy released at startup
4. **Built-in**: the app's bundled `assets/prompts/`

On-demand fragments under `agent/` (unnumbered) have no Project layer and are looked up in three layers: "Global custom (`prompts.custom/agent/xxx.md`) → Local → Built-in".

So you only need to place the few fragments you want to modify or add; everything else automatically uses the default version.

## How to Modify

1. Put fragments under `~/.xencode/prompts.custom/` (create it manually if it doesn't exist): to override a built-in fragment, name it with the same number; to add a fragment, use a number not yet taken; subdirectory fragments (e.g. `agent/plan-mode.md`) go to the corresponding place under `prompts.custom/agent/`.
2. Edit the content.
3. **Takes effect immediately on save** (no app restart needed).

::: tip Prompt fragments take effect immediately
Custom prompt fragments are re-read on every request; changes apply right away, no app restart needed. Note: the skills list, memory, and the subagent list still refresh only when a **new session** starts.
:::

There are three ways to edit: modify files directly with `vi` / `nano` in the terminal; have the AI write them (its file tools can read and write this directory); or [access the app's private directory with a file manager](/guide/files#where-workspace-files-are-stored-on-the-phone).

## Prompt Marketplace

The "**+ → Prompt Marketplace**" in the top-right corner of the prompts page is an online catalog of ready-made fragments: common templates like commit message generation, code review, and release note drafts—pick one and it lands on disk as a local fragment with a single tap.

The page works the same as the skill marketplace: the list auto-paginates as you scroll near the bottom, and the top-right corner refreshes the whole page. Tapping any row opens a **detail bottom sheet** (name, author, release time and version, description and template body); tap **Install** to write the fragment—on success a notice appears, the sheet closes, and the new fragment is there when you return to the list.

Install rules:

- Fragments are always written as `NN-Title.md` under the "next free number" (numbers 01–99; the system prompt is sorted by number) and **never overwrite your existing fragments**; when 01–99 are all taken, you're prompted to delete or repurpose an existing fragment first.
- With a workspace open, it installs into the current project (`.xencode/prompts.custom/`, trackable with the project's git); otherwise it installs into the Global layer (`~/.xencode/prompts.custom/`).
- Installed fragments are fully equivalent to hand-created ones: keep editing them, swipe left to delete, or drag to reorder.

## Fully Disabling Built-in Prompts

Create a file named `.no-builtin` under `prompts.custom/` (contents don't matter—its mere existence activates it) to make the **main agent's** system prompt consist only of the numbered fragments you write at the top level of `prompts.custom/`:

- No built-in fragments are injected anymore, and the skills list, subagent list, memory, project rules, workspace, and current time are no longer appended automatically;
- Whatever dynamic content you need, pull it back on demand with the variables below in your own fragments;
- It affects the main agent only; subagents still get built-in fragments injected per their own definitions;
- If `prompts.custom/` contains no recognizable numbered fragments, the system prompt will be empty—make sure there is at least one `NN-xxx.md`.

::: danger This is a "full takeover"
Once enabled, the AI's rules of behavior are decided entirely by the fragments you write. Be sure to provide identity, tool descriptions, safety boundaries, and similar content yourself, or the AI will lack necessary context.
:::

## Available Variables

Write these placeholders in fragment bodies; at runtime they are replaced with real content—empty lists become empty strings:

::: v-pre
| Variable | Replaced with |
| --- | --- |
| `{{AICODE_SKILLS}}` | List of available skills (one per line) |
| `{{AICODE_SUBAGENTS}}` | List of available subagents (one per line) |
| `{{AICODE_MEMORY_GLOBAL}}` | Global memory list (one per line) |
| `{{AICODE_MEMORY_PROJECT}}` | Project memory list (one per line) |
| `{{AICODE_PROJECT_RULES}}` | Body of the project rules (`AGENTS.md` / `CLAUDE.md`) |
| `{{AICODE_WORKSPACE}}` | Project root directory (`~/workspace`; a hint text when no workspace is selected) |
| `{{AICODE_ENVIRONMENT}}` | Current runtime environment (local container (PRoot) / remote SSH server) |
| `{{AICODE_DATE}}` | Current date (`yyyy-MM-dd`) |
| `{{AICODE_SUBAGENT_NAME}}` | Current subagent name (only set when rendering subagent prompts) |
:::

::: tip Variables expand in place in any fragment
The skills, subagents, memory, project rules, workspace, and current time in the system prompt are carried by these variables in the built-in fragment `99-runtime-context.md` by default; you can also write variables in your own fragments, and they will likewise be replaced with real content at that spot. To change where this content sits or in what order, edit or override `99-runtime-context.md`.
Variables are replaced with **pure data** (list lines, file bodies, paths, dates); headings and lead-ins like "Available skills:" live in the fragment files themselves and are edited along with the fragment.
:::

If there is no override of `99-runtime-context.md` in `prompts.custom/`, the runtime context is injected by the built-in version as usual; if you override it but write no variables, the corresponding content won't appear.

Other placeholders (such as <span v-pre>`{{INSTRUCTION}}`</span> used by the compression prompt) are unaffected.

## Behavior on Upgrade

- The `prompts/` directory is fully overwritten with the built-in version at every startup, so default prompts update automatically when the app upgrades.
- The app never writes to or deletes the `prompts.custom/` directory; your customizations survive upgrades intact.

::: danger Do not modify the prompts/ directory directly
Everything in `prompts/` is fully reset at every startup; manual edits will be overwritten when the app upgrades or restarts. For persistent customization, always keep it in `prompts.custom/`.
:::

## Fragment Reference

| File | Contents |
| --- | --- |
| `00-identity.md` | AI identity and role definition |
| `10-communication.md` | Communication and reply style |
| `15-project-rules.md` | Project rules loading convention (AGENTS.md / CLAUDE.md); see [Memory and project rules](/guide/memory) |
| `20-coding-discipline.md` | Coding discipline |
| `30-comments.md` | Code comment guidelines |
| `40-approach.md` | Working style and process |
| `50-safety.md` | Safety and trust boundaries |
| `60-tools-and-paths.md` | Tool descriptions and path conventions |
| `70-skills-and-mcp.md` | Skills and MCP notes |
| `99-runtime-context.md` | Runtime context: skills, subagents, memory, project rules, workspace, and current time (carried by variables) |
| `agent/plan-mode.md` | PLAN mode reminder (injected on demand) |
| `agent/auto-mode.md` | AUTO mode reminder (injected on demand) |
| `agent/subagent-base.md` | Subagent base operating rules (injected on demand) |
| `agent/subagent-context.md` | Subagent runtime context: identity, workspace, and time (injected on demand) |
| `agent/compact-summary.md` | Prompt for long-conversation context compression |
| `agent/title-generator.md` | Prompt for session title generation |
| `agent/init.md` | Instruction body of the `/init` command (analyzes the codebase and generates/improves `AGENTS.md`); can be triggered with `/init` in the chat input box |

## Restoring Defaults

Delete the corresponding file under `~/.xencode/prompts.custom/` and that fragment reverts to the default version; delete the entire `prompts.custom/` directory to restore everything (once `.no-builtin` is deleted along with it, the built-in prompts are re-enabled).

## Notes

- Older versions placed `80-plan-mode.md`, `81-auto-mode.md`, and `90-subagent-base.md` at the top level of `prompts/`; **since v1.12.0** they have moved into `prompts/agent/` and lost their numbers. Update the corresponding custom overrides to paths like `prompts.custom/agent/plan-mode.md`.
- Fragments like `60-tools-and-paths.md` are updated as tools change. If you override one, you won't automatically receive the new tool descriptions after an app upgrade, and the tool definitions the AI sees may not match reality. When an update is needed, sync manually, or delete your custom version to let the default take effect again.