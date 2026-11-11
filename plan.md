# XenCode — Master Build Plan

> **Status:** Approved direction — **Phases 0–5 complete** (all three harness waves landed), **XenBrowser 6.1–6.4 complete** (CDP core + Spaces, GUI browser tab + snapshot/diff, interaction recorder → QuickJS replay scripts, headless fleet + scraping exports), **Phase 8 complete** (API Discovery — network capture, endpoint catalog, OpenAPI export, copy-as, endpoint monitor) and **XenRE 7.1 + 7.2 + 7.3 complete** (7.1: native ELF/PE/Mach-O analysis, hex/strings/imports panels, capstone disasm, `xen.re` hooks; 7.2: native APK/IPA package analysis + jadx orchestration with managed download; 7.3: XEX container parser + PPC64/Xenon disasm + Ghidra headless loader flow). Remaining: optional orchestrated backends (ILSpy, RetDec) as extensions; the managed-Chromium-download decision (open decision #2) is still open.
> **Last updated:** 2026-11-11
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
- Status: **DONE**.
  - Memory (`app/src/agent/memory.rs`): `XENCODE.md` at the project root with `AGENTS.md` compat fallback, same
    pair under `~/.xencode/` for the user scope; both loaded once per run and appended to the system prompt under
    a `# Project memory` section. Per-file truncation at 32k chars, 64k total budget with project memory protected
    over user memory; empty/missing files are simply skipped.
  - Slash commands (`app/src/agent/commands.rs`): `.xencode/commands/*.md` (project shadows user), file stem is the
    command name, optional `description:` frontmatter, `$ARGUMENTS` substitution (args appended when the body has no
    placeholder). `/name args` expands before the loop runs (`xencode --agent "/review the auth diff"`); `--commands`
    lists what's available with scope + description.
  - Hooks (`script/src/hooks.rs` + loop wiring in `app/src/agent/run.rs`): `[hooks]` table in `xen.toml` —
    `pre_tool_use` / `post_tool_use` / `stop` script lists + per-hook `timeout_ms` (default 10 s). Each run executes
    in its own isolated QuickJS sandbox (fresh `ScriptRuntime`, memory limit, interrupt-guarded deadline) on a
    dedicated thread behind `spawn_blocking` — a hung hook can never take the loop down. Payload arrives as the `ctx`
    argument (`{ hook, tool, args, cwd }` pre / `{ …, result, ok, ms }` post / `{ hook, answer, iterations }` stop);
    verdicts: `{ decision: "deny", reason }` blocks the tool call (result the model sees), `{ feedback }` joins the
    post-tool result, `{ decision: "block", reason }` pushes a user turn and continues the run. Thrown exceptions are
    non-blocking (matching Claude Code); missing hook files are skipped with a warning. Entry convention: callable
    default export → `main(ctx)` → static default value. Hook runs surface as `AgentEvent::HookRun` rows in the
    chat pane; hook console output streams as `HookLog`.
  - Plan mode + permission modes (`app/src/agent/permissions.rs` + CLI): five modes — `default`, `accept-edits`
    (file edits auto-approved), `read-only`, `plan` (`PlanGate`: reads free, mutations hard-denied with a plan-mode
    message), `yolo` — selected via `--permission-mode <name>` / `--plan` sugar. The plan-mode system prompt demands
    a step-by-step plan as the final answer; on approval (interactive prompt) the whole research transcript carries
    into a second run under the regular gate with a fresh non-plan system prompt. Non-interactive plan runs print
    the plan and stop. Denial text is now gate-specific (`PermissionGate::denial_message`).
  - CLI polish: flags may appear on either side of `--agent "prompt"` (deferred mode resolution — previously
    trailing flags were silently ignored), slash expansion and hook inventory print before the provider key check.
  - Verified: 100 workspace tests green (40 app + 60 script) — memory scopes/budget, command expansion/shadowing,
    hook deny/feedback/block/timeout/short-circuit/main-fallback, plan-mode gate + prompt flagging, end-to-end loop
    integration tests with a `MockProvider`; `--commands` + flag parsing smoke-tested against the real binary and
    all six GUI pages still render.

## Phase 4 — Harness ports, wave 2 (M each)

- **MCP client** — JSON-RPC over stdio; unlocks the existing MCP server ecosystem.
- **Subagents** — tokio-parallel child agent loops with scoped prompts and tool allowlists; custom agents in `.xencode/agents/*.md`; ZCode-faithful UI cards (audit the reference components first).
- **Checkpoints** — shadow-git snapshot before each edit; rewind timeline in the GUI.
- **`xen exec`** — headless mode for CI + session resume/fork.
- Status: **DONE**.
  - MCP client (`app/src/agent/mcp.rs`): JSON-RPC 2.0 over newline-delimited stdio, the
    MCP-spec transport. Servers declared in `.xencode/mcp.json` (project) + `~/.xencode/mcp.json`
    (user), project wins per name; connect spawns each server once per run (initialize handshake +
    `notifications/initialized` + paginated `tools/list` under a 10 s timeout — a failing server is
    skipped with a warning, never fatal), tools join the registry as `mcp__<server>__<tool>` with
    `[mcp:<server>]` descriptions, calls route through `tools/call` (30 s timeout, text content
    flattening, `isError` → tool error) behind a per-server mutex. Transport is a trait
    (`McpIo`) — `StdioIo` (tokio child, `kill_on_drop`) in production, a scripted fake in tests.
    Gate policy: every `mcp__*` tool counts as a mutation (Ask / read-only-deny).
  - Subagents (`app/src/agent/subagent.rs`): custom agents in `.xencode/agents/*.md` (project) +
    `~/.xencode/agents/*.md` (user) — `name`/`description`/`tools`/`model` frontmatter, body is the
    system prompt, project shadows user by name, plus the built-in `general-purpose`. The registry
    exposes one `task` tool; consecutive `task` calls in a turn are fanned out across tokio tasks
    (`run.rs::dispatch_subagents`) and run in parallel — gate/ask/hook verdicts resolve sequentially
    first, approved child loops then race. Children: allowlist gate (out-of-list tools hard-denied),
    no re-prompting (parent consented to the agent as a whole), no session persistence, no hooks,
    no nested `task` (recursion cut off via `allow_subagents: false`), identity prompt appended to
    the system prompt (`system_extra`), their mutations still hit the checkpoint store. Child events
    stream to the UI wrapped as `AgentEvent::Subagent`; the child's final report is the tool result.
  - Checkpoints (`app/src/agent/checkpoint.rs`): shadow git repo (`.xencode/checkpoints/repo.git`,
    bare, dedicated index file) tracks the worktree; a snapshot commits before every mutating tool
    (`write_file` / `edit_file` / `bash`) — `add -A` with `.git`/`.xencode`/build-dir pathspec
    excludes, `-c` identity flags, unchanged worktrees produce no checkpoint, snapshots serialize
    behind a mutex (parallel subagents). Timeline in `meta.jsonl` (`ck_<unix_ms>_<seq>` ids, tool,
    detail, hash); rewind = `read-tree` + `checkout-index -a -f` + deletion of newer files + empty-dir
    pruning, resolved by id / hash prefix / `latest`. CLI: `--checkpoints` prints the timeline,
    `--rewind <target>` restores; `--agent`/`exec`/`--agent-ui` record by default (`--no-checkpoints`
    opts out); `AgentEvent::Checkpoint` renders as timeline rows in the chat pane.
  - `xencode exec` (`agent/mod.rs::run_exec`): the headless/CI contract — never interactive
    (Yolo gate by default, `--read-only`/`--permission-mode` override), `--json` envelope
    (`{answer, session_id, iterations}`), `--max-turns`, `--no-session`, session
    `--continue`/`--resume`/`--fork` reuse, slash commands still expand, hooks/MCP/checkpoints active.
  - Test fixture: `xencode --mcp-test-server` — a real MCP server over stdio (echo/add tools) used
    by the client's end-to-end test through the production transport.
  - GUI: `Entry::Subagent` cards (bot icon, agent name + live status, check/x) and
    `Entry::Checkpoint` timeline rows render through the same ZCode-derived visual language; the
    `--agent-ui` demo script now exercises a subagent dispatch and a checkpointed write.
  - Verified: unit tests for MCP framing/id matching/pagination/error surfaces + a real-stdio
    end-to-end roundtrip against `--mcp-test-server`; agent-file parsing/shadowing, allowlist gate,
    parallel task fan-out with a shared scripted provider; snapshot/rewind/exclusion/serialization;
    `task`+`mcp__*` gate classification; collector lifecycle for cards and rows.

## Phase 5 — Harness ports, wave 3 (M each)

- **Repo map** — tree-sitter symbols, relevance-ranked (Aider-style).
- **LSP diagnostics loop** — compiler errors auto-fed back to the model (OpenCode-style).
- **Recipes** — declarative playbooks with params/subtasks (Goose-style) on QuickJS.
- **Oracle** — stronger-model second-opinion agent (Amp-style).
- **OTel tracing** — tokens, cost, tool spans per run.
- Status: **DONE**.
  - Repo map (`app/src/agent/repomap.rs`): tree-sitter queries extract symbol
    definitions (functions / structs / enums / traits / impls / consts / mods for
    Rust; functions / classes / methods / interfaces / type aliases / enums /
    arrow-const arrow fns for TS; the JS / Python / Go equivalents) with
    first-line signatures and line numbers; identifier walks build a
    file→file reference graph; PageRank (damping 0.85, renormalized) ranks
    the files; the renderer emits Aider-style ranked file blocks
    (`path` + `  L###│ signature`, `… N more` collapse at 15 symbols) under a
    char budget (default 4 096 ≈ 1k tokens, `--no-repo-map` opts out,
    `--repo-map` prints the section). Deterministic: stable sort, path tiebreak.
    Skips `.git`/`.xencode`/build dirs and files > 256 KiB; walk capped at
    3 000 files. Scoped child loops (subagents, oracle) don't get the map.
  - Diagnostics loop (`app/src/agent/diagnostics.rs`): after `write_file` /
    `edit_file` on a source file the project's check command runs once
    (timeout-guarded); diagnostics are diffed against the previous round and
    **new** problems are pushed into the loop as a user turn — the model sees
    the compiler's verdict before finishing. Parsers: `cargo check
    --message-format=json` (primary spans + error codes, warnings included)
    and a generic `regex` format with `file`/`line`/`level`/`message` named
    groups. Auto-detection: `Cargo.toml` → cargo check; local
    `node_modules/.bin/tsc` → `tsc --noEmit --pretty false`; otherwise the
    engine is off. A check tool that fails without parseable output surfaces
    once (never wedges the loop). `AgentEvent::Diagnostics` renders as
    `diagnostics:<source>` tool rows in the chat pane and stderr lines in the CLI.
  - Recipes (`script/src/recipes.rs` parsing/preludes + `app/src/agent/recipes.rs`
    runner): markdown playbooks in `.xencode/recipes/*.md` (project) +
    `~/.xencode/recipes/*.md` (user), project shadows user by name. Frontmatter
    (serde_yaml): `name`, `description`, `parameters` (name/type/required/
    default/description). Body: `## ` sections are subtask steps (a body with
    no headings is one step; a preamble joins the first step); `{{param}}`
    substitution with validation (required present, defaults filled, unknown
    rejected, unresolved placeholder = error); ` ```xen ` fenced blocks run on
    the QuickJS automation core (fresh sandbox per block, dedicated thread,
    60 s deadline, `params` global, console captured) and their output joins
    the step prompt as `[recipe prelude output]` context. Each step is a full
    agent run carrying the previous steps' transcript; the run lands in the
    session store. CLI: `xencode recipe <name> k=v …` (permission flags like
    `--agent`), `xencode --recipes` lists name/scope/steps/params/description.
    New `ScriptRuntime::run_source` evaluates pre-read source (TS stripped,
    hook-free) for the preludes.
  - Oracle (`app/src/agent/oracle.rs`): `oracle` tool in the main loop
    (`question` + optional `context` args) and `xencode --oracle "…"`
    standalone (seeds context from the latest session's tail). The
    consultation is a nested agent loop on the configured model — resolution:
    `XENCODE_ORACLE_MODEL` env > `[oracle] model` in xen.toml > the main
    provider's model (`GlmProvider::from_env_with_model` when they differ) —
    with a read-only allowlist gate (`read_file`/`grep`/`list_dir`), no
    session, no hooks, no nested tasks, 8-iteration cap. Events stream to the
    UI wrapped as `AgentEvent::Subagent { name: "oracle" }` — zero new GUI
    render paths; the verdict is the tool result the parent model sees.
  - Telemetry (`app/src/agent/telemetry.rs`): per-run spans in an OTel-ish
    shape — `agent.run` (iterations, answer chars, recorded on drop so error
    paths keep their span), `llm.call` (model, tokens in/out, estimated flag,
    ms, ok), `tool.call` (tool, ok, ms) — persisted as JSONL to
    `.xencode/telemetry/<run_id>.jsonl` and POSTed at run end as
    OTLP/HTTP(JSON) to `[telemetry] endpoint` / `XENCODE_OTEL_ENDPOINT`
    (3 s timeout, fire-and-forget). Tokens come from the provider's SSE
    `usage` when reported; otherwise an estimate (chars/4, marked
    `estimated`) — cost from the `[telemetry]` price table
    (USD/MTok in + out). CLI prints a one-line summary at run end.
    `Provider` gained `model()` (Arc-forwarded) and `Message` an optional
    `usage` (skipped when absent so old session files load unchanged).
  - Config: `[diagnostics]` / `[telemetry]` / `[oracle]` tables in `xen.toml`
    (`script/src/config.rs`), discovered once per CLI entry via
    `harness_for_project` (hooks + diagnostics + telemetry + oracle model).
  - Verified: 162 workspace tests green (92 app + 70 script) — repomap
    extraction/ranking/budget/ignore rules across all five languages,
    diagnostics parsing/auto-detect/new-problems diffing/tool-failure
    surfacing/timeouts, recipe frontmatter/params/expand/prelude runs and
    shadowing, oracle gate/consult/streaming/model resolution, telemetry
    spans/JSONL/implicit-drop/cost/disabled; `--repo-map` smoke-tested on the
    real repo, `--recipes`/`recipe` param validation and demo `--agent-ui`
    and all six GUI pages re-rendered.

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
- Status: **6.1–6.4 DONE**.
  - New crate `browser/` (`xencode-browser`) — one static dependency tree, no Playwright/Node/Python:
    - Discovery (`discover.rs`): `XENCODE_BROWSER` override → PATH lookup → well-known Chrome/Edge/Chromium/Brave
      install paths (Linux/macOS/Windows) → Playwright browser caches (`chromium-*`, `chromium_headless_shell-*`,
      newest revision first). Managed download stays an explicit follow-up (open decision #2); launch failures name
      the fix instead of failing silently.
    - Launch (`launch.rs`): headless spawn with a lean fleet flag set (`--remote-debugging-pipe`, forced 1.0 device
      scale, `--disable-dev-shm-usage`, mute-audio, no-first-run/no-sync/no-background-networking), the two pipe ends
      wired onto fds 3/4 via `pre_exec` (socketpairs half-duplexed), stderr drained, `kill_on_drop`.
    - CDP connection (`cdp.rs`): transport-agnostic connection over AsyncRead/AsyncWrite halves — NUL-delimited JSON
      framing, id-matched calls with per-call timeouts, a shared pending map owned by the reader task (EOF fails every
      outstanding call instead of hanging), and a broadcast event stream so navigation waiters and the screencast pump
      subscribe independently. Session routing (flatten mode) tested; fake-peer tests run over in-memory duplexes.
    - Spaces (`space.rs`): one Chromium process per Space. `CleanRoom` (throwaway temp profile, wiped on drop) and
      `Inherit { profile }` (copy of a real profile — cookies/sessions kept, caches/crashpads/`Singleton*` locks and
      >64 MiB files skipped, 1 GiB budget). Auto-wait actions: navigate (load-event watch, non-fatal timeout),
      click/fill (CSS / `text=` / `xpath=` selectors resolved by JS with scrollIntoView + element registry tokens,
      `Input.dispatchMouseEvent` + `Input.insertText` so React/Vue state follows), press (virtual-key table),
      scroll, evaluate (`Runtime.evaluate`, RemoteObject unwrapping), screenshot (PNG decode).
    - Snapshot primitive (`snapshot.rs`): `DOMSnapshot.captureSnapshot` (`computedStyles: [display, visibility]`,
      `includeDOMRects`) parsed into a node tree keyed by `backendNodeId`. Wire-shape notes verified against Chrome
      for Testing 143 and tolerant of the documented shape: string table at the result level with **-1 = absent**
      (and index 0 not guaranteed empty), `parentIndex` wiring with `childNodeIndexes` fallback, `nodeType`-gated
      `nodeValue` (only text nodes carry one), 2D `layout.bounds` with a flat-array fallback, computed styles from
      `layout.styles` pairs with a `computedStyles` fallback. Direct `#text` children aggregate onto their parent
      element; invisible subtrees (display:none / visibility:hidden, inherited) prune with their descendants. The
      outline renderer collapses bare wrapper chains, skips `html` ghosts and svg internals, and budget-truncates
      (4 096 chars ≈ 1k tokens, `… N more`-style cap).
    - Diff engine (`diff.rs`): match by `backendNodeId` → added / removed / changed lines (`+ button "Sign in"`,
      `~ p "0 items" → "1 item"`), sub-pixel move jitter ignored (2 px epsilon), invisible plumbing never diffs,
      summary header + budget-capped render, `(no changes)` for identical snapshots.
    - Screencast (`space.rs`): `Page.startScreencast` (JPEG q70) pumped into a `watch` channel of decoded frames;
      **frame-level `sessionId` accepted as number (Chrome ≥ 143) or string** and acked verbatim so Chrome keeps
      producing; `Drop` stops the stream. Frame decoding (`frame.rs`): PNG (all color types) + JPEG (zune) →
      straight RGBA.
    - Verified: 28 unit tests (framing/id-matching/session routing/timeouts/EOF, snapshot parse + outline +
      collapse + budget, diff add/remove/change/jitter/budget, selector parsing, key table, profile-copy skip
      rules, launch flags) **plus 3 real-Chromium e2e tests** (auto-skipped when no browser is installed, green
      where one exists — serialized behind a lock: three parallel Chromees OOM the sandbox): navigate → snapshot
      outline → text-selector click verified by evaluate → fill → press → screenshot decode → snapshot diff with
      exactly one changed node; screencast frame delivery; two parallel clean-room Spaces with isolated state.
  - GUI browser tab (6.2): `ui/browser.rs` — omnibox toolbar (back/forward/reload ghost buttons, scheme icon —
    shield for https, globe otherwise — truncated URL, loading dot), contain-fit screencast viewport blitted
    through the new `Canvas::blit_rgba` (nearest-neighbor scale + rounded-rect mask, letterboxed on black),
    status strip (space kind + frame dims, document title). `BrowserPage` is plain state — the windowed GUI
    consumes the same struct. `xencode --browser-ui <url>` renders the tab with a live frame (real Space +
    screencast; on machines without a browser it renders the empty viewport with the launch error in the status
    line, so the surface stays inspectable). `screenshots/08-browser-tab.png`.
  - Headless CLI (6.1): `xencode browser <url> [--screenshot FILE] [--snapshot] [--json] [--click SEL]
    [--fill SEL=TEXT] [--press KEY] [--scroll DX,DY] [--wait MS] [--space clean|inherit=PATH]
    [--browser-path BIN] [--timeout-ms N] [--headed]` — actions run in order through the same auto-wait path;
    `--space inherit=~/.config/google-chrome` drives logged-in state.
  - Config: none required. `XENCODE_BROWSER` (binary override) is the only env knob.
  - Follow-ups (6.3–6.4): shipped — see below. Managed Chromium download (open decision #2) stays open.

- Status: **6.3 DONE** — the interaction recorder, turning browser interactions into reusable QuickJS automation scripts (north star #4: the browser now exposes hooks to QuickJS):
  - Recorder (`browser/src/recorder.rs`): a bootstrap script rides `Page.addScriptToEvaluateOnNewDocument` into every
    document (one-shot guard, capture-phase listeners so page handlers can't swallow events) and streams raw DOM
    events back through a `Runtime.addBinding("xenRecord")` binding. Listened interactions: clicks on actionable
    elements (button/a/role=label/summary/input/[onclick]), `change` commits on text fields + `<select>`,
    Enter/Escape/Tab keydowns, debounced scroll positions. The Rust-side pump folds raw events into deterministic
    [`RecStep`]s via the pure [`RecorderState`]: a focusing click followed by `change` collapses into one `Fill`
    (the click is replay noise), checkbox/radio clicks keep their `Click` and swallow the trailing `change`,
    `<select>` commits become `Select`, navigations within 2 s after a click/press are dropped (the replayed click
    navigates on its own), scroll bursts coalesce into one absolute `ScrollTo` per final position, 500-step cap.
    Password fields (`type=password`) are redacted by default — the emitted script carries a set-before-replay note
    (`--no-redact` keeps values).
  - Selector strategy: high-signal CSS wins (`#id` / `[data-testid=…]` / `tag[name=…]`), falling back to a
    `text=` selector (own-text match, 60-char cap) when the path is structural, else the nth-of-type CSS path
    (depth-capped 6, `CSS.escape`d).
  - Script emission ([`to_quickjs_script`]): the folded steps render as a `xen.browser` sequence — `navigate`,
    `click`, `fill` (JSON-escaped values), `select`, `press`, `scrollTo` — with a header (recorded-at/source/step
    count/redaction note, replay hint `xencode browser run <file>`) and a trailing `xen.browser.snapshot()` so the
    replay prints the final page outline; the script's `export default` carries `{recording, steps}`.
  - `xen.browser` QuickJS surface (`script/src/host/browser.rs`): plain-data [`BrowserCommand`] vocabulary
    (Navigate/Click/Fill/Select/Press/Scroll/ScrollTo/Wait/Snapshot/Screenshot/Url/Title/Eval/Close) +
    [`BrowserReply`] (Unit/Text/Json) over the injectable [`BrowserDriver`] trait — the script crate stays
    browser-free, the host installs the driver (same bridge pattern as `xen.agent`/`xen.ui`). Registered as the
    `xen/browser` ESM module and the `xen.browser` global namespace. Permission gates: `navigate` passes the `net`
    gate (host allowlist; hostless schemes report their scheme name so scoped setups get a consent prompt, not a
    parse error), `screenshot(path)` passes the `fs` write gate, no-path screenshots reply base64 PNG.
    `RuntimeOptions.browser` / `ScriptRuntime.browser` install or clear a driver; without one the functions fail
    with a clear setup message.
  - App-side driver (`app/src/browser_driver.rs`): [`SpaceDriver`] implements the trait over a real Space — lazy
    launch on first command, every command spawns onto the captured main-runtime `Handle` (tokio IO resources are
    runtime-bound; the script thread's current-thread runtime must never poll the browser pipes), replies stay
    plain data. Screenshot writes PNG files (or base64); `Close` drops the Space (kill_on_drop + clean-room wipe).
  - CLI (in `app/src/main.rs`): `xencode browser record <url> --out FILE [--no-redact] [--headless] [--timeout-ms
    N] [--space clean|inherit=PATH] [--browser-path BIN]` — headed by default (a human drives), ends on Ctrl-C /
    window cap / browser close, emits the replay script with a recorded-at UTC header; `xencode browser run
    <script.ts> [--headed] [--json] [--yes] [--timeout-ms N] [--space …] [--browser-path BIN]` — runs the script
    through the SpaceDriver bridge: dedicated thread + current-thread runtime + LocalSet (hook-runner pattern),
    permissions from `xen.toml` with stdin y/n/a consent (`--yes` or non-terminal stdin session-allows, CI-safe),
    console events stream to stderr, the default export prints as JSON (`--json` wraps `{ok, result, ms}`).
  - Verified: 10 recorder unit tests (click+change collapse, checkbox click survival, nav-after-click drop,
    scroll coalescing, selector preference, emission with redaction note + JSON escaping, step cap, bootstrap
    coverage) + 4 `xen.browser` tests (command round-trip through a scripted driver, no-driver setup message via
    JS-level catch, invalid-URL diagnosis, url_host parsing incl. file/about) + 1 real-Chromium e2e (recorder on
    a live fixture: text fill, redacted password fill, save click → folded steps → emitted script). Live CLI
    verified end-to-end: record (headless, window cap) → emitted script → `browser run` replay printing the
    outline; a hand-written script exercised navigate/title/scrollTo/eval/screenshot-to-file/fill in one run
    (~1 s).
- Status: **6.4 DONE** — the scraping engine and the headless fleet:
  - Extraction (`browser/src/scrape.rs`): [`ScrapeSpec`] — an optional repeating `item` selector with per-row
    [`FieldSpec`]s (`name=selector[@attr]`, attr ∈ href/src/html/value/…, no attr = trimmed text; value for form
    controls), page-level fields (merged into every row), next-page selector pagination (max-pages cap, real
    click through the auto-wait path), infinite scroll (rounds × scroll-dy, early exit when the page height
    stabilizes) and a settle wait. The extraction runs as one `Runtime.evaluate` per page (fields resolve relative
    to each item element, missing fields become null instead of dropping the row) and returns JSON rows — Rust
    only validates. Pure export writers over the row maps: `to_json` (pretty), `to_csv` (RFC 4180 — quoting on
    comma/quote/newline, embedded quotes doubled, CRLF rows), `to_markdown` (GFM table with pipe escaping);
    columns are the union of row keys in first-seen order (serde_json maps iterate alphabetically).
  - Fleet (`browser/src/fleet.rs`): W parallel worker Spaces (one Chromium process each — no GIL, no shared
    state), an atomic URL queue keeping submission order in the results, a fleet-wide [`RateLimiter`] (rps cap;
    slots reserved before sleeping so workers queue instead of racing), per-URL deadline (timeout → that URL
    fails, the worker continues), per-URL fail-soft error collection (a worker launch failure fails only the URLs
    it claimed). [`PageResult`] carries url/ok/rows/pages/error/ms; [`run_fleet`] returns results in submission
    order for deterministic exports.
  - CLI (`xencode scrape`): `xencode scrape <url> [--item SEL] [--field NAME=SEL[@ATTR]]… [--page-field …]…
    [--next SEL] [--max-pages N] [--scroll N] [--scroll-dy N] [--wait MS] [--format json|csv|md] [--out FILE]
    [--json]` for a single page, `--urls FILE` (one URL per line, `#` comments) + `--workers N` + `--rps F` for
    the fleet — rows print in the chosen format (or land in `--out`), per-URL failures stream to stderr, `--json`
    emits the machine envelope `{pages, rows, ms, failed, urls, data}`.
  - Verified: 8 scrape unit tests (field-spec parsing incl. `@`-inside-selector safety, spec validation,
    extraction-JS embedding, CSV quoting/union columns, markdown pipe escaping, JSON round-trip, column order,
    number formatting) + 3 fleet unit tests (rate limiter spaces parallel reservations, limiter off when unset,
    URL-list parsing) + 2 real-Chromium e2e (single-page extraction with attrs/page-fields → pagination across a
    two-page fixture → CSV/Markdown exports; a 2-worker fleet over 2 URLs with ordered results). Live CLI
    verified: single-page md/csv exports (file out), 2-worker fleet with the `--json` envelope (4 rows, 0 failed,
    ~1.7 s).


## Phase 7 — XenRE (L)

Goal: decompile apps and binaries — APK, IPA, XEX, ELF/PE/Mach-O, JAR, .NET — from inside XenCode.

- **Native Rust layer:** format parsers (goblin/object for ELF/PE/Mach-O; custom DEX; custom XEX header/section/resource parser + decompression), capstone disassembly (ARM, ARM64, x86, PPC/Xenon).
- **Orchestrated backends (managed downloads, on-demand):** Ghidra headless, jadx (Android), ILSpy (.NET), RetDec — output lands in editor tabs; a fully native decompiler is a later R&D track.
- **GUI panels:** hex viewer, strings, imports/exports, function list, decompiled code tabs — drawn natively.
- **Compliance guardrail:** analysis of authorized/user-owned targets only; no DRM keys or circumvention tooling bundled (XEX decryption keys are never shipped).
- Milestones: **7.1** ELF/PE/Mach-O + hex/strings panels → **7.2** APK/IPA + jadx orchestration → **7.3** XEX parser + PPC disasm + Ghidra loader flow.
- Status: **7.1 DONE** — shipped as a new `re` crate (`xencode-re`) plus CLI, GUI and script surfaces:
  - Format analyzer (`analyzer.rs`): goblin parses ELF / PE / Mach-O (plus COFF, TE, fat-Mach detection) into one unified [`BinaryInfo`] — arch/endianness/bitness, kind, PIE, entry point, image base, sections with rwx/debug/tls flags, symbol tables (ELF `.symtab` + `.dynsym`, raw COFF records for PE images, Mach-O nlist), imports (dynamic undefined syms / PE import table / dyld bind info) and exports (defined dynsyms / PE export directory / LC_DYLD export trie with file-offset→VA mapping), DT_NEEDED/import-DLL/LC_LOAD_DYLIB library lists (Mach-O dylib paths read from raw load commands since goblin only carries the name offset), and a va↔file-offset map built from PT_LOAD segments / Mach-O segments / the PE section table that every other view shares. 512 MiB input cap.
  - Strings (`strings.rs`): `strings(1)`-style runs with file offsets — ASCII (0x20–0x7e) plus UTF-16LE pair runs, detected first (≥2 consecutive pairs so lone `(printable, 0)` bytes don't swallow ASCII), configurable min length.
  - Hex view (`hexview.rs`): offset/hex/ASCII-gutter rows, width-configurable, ASCII gutter padded so tail rows stay column-aligned; the same rows feed the CLI and the GUI panel.
  - Disassembly (`disasm.rs`): capstone 0.14 — x86 (16/32/64), ARM, Thumb, ARM64, PPC/PPC64 big-endian (Xenon-ready); arch auto-detection from `BinaryInfo`, `--arch` override with aliases (`x64`, `aarch64`, `xenon`), objdump-style rendering.
  - Function list (`functions.rs`): symbol-table functions merged with exports and the entry point (`<entry>` synthetic label), deduped by address, sorted, with an exec-section filter for the GUI rail.
  - CLI (`xencode re`, in `app/src/main.rs`): `info <FILE> [--json]` (summary + section table), `strings <FILE> [--min N] [--utf16] [--json]`, `hex <FILE> [--offset 0x…] [--len N] [--json]`, `symbols|imports|exports <FILE> [--json]`, `disasm <FILE> [--offset N | --entry] [--len N] [--arch X] [--json]` — offsets accept `0x` hex, default disasm window is the entry point (4 KiB), output paginated with "N more" tails.
  - QuickJS hooks (`xen.re`, in `script/src/host/re.rs`): `analyze(path)` → full `BinaryInfo`, `strings(path, {minLen, utf16Only, limit})`, `hex(path, {offset|va, len, width})`, `symbols(path, {limit})`, `imports(path)`, `exports(path)`, `functions(path, {limit})`, `disasm(path, {va|offset, len, arch, max})` — every call passes the `fs` read permission gate, results are plain JSON-shaped objects via the shared `jsvalue` bridge. Registered as both the `xen/re` ESM module and the `xen.re` global namespace; promise-aware `eval_json_async` added to the runtime (with real JS exception surfacing via `ctx.catch()` — messages, not bare markers).
  - GUI (`app/src/ui/re.rs`, `xencode --re-ui <FILE> [--out PNG]`): the native binary inspector — toolbar with file-code icon, basename, format/arch chips (ELF · x86_64) and view tabs; left rail with sections (exec sections highlighted) + function list (sky-mono addresses); four views — Hex (12 rows from the entry point's file offset, sky offsets), Strings (`u16` chips on UTF-16 runs), Symbols (kind chips), Imports (`dll!name`); status strip with format/kind/counts/size/entry. Rendered from the real analyzer — the screenshot ships as `screenshots/09-re-tab.png`.
  - Verified: 28 crate tests (23 unit + 5 e2e) — byte-exact handcrafted ELF64 / PE64+ / Mach-O arm64 fixtures pushed through the whole pipeline (sections, symbols, address round-trips, entry-point disasm, strings, hex alignment, PIE flags), garbage/directory/truncation rejection, and a real stock system ELF; 1 QuickJS runtime test (`xen.re` end-to-end inside the sandbox: analyze → disasm `ret` at the entry → strings → functions); 4 UI tests (all four views + empty states + size/ellipsis helpers); CLI verified live against the 233 MB `xencode` binary (44 sections, 155k symbols, 261 imports, entry-point `_start` disasm).
- Status: **7.2 DONE** — the app-package surface, all native Rust plus the first orchestrated backend:
  - Plist (`plist.rs`): binary `bplist00` parser (trailer offsets, nib-encoded objects — int/real/date/data/ASCII/UTF-16/array/set/dict/UID, 0xF length escape via an int object, two's-complement 8-byte ints) plus a minimal XML-plist walker (six primitives + self-closing `<true/>`, entity decoding, whitespace-tolerant base64) into one [`PlistValue`] tree; Core-Data-epoch dates rendered ISO.
  - AXML (`axml.rs`): Android binary-XML chunk walker — string pool (UTF-8 flag 0x100 + UTF-16, 1–2-byte length prefixes), start/end tag chunks (ResXMLTree_attrExt at +16, attributes at 16+attributeStart, 20-byte records) rebuilt into an element tree with typed values (string/int/hex/bool/reference) and namespace-free attribute names; `find_all`/`children_named`/typed accessors feed the manifest reader. Tolerant chunk-size validation (truncation/garbage rejected).
  - DEX (`dex.rs`): header-table parser (112-byte header, size/off pairs at the spec offsets) — strings (uleb128 + MUTF-8), types, protos, fields, methods, class defs with per-class `class_data` (uleb128 delta lists) → descriptor, super, access flags, direct/virtual counts and ACC_NATIVE method names; 256 MiB cap, counts exact while the listing caps at 8 192 classes.
  - APK (`apk.rs`): one pass over the zip — manifest via AXML (package/versionCode/versionName/minSdk/targetSdk/compileSdk from `uses-sdk` or manifest attrs, permissions in manifest order, components activity/service/receiver/provider with exported + launcher detection via MAIN-action + LAUNCHER-category intent filters), every `classes*.dex` parsed (with a descriptor→native-method map folded back onto components as a JNI-surface hint), `lib/<abi>/*.so` inventory → sorted ABI list, resources.arsc/assets stats, and signing detection: v1 (META-INF cert members) plus the APK Signing Block (size-pair validation + id-value walk: 0x7109871a → v2, 0xf05368c0 → v3, 0x1b93ad61 → v3.1). Structural sniff `is_apk` walks central-directory names without inflating.
  - IPA (`ipa.rs`): `Payload/*.app` detection, binary-or-XML Info.plist through [`plist`], executable name (CFBundleExecutable, bundle-basename fallback), embedded Mach-O pushed through the 7.1 analyzer (thin parse; failures degrade softly to a `universal` marker), frameworks/appex bundle lists, mobileprovision + code-signature presence, uncompressed-size totals. 512 MiB cap shared with the analyzer.
  - jadx orchestration (`jadx.rs`) — the plan's first "orchestrated backend (managed downloads, on-demand)": probe (managed `<root>/<version>/bin/jadx` newest-first, then PATH; version via `--version` with a timeout; Java runtime detection incl. major-version gate refusing < 11 with install guidance), `ensure()` with explicit opt-in download (GitHub releases API → `jadx-<semver>.zip`, extract with unix exec-bit preservation, install marker), a redirect-scrape fallback (`releases/latest` → tag → asset URL) that survives GitHub-API 403 rate limits, and `run()` — headless `jadx -d <out> [--no-res] [--deobf] [-j N] <apk>` via std::process with stderr warning curation (progress spam filtered), output inventory (`sources/**.java` capped listing + exact totals, `resources/**` count) and elapsed/exit reporting. Install root `XENCODE_TOOLS`/`~/.xencode/tools/jadx`; Java override `XENCODE_JAVA`. ureq+rustls (blocking) keeps the crate tokio-free; the fetch closure is injectable so tests never touch the network.
  - CLI (`xencode re apk|ipa|decompile`, in `app/src/main.rs`): `apk <FILE> [--json]` (identity/sdk/perms/components with launcher+exported+jni tags/dex table/native libs/signing), `ipa <FILE> [--json]` (bundle identity, arch, embedded-binary line, frameworks/plugins/provisioning), `decompile <FILE> [--out DIR] [--download] [--no-res] [--deobf] [-j N] [--max-files N] [--json]` — refuse non-APKs up front, print the resolved backend line, list decompiled classes with an "N more" tail, exit non-zero on jadx failure.
  - QuickJS hooks (`xen.re`, in `script/src/host/re.rs`): `apk(path)` → ApkInfo, `ipa(path)` → IpaInfo, `decompile(path, {outDir?, noRes?, deobf?, download?, threads?, maxFiles?})` → JadxRun — fs read gate + 512 MiB cap for the analyzers; decompile additionally gates `fs` write on the out dir, `proc` on the jadx spawn, and `net` on api.github.com/github.com/objects.githubusercontent.com when `download: true`; blocking work rides `spawn_blocking`. Registered in the `xen/re` module and the `xen.re` global namespace.
  - GUI (`app/src/ui/re.rs`, `xencode --re-ui <FILE>`): a fifth **Package** tab — identity rows (mono keys, sky) and a wrapping chip flow (permissions subtle, components sky, ABIs/signing green, dex stats subtle), rendered from the real analyzers; APK/IPA files open on Package directly (plain binaries still open on Hex), IPA embedded binaries keep the symbol/section rail alive. Screenshot ships as `screenshots/10-re-package.png`.
  - Verified: 19 new `xencode-re` tests (hand-built fixtures pushed through the whole pipeline — byte-assembled binary plists incl. escaped-length/UTF-16/negative-int objects, compiled AXML manifests in both pool encodings, a handcrafted DEX with a native method, full APK/IPA zips with signing blocks; jadx release-asset parsing, ensure/download flow, fake-jadx run inventory, exec-bit extraction, java-major gate) plus a QuickJS runtime test (`xen.re.apk` end-to-end inside the sandbox + clean `decompile` error on non-APKs) and 5 GUI tests (all five views + empty states). Live CLI verified end-to-end against generated APK/IPA fixtures and the real GitHub backend: managed download resolved jadx 1.5.6 (via the rate-limit fallback), extracted with exec bits, probed green, and the missing-JRE gate refused with a clear message.
- Status: **7.3 DONE** — the XEX container surface, PPC64/Xenon disasm upgrade, and the second orchestrated backend (Ghidra headless):
  - XEX parser (`xex.rs`): keyless structure parse of XEX2 (plus XEX1/XEX0 sniffing with the legacy security layout) — main header, the full optional-header walk with the low-byte size rule (`0x00/0x01` inline, `0xFF` size-prefixed struct, `n` → `n*4` raw), FILE_FORMAT_INFO (encryption/compression enums + basic block table + LZX window), EXECUTION_INFO (title id + publisher tag, MSB-first version bitfields, disc/savegame), ENTRY_POINT/IMAGE_BASE (read verbatim — no flag bit, matching xenia + zeroKilo), IMPORT_LIBRARIES string table, STATIC_LIBRARIES records, RESOURCE_INFO, TLS, checksum/timestamp; security block (image_size, load_address, image flags, region, media id) with page descriptors (`type = v & 0xF`, `page_count = v >> 4`, 20-byte digests ignored) merged into sections (header pages advance the address counter but never surface). Key-bearing headers (LAN_KEY, DELTA_PATCH) are parsed-but-never-decoded; XexInfo serializes no key material.
  - Decompression: raw + basic (`(data,zero)` run-length pairs, zero-fill tail) for unencrypted payloads — the formats actually reachable without keys (homebrew/devkit). AES-encrypted payloads refuse with the compliance message (structure still parses — headers are plaintext); LZX (`normal`) and delta refuse with guidance (retail LZX files are AES-encrypted regardless, and a from-scratch MS-PATCH decoder without a conformance corpus would be a correctness gamble). `payload_image()` gives every byte view the flat image + a BinaryInfo whose va↔offset map speaks image offsets (format `XEX`, arch PPC64 BE, sections `.xex-code/.xex-data/…` with exec/write flags, libs = import names).
  - PPC disasm: the `xenon` alias now maps to PPC64 big-endian (the real Xenon ISA — the old 32-bit mapping predated XEX-driven arch detection); XEX images auto-disassemble as PPC64 BE via `arch_from_binary`. Module doc notes capstone covers the common ISA; VMX128 extensions are not special-cased.
  - Ghidra orchestration (`ghidra.rs`) — the plan's second managed backend: probe (managed `<root>/<ver>/ghidra_*/support/analyzeHeadless` newest-first, then PATH; version from `Ghidra/application.properties`, no spawn), Java gate (JDK 21 for 11+, 17 for 10.x, with JAVA_HOME pinning from the detected runtime), `ensure()` with explicit opt-in download (GitHub API → `ghidra_<v>_PUBLIC_*.zip`, plus an `expanded_assets/<tag>` scrape fallback that survives API rate limits — the asset URL is not derivable from the tag), zip extraction with exec bits, install marker. `run()` drives `analyzeHeadless` with a bundled Java post-script (`XenDumpDecomp.java`, include_str!'d and written next to the output): decompiles every function via `DecompInterface` into `<out>/*.c` with per-file `name_addr.c` naming, capped + inventoried exactly like jadx's `.java` output; `-deleteProject` keeps the workspace clean. **XEX loader flow**: XEX inputs are flattened by `xex::decompress` first and imported as raw binaries with `-loader BinaryLoader -processor PPC:BE:64:default -loader-baseAddr <load address>` — Xenon code lands at its real VAs (non-XEX inputs import natively).
  - CLI (`xencode re xex|ghidra`): `xex <FILE> [--json] [--extract DIR]` (structure summary incl. module/codec/identity rows + section table; `--extract` materializes `image.bin` + one file per section; encrypted files still summarize, with the guardrail note), `ghidra <FILE> [--out DIR] [--download] [--max-files N] [--max-fns N] [--timeout-sec N] [--lang ID] [--json]` (backend line, decompiled-function count, warnings, capped listing; non-zero exit on Ghidra failure). `re disasm`/`re strings` on XEX inputs ride the decompressed image via `re_analyze`'s XEX path; `re hex` stays a raw container view (headers are plaintext and worth reading).
  - QuickJS hooks (`xen.re`): `xex(path)` → XexInfo (fs read gate; keyless so encrypted files work), `ghidra(path, {outDir?, download?, maxFiles?, maxFns?, timeoutSec?, lang?})` → GhidraRun (fs read + write gates, `proc` gate on the analyzeHeadless spawn, net gate on api.github.com/github.com/objects.githubusercontent.com when `download: true`; blocking work on `spawn_blocking`). `load_and_analyze` swaps byte views to the decompressed image for XEX so `analyze/disasm/strings/functions/hex` all speak image offsets.
  - GUI (`app/src/ui/re.rs`, `xencode --re-ui <FILE>`): a sixth **XEX** tab — identity rows (magic, image@load, entry, codec, title, version, disc, pe name) and a wrapping chip flow (sections green/sky by page type, imports sky, static libs + resources subtle, encrypted guardrail chip), rendered from the real parser; XEX files open on XEX directly, the sections/function rail stays live via the bridge BinaryInfo. Screenshot ships as `screenshots/11-re-xex.png`.
  - Verified: 15 new `xencode-re` unit tests (hand-built XEX2 fixtures: raw/basic/compressed reconstruct, 4 KiB page flag, section merge incl. header-page skipping, encrypted/LZX/delta refusals with the exact guardrail wording, DLL module flag → shared library, bridge VA↔image-offset round trips, garbage/truncation/overflow rejection) + 3 e2e tests (XEX through `analyze_bytes` → `payload_image` → entry disasm `li r3,1; blr` + strings + functions; encrypted structure-parse/refusal split; basic-compression end-to-end), 12 ghidra tests (release JSON + assets-fragment parsing, ensure/consent/download flow with a fake release zip, java-major floor, fake-analyzeHeadless run with the XEX raw-loader arg contract, JAVA_HOME derivation), 1 QuickJS runtime test (`xen.re.xex` structure + `disasm` on the image + `analyze` reporting xex/ppc64/big + clean `ghidra` error), 1 GUI test (XEX view rows/chips + all-six-tab sweep). Live CLI verified against a generated XEX fixture: `re xex` summary, PPC64 entry disasm, image strings, `--extract`, and the Ghidra missing-backend error message.
## Phase 8 — API Discovery (M)

Goal: "easily get any website API."

- Built on XenBrowser network capture (6.2): record every XHR/fetch → **endpoint catalog** (URL, method, params, headers, auth, response shape).
- **Copy as:** cURL / fetch / reqwest / QuickJS.
- **OpenAPI auto-generation** from observed traffic; JSON schema inference.
- **Endpoint monitor** — re-capture → diff → alert via automation; secret redaction by default.
- Status: **DONE** — shipped in the `browser` crate (`netcapture.rs` + `apidisco.rs`) and wired into the CLI as `xencode api`:
  - Network capture (`netcapture.rs`): `Network.enable` on the Space's session + a pump task folding CDP events into
    `NetEntry` exchange records — `requestWillBeSent` opens an entry (method/URL/headers/post data/initiator), a
    `redirectResponse` splits redirect chains into hop entries (status/mime copied from the redirect response),
    `responseReceived` fills status/mime/headers, `loadingFinished` fetches response bodies (`Network.getResponseBody`,
    JSON/text/xml only, 256 KiB per-entry cap, 8 MiB session budget) and non-inlined post data
    (`Network.getRequestPostData`), `loadingFailed` records errors, `requestWillBeSentExtraInfo` sets cookie-auth
    presence flags (cookie values are never copied). The pump is split into a pure [`CaptureState`] event-fold
    (unit-testable without a browser) plus the thin async task around it; `NetCapture::stop()` disables the domain and
    returns entries in first-seen order; entry cap (4 000) guards runaway pages. 9 unit tests.
  - Endpoint catalog (`apidisco.rs`): entries filter to API traffic (XHR/Fetch/Ping/EventSource/Other, JSON-mime or
    JSON-bodied unclassified; page assets — Document/Script/Stylesheet/Image/Font/Media/Manifest/WebSocket — and 3xx
    hops and failures excluded) and group by (method, `scheme://host`, templated path) — numeric/float segments →
    `{id}`, UUIDs → `{uuid}`, ≥16-char hex → `{hex}`, mixed-case tokens (≥20 chars, ≥2 digits) → `{token}`. Each
    [`Endpoint`] merges observed statuses, query params (per-param merged scalar schemas + observation counts),
    request/response JSON shapes, auth kinds (Bearer / API-key / cookie presence / sensitive query token), notable
    request headers (50-name noise list filtered) and a concrete redacted example entry for replay. 12 unit tests.
  - JSON schema inference: value → JSON-Schema-ish shape (integer/number/boolean/null, string with light format
    heuristics — date-time (RFC3339-ish / date), uuid, uri, email — arrays merge item schemas over ≤32 samples,
    objects with properties + required intersection), [`merge_schema`] deepens same-type shapes and falls back to
    `anyOf` on conflict; one-line briefs (`object{id: integer, tags: string[]}`) for rendering and monitor reports.
  - OpenAPI 3.1 export: servers/tags from observed hosts, one operation per (path, method) with path params from the
    template, query params (required iff observed in every sample), `requestBody` when a JSON body was seen, responses
    per observed status with inferred response schema and canonical reasons, and `components.securitySchemes`
    (bearerAuth / apiKeyHeader / cookieAuth / queryToken) wired as operation-level security.
  - Copy-as: cURL (shell-quoted, line continuations, `--data` for JSON bodies), fetch (options object; plain GETs
    collapse to one line), reqwest (`.header`/`.body(r#…#)` chain with raw-string delimiter collision handling),
    QuickJS (`xen.http.fetch` — object bodies ride the auto-JSON path). Redacted values carry through so users refill
    them; noise headers stripped.
  - Secret redaction by default: 15 sensitive header names + 20 sensitive query param names → `***` (bodies are left
    intact for schema integrity — captures are the sharing boundary, documented); `--no-redact` escape hatch. Capture
    artifacts (`CaptureFile`) record `redacted: true/false` and the monitor warns on baseline/run mismatches.
  - Endpoint monitor: [`diff_catalogs`] compares endpoint-by-endpoint (identity method+base+path) → added / removed /
    changed (statuses, request/response shape, auth) with old/new shape briefs in the report; `alert_json()` is the
    machine envelope (`alert: true/false`) automation consumes. `xencode api diff` and `xencode api monitor` exit
    non-zero on alert, so cron/CI/webhook triggers alert without parsing output — the QuickJS trigger story wires
    straight onto the exit code + JSON.
  - CLI (`xencode api`, in `app/src/main.rs`): `capture <url> [--click/--fill/--press/--scroll/--wait interaction
    steps] [--no-bodies] [--no-redact] [--out FILE] [--openapi FILE] [--json]` (Space kinds, browser-path, timeouts,
    headed flag shared with `browser`), `show <FILE> [--json]`, `openapi <FILE> [--out] [--title]`,
    `copy <FILE> <N> [--as curl|fetch|reqwest|quickjs]`, `diff <OLD> <NEW> [--json]`, `monitor <url> --baseline FILE
    [--update-baseline] [--json]` — capture always gives post-load XHRs a 1.5 s settle window (a trailing `--wait`
    adds more) so client-rendered apps still land their calls.
  - Verified: 21 new unit tests (event pairing, redirect hops, auth detection, redaction, entry caps, body budget,
    URL templating, schema merge, catalog grouping/filtering, OpenAPI shape, all four copy-as flavors incl. shell/
    raw-string escaping, monitor diff + alert envelope) **plus a 4th real-Chromium e2e**: local CORS JSON server +
    page `fetch()` → captured entry (method/status/query/body) → catalog endpoint with inferred response shape →
    curl snippet with the concrete URL → OpenAPI path → monitor self-diff with no changes → capture artifact
    round-trip. Live CLI verified against jsonplaceholder.typicode.com: `/users/1` + `/users/7` collapsed into
    `/users/{id}`, deep response schemas (nested `address`/`company`, `email<email>` format), monitor flagged an
    added/removed endpoint pair and exited 1.

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
| 15 | Browser tool + recorder + scraping | Cline / ego(lite) | XenBrowser | 6.1–6.4 ✅ |

## Decisions

1. **Execution order** — as in the phase map; reshuffle on request.
2. **Open:** Chromium strategy — auto-detect system Chrome/Edge first, managed download fallback (recommended).
3. **Open:** RE backends (~1 GB) — on-demand download prompt (recommended) vs skip external backends in v1.
4. Phase 0 + Phase 1 start together on "go".
