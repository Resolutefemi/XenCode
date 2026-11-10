//! Memory files (Phase 3, harness port #1 — from Claude Code / Codex).
//!
//! Project memory lives at the project root as `XENCODE.md` (with `AGENTS.md`
//! as the compatibility fallback for Codex-style repos); user memory lives in
//! `~/.xencode/XENCODE.md` (fallback `AGENTS.md`). Both scopes are loaded
//! once per run and appended to the system prompt, so standing instructions —
//! build commands, conventions, gotchas — reach every turn without being
//! re-stated by the user.
//!
//! Precedence per scope: `XENCODE.md` wins over `AGENTS.md` when both exist
//! (the former is the canonical XenCode name), and project memory always
//! renders after user memory so project-specific rules sit closest to the
//! conversation.

use std::path::{Path, PathBuf};

/// Per-file character caps: a runaway memory file must not eat the context
/// window. 32 KiB per file, 64 KiB for the whole section — plenty for real
/// memory files, tight enough to bound worst-case token spend.
const MAX_FILE_CHARS: usize = 32 * 1024;
const MAX_TOTAL_CHARS: usize = 64 * 1024;

/// One loaded memory file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoryFile {
    /// `user` or `project`.
    pub scope: &'static str,
    /// Canonical file name that provided the content (`XENCODE.md` or
    /// `AGENTS.md` — which fallback won).
    pub source: String,
    pub path: PathBuf,
    pub content: String,
    /// True when the file was truncated at [`MAX_FILE_CHARS`].
    pub truncated: bool,
}

fn read_memory_file(scope: &'static str, path: PathBuf) -> Option<MemoryFile> {
    let text = std::fs::read_to_string(&path).ok()?;
    let content = text.trim().to_string();
    if content.is_empty() {
        return None;
    }
    let mut truncated = false;
    let content = if content.chars().count() > MAX_FILE_CHARS {
        truncated = true;
        content.chars().take(MAX_FILE_CHARS).collect()
    } else {
        content
    };
    Some(MemoryFile {
        scope,
        source: path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
        path,
        content,
        truncated,
    })
}

/// First existing candidate in `dir` — `XENCODE.md`, then `AGENTS.md`.
fn pick(scope: &'static str, dir: &Path, names: &[&str]) -> Option<MemoryFile> {
    for name in names {
        let p = dir.join(name);
        if p.is_file() {
            return read_memory_file(scope, p);
        }
    }
    None
}

/// Load every memory file visible for a project at `cwd`: the user scope
/// (`~/.xencode/`) first, then the project scope. Missing files are simply
/// skipped — memory is optional by design.
pub fn load(cwd: &Path) -> Vec<MemoryFile> {
    let mut out = Vec::new();
    if let Some(home) = home_dir() {
        // User scope lives in ~/.xencode/, not ~ itself.
        out.extend(pick("user", &home.join(".xencode"), &["XENCODE.md", "AGENTS.md"]));
    }
    out.extend(pick("project", cwd, &["XENCODE.md", "AGENTS.md"]));

    // Total budget: project memory is the most specific, so it is protected
    // first; user-scope files are the ones dropped when the budget runs out.
    // Render order (user first) is unchanged either way.
    apply_total_budget(out, MAX_TOTAL_CHARS)
}

/// Keep whole files under `cap` chars, protecting project-scope files before
/// user-scope ones. Called with [`MAX_TOTAL_CHARS`] by [`load`]; parameterized
/// so the priority policy is unit-testable with small caps.
fn apply_total_budget(files: Vec<MemoryFile>, cap: usize) -> Vec<MemoryFile> {
    let mut total = 0usize;
    let mut keep: Vec<bool> = vec![false; files.len()];
    for pass in ["project", "user"] {
        for (i, f) in files.iter().enumerate() {
            if f.scope == pass {
                let len = f.content.chars().count();
                if total + len <= cap {
                    keep[i] = true;
                    total += len;
                }
            }
        }
    }
    files
        .into_iter()
        .zip(keep)
        .filter(|(_, k)| *k)
        .map(|(f, _)| f)
        .collect()
}

/// `~` without the `dirs` crate: `HOME` on unix, `USERPROFILE` on windows.
pub fn home_dir() -> Option<PathBuf> {
    std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .map(PathBuf::from)
        .filter(|p| p.is_dir())
}

