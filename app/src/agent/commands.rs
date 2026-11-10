//! Markdown slash commands (Phase 3, harness port #2 — from Claude Code).
//!
//! A command is a markdown file in `.xencode/commands/` (project scope) or
//! `~/.xencode/commands/` (user scope); the file stem is the command name
//! (`review.md` → `/review`). Typing `/review the auth diff` expands to the
//! file body with `$ARGUMENTS` replaced by `the auth diff` — the expanded
//! text is what the agent loop receives as the prompt.
//!
//! Optional frontmatter carries a `description:` (shown by `--commands`);
//! anything else in frontmatter is ignored. When the body has no
//! `$ARGUMENTS` placeholder the arguments are appended as a section, so
//! argument-less instructions still receive them. Project commands shadow
//! user commands with the same name.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use crate::agent::memory::home_dir;

const COMMANDS_DIR: &str = ".xencode/commands";
/// Arguments placeholder — every occurrence is replaced.
pub const ARGUMENTS_PLACEHOLDER: &str = "$ARGUMENTS";

/// One parsed slash command.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SlashCommand {
    /// File stem — the name after the slash.
    pub name: String,
    /// `description:` from the frontmatter (empty when absent).
    pub description: String,
    /// Body with frontmatter stripped, `$ARGUMENTS` untouched.
    pub body: String,
    /// `user` or `project`.
    pub scope: &'static str,
    pub path: PathBuf,
}

impl SlashCommand {
    /// Expand this command for `arguments` (the text after `/name`).
    pub fn expand(&self, arguments: &str) -> String {
        let args = arguments.trim();
        if self.body.contains(ARGUMENTS_PLACEHOLDER) {
            self.body.replace(ARGUMENTS_PLACEHOLDER, args)
        } else if args.is_empty() {
            self.body.clone()
        } else {
            format!(
                "{}\n\nArguments:\n{}\n",
                self.body.trim_end(),
                args
            )
        }
    }
}

/// Parse a command markdown file: optional `---` frontmatter, then the body.
fn parse(scope: &'static str, path: PathBuf) -> Option<SlashCommand> {
    let text = std::fs::read_to_string(&path).ok()?;
    let name = path.file_stem()?.to_string_lossy().into_owned();
    if name.is_empty() {
        return None;
    }

    let mut description = String::new();
    let mut body = text.as_str();

    if let Some(rest) = body.strip_prefix("---") {
        if let Some(end) = rest.find("\n---") {
            let front = &rest[..end];
            body = rest[end + 4..].trim_start_matches('\n');
            for line in front.lines() {
                let line = line.trim();
                if let Some(v) = line.strip_prefix("description:") {
                    description = v.trim().trim_matches('"').to_string();
                }
            }
        }
        // Unterminated frontmatter → treat the whole file as body.
    }

    Some(SlashCommand { name, description, body: body.to_string(), scope, path })
}

/// Load every command visible for a project: user scope first, then project
/// scope; project commands shadow user commands of the same name. Missing
/// directories are skipped.
pub fn list(cwd: &Path) -> Vec<SlashCommand> {
    let mut by_name: BTreeMap<String, SlashCommand> = BTreeMap::new();
    if let Some(home) = home_dir() {
        for cmd in scan("user", &home.join(COMMANDS_DIR)) {
            by_name.entry(cmd.name.clone()).or_insert(cmd);
        }
    }
    for cmd in scan("project", &cwd.join(COMMANDS_DIR)) {
        by_name.insert(cmd.name.clone(), cmd); // project wins
    }
    by_name.into_values().collect()
}

