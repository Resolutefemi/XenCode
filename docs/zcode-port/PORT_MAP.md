# ZCode to XenCode Rust port map

Authoritative tracker for the file-by-file port of the ZCode workspace UI
(zai-org/ZCode, Apache-2.0) into `rust/crates/xencode-desktop` (egui).
UI/UX must match ZCode exactly. Only deltas allowed: the brand glyph
(XenCode X instead of the Z glyph), the backend API layer and the database
integrations (both stay XenCode: shared DB + xencode-core + rquickjs).

Reference clone (not committed): `reference/ZCode` at tag v3.17.x.
Regenerate the theme tokens with:

```
python3 scripts/port/zcode_theme_tokens.py --zcode ../reference/ZCode \
    --rust rust/crates/xencode-desktop/src/theme/tokens.rs
```

## Status legend

- DONE — ported and verified (`cargo check`/`test` exit 0)
- PARTIAL — structure/colors in, interaction fidelity pending
- TODO — not started
- N/A — web/Electron-only, no desktop equivalent (tracked for completeness)

## Foundations

| ZCode source | XenCode Rust | Status |
| --- | --- | --- |
| `packages/ui/src/styles.css` (`@theme` + `.dark`, 169/165 tokens) | `src/theme/tokens.rs` (generated) + `src/theme/mod.rs` | DONE |
| Tailwind `--text-ui-*` type scale (14px base) | `theme::text` | DONE |
| Tailwind 4px spacing + shadcn radius scale | `theme::geom` | DONE |
| Bold weight (`font-medium`/semibold runs) | embedded FreeSansBold as the `xen-bold` family (egui ships Light only) | DONE |
| `components/mobile-ui/ZCodeAppLogo.tsx` | `XenApp::init_logo` (art/xencode-icon-512.png, X glyph) | DONE |

## Shell

| ZCode source | XenCode Rust | Status |
| --- | --- | --- |
| `app-shell/WorkspaceShellLayout.tsx` (layout tree) | `src/ui/app_shell.rs::show` | PARTIAL (panels + dispatch; animated panes pending) |
| `DesktopTopOverlay.tsx` (sidebar toggle, nav arrows, new task, window controls) | `app_shell::top_overlay` | PARTIAL (toggle/brand/theme/window controls; nav arrows pending) |
| `DesktopWindowControls.tsx` / `DesktopWindowFrame.tsx` | `top_overlay` window controls (min/max/close) | DONE |
| `workspaceShellWindowChrome.ts` | egui viewport commands | DONE |
| `app-shell/useAnimatedResizablePanel.ts` | egui resizable SidePanel | PARTIAL (no animation curve yet) |
| `sidePaneLayout.ts` + `AnimatedSidePanePanel.tsx` (right side panes) | TODO | TODO |
| `useAppChromeState.ts` | `XenApp` chrome fields | PARTIAL |

## Sidebar

| ZCode source | XenCode Rust | Status |
| --- | --- | --- |
| `WorkspaceSidebar/` + `project-sidebar/` | `src/ui/sidebar.rs` | PARTIAL |
| Workspace header + `GitBranchSwitcher.tsx` | `sidebar::workspace_header` (branch chip from .git/HEAD) | PARTIAL (real git state, no switcher menu yet) |
| New Task button (⌘N) | `sidebar::new_task_button` | DONE |
| Tasks list (sessions, pinned/grouped/archived) | `sidebar::tasks_section` (shared-DB rows, relative time) | PARTIAL (no pin/group/archive yet) |
| Projects list + add project | `sidebar::projects_section` | DONE |
| `WorkspacePurposeSection.tsx` | TODO | TODO |
| `RemoteProjectDetailsPopover.tsx` (SSH/WSL/Docker) | TODO (needs remote layer) | TODO |

## Conversation

