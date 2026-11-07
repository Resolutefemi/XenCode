//! Checkpoints: ledger (`.xencode/checkpoints.json`) + atomic git snapshot
//! commits `xencode-checkpoint(<label>): <summary>` — the time machine.

use crate::config::write_atomic;
use crate::gitx;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Checkpoint {
    pub id: String,
    pub label: String,
    pub summary: String,
    #[serde(default)]
    pub commit: Option<String>,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "filesChanged")]
    pub files_changed: u32,
}

pub struct CheckpointStore {
    pub root: PathBuf,
}

impl CheckpointStore {
    pub fn new(workspace_root: &Path) -> Self {
        Self { root: workspace_root.to_path_buf() }
    }

    fn ledger(&self) -> PathBuf {
        self.root.join(".xencode").join("checkpoints.json")
    }

    pub fn list(&self) -> Vec<Checkpoint> {
        std::fs::read_to_string(self.ledger())
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok())
            .unwrap_or_default()
    }

    /// Create a checkpoint: stage everything except `.xencode`, commit, record.
    pub fn create(&self, label: &str, summary: &str) -> Result<Checkpoint, String> {
        if !gitx::is_git_repo(&self.root) {
            return Err("not a git repository: `git init` the workspace to enable checkpoints".into());
        }
        let dirty = gitx::porcelain_status(&self.root).map_err(|e| e.to_string())?;
        if dirty.trim().is_empty() {
            return Err("nothing to checkpoint (working tree clean)".into());
        }
        let files_changed = dirty.lines().filter(|l| !l.trim().is_empty()).count() as u32;
        gitx::run(&self.root, &["add", "-A", "--", ".", ":(exclude).xencode"]).map_err(|e| e.to_string())?;
        gitx::run(&self.root, &["commit", "-m", &format!("xencode-checkpoint({label}): {summary}"), "--allow-empty-message"])
            .map_err(|e| e.to_string())?;
        let commit = gitx::run_out(&self.root, &["rev-parse", "--short", "HEAD"])?;
        let cp = Checkpoint {
            id: format!("c{}", crate::base36(crate::now_ms())),
            label: label.to_string(),
            summary: summary.to_string(),
            commit: Some(commit),
            created_at: crate::now_ms(),
            files_changed,
        };
        self.append(&cp)?;
        Ok(cp)
    }

    fn append(&self, cp: &Checkpoint) -> Result<(), String> {
        let mut all = self.list();
        all.push(cp.clone());
        let json = serde_json::to_string_pretty(&all).map_err(|e| e.to_string())?;
        write_atomic(&self.ledger(), json.as_bytes()).map_err(|e| e.to_string())
    }

    /// Hard-reset the working tree to a checkpoint's commit.
    pub fn restore(&self, id: &str) -> Result<String, String> {
        let cp = self
            .list()
            .into_iter()
            .find(|c| c.id == id)
            .ok_or_else(|| format!("no checkpoint with id `{id}`"))?;
        let commit = cp.commit.as_deref().ok_or("checkpoint has no git commit")?;
        gitx::run(&self.root, &["reset", "--hard", commit]).map_err(|e| e.to_string())?;
        Ok(format!("restored {id} -> {commit}: {}", cp.summary))
    }

    pub fn diff(&self, id: &str) -> Result<String, String> {
        let cp = self
            .list()
            .into_iter()
            .find(|c| c.id == id)
            .ok_or_else(|| format!("no checkpoint with id `{id}`"))?;
        match &cp.commit {
            Some(c) => gitx::run_out(&self.root, &["diff", "--stat", &format!("{c}~..{c}")]),
            None => Err("checkpoint has no git commit".into()),
        }
    }

    pub fn render_list(&self) -> String {
        let all = self.list();
        if all.is_empty() {
            return "(no checkpoints)".into();
        }
        all.iter()
            .rev()
            .map(|c| {
                format!(
                    "{}  {}  {} files  {}{}",
                    c.id,
                    crate::state::sessions::fmt_ts(c.created_at),
                    c.files_changed,
                    c.summary,
                    c.commit.as_deref().map(|h| format!("  @{h}")).unwrap_or_default()
                )
            })
            .collect::<Vec<_>>()
            .join("\n")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn git_repo() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        gitx::run(dir.path(), &["init", "-q"]).unwrap();
        gitx::run(dir.path(), &["config", "user.email", "t@t"]).unwrap();
        gitx::run(dir.path(), &["config", "user.name", "t"]).unwrap();
        std::fs::write(dir.path().join("seed.txt"), "seed\n").unwrap();
        gitx::run(dir.path(), &["add", "-A"]).unwrap();
        gitx::run(dir.path(), &["commit", "-q", "-m", "seed"]).unwrap();
        dir
    }

    #[test]
    fn create_list_restore() {
        let dir = git_repo();
        let store = CheckpointStore::new(dir.path());
        std::fs::write(dir.path().join("f.txt"), "v1\n").unwrap();
        let cp = store.create("g1", "first drop").unwrap();
        assert_eq!(cp.files_changed, 1);
        std::fs::write(dir.path().join("f.txt"), "v2\n").unwrap();
        let cp2 = store.create("g2", "second").unwrap();
        assert_ne!(cp.id, cp2.id);
        assert_eq!(store.list().len(), 2);
        let out = store.restore(&cp.id).unwrap();
        assert!(out.contains("restored"));
        assert_eq!(std::fs::read_to_string(dir.path().join("f.txt")).unwrap(), "v1\n");
        let d = store.diff(&cp.id).unwrap();
        assert!(!d.is_empty());
        // Clean tree is a no-op error.
        assert!(store.create("g3", "x").is_err());
    }
}
