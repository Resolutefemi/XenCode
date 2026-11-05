//! Thin git shell wrappers (zero heavy deps — we drive the `git` binary).

use crate::error::{Result, XeError};
use std::path::{Path, PathBuf};
use std::process::Command;

pub fn run(dir: &Path, args: &[&str]) -> Result<String> {
    let out = Command::new("git")
        .args(args)
        .current_dir(dir)
        .env("GIT_OPTIONAL_LOCKS", "0")
        .output()
        .map_err(|e| XeError::Git(format!("git spawn failed: {e}")))?;
    if !out.status.success() {
        return Err(XeError::Git(format!(
            "git {} failed: {}",
            args.first().unwrap_or(&""),
            String::from_utf8_lossy(&out.stderr).trim()
        )));
    }
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
}

pub fn run_out(dir: &Path, args: &[&str]) -> std::result::Result<String, String> {
    run(dir, args).map_err(|e| e.to_string())
}

pub fn is_git_repo(dir: &Path) -> bool {
    run(dir, &["rev-parse", "--is-inside-work-tree"])
        .map(|s| s == "true")
        .unwrap_or(false)
}

/// Porcelain status of the tree, excluding the `.xencode` runtime directory.
pub fn porcelain_status(dir: &Path) -> Result<String> {
    run(dir, &["status", "--porcelain", "--", ".", ":(exclude).xencode"])
}

pub fn has_commits(dir: &Path) -> bool {
    run(dir, &["rev-parse", "HEAD"]).is_ok()
}

/// Create an isolated worktree + branch for a goal node (swimlane).
/// Returns (path, branch).
pub fn worktree_create(root: &Path, id: &str) -> std::result::Result<(PathBuf, String), String> {
    if !is_git_repo(root) {
        return Err("not a git repository".into());
    }
    let base = root.join(".xencode").join("worktrees");
    std::fs::create_dir_all(&base).map_err(|e| e.to_string())?;
    let path = base.join(id);
    if path.exists() {
        return Err(format!("worktree `{id}` already exists"));
    }
    let branch = format!("xencode-swimlane-{id}");
    run(root, &["worktree", "add", "-b", &branch, path.to_string_lossy().as_ref()])
        .map_err(|e| e.to_string())?;
    Ok((path, branch))
}

pub fn worktree_remove(root: &Path, id: &str) -> std::result::Result<(), String> {
    let path = root.join(".xencode").join("worktrees").join(id);
    if !path.exists() {
        return Err(format!("worktree `{id}` not found"));
    }
    run(root, &["worktree", "remove", "--force", path.to_string_lossy().as_ref()])
        .map_err(|e| e.to_string())?;
    let branch = format!("xencode-swimlane-{id}");
    let _ = run(root, &["branch", "-D", &branch]);
    Ok(())
}

pub fn worktree_list(root: &Path) -> Vec<String> {
    let base = root.join(".xencode").join("worktrees");
    std::fs::read_dir(&base)
        .into_iter()
        .flatten()
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_dir())
        .map(|e| e.file_name().to_string_lossy().to_string())
        .collect()
}

pub fn diff_stat(root: &Path) -> std::result::Result<String, String> {
    if !is_git_repo(root) {
        return Err("not a git repository".into());
    }
    run_out(root, &["diff", "--stat"])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn git_repo() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        run(dir.path(), &["init", "-q"]).unwrap();
        run(dir.path(), &["config", "user.email", "t@t"]).unwrap();
        run(dir.path(), &["config", "user.name", "t"]).unwrap();
        dir
    }

    #[test]
    fn repo_detection_and_status() {
        let dir = git_repo();
        assert!(is_git_repo(dir.path()));
        let plain = tempfile::tempdir().unwrap();
        assert!(!is_git_repo(plain.path()));
        std::fs::write(dir.path().join("x.txt"), "1").unwrap();
        let st = porcelain_status(dir.path()).unwrap();
        assert!(st.contains("x.txt"));
    }

    #[test]
    fn worktree_lifecycle() {
        let dir = git_repo();
        std::fs::write(dir.path().join("s.txt"), "s").unwrap();
        run(dir.path(), &["add", "-A"]).unwrap();
        run(dir.path(), &["commit", "-q", "-m", "s"]).unwrap();
        let (path, branch) = worktree_create(dir.path(), "g1").unwrap();
        assert!(path.exists());
        assert_eq!(branch, "xencode-swimlane-g1");
        assert_eq!(worktree_list(dir.path()), vec!["g1".to_string()]);
        worktree_remove(dir.path(), "g1").unwrap();
        assert!(worktree_list(dir.path()).is_empty());
    }
}
