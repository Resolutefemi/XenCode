# MCP Servers

MCP (Model Context Protocol) lets the AI plug into external tools — querying databases, searching the web, driving a browser, and so on. Once connected, these tools automatically appear in the AI's tool list and are called just like built-in tools.

The entry point is "Settings → MCP Servers".

## Two levels of scope

MCP configuration comes in two levels, global and project:

- **Global**: shared by all projects; the configuration lives in `~/.xencode/mcp.json`.
- **Project-level**: effective only in the current workspace; the configuration lives in `<workspace>/.xencode/mcp.json` and can be committed to git along with the project.

What actually takes effect is the merged result, with **project-level winning** — for same-named servers, the project's configuration overrides the global one.

## Server list

Each row shows the server name, source (global / project), type (built-in / HTTP) and connection status; when connected it also shows the tool count, and an unconnected local stdio server shows its launch command.

- Tap a row to edit it.
- Swipe left to reveal the delete button; deletion applies to the scope labeled on the row.
- "Reconnect" at the top-right re-establishes every server in one tap; "+" adds a new server.

If you edit `mcp.json` directly inside the container or with an external editor, the app discovers it and refreshes within seconds: added or enabled servers connect automatically, deleted or disabled ones disconnect — no restart needed.

## Adding a server

At the top of the edit page, first pick the **scope** (new entries default to "Project"; editing an existing entry keeps its original scope); "Enabled" below controls whether it joins connections. The remaining fields come in two kinds by transport type.

**Name** is just an alias, but it is embedded in the tool names given to the model (`mcp__name__tool`), so it may only contain letters, digits, underscores and hyphens; entering characters such as Chinese letters raises an error and blocks saving.

### Remote HTTP

- **Name**: an alias, e.g. `fetch`, `github`.
- **Server URL**: the remote server's Streamable HTTP link.
- **Custom headers**: key-value pairs for fixed headers such as auth tokens.

### Local stdio

- **Name**: an alias.
- **Launch command**: something like `npx`, `node`, `python`.
- **Command arguments**: added one by one, e.g. `-y`, `@modelcontextprotocol/server-xxx`.
- **Environment variables**: key-value pairs passed to the process, e.g. API_KEY.

stdio servers must run inside a container: in local mode they run on the current container, and in remote SSH mode they run on the "default container". After switching containers, all stdio servers automatically disconnect and reconnect on the new one; HTTP servers are unaffected.

## Tool management

Once connected, the "Tools" tab of the edit page lists every tool the server provides — one card per tool, showing its description and parameter names.

- The toggle at the top-right can **disable a single tool**; a disabled tool is no longer offered to the model, saving some context.
- MCP tools go through the same permission mechanism as built-in tools: by default every call asks for your confirmation, and you can choose "Always allow" in the dialog to remember it.
- If no tools are detected, tap refresh at the top-right to fetch again.

The log button at the top-right of the edit page jumps to the Logs page pre-filtered by this server's name — handy when troubleshooting connection failures.

## Letting the AI configure it for you

Complex MCP configuration can be handed straight to the AI; its management tools can install to global or project scope. After adding or removing servers, the configuration **takes effect on the next session** (creating a session auto-connects servers that are not yet connected).
