# XenCode Desktop & CLI (PC — Rust)

XenCode runs the same agent platform on your PC: a native desktop app for Windows, macOS and Linux, and a terminal-native CLI — both built in **Rust** with an embedded **rquickjs** (QuickJS) `.mjs` automation engine. Both share one agent runtime and one workspace database (the `.xencode/` directory), so goals, XP, sessions and events mean the same thing on every surface — Android included.

## Install

| Platform | Source |
| --- | --- |
| Windows | `xencode-desktop-windows-amd64.zip` from [Releases](https://github.com/Resolutefemi/XenCode/releases) (PC artifacts are tagged `pc-v*`) |
| macOS | `xencode-desktop-macos-arm64.zip` (contains `Xencode.app`) |
| Linux | `xencode-desktop-linux-amd64.deb` |
| CLI (all OS) | `xencode-cli-<os>-<arch>.tar.gz` / `.zip` — unpack and put `xencode` on your `PATH` |

Or build from source (Rust stable):

```bash
cd rust
cargo build --release -p xencode-cli     # CLI → rust/target/release/xencode
cargo run -p xencode-desktop             # desktop app
cargo test --workspace                   # the 73-check verification gate
```

## First run

```bash
xencode init                                        # .xencode/ scaffolding
xencode config set-provider zai --kind openai \
    --base-url https://api.z.ai --api-key <KEY> --model glm-4.7
xencode doctor                                      # verify the setup
```

Providers can be `openai` (any compatible endpoint), `anthropic` (native Messages SSE) or `gemini` (native `streamGenerateContent`). All of them stream tool calls through the same loop.

## The DeepSeek-styled desktop UI

The desktop app's UI is a faithful port of the **DeepSeek Harness (`dsh`) Web UI** design system — same dark surfaces (`#151517` base, `#1B1B1C` sidebar, `#2C2C2E` cards), DeepSeek blue accents (`#7AAAFF`/`#5686FE`), 4/8/12/16/20px radii, 14px body type, hover/active states and layout patterns. The only visual difference is the brand glyph: the XenCode logo (window/taskbar icon, sidebar mark, empty-state and assistant avatar).

| Page | What it shows |
| --- | --- |
| ![Home](/screenshots/pc/01_home.png) | **Home** — sidebar (brand, New chat, shared-DB conversations, panels, settings) and the dsh-style empty state with suggestion pills |
| ![Chat](/screenshots/pc/02_chat.png) | **Chat** — right-aligned user bubbles, collapsible tool-call cards, assistant messages with avatar, composer with Attach + mode chips (Build / Plan / Yolo / Read-only) and the round send button |
| ![Goals](/screenshots/pc/03_goals.png) | **Goals** — Goal DAG on a dark code block, sequential and ×4 swimlane runners |
| ![Telemetry](/screenshots/pc/04_telemetry.png) | **Telemetry** — XP level/streak from the shared DB, token totals, spec radar |
| ![Automation](/screenshots/pc/05_automation.png) | **Automation** — `.mjs` script runner on the embedded rquickjs engine |
| ![Decisions](/screenshots/pc/06_decisions.png) | **Decisions** — pending decision cards with PENDING badges |
| ![Events](/screenshots/pc/07_events.png) | **Events** — the bi-directional mobile ⇄ desktop sync log |
| ![Settings](/screenshots/pc/08_settings.png) | **Settings** — provider cards (active badge), config/workspace paths |

Keyboard shortcuts `Ctrl+1…7` switch pages. Every capture above is regenerable with the UI tour: `XENCODE_UI_TOUR=<dir> xencode-desktop` walks all eight pages and writes `<dir>/marker.txt` per page — `scripts/run_ui_tour.sh` + `scripts/capture_ui_pages.py` screenshot each one under Xvfb (software GL), producing exactly these files in `docs/screenshots/pc/`.

## Commands

| Command | Purpose |
| --- | --- |
| `xencode chat` | Interactive REPL — `/help /tools /mode /plan /image /resume /todo /goal /dag /xp /radar /checkpoint /session /compact /decide /events /run /quit` |
| `xencode chat --image shot.png` | Start the REPL with images staged for the first message |
| `xencode exec "<prompt>" [--image x.png]` | One-shot agent run (streams, persists to the shared DB, exits) |
| `xencode run <script.mjs>` | Execute an automation script on the embedded rquickjs engine |
| `xencode goal add/list/run/set` | The Goal DAG: add nodes, run READY nodes in parallel swimlanes (isolated `git worktree` per lane) |
| `xencode dag` | Render the DAG |
| `xencode checkpoint list/create/restore/diff` | The time machine: atomic `xencode-checkpoint(<label>): <summary>` commits |
| `xencode session list/show/delete` | Persisted conversations (`.xencode/sessions/`) |
| `xencode todo set/show` | The shared TODO list |
| `xencode mcp list/call` | MCP servers over stdio (`.xencode/mcp.json`); tools surface as `mcp_<server>_<tool>` |
| `xencode decide list/resolve` | Decision cards raised by the agent or scripts |
| `xencode events --n 20` | Tail the bi-directional sync log (`.xencode/events.ndjson`) |
| `xencode worktree list/create/remove` | Manage swimlane worktrees |
| `xencode xp` / `xencode radar` | XP level + streak; compile streak + spec radar |
| `xencode doctor` | Environment diagnostics |

## Agent features

- **Tools** — `read_file` (offset/limit), `write_file`, `edit_file` (exact replace + `replace_all`), `list_files`, `glob`, `grep` (content/files/count, case-insensitive, glob filter, head limit), `delete_file`, `run_command` (timeout), `todo_write/read`, `web_fetch` (SSRF-guarded), `load_skill`, plus `task` (spawn a fresh subagent with its own context; depth-limited) and every connected MCP tool. Results are clipped at 12 000 chars; errors are `ERROR: …` strings.
- **Permissions** — `read-only` (mutation denied), `confirm` (interactive ask; auto-asks become decision cards in non-interactive runs), `plan`, `yolo`.
- **Plan mode** — `/plan` in the CLI, the Plan chip in the desktop composer. The agent may read, search, fetch web pages, load skills, draft the plan with `todo_write` and spawn research subagents; writes, edits, deletes and shell commands are denied with plan-mode guidance. The mode lands in `chat_sessions.mode` (the app's `PLAN` badge) with the previous mode remembered in `mode_before_plan`, exactly like the Android app's plan mode. Switch back (toggle `/plan` again or the Build chip) and say “go ahead” to execute the approved plan.
- **Images** — attach screenshots or photos to any message: 📎 in the desktop chat pane (png / jpg / gif / webp / bmp, ≤ 8 MiB each) or `xencode chat --image a.png --image b.png` / `/image <path…>` in the CLI. All three protocols get proper vision parts (OpenAI `image_url` data URLs, Anthropic base64 `image` blocks, Gemini `inline_data`), thumbnails render inline on the desktop, and every attachment is persisted in `agent_messages.attachments` using the app's `AgentAttachment` JSON — so the phone shows the same attachments on the same rows.
- **Session resume (row sync)** — `/resume` lists the newest shared-DB sessions and `/resume <id>` continues one; the desktop's **CONVERSATIONS** list in the sidebar does the same — click any session (created on the phone or the PC) and its rows become the transcript while new turns append to the same rows. USER / ASSISTANT rows are rebound as model history (images re-attach when the files still exist) and new turns append to the same session — a conversation started on the phone keeps going on the PC with full context.
- **Hooks** — `.xencode/hooks.json`: `pre` hooks (failing exit code blocks the tool) and `post` hooks, matched by tool name or `*`; they receive `XENCODE_TOOL` / `XENCODE_TOOL_ARGS`.
- **Checkpoints** — every checkpoint is an atomic git commit that excludes `.xencode/`, recorded in `.xencode/checkpoints.json`; `restore` hard-resets, `diff` shows the stat.
- **Compaction** — past a threshold the loop folds older turns into a digest so long sessions keep going.
- **Telemetry** — XP (`100·n²` curve, +5 per turn, +50 per goal, daily streak bonus), compile streak, and the spec radar (per-spec GREEN/RED) in `.xencode/telemetry.json`.

## The .mjs automation engine

Automation scripts are real ES modules executed by QuickJS (via rquickjs) with native host bindings — the same shape as the Android runtime uses:

```js
import { fs, ast, pty, git, sync } from "xencode:core";

export async function main() {
  // surgical read (1-based, inclusive)
  const head = fs.readRange("src/lib.rs", 1, 40);
  // atomic write inside the workspace sandbox
  fs.writeAtomic("report.md", `# Report\n\n${head.length} chars\n`);
  // regex-lite symbol surgery
  ast.replaceSymbol("src/lib.rs", "old_fn", "fn old_fn() { /* new */ }");
  // run a command with a timeout
  const build = pty.exec("cargo test --workspace", { timeoutMs: 120000 });
  sync.recordTest("cargo-test", build.exit === 0);
  // gate on the Iron Laws: no verification, no completion
  if (build.exit !== 0) throw new Error("tests failed");
  // checkpoint the verified state
  git.checkpoint("g1", "verified drop");
  // ask the user (decision card on desktop/CLI/phone)
  sync.requestUserDecision({ title: "Push?", body: "tests are green", options: ["push", "hold"] });
  // bi-directional sync event (desktop + phone pick this up)
  sync.emitMobileAndDesktop("REPORT_READY", { summary: "report.md written" });
}
```

Rules of the engine: all host bindings are synchronous (blocking with internal timeouts), so `await` on them resolves immediately and an `async main()` settles before the engine returns. Paths are sandboxed to the workspace. Errors come back as `ERROR: …` strings.

## The Iron Laws (what the agent enforces)

1. Zero placeholders, zero truncation.
2. The disk is the persistent brain (`.xencode/`).
3. 100% parity: Mobile, Windows, macOS, Linux, CLI.
4. Automate multi-file work with `.mjs` first.
5. No completion without exit code 0.
6. Surgical reads (grep/glob/ranges), never dump big files.
7. Isolated worktrees + atomic `xencode-checkpoint(<node>): <summary>` commits.
8. 3-strike self-healing, then `.xencode/BLOCKERS.md` and a decision card.
