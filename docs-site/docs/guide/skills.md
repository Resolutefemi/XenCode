# Skills

Skills are "specialist extension packs" for the AI — standard procedures, best practices and domain know-how for a class of tasks distilled into a guideline the AI loads on demand when it meets a related task, so you never have to repeat background rules in every conversation.

The entry point is "Settings → Skills". Enabled skills appear in the `/` menu of the chat input box — trigger one directly with `/skill-name`, optionally with arguments (e.g. `/pdf-report a4`); see the "Slash commands" section of [Chat & messages](./chat.md).

## Where skills come from

A skill is just a directory — as long as it contains `SKILL.md` (or a compatible `CLAUDE.md`) it is recognized automatically. There are two levels:

- **Global skills**: live in `~/.xencode/skills/`, shared across projects and never lost on app upgrades. They follow the execution environment: in local mode that is `/root/.xencode/skills/` inside the container (mapped to the app's private directory on the phone); in remote mode it means `~/.xencode/skills/` on the **server**, with reads and writes going over SSH.
- **Project-level skills**: live in `<workspace>/.xencode/skills/`, effective only in the current workspace, and can be committed to git with the project. In remote mode this means the workspace **on the server**, with reads and writes going over SSH.

For same-named skills, project-level wins.

You can create skills directly in the app (see below), create skill directories by hand in the container, or let the AI install them from GitHub. In local mode the app watches the skill directories — after manual adds, deletes or renames the list refreshes within seconds without re-entering the page. In remote mode changes on the server are not watched; after manual edits you must leave and re-enter the settings page to refresh the list.

## List page

Skills are shown in two collapsible groups, "Current project / Global"; tap a group title to fold or unfold.

Each row is a skill: icon, name and summary; the colored badge next to the name shows enabled or disabled. Tap a row for details; swipe left to reveal delete — deleting asks for confirmation and removes the skill directory outright, **irrecoverably**.

The "+" at the top-right adds a skill (see "Adding a skill" below).

## Adding a skill

Tap "+" at the top-right to open a bottom sheet; first pick the **scope** (global / current project, global by default), then how to add:

- **Create manually**: open the edit page and fill in name, description and body to build a skill from scratch.
- **Import from file**: pick a `.md` file (`.markdown` / `.txt` also work). The app reads the content, builds the skill from its frontmatter `name`/`description` (falling back to the file name when `name` is missing), and writes `<scope>/skills/<name>/SKILL.md`.
- **Import from archive**: pick a `.zip`. The app extracts it and recognizes every directory inside containing `SKILL.md` (or `CLAUDE.md`), importing them one by one — one archive can carry several skills, as with GitHub repo download bundles.
- **Skill market**: open the built-in online market to browse ready-made skills (see "Skill market" below).

Importing is equivalent to creating manually: files are always normalized to `SKILL.md` on disk, and afterwards can be modified with the editor or container commands just the same.

**Cases that get skipped** (a dialog lists each reason; other skills are unaffected):

- Invalid skill name (characters that cannot appear in a directory name like `/` `:`, empty, or too long);
- A same-named skill already exists in the target scope (never overwritten);
- Empty skill body;
- The archive contains no directory with `SKILL.md` (the whole import fails).

## Skill market

"+ → Skill market" is an online skill catalog: a set of ready-made skills organized by category (backend, frontend, UI/UX, testing, DevOps, productivity, etc.) — pick one and install it into the current project with a tap.

Top to bottom the page has:

- **Category filter bar**: the "All" and category chips laid out horizontally at the top; tap to switch and the list below refreshes.
- **Skill list**: rows under a category (icon, name, one-line description); scrolling near the bottom auto-loads the next page; an empty list shows that the category has no skills yet; the refresh button at the top-right reloads the whole page.

Tapping any row opens a **details bottom sheet**: skill name, author, release time and version, description, plus a note that it will be saved to the current project. Tap **Install** to download the skill body and write it into the current project's `.xencode/skills/`; on success a toast shows, the sheet closes, and the skill list refreshes. An installed skill is fully equivalent to a hand-made one — keep editing it, toggle it, or swipe left to delete.

A few notes:

- Market skills are **always installed into the current project scope** (details above under "Where skills come from") and never touch your global skills; on a name clash an index is appended automatically (e.g. `Rest Api Design Review 2`) — nothing is overwritten.
- Failed installs (server unreachable, invalid name, etc.) show the reason in a toast and can be retried directly.
- The market list is fetched from a built-in service (`https://xencodepro.onrender.com`); the URL can also be changed in `MarketplaceConfig` in the app source. When the network is down or the service is unresponsive, the page shows a retry prompt.

## Details page

Three cards: "Enabled" (name, source and the enable toggle), "Summary" (the description), and "Body" (the full content of `SKILL.md` in monospace, selectable and copyable).

The pencil button at the top-right opens the edit page.

## Creating & editing

The edit page has two groups:

- **Basics**: name, description, scope. The name is the identifier the AI uses when loading the skill — lowercase English with hyphens is recommended (e.g. `pdf-report`); the description should state "when to use it" and the AI decides whether to load it based on that sentence; the scope — "current project" or "global" — decides at which level the skill directory lands.
- **Instruction body**: the skill's full instructions, in Markdown. Spell out the standard procedure, constraints and caveats for this kind of task.

The "Save" button at the top-right unlocks only after both name and body are filled. Saving writes `<skill directory>/SKILL.md` — fully equivalent to a hand-written skill, and afterwards editable with a text editor or container commands just the same.

A few things to watch:

- **Scope can only be chosen at creation**. For existing skills the scope is greyed out — changing scope means relocating the directory, which you must do yourself inside the container.
- **Renaming only changes `name` inside `SKILL.md`, not the directory name**. Skill bodies often reference sibling scripts as `~/.xencode/skills/<directory>/run.py`; renaming the directory too would break those references.
- When editing a skill installed from GitHub that uses `CLAUDE.md` instead of `SKILL.md`, saving writes back to the original file — no extra copy appears.
- Saving fails — with a red banner at the top of the page — when a same-named skill already exists in the scope, the name contains characters like `/` `:` that cannot appear in a directory name, or the body is empty.

## Enabling & disabling

The toggle on the details page controls whether the skill takes part in AI sessions. Disabled, it disappears from the available-skills list in the system prompt and the AI cannot load it, saving context — very useful when you have many skills.

The disable list is written to `skills.json`: the global list lives at `~/.xencode/skills.json` in the current execution environment — in local mode stored in the app's private directory on the phone, in remote mode stored on the server; the two are independent and never migrated or synced automatically. The project-level list is at `<workspace>/.xencode/skills.json` in local mode; in remote mode it still lives in the app's private directory on the phone, isolated per server and per workspace. Format:

```json
{ "disabled": ["skill-name"] }
```

The two levels are unioned — disabled in either scope means disabled. You can also hand-edit the list; it takes effect on the next read. In local mode the list refreshes within seconds; in remote mode, after hand-editing the list on the server you must leave and re-enter the settings page to refresh.
