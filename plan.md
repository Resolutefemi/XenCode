# XenCode — Master Build Plan

> **Status:** Approved direction — execution starts on "go".
> **Last updated:** 2026-11-10
> **Stack:** 100% Rust (native self-drawn GUI, no webview) · QuickJS (`rquickjs`) for all automation — no Python · ZCode v3.17.2 is the sole UI/UX spec.

## North stars

1. **UI/UX parity with ZCode** — pixel-faithful; every surface mirrored from the ZCode reference source, never freelanced.
2. **One static binary** — QuickJS embedded (~4 ms script boot), no Python, no Node, no external runtimes.
3. **Capability-scoped automation** — every script sandboxed; permissions declared in `xen.toml`, consented in the GUI.
4. **Everything is scriptable** — agent, browser, and RE toolkit all expose hooks to QuickJS.

## Phase map

| Phase | Deliverable | Size | Depends on |
|---|---|---|---|
| 0 | GUI sign-off — queued fixes + 6 rendered pages | S | — |
| 1 | Agent core backend (GLM loop, tools, sessions) | M | — |
| 2 | QuickJS automation core (`xencode-script`) | M | 1 |
| 3 | Harness ports, wave 1 (memory, commands, hooks, plan mode) | S ×4 | 2 |
| 4 | Harness ports, wave 2 (MCP, subagents, checkpoints, `xen exec`) | M ×4 | 3 |
| 5 | Harness ports, wave 3 (repo map, LSP loop, recipes, Oracle, OTel) | M ×5 | 4 |
| 6 | **XenBrowser** — embedded agent browser | L | 1 |
| 7 | **XenRE** — decompiler / RE toolkit | L | 4 |
| 8 | **API Discovery** — website API extraction | M | 6.2 |

Execution order: 0 → 1 → 2 → 6.1–6.2 → 3 → 4 → 8 → 7 → 5. Phases 6 and 7 can run in parallel with 3–5.

## Phase 0 — GUI sign-off (S)

- Apply the two queued fidelity fixes (home watermark glow underlay, string audit on settings/chat/git/terminal pages).
- Re-render all 6 pages (`--screenshot --out`) → side-by-side acceptance vs ZCode → push.
- Status: **DONE** — all six pages rendered, QA'd and committed under `screenshots/` (also embedded in the README).
  Re-verified after the Phase-1 completion work, and again after the **global alignment audit**: `fonts::draw_mid`
  now derives its optical centering from the real font metrics (cap-height band centered on the row centerline)
  instead of a hardcoded constant, so leading icons, status dots and avatars share the exact centerline with their
  labels across every surface (sidebar rows, task dots, folder headers, composer mode row, chips, settings nav,
  git pane, footer). Icons that sat off-center inside their rows (settings nav 29px rows, select triggers, code
  preview header, git-pane refresh) were re-centered; the two-line footer/settings text blocks are block-centered
  on their avatar row. Screenshots re-rendered from the fixed renderer.

## Phase 1 — Agent core backend (M)

- Agent loop on GLM with streaming; tool executors (read/write/edit, glob/grep, process, todo).
- Session store: resume, fork, compaction.
- Permission prompts wired to the native GUI.
- Status: **DONE**.
  - Loop + streaming: GLM SSE client forwards deltas as `AgentEvent::Text` (real streaming to stdout and the GUI).
  - Sessions: JSON store with save/load/list plus **resume** (`--continue`, `--resume <id>`), **fork** (`--fork <id>`) and
    **compaction** (transcript over budget → older turns folded into a model-written summary, tool pairing preserved).
  - Permissions: `Decision::Allow/Ask/Deny` gates — `AskGate` prompts on mutations (stdin y/n/a in the CLI,
    `--yes` for CI, `--read-only` for hard read-only); consent cards rendered in the chat pane.
  - GUI wiring: `--agent-ui "prompt"` runs the agent and renders the live conversation (user bubble, streamed
    answer, tool rows, consent cards, composer) through the native renderer — `screenshots/07-agent-ui.png`;
    `--render-session <id>` renders a saved transcript. The windowed GUI consumes the same `ChatPage` state.

