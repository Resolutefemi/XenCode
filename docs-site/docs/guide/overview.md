# Feature Overview

This page is a quick index of the user manual, mapping the core UI workflows and settings entries to their documentation. To watch it in action first, browse the [video tutorials](/guide/video-tutorials).

::: tip Version note
Entries marked with a version (e.g. "since 1.11.0") were introduced in that version.
:::

## Core workflows & main UI

| Feature | Description |
| --- | --- |
| Chat & workspace | Title bar, sidebar, toolbar, message queue, workspace switching → [Docs](/guide/chat) |
| Modes (three modes) | Permission control and use cases for Build / Plan / Auto → [Docs](/guide/modes) |
| Checkpoints & undo | Automatic snapshot before AI edits, one-tap safe rollback → [Docs](/guide/checkpoint) |
| Terminal | Multi-tab sessions, auxiliary key bar, color and font settings → [Docs](/guide/terminal) |
| Built-in browser | WebView browser where the AI can automate web pages (navigate, click, fill forms, screenshot, etc.) (since 1.12.0) → [Docs](/guide/browser) |
| Network request | Manually send HTTP requests, edit Query / Headers / Body, view responses and history → [Docs](/guide/network-request) |
| File browsing & code editing | Indented tree view, syntax highlighting, built-in code editor (since 1.11.0), plus where workspace files live on your phone and how to access them → [Docs](/guide/files) |
| Git version management | Visual status management, branch switching, commit history, revert and delete (since 1.11.0) → [Docs](/guide/git) |
| Tablet & large screens | Responsive split panes, persistent sidebar, side-by-side workbench (since 1.11.0) → [Docs](/guide/tablet) |

## Settings & extensions quick reference

### General settings

| Entry | Description |
| --- | --- |
| Preferences | Global preferences: stale-model cleanup, session at startup, Enter-to-send, first-token & interval timeouts, retry count, auto-compression threshold, sendFile per-file cap (since 1.12.0) → [Docs](/guide/general) |
| Theme, wallpaper, language | Light/dark themes, preset colors, Monet color extraction, wallpaper and multi-language → [Docs](/guide/appearance) |
| Terminal settings | Terminal colors, font size and cursor style → [Docs](/guide/terminal) |

### AI configuration & extensions

| Entry | Description |
| --- | --- |
| AI vendors | Connect model services, manage model lists, multi-key (since 1.11.0), thinking effort → [Docs](/guide/providers) |
| Default & dedicated models | Default model for new sessions, plus dedicated models for image recognition, image generation and title summarization → [Docs](/guide/default-models) |
| MCP servers | Connect external tools, global and project-level configuration → [Docs](/guide/mcp) |
| Skills | On-demand specialist extension packs → [Docs](/guide/skills) |
| Subagents | Spawn independent sessions to run tasks in parallel; create, edit, enable and disable in settings, with custom models and tool sets (since 1.11.0) → [Docs](/guide/subagent) |
| Shizuku tool | The AI's `Shizuku` tool: run system commands as adb shell (uid 2000), read and write /sdcard (since 1.12.0) → [Docs](/guide/shizuku) |
| Available Tools | Browse all built-in tools' purpose, parameters, and permission mode; includes GitHub issue / PR / CI integration (1.10.0+) → [Docs](/guide/available-tools) |
| Custom prompts | Override and customize AI system prompt fragments → [Docs](/guide/custom-prompts) |
| Memory & project rules | Cross-session long-term memory, plus AGENTS.md / CLAUDE.md project rules → [Docs](/guide/memory) |

### Runtime

| Entry | Description |
| --- | --- |
| Container & images | Local Linux container, custom images, mounting phone directories, remote SSH backend → [Docs](/guide/container) |
| Network proxy | Global proxy and vendor-level proxy (since 1.11.0) → [Docs](/guide/proxy) |
| Connection & sync | SFTP / FTP channels, workspace sync, built-in FTP server → [Docs](/guide/sync) |
| Remote environments | InstaBox cloud Linux machines (`linux_machine` tool), E2B placeholder → [Docs](/guide/remote-environments) |

### Permissions & background

| Entry | Description |
| --- | --- |
| Tool authorization | Authorization rules for AI tool calls → [Docs](/guide/permissions) |
| System permissions | Install unknown apps, storage, battery optimization, auto-start, Shizuku and other system permissions → [Docs](/guide/app-permissions) |
| Background running | Background keep-alive, keep screen on, AI-completion notifications → [Docs](/guide/background-run) |

### Data & diagnostics

| Entry | Description |
| --- | --- |
| Token stats | Usage, cost estimation, call details → [Docs](/guide/token-stats) |
| Storage | Per-category usage of chats, containers, workspaces, and cleanup of temporary data (since 1.12.0) → [Docs](/guide/storage) |
| Backup & restore | Encrypted export/import of configuration and workspaces → [Docs](/guide/backup) |
| Logs | View runtime logs and crash reports → [Docs](/guide/logs) |
| Common errors | Meaning of retry-bubble categories, common HTTP error codes and troubleshooting → [Docs](/guide/error-codes) |

### Help & about

| Entry | Description |
| --- | --- |
| About | Version info and update checks → [Docs](/guide/about) |

## Advanced tutorials

| Topic | Description |
| --- | --- |
| Building Android apps in the container | Set up JDK and Android SDK, build an APK from source → [Docs](/advanced/build-android-app) |
| Building Flutter apps in the container | Install JDK, Android SDK and Flutter SDK, build a Flutter debug APK → [Docs](/advanced/build-flutter-app) |
| Installing Playwright browser automation | Install Chromium inside the container and connect Playwright MCP so the AI can operate web pages → [Docs](/advanced/playwright-mcp) |
| Wireless debugging of other devices with adb | Use adb from the container to connect another phone or this one — install apps, capture logs, take screenshots → [Docs](/advanced/adb-wireless-debug) |
| Custom dashboard cards | Draw balance or usage cards above the input box with scripts → [Docs](/advanced/dashboard-cards) |
