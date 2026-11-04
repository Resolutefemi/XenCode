Create or update `AGENTS.md` for this codebase (improve in place if the root already has one; if only `CLAUDE.md` exists, improve `CLAUDE.md`; if neither exists, create `AGENTS.md`).

The goal is a lean instruction file that helps later AI sessions make fewer mistakes and get up to speed quickly. Every line must answer "without this, would the AI likely go wrong?" Delete anything that cannot.

User-specified focus or constraints (must follow):
$ARGUMENTS

## What to read first
Prefer the highest-value sources:
- `README*`, root manifests (`package.json` / `Cargo.toml` / `pyproject.toml` / `go.mod` / `build.gradle.kts`, and similar), workspace config, lockfiles
- Build, test, lint, format, typecheck, and codegen config
- CI workflows and pre-commit / task-runner config
- Existing instruction files (`AGENTS.md`, `CLAUDE.md`, `.cursor/rules/`, `.cursorrules`, `.github/copilot-instructions.md`)

If architecture is still unclear after reading config and docs, sample a few representative code files to find the entry, module boundaries, and execution flow. Prefer files that connect the system.

Trust executable sources: when docs conflict with config or scripts, trust config and scripts. Keep only what you can verify.

## What to extract
Facts most valuable to an AI working in this repo:
- Exact development commands, especially non-obvious ones
- How to run a single test, a single package, or one focused verification
- Command order when sequence matters (for example `lint -> typecheck -> test`)
- Monorepo / multi-package boundaries, main directory ownership, the real app or library entry
- Framework or toolchain pitfalls: generated code, migrations, codegen, build artifacts, env loading, dev server, deploy flow
- Repo-specific style or process that differs from language or framework defaults
- Test pitfalls: fixtures, integration preconditions, snapshot flow, required services, flaky or expensive suites
- Important constraints from existing instruction files worth keeping

Good `AGENTS.md` content is usually hard information you only get after reading several files.

## Whether to ask the user
Ask only when the repo truly cannot answer an important question, and at most one short batch: unwritten team conventions, branch / PR / release expectations, known but unwritten env or test preconditions. Do not ask what the repo already states clearly.

## Writing rules
Write only high signal, repo-specific content:
- Exact commands and shortcuts the AI is likely to guess wrong
- Architecture that filenames do not make obvious
- Conventions that differ from language or framework defaults
- Environment setup, quirks, and ops pitfalls
- Existing instruction sources worth citing

Do not write: generic software advice, long tutorials or exhaustive file trees, obvious language conventions, guesses or unverifiable claims, or content that belongs elsewhere and should be linked.

If unsure, omit. Prefer short sections with bullets. Keep a simple repo short; for a complex repo, summarize only the few structural facts that actually change how the AI should work.

If `AGENTS.md` (or `CLAUDE.md`) already exists: improve in place, do not blindly rewrite — keep verified useful content, drop fluff and stale claims, and align with the current codebase.