fn scan(scope: &'static str, dir: &Path) -> Vec<SlashCommand> {
    let Ok(rd) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut out = Vec::new();
    for entry in rd.flatten() {
        let p = entry.path();
        if p.extension().map(|e| e == "md").unwrap_or(false) && p.is_file() {
            if let Some(cmd) = parse(scope, p) {
                out.push(cmd);
            }
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}

/// Expand a prompt that starts with `/name` into the command body. Returns
/// `None` when the input is not a command (prompt passes through unchanged)
/// or names an unknown command (also passed through, so typos are visible).
pub fn expand(cwd: &Path, input: &str) -> Option<String> {
    let input = input.trim();
    let rest = input.strip_prefix('/')?;
    if rest.is_empty() || rest.starts_with(char::is_whitespace) {
        return None;
    }
    let (name, args) = match rest.find(char::is_whitespace) {
        Some(i) => (&rest[..i], rest[i..].trim()),
        None => (rest, ""),
    };
    list(cwd)
        .into_iter()
        .find(|c| c.name == name)
        .map(|c| c.expand(args))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-cmd-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn with_home(home: &Path, f: impl FnOnce()) {
        let _guard = crate::agent::memory::env_lock();
        let old = std::env::var("HOME").ok();
        std::env::set_var("HOME", home);
        f();
        match old {
            Some(v) => std::env::set_var("HOME", v),
            None => std::env::remove_var("HOME"),
        }
    }

    #[test]
    fn parses_frontmatter_and_body() {
        let dir = tmp("parse");
        let empty_home = tmp("parse-home");
        let cdir = dir.join(".xencode/commands");
        fs::create_dir_all(&cdir).unwrap();
        fs::write(
            cdir.join("review.md"),
            "---\ndescription: Review code for bugs and style\nmodel: ignored\n---\nReview $ARGUMENTS carefully. Check error handling.\n",
        )
        .unwrap();

        with_home(&empty_home, || {
            let cmds = list(&dir);
            assert_eq!(cmds.len(), 1);
            assert_eq!(cmds[0].name, "review");
            assert_eq!(cmds[0].description, "Review code for bugs and style");
            assert_eq!(cmds[0].scope, "project");
            assert!(cmds[0].body.contains("$ARGUMENTS"));
        });
    }

    #[test]
    fn expands_arguments_placeholder() {
        let dir = tmp("expand");
        let empty_home = tmp("expand-home");
        let cdir = dir.join(".xencode/commands");
        fs::create_dir_all(&cdir).unwrap();
        fs::write(cdir.join("fix.md"), "Fix the issue in $ARGUMENTS. Run the tests.\n").unwrap();

        with_home(&empty_home, || {
            let out = expand(&dir, "/fix the login race condition").unwrap();
            assert_eq!(out, "Fix the issue in the login race condition. Run the tests.\n");

            // Empty arguments → placeholder becomes empty, no Arguments section.
            let out2 = expand(&dir, "/fix").unwrap();
            assert_eq!(out2, "Fix the issue in . Run the tests.\n");
        });
    }

    #[test]
    fn appends_arguments_when_no_placeholder() {
        let dir = tmp("append");
        let empty_home = tmp("append-home");
        let cdir = dir.join(".xencode/commands");
        fs::create_dir_all(&cdir).unwrap();
        fs::write(cdir.join("audit.md"), "Audit the whole repository.\n").unwrap();

        with_home(&empty_home, || {
            let out = expand(&dir, "/audit src/ and tests/").unwrap();
            assert!(out.starts_with("Audit the whole repository."));
            assert!(out.contains("Arguments:\nsrc/ and tests/"));
        });
    }

    #[test]
    fn project_shadows_user_scope() {
        let home = tmp("shadow-home");
        let proj = tmp("shadow-proj");
        fs::create_dir_all(home.join(".xencode/commands")).unwrap();
        fs::create_dir_all(proj.join(".xencode/commands")).unwrap();
        fs::write(home.join(".xencode/commands/deploy.md"), "USER deploy flow").unwrap();
        fs::write(proj.join(".xencode/commands/deploy.md"), "PROJECT deploy flow").unwrap();
        fs::write(home.join(".xencode/commands/user-only.md"), "USER only").unwrap();

        with_home(&home, || {
            let cmds = list(&proj);
            assert_eq!(cmds.len(), 2, "user-only + shadowed project command");
            let deploy = cmds.iter().find(|c| c.name == "deploy").unwrap();
            assert_eq!(deploy.scope, "project");
            assert!(deploy.body.contains("PROJECT"));
            let out = expand(&proj, "/deploy prod").unwrap();
            assert!(out.contains("PROJECT deploy flow"));
        });
    }

    #[test]
    fn non_commands_pass_through() {
        let dir = tmp("pass");
        let empty_home = tmp("pass-home");
        with_home(&empty_home, || {
            assert!(expand(&dir, "plain prompt").is_none());
            assert!(expand(&dir, "/").is_none());
            assert!(expand(&dir, "/ nospace").is_none());
            assert!(expand(&dir, "/missing-cmd args").is_none(), "unknown → passthrough");
        });
    }

    #[test]
    fn unterminated_frontmatter_is_body() {
        let dir = tmp("fm");
        let empty_home = tmp("fm-home");
        let cdir = dir.join(".xencode/commands");
        fs::create_dir_all(&cdir).unwrap();
        fs::write(cdir.join("odd.md"), "---\nno closing fence here\n").unwrap();
        with_home(&empty_home, || {
            let cmds = list(&dir);
            assert_eq!(cmds.len(), 1);
            assert!(cmds[0].body.contains("no closing fence"));
            assert_eq!(cmds[0].description, "");
        });
    }
}
