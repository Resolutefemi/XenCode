# Available Tools

The "Settings → Available Tools" page lists every built-in tool the AI can use in the
current version: what each tool does, its parameters, and how it is authorized. It is a
display-only page — you can only look here, not change behavior; actual tool authorization
is controlled by [Tool Permissions](/guide/permissions) and the session mode
(Build / Plan / Ask).

## Page content

Tools are grouped into five categories. Tap a group title to collapse or expand it:

| Group | Includes (partial) |
| --- | --- |
| Files | Read / write / edit files, list directory, search code, send file, view image, render layout |
| Commands & Terminal | Run command (Bash), background terminal, Shizuku host actions |
| Network | Web search, fetch web page, GitHub read, GitHub write, remote Linux machine |
| AI Collaboration | Plan mode, todo list, memory, load skill, generate image, ask user, subagents, etc. |
| Extensions | MCP management, built-in browser |

Tap any tool to open its detail page, which shows:

- **Tool name and summary**: the identifier the AI uses in chat (e.g. `githubWrite`) and what it does.
- **Permission mode**: "Auto" means tool calls run without confirmation; "Ask" means the app
  asks for consent before every call in Build mode. Write-type actions are always rejected in
  Plan / Ask mode.
- **Parameters**: each parameter's name, type, whether it is required, and its meaning.

## Layout rendering preview

`renderLayout` turns an Android XML layout file (`res/layout/*.xml`) into a PNG preview:

- Auto-approved and read-only; the PNG is saved under `~/workspace/.aicode/previews/`
  (one file per layout, latest render wins) and shown in chat — tap the preview row in the
  tool card (or the phone frame in the editor) to open it full screen.
- Optional parameters: `theme` (light / dark), `width_dp` and `height_dp` for the viewport.
- The render is **broad but approximate**: standard framework widgets (text, buttons,
  images, spinner, rating bar, tables, and all base layouts including
  LinearLayout / FrameLayout / RelativeLayout / GridLayout) draw faithfully; Material
  cards, floating action buttons, tab bars, bottom bars, text-field containers and list
  containers (RecyclerView / ListView / GridView / NavigationView) render as close
  lookalikes with sample content where an adapter would feed real data; ConstraintLayout
  parent and sibling constraints are translated into RelativeLayout rules (chains, ratio
  sizing and bias degrade with a note); shapes, layer-lists (with insets), radial/sweep
  gradients, dashed strokes, Material theme colors (`?attr/colorPrimary` and friends) and
  DataBinding-wrapped layouts are supported. Custom view classes, fragments and unsupported
  attributes become labeled placeholders. Every degradation is reported, and the tool
  result is marked `approximate` so the AI can tell you what may look different.
- The code editor shows a phone icon on `.xml` files: tapping it previews the layout you
  are editing (unsaved edits included), re-rendering as you type.

## GitHub integration

GitHub Read (`github`) and GitHub Write (`githubWrite`) let the AI operate on GitHub directly:

**Read actions** (auto-approved): list and view issues, issue comments, list and view pull
requests, fetch a PR's unified diff, and view CI check results for a branch / commit.

**Write actions** (confirmation required): create / update / comment on issues, create pull
requests, submit PR reviews (approve / request changes / comment), and merge PRs.

### First-time setup

1. In **Credentials** (Git pane → "Credentials & Identity"), add a credential for
   `github.com`: put your GitHub username in the username field and a GitHub Personal
   Access Token in the token field.
2. Required token scopes: read actions need `repo` (for private repositories) or the
   matching public-repo read access; write actions (issues / PRs / reviews / merges) need
   the full `repo` scope.
3. When the current workspace's Git remote points at a GitHub repository, the AI detects
   `owner/repo` automatically; for other repositories the AI can also be told the repo
   explicitly (`owner/repo`).

The token is stored only in the app's private directory (shared with Git push/pull
credentials) and never appears in conversation content.

## Remote Linux machine (InstaBox)

`linux_machine` talks to a persistent InstaBox cloud Linux computer (home `/data/home`).
It appears in the tool list only after you enable InstaBox and save an API key in
**Settings → Remote Environments**. Write actions need confirmation in Build mode and
are blocked in Plan / Ask; read-only actions (`info`, `list`, `files_list`, `files_read`,
`files_exists`, `list_ports`, `desktop_status`, `metrics`) stay available.

See [Remote Environments](/guide/remote-environments) for setup.

### Common failures and fixes

| Message | Cause and fix |
| --- | --- |
| `NO_TOKEN` | No `github.com` credential saved; add one with the steps above |
| `GITHUB_HTTP_401` | Token invalid or expired; generate a new one and update the credential |
| `GITHUB_HTTP_403` | Insufficient token scope or rate-limited; check the scope and retry later |
| `GITHUB_HTTP_404` | Wrong repo name, or the token cannot access this repository |
| `GITHUB_HTTP_422` | GitHub rejected the request payload (e.g. a missing required field); fix as suggested |
| `MISSING_REPO` | The workspace has no GitHub remote; have the AI pass `repo="owner/repo"` explicitly |