| ZCode source | XenCode Rust | Status |
| --- | --- | --- |
| `v4/` timeline (user bubble, assistant, stream) | `src/ui/conversation.rs` | PARTIAL |
| `ai-elements/message.tsx` markdown + inline styles | `src/ui/markdown.rs` (pulldown-cmark, Streamdown inline styles, brand links) | DONE |
| `ai-elements/code-block.tsx` (header, wrap + copy, mono body) | `markdown::code_block` (rounded-xl card, mono language label, 2s copied check) | DONE |
| Syntax highlighting (shiki) | `markdown::highlight` (kw/string/comment/number pass, One Dark + light-github palettes) | PARTIAL (shiki-faithful per-language themes pending) |
| `markdown-list.tsx` + `markdown-table.tsx` | `markdown::flush_list_item` / `table_render` (header rule) | DONE |
| Reasoning/thought preview blocks (`ai-elements/reasoning.tsx`) | `conversation::reasoning_block` + `<think>` splitter | DONE |
| `ToolCallBlocks/` (per-tool cards, collapsible) | `conversation::tool_block` (kind glyphs + kindLabel per renderer.tsx) | DONE |
| Edit diff counts (`renderDiffCount` +N/-N) | TODO (needs per-tool payload stats) | TODO |
| `AssistantPreviewCards.tsx` / `AssistantCodeCommentCards.tsx` | TODO | TODO |
| `MessageChangeSummaryPanel.tsx` | TODO | TODO |
| Empty-task home (greeting + centered composer) | `conversation::empty_task_home` | PARTIAL (geometry in; greeting copy to verify against app) |
| Session resume (row sync in) | `XenApp::resume_session`/`bind_resumed` | DONE |

## Composer

| ZCode source | XenCode Rust | Status |
| --- | --- | --- |
| `LexicalChatInput.tsx` | `src/ui/composer.rs` | PARTIAL (multiline + Enter/Shift-Enter; no rich editing) |
| Staged image attachments strip | `composer::card_at` attachment strip | DONE |
| `chat-input-toolbar/ChatModeSwitchControl.tsx` | mode chip + `MenuKind::Mode` popover | PARTIAL (presets mapped to PermissionMode) |
| `chat-input-toolbar/ComposerModelMenu.tsx` + `ComposerProviderMenu.tsx` | model seat + `MenuKind::Model` popover | PARTIAL |
| `chat-input-toolbar/ThoughtLevelCycleControl.tsx` | effort chip (brain glyph, cycles Off/Low/Medium/High) | DONE |
| `chat-input-toolbar/contextUsage.tsx` (context meter) | context chip (mini progress + %; transcript-size estimate until usage rows land) | DONE |
| `mentions/` (@ files), `/` commands, `$` skills in input | TODO | TODO |
| Send button | `composer` send (primary fill, arrow-up, busy square) | DONE |
| Plan-mode notice | `conversation::plan_notice` | DONE |

## Tool pages

| ZCode source | XenCode Rust | Status |
| --- | --- | --- |
| `settings/` pages (providers, usage stats, appearance) | `src/ui/panels.rs::page_settings` | PARTIAL (providers/paths; usage + appearance TODO) |
| Usage stats (`CodingPlanUsageRemainingPanel.tsx`) | `page_telemetry` (XP ledger + llm totals) | PARTIAL |
| Goal mode views | `page_goals` (DAG + runner) | PARTIAL |
| Automations | `page_automation` (MjsEngine) | PARTIAL |
| `BotsDialog/`, `quickpick/`, `command-center/` | TODO | TODO |
| `terminal/` (xterm pane) | TODO (port after side panes) | TODO |
| `GitPane/` + `git-graph/` | TODO | TODO |
| `onboarding/` | TODO | TODO |
| `i18n/` (en + zh) | hard-coded English | TODO |
| `web-remote-control/`, `login/`, `share-ui/`, `web-share/` | N/A for desktop offline app (tracked) | N/A |

## Infra

| ZCode source | XenCode Rust | Status |
| --- | --- | --- |
| `packages/client` state stores | `xencode-core` store (already XenCode) | N/A by design |
| `packages/rpc` + `packages/server` API | `xencode-core` agent + shared DB (already XenCode) | N/A by design |
| Screenshot tour (8 pages) | `XENCODE_UI_TOUR` + `scripts/capture_ui_pages.py` | DONE (kept) |

## Verification per session

1. `cargo check --workspace` exit 0 and 0 warnings
2. `cargo test -p xencode-desktop` exit 0
3. Tour capture renders every page (no blank/missing surfaces)
