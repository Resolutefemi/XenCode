# CLAUDE.md

AI collaboration rules for this repository (read by Claude Code, XenCode, and other AI coding assistants); they take precedence over the default general rules.

## Basic Conventions

- **Always respond in English.**
- Use the existing file tools for reading and writing files; do not substitute `cat` / `sed` / `echo >`.
- **Never use em-dashes (—) in user-facing content**: README, docs-site pages, UI copy, code comments and commit messages use commas, colons or plain hyphens instead.
- Modules: `:core` (shared JVM agent runtime), `:cli` (Clikt CLI), `:desktop` (Compose Desktop app), plus the Rust workspace under `rust/` (`xencode-desktop` egui rewrite, `xencode-cloud` Axum backend).
- The mobile app lives in [Resolutefemi/XenAI](https://github.com/Resolutefemi/XenAI); do not add Android code here.

## Build & Verification

**After changing compiled code → run the matching smoke build and tests before pushing.** Skip for docs-only changes.

| Purpose | Command |
| --- | --- |
| JVM smoke build | `./gradlew :desktop:compileKotlin :cli:compileKotlin` |
| JVM unit tests | `./gradlew :core:test :cli:test :desktop:test` |
| CLI distribution | `./gradlew :cli:installDist` |
| Rust smoke build | `cargo check --workspace` (run inside `rust/`) |
| Rust tests | `cargo test --workspace` |
| Rust lint gate | `cargo clippy --workspace --all-targets` must be warning-free |

- Kotlin: JDK 21, Kotlin 2.2.x, Compose Multiplatform 1.9.x.
- Rust: stable toolchain; new code must compile with zero warnings (`#![deny(warnings)]` in CI).

## Architecture Map

- **`core/`** (shared by CLI and desktop): `provider/` (ChatClient over OpenAI-compatible / native Anthropic / native Gemini, PcConfigStore at `~/.xencode/pc-config.json`), `agent/` (AgentRuntime, AgentSession tool loop, SlashCommands, GoalRunner with parallel swimlanes, Worktrees), `tools/` (LocalTools, Permissions deny>ask>allow, Hooks, TodoStore, Compactor), `state/` (SessionStore, CheckpointStore, DecisionStore, XenEventLog ndjson), `goal/` (GoalDag + GoalDagFile at `.xencode/GOAL_DAG.json`), `mcp/` (stdio MCP client), `mjs/` (embedded .mjs automation engine), `prompt/` (SystemPromptBuilder), `telemetry/` (XP ledger, streaks).
- **`desktop/`**: Compose panes, one `DesktopAgentController` per (provider, workspace) built on AgentRuntime; NavRail destinations Chat / Goals / Progress / Automation / Settings; state via StateFlow + collectAsState, no Android lifecycle machinery.
- **`cli/`**: one command per file under `cli/src/main/kotlin/com/xencode/cli/`, shared Stores/Workspace helpers.
- **`rust/crates/xencode-desktop/`**: egui app; `theme.rs` tokens, `markdown.rs` (pulldown-cmark to LayoutJob), `panes/` (Git, terminal, files side panes), `cloud.rs` (XenCloud client).
- **`rust/crates/xencode-cloud/`**: Axum service; `auth.rs`, `gateway.rs` (SSE proxy + key protection), `metering.rs` + `billing.rs` (usage, credits, Stripe), `sessions.rs` (Postgres sync), `routing.rs` (model routing + server-side prompts).

Hard rules:

- Provider keys in `PcConfig` are user-owned; XenCloud master keys live only in server env vars and are never returned by any endpoint.
- The XP curve (`100·n²`), `.xencode/` layout and event names are shared contracts with XenAI; change them only in a coordinated way.
- Desktop chat persists sessions through SessionStore (`.xencode/sessions/`), same format as the CLI.

## Git Commit Conventions

Conventional Commits (validated locally by `.githooks/commit-msg`), format `<type>(<scope>): <subject>`, with the body separated by a blank line.

- **type** ∈ `feat | fix | refactor | docs | style | chore | ci | build | perf | test`
- **scope** optional: `agent | ui | cli | core | desktop | rust | cloud | docs | build | deps`
- **subject** a one-line summary, no trailing period. E.g. `fix(cloud): reject chat requests when credits are exhausted`
- Use `git commit --no-verify` to skip validation only in emergencies.

## Branch Workflow

Releases are tag-driven (`pc-v*` tags trigger `pc-release.yml`); day-to-day commits on `main` do not affect the release package.

- **New features / complex multi-file changes / architectural refactors** → open a `feat/xxx` or `refactor/xxx` branch, merge back into `main` once verification passes.
- **Routine bug fixes / added unit tests / CI and build config / docs-only** → commit directly to `main`.
- **Clean up branches after merging**: `git branch -d <branch>` once merged; delete pushed branches with `git push origin --delete <branch>`.

## Docs Sync

- User-facing feature changes → check `docs-site/docs/` (single source of truth, deployed by `docs-deploy.yml` on tags) and the sidebar in `docs-site/.vitepress/config.ts`.
- Agent prompt or tool-signature changes → update the prompt sources referenced by `SystemPromptBuilder`.
- README changes that add/remove build commands must also update this file's Build & Verification table.

## Releases

1. Merge the work into `main` and verify: `./gradlew :core:test :cli:test :desktop:test` plus `cargo test --workspace` inside `rust/`.
2. Tag `pc-vX.Y.Z` and push the tag; CI builds CLI dists for all three OSes, desktop installers (deb+rpm / msi+exe / dmg) and attaches them to one GitHub Release.
3. Docs site deploys on the same tag via `docs-deploy.yml`.
