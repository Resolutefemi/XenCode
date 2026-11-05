# Memory & Project Rules

This chapter covers two features — automatic memory and project rules — that keep the AI's context continuous and the project consistent across sessions.

## Automatic memory

Automatic memory is a long-term knowledge base the AI maintains on its own. Project conventions, user preferences and architectural decisions that form during conversations are recorded automatically and reused in later sessions.

### Scope

| Scope | Description | Storage location |
| --- | --- | --- |
| Global | Personal preferences shared across projects | `~/.xencode/memory/` |
| Project | Conventions and experience specific to the current project | Local mode: `<project root>/.xencode/memory/`; remote SSH mode: `~/.xencode/memory/projects/<project name>-<identity hash>/` |

In remote mode, project memory is stored on the phone and isolated by "project name + server identity"; container resets do not affect it.

### Memory files

Each memory is a Markdown file made up of YAML frontmatter (`name`, `description`) and a body:

```markdown
---
name: conventions
description: Project coding conventions and naming rules
---
(body)
```

### How it takes effect

At the start of a session, summaries of all memories (names and descriptions) are injected into the prompt; when details are needed, the AI reads the full content with the `memory` tool.

### The memory tool

| Parameter | Description |
| --- | --- |
| `action` | `read` / `save` / `edit` / `delete` / `list` |
| `name` | Memory name (i.e. the file name) |
| `description` | Summary; required for `save` |
| `content` | Body (Markdown); required for `save` |
| `edits` | List of partial edits, same semantics as file editing |
| `scope` | `project` (default) or `global` |

For same-named memories, project-level takes precedence over global. When updating an existing memory, prefer `edit` for partial changes; record hard-won lessons only after verifying the root cause.

## Project rules

`AGENTS.md` in the project root declares project-specific rules, architectural constraints and build guidance — the highest-priority convention the AI follows; when `AGENTS.md` is absent, `CLAUDE.md` is read instead.

### How it takes effect

Rule files are loaded automatically at the start of a session, no manual setup needed; content beyond 32,000 characters is truncated.

### Customizing the loading convention

The loading convention for project rules corresponds to the prompt fragment `15-project-rules.md` and can be overridden via `~/.xencode/prompts.custom/15-project-rules.md`; see [Custom prompts](/guide/custom-prompts).