## Phase 2 — QuickJS automation core (M)

- `xencode-script` crate: rquickjs (async over tokio), one isolated context per script, memory limit + interrupt guard.
- **Host bridge (`xen.*`)** — the explicit Rust→JS exposure QuickJS requires:
  - `xen.http` — fetch/headers/streaming on reqwest
  - `xen.fs` — read/write/append/watch/glob (tokio + notify)
  - `xen.proc` — spawn processes, capture stdout/stderr/exit
  - `xen.agent` — hooks (beforeTool/afterTool/onStop), session context, agent messaging
  - `xen.ui` — toasts, prompts, progress in the native GUI
  - Shims: `console`, `setTimeout/setInterval`, `URL`, `TextEncoder/Decoder`, `atob/btoa`, `crypto.getRandomValues`
- **Solving "no npm"** in three tiers:
  1. Built-in Rust modules covering the top ~20 npm use-cases: path, glob, yaml, toml, csv, zip, sqlite, dotenv, semver, ansi, markdown — zero install, instant.
  2. npm-lite vendor cache: pure-JS packages resolved from the registry, sha512-verified, lockfiled, imported as ESM — no node_modules.
  3. TypeScript stripped in-process via oxc — users write TS, we transpile in microseconds.
- Permissions (Deno-style) in `xen.toml`: `net` scopes, `fs` scopes, `proc`, `env` — GUI consent on first use.
- Triggers: cron schedules, file-watch, webhooks, lifecycle hooks — one engine, four triggers.
- Status: **DONE**.
  - Runtime (`script/src/runtime.rs`): `ScriptRuntime` — isolated `AsyncRuntime` + context per script, 64 MiB heap limit,
    1 MiB stack, interrupt guard (deadline + manual cancel), custom `XenResolver`/`XenFileLoader` (relative + npm +
    `node:` specifier mapping, `.ts/.tsx/.mts/.cts` stripped in-process, `.json` modules), global `xen` aggregate object
    beside the ESM `xen/*` modules, and the rquickjs `rt.drive()` pump on a `LocalSet` (QuickJS values never cross a
    thread — script execution runs on a dedicated blocking thread per run).
  - Host bridge: `xen.http` (fetch with WHATWG-style Response: status/headers/text()/json()/arrayBuffer()/bytes()),
    `xen.fs` (readFile/readText/writeFile/appendFile/remove/mkdir/rename/copy/stat/glob + debounced `watch` handles),
    `xen.proc` (spawn/run with cwd/env/stdin, stdout/stderr/exit capture), `xen.agent` (hook registry + inbox +
    onMessage delivery through `AgentBridge`), `xen.ui` (toast/progress events + text prompts through `UiBridge`).
    Shared state rides context userdata; every op passes the Deno-style permission gates.
  - Shims: console (log/info/warn/error/debug/trace/assert as UI events), setTimeout/setInterval/clear*/queueMicrotask
    (tokio-driven schedular futures with a cancel-flag registry), URL/URLSearchParams, TextEncoder/TextDecoder,
    atob/btoa, crypto.getRandomValues/randomUUID, performance.now.
  - Tier 1 built-ins: `xen/path` (node:path-compatible), `xen/glob`, `xen/yaml`, `xen/toml`, `xen/csv`, `xen/zip`,
    `xen/sqlite` (rusqlite bundled, positional + named params), `xen/dotenv` (+ env injection), `xen/env`
    (permission-gated env access), `xen/semver`, `xen/ansi`, `xen/markdown` — all native ESM, also reachable via
    npm-style bare specifiers (`import { parse } from "yaml"`).
  - Tier 2 vendor cache (`script/src/npm.rs`): registry metadata → tarball download → sha512/sha256 SRI verification →
    gzip/tar extraction into `~/.cache/xencode/npm`, `package.json` ESM entry resolution (module → exports.import →
    main → index.js), transitive pure-JS prefetch (depth 8, 64 packages), `xen.lock` pinning for offline reruns,
    `xen-script cache ls|clean`.
  - Tier 3 TS strip (`script/src/ts.rs`): oxc parser + TypeScript transformer (annotations/interfaces/enums/namespaces
    erased) + codegen, microseconds for typical scripts.
  - Permissions: `[permissions]` net/fs/proc/env grants in `xen.toml` ($PROJECT-relative scopes, host allowlists with
    `*.domain` matching), session-cached consent through the `PromptBridge` (stdin y/n/a in the CLI, GUI consent card
    ready), `--yes` / `--read-only` CLI flags.
  - Triggers (`script/src/triggers.rs`): one engine, four kinds — cron (`cron` crate + `@daily`-style shorthands),
    file-watch (notify recursive + glob filter + 1 s debounce), webhooks (built-in 127.0.0.1 HTTP server, path-routed
    POST/GET), lifecycle (onStart/onStop/onError) — each run through a fresh isolated runtime via the injected
    `RunFactory`, run history surfaced as `RunSummary`.
  - CLI (`xen-script`): `run <script.ts|js>` with `--arg/--yes/--read-only/--timeout-ms/--memory-mb/--json`,
    `triggers` daemon (Ctrl-C → onStop), `info`, `cache ls|clean`; UI events stream to stderr.
  - Verified end-to-end: TS demo exercising path/yaml/toml/semver/markdown/ansi + `xen.proc` + `xen.fs` roundtrip +
    timers + top-level await + default export; trigger daemon firing cron/webhook/lifecycle. 52 unit tests green.

