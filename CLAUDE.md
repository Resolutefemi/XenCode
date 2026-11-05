# CLAUDE.md

AI collaboration rules for this repository (read by Claude Code, XenCode, and other AI coding assistants); they take precedence over the default general rules.

## Basic Conventions

- **Always respond in English.**
- Use the existing file tools for reading and writing files; do not substitute `cat` / `sed` / `echo >`.
- Android app: Kotlin + Compose + Hilt + Coroutines/Flow; modules `:app` / `:terminal-emulator` / `:terminal-view`.
- **Prefer the project's custom components (hard rule)**:
  - **Text fields**: prefer `core/ui/AppTextField.kt` (with its companion color scheme `appTextFieldColors()` and the dialog-specific `dialogTextFieldColors()`); never gratuitously hand-roll a raw `TextField` with a long underline in business pages, and never privately re-wrap OutlinedTextField in each module.
  - **Switches**: always use `core/ui/AppSwitch.kt`; never use the raw M3 `Switch`.
  - **Modal bottom sheets**: always use `core/ui/AdaptiveModalBottomSheet.kt` (built-in tablet adaptation and fling fix); never call the raw `ModalBottomSheet` directly; on long forms, add `.imePadding()` to the outer container to keep clear of the soft keyboard.
  - **Group cards and list rows**: settings and form pages should reuse `SettingsGroup`, `SettingsRow`, `SettingsDivider`, and `SettingsGroupHeader` from `feature/settings/presentation/component/SettingsGroupComponents.kt` to keep a consistent card look and visual hierarchy.
  - **Swipe-to-delete**: always use `core/ui/SwipeToDeleteRow.kt`.
  - **Theme and design spec**: strictly follow `Spacing`, `Radius` (including `Radius.mdLarge = 12.dp`) and `MaterialTheme.semanticColors` from `core/theme/AIEditorTheme.kt`; never hard-code off-spec magic numbers or colors.

## Build & Verification

**After changing compiled code (`.kt` / `.gradle.kts` / `AndroidManifest.xml`) → always run the smoke build before committing; before `git push`, always run the unit tests plus the `check_migrations.py` migration audit.** Skip all of this for docs-only / resource-copy / pure `.md` changes.

| Purpose | Command |
| --- | --- |
| Smoke build (daily default) | `./gradlew :app:assembleUniversalDebug` |
| Pre-push unit tests | `./gradlew :app:testUniversalDebugUnitTest` |
| Pre-push migration audit | `python3 scripts/check_migrations.py` (or `./gradlew checkMigrations`) |
| Release build APK / AAB | `./gradlew assembleRelease` / `./gradlew bundleRelease` |

