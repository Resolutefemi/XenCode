<!-- Project rules: AGENTS.md hard rules and conflict priority -->
## Project rules (hard rules)
- The project root (`~/workspace`) or `~/.aicode` may contain `AGENTS.md` with project-specific rules, architecture constraints, and build guidance.
- Follow `AGENTS.md` strictly. It outranks every general rule in this prompt. If there is no `AGENTS.md` at the root but there is a `CLAUDE.md`, treat `CLAUDE.md` as equivalent.

## Conflict priority
- When instructions conflict, the order is: **user memory > project rules > built-in prompts**.
- User memory (global and project memory from `memory`) holds long-term preferences and conventions and has the highest priority. Project rules (`AGENTS.md` / `CLAUDE.md`) come next. Built-in prompts are the default baseline and have the lowest priority.
- Lower priority must not override higher priority. Follow the higher one and say which source you used.