## Phase 3 — Harness ports, wave 1 (S each)

- **Memory files** — `XENCODE.md` (+ `AGENTS.md` compat), project + user scope, auto-loaded into context.
- **Markdown slash commands** — `.xencode/commands/*.md` with `$ARGUMENTS`.
- **Hooks** — PreToolUse / PostToolUse / Stop; executed by the QuickJS runtime.
- **Plan mode** — read-only until the user approves the plan.

## Phase 4 — Harness ports, wave 2 (M each)

- **MCP client** — JSON-RPC over stdio; unlocks the existing MCP server ecosystem.
- **Subagents** — tokio-parallel child agent loops with scoped prompts and tool allowlists; custom agents in `.xencode/agents/*.md`; ZCode-faithful UI cards (audit the reference components first).
- **Checkpoints** — shadow-git snapshot before each edit; rewind timeline in the GUI.
- **`xen exec`** — headless mode for CI + session resume/fork.

## Phase 5 — Harness ports, wave 3 (M each)

- **Repo map** — tree-sitter symbols, relevance-ranked (Aider-style).
- **LSP diagnostics loop** — compiler errors auto-fed back to the model (OpenCode-style).
- **Recipes** — declarative playbooks with params/subtasks (Goose-style) on QuickJS.
- **Oracle** — stronger-model second-opinion agent (Amp-style).
- **OTel tracing** — tokens, cost, tool spans per run.

## Phase 6 — XenBrowser (L)

Goal: like ego(lite) — but better, on every platform, built in.

| ego(lite) does | XenBrowser does |
|---|---|
| macOS only | Linux / Windows / macOS day one (Rust control plane) |
| Separate app around your Chrome | Built into XenCode as a native browser tab + CLI-drivable headless — one binary |
| `snapshot()` DOM outlines | Same primitive + snapshot diffing — only deltas go to the model (cheaper tokens) |
| Agent drives your logged-in browser | Same: isolated Spaces inheriting profile/cookies — plus clean-room profiles for scraping |
| Interactive agent tasks only | Record → replay: interactions become reusable QuickJS automation scripts |
| Not built for headless fleets | Parallel headless Spaces at scale (isolated contexts, no GIL) |