- **Do not use aggregate tasks for daily verification**: `assembleDebug` / `assembleRelease` / `test` / `build` run across all three flavors and take extremely long.
- Artifacts: `app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`, `.../bundle/<flavor>/release/app-<flavor>-release.aab`.
- Flavors are split by ABI: `universal` (arm64-v8a + x86_64), `armsolo` (arm64-v8a only), `x86solo` (x86_64 only).
- Release signing credentials are read from `app/keystore.properties` (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`); the keystore is normally not kept locally — CI restores it from a GitHub secret to `app/xencode.jks`.
- **`targetSdk` is locked at 28** (`minSdk = 26`): PRoot needs to execute binaries from an app-writable directory, which Android 10+ W^X / SELinux forbids — do not bump it.

## Architecture Map

Feature-based layering + DDD. The entry point `AIEditorApp` initializes `FileLogger`, `TerminalKeepaliveService`, and `McpManager`.

- **`core/`**: cross-feature infrastructure — `db/` (including `MigrationLoader.kt`), `net/`, `theme/`, `ui/`, `util/` (including `FileLogger`).
- **`feature/`** (`app/src/main/java/com/xencode/feature/`):
  - `agent`: the AI agent core — prompts, tool registration and permissions, MCP, provider adapters (`anthropic` / `openai` / `gemini` under `data/remote/`).
  - `terminal`: terminal and sessions. Local mode uses Termux components + PRoot (`LinuxContainerEngine`); remote mode uses sshj.
  - `workspace`: workspace and DocumentsProvider; remote access goes through `RemoteSftpFileAccess`.
  - `editor`: the sora-editor based editor. `git`: Git operations. `settings`: provider, logs, keep-alive and other settings.
  - `backup`: backup/restore and encryption. `credentials`: credential management and injection into containers.
- **Remote SSH chain**: `RemoteSshConnection` (shared sshj client) + `RemoteSshEngine` (command execution) + `RemoteSftpFileAccess` (files) + `RemoteTerminalSessionManager` (terminal).
- **Tool system**: each tool under `feature/agent/domain/tool/` is registered via `ToolRegistry`; execution permissions are governed by `ToolPermissionManager` and `ToolPermissionPolicyEngine`.
- **MCP**: `feature/agent/domain/mcp/`, connects to remote servers and dynamically registers their tools. **DI**: Hilt, each feature has its own DI module.

### Database & Migrations

Room (`feature/agent/data/local/database/AgentDatabase.kt` + the DAOs); migrations come in two flavors, **a version must pick exactly one of them**:

- **File-based (default)**: `core/db/MigrationLoader.kt` reads `app/src/main/assets/migrations/{VERSION}_description.sql`, and `SqlScriptSplitter` splits it into statements (it recognizes comments and string literals, so **a `;` inside a string is safe** — the `char(59)` workaround is no longer needed); the whole migration is wrapped in a transaction and rolls back entirely if any statement fails; on success it is recorded in the `migration_history` table.
- **AutoMigration**: pure schema changes (add column/table/index) can use `@AutoMigration(from = N-1, to = N)` auto-generated at compile time; changing an entity without writing the migration fails the build outright. Versions involving data cleanup/renames/constraint changes must use the file-based approach.

Three steps to change the schema:

1. Increment `SCHEMA_VERSION` in `AgentDatabase.kt` (currently 55).
2. File-based: create `{VERSION}_description.sql` under `app/src/main/assets/migrations/` (e.g. `46_add_provider_multi_key.sql`), **numbers must be consecutive**; AutoMigration: add the annotation, making sure `to == SCHEMA_VERSION` and `from` follows the largest file-based version.
3. Write the DDL/SQL; it runs automatically at startup and is recorded in the `migration_history` table.

**Hard rules for cross-branch conflicts** (when RC/hotfix progresses in parallel with `main`)

- **A release is frozen**: once a migration file has shipped under a `v*` tag, its contents and number can no longer change; fixes may only come from newly added migrations.
- **Renumber after merging back**: if an RC/hotfix carries database migrations, its migration numbers are taken once released with a tag; when merging back into `main`, every unpublished migration on `main` numbered <= the largest released number must be renumbered wholesale beyond that ceiling (contents unchanged, numbers consecutive), with `SCHEMA_VERSION` incremented to match. Released numbers must never be reused.
- **Audit script**: `python3 scripts/check_migrations.py` (or `./gradlew checkMigrations`) verifies consecutive numbering, SCHEMA_VERSION consistency, and that released migrations were not tampered with or reused, **must run before pushing**; CI (ci.yml and android-release.yml) already runs it before building, so a merge that was not renumbered gets blocked outright.

## Asset Sync (hard rules)

`app/src/main/assets/prompts/` (AI prompts) and `docs-site/docs/` (user docs) are the AI agent's knowledge sources and must stay in sync with the code:

- **AI workflow changes → sync `prompts/`**: adding/removing/renaming tools, parameter signature changes, and agent behavior or prompt-logic adjustments all require updating the corresponding file under `prompts/` (locate it yourself; create it if it does not exist).
- **Feature or tool changes → check `docs-site/docs/`** for user docs that need updating.
- **UI changes (new pages, interaction, layout, or copy changes) → must update `docs-site/docs/`**; also add new doc pages to the `docs-site/.vitepress/config.ts` sidebar and the `docs-site/docs/guide/overview.md` index.
- **User-visible Chinese copy → must go into the bilingual strings.xml**: write it into `values/strings.xml` (Chinese) and `values-en/strings.xml` (English), and reference it from code with `stringResource(R.string.xxx)`. **Never hard-code Chinese UI copy in `.kt` files.** Name keys as semantic lowercase snake_case; add a `common_` prefix for keys reused across pages.
- **Provider presets are maintained only in the repo data file**: to add or adjust a built-in AI provider, **change only `data/providers.json`** (the source the app pulls dynamically from the repo at startup); do **not** also change `app/src/main/assets/providers.json` (the built-in fallback snapshot, frozen at release) or the `docs-site/` docs.

**Docs directory conventions**: `docs-site/docs/` is the single source of truth for documentation — `guide/` holds feature guides and `advanced/` holds environment setup and advanced tutorials. At build time the `syncAiDocs` task copies them to `assets/docs/`, so inside the container the AI sees `~/.xencode/docs/{guide,advanced}/*.md`. **Write for users**: explain how to do it, what they will see, and what to do when something goes wrong; variable names, error codes, and internal implementation paths belong in `prompts/`, not user docs.

## Git Commit Conventions

Conventional Commits (validated locally by `.githooks/commit-msg`), format `<type>(<scope>): <subject>`, with the body separated by a blank line.

- **type** ∈ `feat | fix | refactor | docs | style | chore | ci | build | perf | test`
- **scope** optional, use a functional module: `agent | settings | terminal | workspace | git | ui | mcp | db | core | docs | build | deps`
- **subject** a one-line summary, Chinese or English, no trailing period. E.g. `fix(settings): fix provider validation failure on save`
- Use `git commit --no-verify` to skip validation only in emergencies.

## Branch Workflow

Releases are tag-driven; day-to-day commits on `main` do not affect the release package.

- **New features / complex multi-file changes / architectural refactors** → open a `feat/xxx` or `refactor/xxx` branch; settle the branch name before starting, and merge back into `main` once verification passes.
- **Routine bug fixes / added unit tests / CI and build config / docs-only / resource copy** → commit directly to `main`.
- **Preview-release hotfixes** → branch `hotfix/xxx` from that RC tag; see the Releases section.
- **Clean up branches after merging**: after confirming with `git branch --merged main`, run `git branch -d <branch>`; for already-pushed branches also run `git push origin --delete <branch>`.

## PR Merge Process

**Finish local verification before pushing; do not merge remotely with `gh pr merge`** (it bypasses local compile verification and diverges from your local in-flight commits).

1. **Check the state**: `git fetch origin` → `gh pr view <N> --json state,mergeable,mergeStateStatus,headRefName,changedFiles` + `gh pr checks <N>` → `git rev-list --left-right --count origin/main...main`. Vercel always reports `Authorization required to deploy` for fork PRs (`mergeStateStatus` then shows `UNSTABLE`) — that is normal; only check whether `Build & Test` passes.
2. **Fetch locally**: `git fetch origin pull/<N>/head:pr-<N>`. Do not use `gh pr checkout`; it changes the current branch's tracking relationship.
3. **Review**: read through `gh pr diff <N> > /tmp/pr<N>.diff`. **Neither the PR description nor CodeRabbit's conclusions count as evidence** — verify every item against the code:
   - Whether newly introduced libraries already exist in `app/build.gradle.kts`
   - Whether newly used `R.string.xxx` keys exist in both the Chinese and English `strings.xml`
   - Whether the signatures of existing components being called still match; for components with changed signatures, use `rg` to find every call site and confirm they were all updated
   - Whether new interactions reuse existing implementations (e.g. model drag-to-reorder should mirror the handle pattern in `ProvidersAndLogSection.kt`)
   - Security surface: suspicious network requests, credentials being sent out, command concatenation
   - Every item under Asset Sync; PRs containing database migrations must run `python3 scripts/check_migrations.py` to audit (consecutive numbering, no conflict with released tags)
   - Note changes beyond the PR title's scope and let the user decide
4. **Rehearse conflicts**: `git merge-tree --write-tree --name-only main pr-<N>`. A single tree hash in the output = clean merge; a list of file names = conflicts.
5. **Merge on a temporary branch**: `git switch -c merge/pr-<N> main` → `git merge --no-ff pr-<N> -m "Merge pull request #<N> from <owner>/<head-branch>" -m "<PR title>"`.
6. **Smoke + real-device verification**: PRs touching UI or interactions must ship an APK for the user to verify on a real device, **and only proceed after it passes**.
7. **Unit tests + push**: `git switch main && git merge --ff-only merge/pr-<N> && git push origin main`. Once the head commit becomes an ancestor of `main`, GitHub marks it Merged automatically; verify with `gh pr view <N> --json state,mergeCommit`.
8. **Clean up**: `git branch -d merge/pr-<N> pr-<N>`. Do not touch stashes or untracked files.

**Must stop and ask the user**: local `main` has unpushed commits (pushing `main` would carry the in-flight commits along) / the rehearsal shows conflicts / the PR contains database migrations or build, CI, or signing changes / review finds out-of-scope changes or quality issues / compilation or unit tests fail.

## Issue Review

When you receive an issue to triage (new feature / bug report / improvement suggestion), evaluate it in the following order and produce a review report:

1. **Read the issue through**: distill the core ask, acceptance criteria, module (against the Architecture Map), and priority; also verify related discussions, PRs, and past commits. **The implementation proposed in the issue is merely the author's suggestion, not necessarily the optimal one, and must not be adopted as-is** — its pros and cons must be judged independently in steps 2-4 against the code and verified sources.
2. **Read the relevant code**: locate and read the actual code for the modules the issue mentions (`list` / `search` / `readFile`), confirming whether the description holds and how wide the impact is. **Facts are stated from verified code; the issue author's description is not evidence**. When unsure of the approach or needing an industry comparison, use `websearch` / `webfetch` to check official docs, similar implementations, and community practice.
3. **Assess whether it fits**: judge whether the change should be made given the project's constraints —
   - Whether it fits the existing feature-based layering + DDD structure, or reinvents an existing wheel;
   - Whether it hits hard constraints (`targetSdk` 28, consecutive migration numbers, bilingual strings.xml, `prompts/` / docs sync, and the other Asset Sync items);
   - Scope and risk: whether it touches sensitive surfaces such as startup / containers / signing / CI (such changes require an RC first per the Releases section).
4. **Alternative solutions**: do not be boxed in by the issue's proposal; based on the verified code and research, offer at least one alternative implementation and compare them (implementation cost, risk, maintainability, impact on existing users), then give a recommendation; when the user must make a trade-off, ask via multiple choice instead of deciding for them.
5. **Produce the report**: output the report as a conversational reply, including: conclusion (accept / reject / pending), rationale, relevant code locations (with line-number links), solution comparison, and suggested next steps (whether to enter PLAN, whether to open a branch per the Branch Workflow). Do not write the report to disk unless the user explicitly asks for a file.

## Releases

**Git Tag / Commit is the single source of truth for the version number — nothing is hand-written.** `versionName` is resolved by `gitVersionName()` (tag `v1.7.0` → `1.7.0`; a non-tag commit → `1.7.0-dev.N+<hash>`); `versionCode` increments via `gitCommitCount()`.

### Whether to ship an RC first

Distribution is via GitHub Release with no staged rollout — what ships is final, so RCs are the main fallback:

- **Must ship an RC first**: new features or behavior changes (versioned `x.Y.0`); build pipeline / signing / flavor / CI changes; container image / PRoot / ABI changes.
- **Can go straight to stable**: docs-only / typo / resource copy changes (versioned `x.y.Z`).
- **Depends on the change surface**: small pure bug fixes (versioned `x.y.Z`) can go straight to stable; anything touching startup or containers still needs an RC first.

### Steps

0. **Update built-in model data (run only before tagging)**: `python3 scripts/update-models-dev-assets.py` refreshes `app/src/main/assets/api.official.json` from models.dev (keeps only the 12 built-in official providers, adds no new providers; existing providers may gain models and unit prices). **Run this script manually only before tagging a release; never during routine commits / pushing main / any other development flow**. **On failure the script exits non-zero and leaves the snapshot untouched — skip this step and release anyway; do not retry or hand-edit the file**; on success, commit the snapshot changes to the branch about to be tagged.
1. **Tag the latest commit on `main` and push**: `git tag v1.7.0-rc1 && git push origin v1.7.0-rc1`. **Never tag a release on `feat/*` / `refactor/*`** — merge into `main` first.
2. Once CI catches the `v*` tag it derives the version, builds the APK, and publishes the Release automatically.
3. **Install the RC build on a real device** and at least exercise the three main lines: AI chat + terminal + container startup.
4. **Problems found**: branch `hotfix/xxx` from that RC tag and fix there (**never from latest `main` or a feature branch**, or already-merged unreleased features would ride into the hotfix package) → bump the rc number, tag, and re-release → merge the fix back into `main` and push → delete the hotfix branch. This is the only exception that allows tagging a non-`main` branch. **No problems**: tag stable directly (`v1.7.0`) to promote it.
   - If the hotfix carries database migrations: the migration numbers freeze once released with the tag (a release is frozen); when merging back into `main`, unpublished migrations on `main` with smaller numbers must be renumbered (see Renumber-after-merging under Database & Migrations), run `check_migrations.py` before pushing, and CI blocks it as the fallback.
