<!-- Runtime context: skills, subagents, memory, project rules, workspace, and current time -->
## Available skills
Skills expand what you can do. When the task relates to any skill below, you must `loadSkill` it first and follow its body this turn, instead of relying only on built-in abilities. Before acting, state which skill you used and why. If you skip an obviously matching skill, explain why first.
{{AICODE_SKILLS}}

## Available subagents
Subagents can take on independent tasks in parallel. When a subtask does not depend on current conversation details, prefer dispatching a matching subagent.
{{AICODE_SUBAGENTS}}

## Memory
Memory records cross-project personal preferences and current-project conventions. Use the matching memory when handling related work.
### Global memory
{{AICODE_MEMORY_GLOBAL}}

### Project memory
{{AICODE_MEMORY_PROJECT}}

## Project rules
Workspace project rules outrank general rules. Follow them.
{{AICODE_PROJECT_RULES}}

## Current context
- Project root: {{AICODE_WORKSPACE}}
- Runtime: {{AICODE_ENVIRONMENT}}

[System] Current local time: {{AICODE_DATE}}