- Architecture: pure-Rust CDP client driving Chromium (auto-detect system Chrome/Edge, optional managed download); screencast frames composited into the native renderer — a real browser tab inside XenCode.
- Scraping engine: auto-wait, CSS/XPath/text/AI selectors, pagination + infinite scroll, proxies, rate limits, exports (JSON/CSV/Markdown).
- Milestones: **6.1** CDP core + Spaces → **6.2** GUI tab + snapshot/diff → **6.3** recorder → **6.4** headless fleet + scraping exports.

## Phase 7 — XenRE (L)

Goal: decompile apps and binaries — APK, IPA, XEX, ELF/PE/Mach-O, JAR, .NET — from inside XenCode.

- **Native Rust layer:** format parsers (goblin/object for ELF/PE/Mach-O; custom DEX; custom XEX header/section/resource parser + decompression), capstone disassembly (ARM, ARM64, x86, PPC/Xenon).
- **Orchestrated backends (managed downloads, on-demand):** Ghidra headless, jadx (Android), ILSpy (.NET), RetDec — output lands in editor tabs; a fully native decompiler is a later R&D track.
- **GUI panels:** hex viewer, strings, imports/exports, function list, decompiled code tabs — drawn natively.
- **Compliance guardrail:** analysis of authorized/user-owned targets only; no DRM keys or circumvention tooling bundled (XEX decryption keys are never shipped).
- Milestones: **7.1** ELF/PE/Mach-O + hex/strings panels → **7.2** APK/IPA + jadx orchestration → **7.3** XEX parser + PPC disasm + Ghidra loader flow.

## Phase 8 — API Discovery (M)

Goal: "easily get any website API."

- Built on XenBrowser network capture (6.2): record every XHR/fetch → **endpoint catalog** (URL, method, params, headers, auth, response shape).
- **Copy as:** cURL / fetch / reqwest / QuickJS.
- **OpenAPI auto-generation** from observed traffic; JSON schema inference.
- **Endpoint monitor** — re-capture → diff → alert via automation; secret redaction by default.

## Copied harness features — full mapping

| # | Feature | Copied from | Lands in | Phase |
|---|---|---|---|---|
| 1 | Memory files (`XENCODE.md`, `AGENTS.md`) | Claude Code / Codex | Agent core | 3 |
| 2 | Markdown slash commands | Claude Code | Agent core + GUI | 3 |
| 3 | Hooks (PreToolUse / PostToolUse / Stop) | Claude Code | QuickJS runtime | 3 |
| 4 | Plan mode | Claude Code / Codex | Agent core + GUI | 3 |
| 5 | Permission modes | Claude Code / Codex | Agent core | 3 |
| 6 | MCP client | Anthropic standard | Agent core | 4 |
| 7 | Subagents | Claude Code / Amp / ZCode | Agent core + GUI | 4 |
| 8 | Checkpoints / rewind | Cline / Claude Code | Agent core + GUI | 4 |
| 9 | Headless `xen exec` + resume/fork | Codex / OpenCode | CLI | 4 |
| 10 | Repo map | Aider | Agent core | 5 |
| 11 | LSP diagnostics loop | OpenCode | Agent core | 5 |
| 12 | Recipes | Goose | QuickJS runtime | 5 |
| 13 | Oracle | Amp | Agent core | 5 |
| 14 | OTel tracing | Observability harnesses | Infra | 5 |
| 15 | Browser tool | Cline / ego(lite) | XenBrowser | 6 |

## Decisions

1. **Execution order** — as in the phase map; reshuffle on request.
2. **Open:** Chromium strategy — auto-detect system Chrome/Edge first, managed download fallback (recommended).
3. **Open:** RE backends (~1 GB) — on-demand download prompt (recommended) vs skip external backends in v1.
4. Phase 0 + Phase 1 start together on "go".
