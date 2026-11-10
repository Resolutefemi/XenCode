<h1 align="center">XenCode</h1>

<p align="center">
  <img src="public/logo/icons/128x128.png" alt="XenCode" width="96" />
</p>

<p align="center">
  A <b>100% Rust</b> coding-agent desktop app — native self-drawn GUI (no webview, no Electron)<br/>
  with a GLM-powered agent core and a QuickJS automation engine (no Python).
</p>

> **Status:** active development. The GUI is a pixel-faithful reimplementation of the ZCode v3.17.2 UX (all six primary pages rendered and verified). **Phases 0–3 are complete**: the agent core runs headlessly (GLM streaming, sessions, permission modes), the chat pane renders live transcripts, the QuickJS automation engine ships with the full `xen.*` host bridge, and harness wave 1 landed — memory files, markdown slash commands, QuickJS-executed hooks and plan mode. **XenBrowser** (embedded agent browser) and harness wave 2 (MCP, subagents, checkpoints, `xen exec`) are next — see [plan.md](plan.md).

## Highlights

- 🦀 **Native GUI** — software-rasterized in pure Rust (tiny-skia + ab_glyph); zero web technology in the render path
- 🤖 **Agent core** — GLM streaming, tool execution (read / write / edit / list / grep / bash / todo), JSON sessions with resume / fork / compaction, permission gates with consent prompts
- 🧠 **Memory files** — `XENCODE.md` / `AGENTS.md` (project + `~/.xencode/` user scope) auto-loaded into every run's context
- ⌨️ **Slash commands** — markdown prompts in `.xencode/commands/*.md` with `$ARGUMENTS` substitution
- 🪝 **Hooks** — `PreToolUse` / `PostToolUse` / `Stop` executed by the QuickJS runtime; a deny verdict blocks the tool, feedback joins the result, a stop-block keeps the loop going
- 🗺️ **Plan mode** — `--plan` researches read-only, prints the plan, then implements after approval; five permission modes (`default` / `accept-edits` / `read-only` / `plan` / `yolo`)
- 🛡️ **Permission prompts** — mutations ask for consent (y/n/a in the CLI, consent cards in the chat pane)
- 📸 **Visual ground truth** — every GUI page renders to PNG headlessly via `--screenshot`; live agent runs render via `--agent-ui`
- ⚡ **QuickJS automation** — capability-scoped `xen.*` scripting, ~4 ms boot, one static binary; `xen-script run/triggers` CLI
- 🌐 **XenBrowser & XenRE (roadmap)** — embedded agent browser with Spaces; decompiler toolkit for APK / IPA / XEX / ELF / PE

## Screenshots

| Splash | Home |
| --- | --- |
| ![splash](screenshots/01-splash.png) | ![home](screenshots/02-home.png) |
| **Chat** | **Appearance settings** |
| ![chat](screenshots/03-chat.png) | ![settings](screenshots/04-settings-appearance.png) |
| **Git pane** | **Terminal dock** |
| ![git](screenshots/05-git-pane.png) | ![terminal](screenshots/06-terminal-dock.png) |

**Live agent run** — the composer → loop wiring rendered in the native chat pane:

| Tool rows, consent card, streamed answer |
| --- |
| ![agent-ui](screenshots/07-agent-ui.png) |

## Build & run

Requires a stable [Rust](https://rustup.rs) toolchain.

```bash
cd app
cargo build --release
./target/release/xencode --screenshot --out shots   # render every GUI page to PNG
```

Live window mode (winit + softbuffer) is the next GUI milestone.

## Headless agent

```bash
export XENCODE_API_KEY=...        # your z.ai API key
export XENCODE_MODEL=glm-4.6      # optional (default: glm-4.6)
export XENCODE_BASE_URL=...       # optional (default: https://api.z.ai/api/paas/v4)

cd your-project
xencode --agent "Explain this codebase"
```

The agent streams its answer to stdout, executes tools inside the working
directory (gated by a permission policy), and saves the transcript under
`.xencode/sessions/`.

**Sessions** — resume where you left off, or branch a conversation:

```bash
xencode --agent --continue "now add tests"      # most recent session
xencode --agent --resume <id> "keep going"      # explicit session
xencode --agent --fork <id> "try another way"   # branch under a new id
```

Long sessions compact automatically: once the transcript outgrows the
context budget, older turns are folded into a model-written summary and the
loop keeps running.

**Permissions** — reads run free; mutations prompt for consent:

```text
xencode · permission: bash `cargo test` — [y]es [n]o [a]lways?
```

`--yes` auto-approves (CI), `--read-only` denies every mutation outright.

**Memory files** — standing instructions load into every run's context.
`XENCODE.md` at the project root (`AGENTS.md` works too for Codex-style
repos) plus `~/.xencode/XENCODE.md` for user-wide rules; project memory is
protected over user memory when the context budget gets tight.

**Slash commands** — markdown prompts in `.xencode/commands/` (project) and
`~/.xencode/commands/` (user):

```markdown
<!-- .xencode/commands/review.md -->
---
description: Review a diff for bugs
---
Review $ARGUMENTS with care. Focus on error handling and edge cases.
```

```bash
xencode --agent "/review the auth diff"   # expands the command
xencode --commands                        # list what's available
```

**Hooks** — QuickJS scripts that observe and gate the loop, configured in
`xen.toml`. Every run is a fresh sandbox: isolated QuickJS context, memory
limit, interrupt-guarded deadline (default 10 s).

```toml
[hooks]
pre_tool_use  = [".xencode/hooks/guard.ts"]   # { decision: "deny", reason } blocks the tool
post_tool_use = [".xencode/hooks/audit.js"]   # { feedback: "…" } joins the tool result
stop          = [".xencode/hooks/verify.ts"]  # { decision: "block", reason } keeps the loop going
```

**Plan mode** — research read-only, present a plan, implement on approval:

```bash
xencode --agent "refactor the session store" --plan
xencode --agent "..." --permission-mode accept-edits  # edits auto-approved
xencode --agent "..." --permission-mode yolo          # never ask (bypass)
```

Approved plans carry the whole research transcript into the execution run
under the regular permission mode.

**Render an agent run** into the native chat pane (no API key needed with
`XENCODE_DEMO=1`):

```bash
xencode --agent-ui "Fix the failing test" --out run.png
XENCODE_DEMO=1 xencode --agent-ui "Fix the failing test" --out demo.png
xencode --render-session <id> --out transcript.png
```

## Repository layout

```
app/          the entire Rust application (GUI + agent core)
  src/ui/     native renderer pages (shell, sidebar, splash, chat, settings…)
  src/agent/  provider clients, tools, agent loop, sessions, permission gates
public/       brand assets
screenshots/  rendered GUI pages (visual ground truth)
plan.md       master build plan — phases 0–8
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
