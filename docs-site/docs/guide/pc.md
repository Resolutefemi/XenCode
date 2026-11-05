# XenCode Desktop & CLI (PC)

XenCode runs the same agent platform on your PC: a native desktop app for Windows, macOS and Linux, and a terminal-native CLI. Both share one agent runtime and one workspace database (the `.xencode/` directory), so goals, XP, sessions and events mean the same thing on every surface — Android included.

## Install

| Platform | Source |
| --- | --- |
| Windows | `XenCode-<version>.msi` / `.exe` from [Releases](https://github.com/Resolutefemi/XenCode/releases) (PC artifacts are tagged `pc-v*`) |
| macOS | `XenCode-<version>.dmg` |
| Linux | `xencode-<version>.deb` / `.rpm` |
| CLI (all OS) | `xencode-cli-<os>-<arch>.tar.gz` / `.zip` — unpack and add `bin/` to your `PATH` |

Or build from source (JDK 21):

```bash
./gradlew :desktop:run          # launch the desktop app
./gradlew :desktop:packageDeb   # or packageMsi / packageExe / packageDmg / packageRpm
./gradlew :cli:installDist      # CLI distribution at cli/build/install/xencode/
```

## First run

```bash
xencode init                                        # ~/.xencode/ starter config + AGENTS.md
xencode config add --name zai --kind openai \
    --base-url https://api.z.ai --api-key <KEY> --model glm-4.7
xencode doctor                                      # verify the setup
```

Three provider kinds are supported: `openai` (any chat-completions-compatible endpoint), `anthropic` (native Messages API) and `gemini` (native Google endpoint). The desktop app configures the same file through Settings.

## The agent surface

- **Tools** — `read_file` (offset/limit), `write_file`, `edit_file` (unique-match search/replace), `glob`, `grep` (content/count/files modes), `delete_file`, `run_command` (timeout), `todo_write`/`todo_read`. Everything is sandboxed to the workspace: absolute paths and `..` escapes are rejected.
- **Permissions** — `.xencode/permissions.json`: mode `read-only` / `default` / `yolo` plus `tool:` and `cmd:` glob rules (deny > ask > allow). In `default` mode destructive commands (rm, git push, sudo, …) raise a **decision card** instead of running; resolve it with `xencode decide approve <id>` or the desktop banner.
- **Hooks** — `.xencode/hooks.json`: pre/post tool shell hooks. A pre-hook exiting non-zero blocks the call; hooks receive `XENCODE_HOOK_TOOL` / `XENCODE_HOOK_ARGS` / `XENCODE_HOOK_RESULT`.
- **Checkpoints (Time-Machine)** — before risky edits the runtime takes an atomic git commit (`.xencode` excluded). `xencode checkpoint list / restore <id> / diff <id>`.
- **Sessions** — every chat is persisted under `.xencode/sessions/`; resume with `xencode chat --resume <id>`, `xencode chat --continue`, or inspect with `xencode session list / show`.
- **Compaction** — long contexts compact deterministically (`/compact` in the chat, or the Compact button) keeping the first ask and recent turns.
- **TODO list** — one list per workspace (`.xencode/TODO.json`), shared between you and the agent: `xencode todo add …` and the agent's `todo_write` see the same items.
- **MCP** — stdio Model Context Protocol servers configured in `.xencode/mcp.json` (`xencode mcp init` writes an example). Their tools surface as `mcp_<server>_<tool>`.

## Goal swimlanes

Break work into a DAG and let parallel agents execute it:

```bash
xencode goal add "Set up the repo" note "git init, README"
xencode goal add "Write tests" after g1
xencode goal run --parallel 4      # READY nodes run concurrently
```

Each READY node gets its own agent turn; in a git workspace every lane runs in an isolated worktree on branch `xencode/<id>` (see `xencode worktree list`). Completed lanes are checkpoint-committed but **never merged back automatically** — review and merge the branch yourself. XP (+50 per goal) lands on the same curve as the Android app; streaks and the RED/GREEN spec radar live in `.xencode/telemetry.json` and the Progress pane.

## `.mjs` automation

Repetitive multi-file work belongs in a script, not in chat turns. Scripts run on the embedded engine with the `xencode:core` bindings:

```js
import { fs, pty, sync } from "xencode:core";

export async function main() {
  const kt = await fs.glob("src/**/*.kt");
  const status = await pty.exec("git status --short");
  await fs.writeAtomic(".xencode/mjs-report.txt",
    `files=${kt.length}\n${status.output}`);
  await sync.emitMobileAndDesktop("STATE_SYNC", { summary: "report written" });
  return `tracked ${kt.length} files`;
}
```

- `fs` — `glob`, `readRange(path, start, end)`, `writeAtomic`, `applyPatch` (strict unified diff)
- `ast` — `symbols`, `findPattern`, `replaceSymbol` (surgical declaration replacement)
- `pty` — `exec(command, { cwd, timeoutMs })` → `{ exit, output }`
- `git` — `worktreeCreate(id)`, `checkpoint(nodeId, message)`, `diffStat()`
- `sync` — `emitMobileAndDesktop(type, payload)`, `requestUserDecision({title, body, options})`, `recordTest(id, passed)`

Run with `xencode run <script.mjs>` (or the Automation pane). `xencode run --example` prints a full template.

## Events

Every state transition lands in `.xencode/events.ndjson` — `xencode events` tails it, and the desktop Progress / Automation panes render it live. The event log is the backbone of the Windows-desktop / CLI parity: whatever ran where, the feed tells the same story.

## Where things live

| Path | Contents |
| --- | --- |
| `~/.xencode/pc-config.json` | providers + default workspace (global) |
| `<workspace>/.xencode/GOAL_DAG.json` | goal DAG (shared with Android) |
| `<workspace>/.xencode/sessions/` | resumable chat sessions |
| `<workspace>/.xencode/checkpoints.json` | Time-Machine ledger |
| `<workspace>/.xencode/decisions.json` | decision cards |
| `<workspace>/.xencode/events.ndjson` | sync event stream |
| `<workspace>/.xencode/TODO.json` | shared TODO list |
| `<workspace>/.xencode/permissions.json` / `hooks.json` / `mcp.json` | agent policy |
| `<workspace>/.xencode/telemetry.json` | streaks, radar, counters |
| `<workspace>/.xencode/scripts/` | your `.mjs` workflows |
