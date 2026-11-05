# XenCode vs. the field — feature matrix

How the XenCode Rust PC line compares with the harnesses it was benchmarked
against (references cloned for study: DeepSeek Harness `dsh`, OpenAI Codex,
SST OpenCode, Aider, SWE-agent, OpenHands, Gemini CLI, mini-swe-agent).
Last updated: 2026-11-06 (Rust line v2.0.0).

Legend: ✅ shipped · 🧩 partial (core exists, UX surface growing) · 🗺️ roadmap

| Capability | XenCode | Claude Code | DeepSeek Harness | OpenCode | Codex |
| --- | --- | --- | --- | --- | --- |
| Streaming chat + tool loop | ✅ | ✅ | ✅ | ✅ | ✅ |
| Multi-provider (OpenAI-compat + native Anthropic + native Gemini) | ✅ 3 protocols, one config | 1 vendor | several | several | OpenAI-first |
| Permission modes (read-only / confirm / yolo) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Hook system (pre blocks tool, post audit) | ✅ | ✅ richer events | 🧩 plugin host | 🗺️ | 🧩 |
| MCP (stdio) with `mcp_<server>_<tool>` namespacing | ✅ | ✅ +HTTP/SSE | ✅ | ✅ +HTTP | ✅ |
| Subagents (`task` tool, isolated context, depth-limited) | ✅ | ✅ custom defs | ✅ teams | ✅ agents | ✅ |
| Parallel swimlanes in isolated `git worktree`s | ✅ (`goal run --parallel N`) | ✅ background agents | 🧩 | 🧩 | 🗺️ |
| Goal DAG with dependency scheduling (shared with mobile) | ✅ format 1 twin | 🗺️ (goals host-side) | 🗺️ | 🗺️ | 🗺️ |
| Checkpoint time machine (atomic commits + restore + diff) | ✅ | ✅ | 🧩 | 🗺️ | 🧩 |
| Decision cards resolved from desktop / CLI / phone | ✅ | 🧩 (AskUserQuestion) | ✅ | 🧩 | 🗺️ |
| `.mjs` automation on embedded QuickJS (rquickjs, real ESM) | ✅ unique | 🗺️ (skills only) | 🧩 plugins | 🗺️ | 🗺️ |
| Native `xencode:core` bindings: fs / ast / pty / git / sync | ✅ | 🗺️ | 🧩 | 🗺️ | 🗺️ |
| AST-lite symbol surgery (`replaceSymbol`) for agents | ✅ | 🧩 via tools | 🧩 | ✅ LSP | 🧩 |
| Gamified telemetry: XP curve shared with mobile, streaks, spec radar | ✅ unique | 🗺️ | 🗺️ | 🗺️ | 🗺️ |
| Bi-directional ndjson event log read by the Android app | ✅ unique | 🗺️ | 🧩 | 🗺️ | 🗺️ |
| Compaction of long histories | ✅ | ✅ | ✅ | ✅ | ✅ |
| Sessions: persist / list / show / resume | ✅ | ✅ | ✅ | ✅ | ✅ |
| Headless one-shot mode for CI (`exec`) | ✅ | ✅ `-p` | ✅ headless | ✅ `run` | ✅ `exec` |
| Desktop GUI (Win/macOS/Linux) with agent panes + embedded icon | ✅ egui | 🗺️ (IDE ext) | ✅ desktop | 🗺️ | ✅ IDE |
| CLI single static binary (no JVM/Node runtime) | ✅ | 🗺️ (Node) | 🗺️ (Node) | 🗺️ (TS/Go) | ✅ Rust |
| Cross-platform CI matrix + tag-driven release | ✅ | n/a | n/a | n/a | n/a |
| Skills / custom slash-command files | 🗺️ | ✅ | ✅ | ✅ | 🧩 |
| LSP diagnostics tool | 🗺️ | 🧩 | 🧩 | ✅ | 🗺️ |
| GitHub bot (mention → branch → PR) | 🗺️ | ✅ Actions | 🧩 | ✅ | 🧩 cloud |
| WebFetch / WebSearch tools | 🗺️ | ✅ | ✅ | ✅ | ✅ |
| Sandboxed command execution (seatbelt/landlock) | 🗺️ | ✅ | ✅ | 🧩 | ✅ |

## Why XenCode wins where it counts

1. **One brain, every surface.** The Goal DAG, XP curve, decision cards and
   event log are format-twins with the Android app — a goal started on the
   phone is resumable on the desktop and the CLI, with no import/export.
2. **Automation as a first-class citizen.** `.mjs` scripts run on a real ES
   module engine (QuickJS/rquickjs) with native bindings instead of a
   shimmed interpreter; the same scripts run identically on Windows, macOS,
   Linux and inside the Android app's runtime.
3. **Verified completion.** Checkpoints only exist after a verified state;
   the spec radar and compile streak make verification a visible habit, and
   CI gates on 73 checks + clippy with zero warnings.
4. **No runtime tax.** The whole PC line is Rust: one static CLI binary and
   a small native desktop app per OS — no JVM, no Node, no browser engine.
