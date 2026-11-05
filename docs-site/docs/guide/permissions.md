# Tool Authorization

The AI needs your authorization before running commands or writing files. This page manages the saved authorization rules; the entry is "Settings → Tool authorization".

## Where rules come from

When the AI asks to call a tool in conversation, an authorization dialog pops up with two choices:

- **Always allow**: remembers an allow rule in the **current project**; future calls matching the rule pass automatically. Rules are exact matches (e.g. `git pull`, `rm temp.log`). Commands containing pipes, redirections or command substitution cannot be remembered and can only be allowed once.
- **Deny**: refuses just this once and produces no rule.

There is also a built-in allowlist: a handful of completely harmless, read-only commands that spawn no subprocesses (`ls`, `pwd`, `cat`, `grep`, `ps`, `top`, `git status`, etc.) pass without a dialog.

Exception: the `Shizuku` tool (operating on the host Android system as adb shell) is high-risk — every command pops a confirmation dialog, "Always allow" is not supported, and the built-in allowlist does not apply; see [Shizuku tool](/guide/shizuku).

## Authorization with parallel sessions

When multiple sessions (including subagents) work at the same time, each can have an authorization request pending without blocking the others:

- The top of the authorization dialog shows the **title of the session that raised it** (e.g. "Session 'Refactor the login module'"), making clear which session the dialog belongs to.
- The dialog appears on any session's screen, and after you handle it, it automatically moves to the next pending request.
- In the sidebar session list, the indicator on the left of a session **waiting for authorization** is **solid orange** (green pulsing while executing). That is how you spot background sessions suspended on authorization — tap in and handle them.

## Page structure

Rules come in two collapsible groups, "Current project" and "Global"; tap a title to fold it.

Each rule shows a verdict icon (check mark = allow, cross = deny), the tool name, the match scope and verdict, e.g. `git pull · allow`, `entire tool · allow`.

- A plus icon on the right of a project rule can **promote it to global** so it applies to all projects; after promotion it disappears from this project's list.
- Swipe a rule row left to reveal the red delete button.

## Disabling safety interception

At the top of the page there is a "Disable safety interception" toggle, off by default.

- Off (default): even in AUTO (automatic) mode, catastrophic deletions are still intercepted — `rm` on the root directory, critical system directories (`/etc`, `/usr`, etc.), the workspace root or the user home directory are rejected outright; commands containing command substitution, sub-shells, absolute-path redirections and the like that cannot be statically judged and look destructive (e.g. `rm -rf $(...)`, `cat > /etc/xxx`) are likewise blocked (the deletion target cannot be confirmed). Meanwhile the `Shizuku` tool still asks per call in AUTO (not exempted by "allow all"). This is the last line of defense against accidental deletion.
- On: in AUTO mode even this layer is removed — every command passes, and the `Shizuku` tool stops popping dialogs. Note that "Shizuku cannot be remembered" is unaffected by this toggle: in BUILD mode it still asks every time and cannot be remembered. Safety interception in BUILD / PLAN modes is unaffected.

Before turning it on, a confirmation dialog appears; the confirm button has a **5-second countdown** and cannot be tapped before it ends, guarding against accidents.

::: danger Enable only when truly necessary
Once enabled, destructive commands like `rm` in AUTO mode have no last resort left behind. Turn it on only when you fully understand the risk and genuinely need it.
:::

## Elevation for high-risk commands

Outside PLAN mode, catastrophic operations — `rm` on the root directory, critical system directories (`/etc`, `/usr`, etc.), the workspace root or the user home directory — are rejected outright by the built-in guard. If the AI judges one truly necessary, it retries the call with `elevate: true`: instead of a flat rejection, a dialog asks for a **one-time authorization** (titled "High-risk elevation confirmation").

- Available in both BUILD and AUTO; in AUTO there are two cases that still pop a dialog: catastrophic-deletion elevation confirmation, and `Shizuku` tool calls.
- Elevation applies only to that single call and produces no remembered rule ("Always allow" is unavailable).
- PLAN mode still hard-blocks, unaffected by elevation.

## About "deny" rules

The authorization dialog has no "deny" option (denying affects only the single call), so normal usage never produces deny rules.

Deny rules can currently only be created by hand-editing the config files: project rules live in `<project root>/.xencode/permissions.json`, global rules in `~/.xencode/permissions.json`. After a manual edit is saved, the app detects it and refreshes the page within seconds, and the rule takes effect immediately.

Config file format:

```json
{
  "permissions": {
    "allow": ["Bash(git pull)", "writeFile"],
    "deny": ["Bash(rm -rf /)"]
  }
}
```

::: tip Global deny wins over project allow
Rules in `deny` block same-named `allow` rules. For example, if `Bash(rm -rf /)` is denied globally, no project can ever allow that command. For an extra safety net, add rules to the global `deny` list.
:::