/// Serialize the process-global `HOME` mutation used by tests (memory and
/// command tests both redirect the user scope; parallel set_var calls race).
#[cfg(test)]
pub(crate) fn env_lock() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();
    LOCK.get_or_init(|| std::sync::Mutex::new(()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

/// Render the loaded memory files as the memory section of the system prompt.
/// `None` when nothing was found (no empty section in the prompt).
pub fn system_memory_section(cwd: &Path) -> Option<String> {
    let files = load(cwd);
    if files.is_empty() {
        return None;
    }
    let mut out = String::from("\n\n# Project memory\n\nThe following memory files \
        were loaded for this session. They carry the user's standing \
        instructions — treat them as authoritative project conventions.\n");
    for f in &files {
        out.push_str(&format!(
            "\n## {} memory ({})\n\n{}\n",
            f.scope, f.source, f.content
        ));
        if f.truncated {
            out.push_str("\n(truncated at 32k characters)\n");
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-mem-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// Isolate the user-scope lookup from the developer's real home. Takes
    /// the shared env lock — HOME is process-global and tests run in
    /// parallel.
    fn with_home(home: &Path, f: impl FnOnce()) {
        let _guard = env_lock();
        let old = std::env::var("HOME").ok();
        std::env::set_var("HOME", home);
        f();
        match old {
            Some(v) => std::env::set_var("HOME", v),
            None => std::env::remove_var("HOME"),
        }
    }

    #[test]
    fn project_memory_prefers_xencode_md() {
        let dir = tmp("proj");
        let empty_home = tmp("proj-home");
        fs::write(dir.join("XENCODE.md"), "always run cargo test first").unwrap();
        fs::write(dir.join("AGENTS.md"), "legacy agents file").unwrap();

        with_home(&empty_home, || {
            let files = load(&dir);
            assert_eq!(files.len(), 1, "one project file, no user file");
            assert_eq!(files[0].scope, "project");
            assert_eq!(files[0].source, "XENCODE.md", "XENCODE.md wins the fallback race");
            assert!(files[0].content.contains("cargo test"));

            let section = system_memory_section(&dir).unwrap();
            assert!(section.contains("# Project memory"));
            assert!(section.contains("project memory (XENCODE.md)"));
            assert!(section.contains("always run cargo test first"));
        });
    }

    #[test]
    fn agents_md_is_the_fallback() {
        let dir = tmp("agents");
        let empty_home = tmp("agents-home");
        fs::write(dir.join("AGENTS.md"), "codex-style instructions").unwrap();
        with_home(&empty_home, || {
            let files = load(&dir);
            assert_eq!(files[0].source, "AGENTS.md");
            assert!(files[0].content.contains("codex-style"));
        });
    }

    #[test]
    fn user_and_project_scopes_combine() {
        let home = tmp("home");
        let proj = tmp("scopeproj");
        fs::create_dir_all(home.join(".xencode")).unwrap();
        fs::write(home.join(".xencode").join("XENCODE.md"), "user rule: be terse").unwrap();
        fs::write(proj.join("XENCODE.md"), "project rule: use rustfmt").unwrap();

        with_home(&home, || {
            let files = load(&proj);
            assert_eq!(files.len(), 2);
            assert_eq!(files[0].scope, "user", "user scope loads first");
            assert_eq!(files[1].scope, "project");
            let section = system_memory_section(&proj).unwrap();
            let user_pos = section.find("user rule").unwrap();
            let proj_pos = section.find("project rule").unwrap();
            assert!(user_pos < proj_pos, "project rules render after user rules");
        });
    }

    #[test]
    fn no_memory_files_means_no_section() {
        let dir = tmp("empty");
        assert!(load(&dir).is_empty());
        assert!(system_memory_section(&dir).is_none());
    }

    #[test]
    fn oversized_files_are_truncated() {
        let home = tmp("bighome");
        let proj = tmp("bigproj");
        fs::create_dir_all(home.join(".xencode")).unwrap();

        // Both files over the per-file cap → truncated to the cap, both kept:
        // 2 × 32k chars exactly fits the 64k total budget.
        let big = "x".repeat(MAX_FILE_CHARS + 500);
        fs::write(proj.join("XENCODE.md"), &big).unwrap();
        let huge = "y".repeat(MAX_TOTAL_CHARS);
        fs::write(home.join(".xencode").join("XENCODE.md"), &huge).unwrap();

        with_home(&home, || {
            let files = load(&proj);
            assert_eq!(files.len(), 2);
            assert_eq!(files[0].scope, "user");
            assert_eq!(files[0].content.chars().count(), MAX_FILE_CHARS);
            assert_eq!(files[1].scope, "project");
            assert!(files[1].truncated);
            assert_eq!(files[1].content.chars().count(), MAX_FILE_CHARS);
            let section = system_memory_section(&proj).unwrap();
            assert!(section.contains("(truncated at 32k characters)"));
        });
    }

    #[test]
    fn total_budget_protects_project_memory_over_user_memory() {
        let mk = |scope: &'static str, n: usize| MemoryFile {
            scope,
            source: "XENCODE.md".into(),
            path: PathBuf::from("/tmp/XENCODE.md"),
            content: "c".repeat(n),
            truncated: false,
        };
        // 150-char cap: the 100-char project file fits, the 100-char user
        // file does not — project (most specific) wins the budget.
        let kept = apply_total_budget(vec![mk("user", 100), mk("project", 100)], 150);
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].scope, "project");

        // Both fit when the cap allows.
        let kept = apply_total_budget(vec![mk("user", 100), mk("project", 100)], 200);
        assert_eq!(kept.len(), 2);
    }
}
