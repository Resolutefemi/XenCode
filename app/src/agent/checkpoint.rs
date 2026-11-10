//! Checkpoints / rewind — Phase 4 harness port #8 (Cline / Claude Code).
//!
//! A shadow git repository under `.xencode/checkpoints/` tracks the project
//! worktree. Before every mutating tool call the loop commits a snapshot
//! (`add -A` + `commit` through plumbing flags, no index or config touched in
//! the user's own repo), so any edit can be rewound from the timeline.
//!
//! - The shadow repo is **bare** (`repo.git`) with a dedicated index file —
//!   the user's `.git` is never touched and `.git`/`.xencode` (plus the usual
//!   build dirs) are excluded from tracking.
//! - Unchanged worktrees produce no checkpoint (a `nothing to commit` commit
//!   is a no-op).
//! - Rewind = `read-tree` the snapshot into the shadow index +
//!   `checkout-index -a -f` to restore files + delete files that only exist
//!   in newer states. Empty directories are pruned best-effort.
//! - Snapshots serialize behind a mutex (parallel subagents share one store).

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

/// One point on the rewind timeline.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Checkpoint {
    /// Stable handle: `ck_<unix_ms>_<seq>`.
    pub id: String,
    pub ts_unix_ms: u64,
    /// The tool that was about to mutate (`write_file`, `edit_file`, `bash`).
    pub tool: String,
    /// Short human detail (the tool's summary — usually the path).
    pub detail: String,
    /// Shadow-repo commit hash.
    pub hash: String,
}

/// Paths never tracked by the shadow repo (mirrors the agent's own walk
/// exclusions).
const EXCLUDES: &[&str] = &[":(exclude).git", ":(exclude).xencode", ":(exclude)target", ":(exclude)node_modules", ":(exclude)dist", ":(exclude)build"];

/// Directories skipped during rewind cleanup (same set, plain names).
const SKIP_DIRS: &[&str] = &[".git", ".xencode", "target", "node_modules", "dist", "build"];

pub struct CheckpointStore {
    /// `.xencode/checkpoints` — meta.jsonl + repo.git + index live here.
    dir: PathBuf,
    /// The project worktree being tracked.
    work: PathBuf,
    /// One snapshot at a time (parallel subagents share the shadow index).
    lock: Mutex<()>,
    /// Warn once when git is unavailable or the store cannot initialize.
    warned: AtomicBool,
}

impl CheckpointStore {
    /// Open (and lazily initialize on first snapshot) the store for `cwd`.
    pub fn open(cwd: &Path) -> std::sync::Arc<Self> {
        std::sync::Arc::new(Self {
            dir: cwd.join(".xencode").join("checkpoints"),
            work: cwd.to_path_buf(),
            lock: Mutex::new(()),
            warned: AtomicBool::new(false),
        })
    }

    fn git(&self) -> tokio::process::Command {
        let mut c = tokio::process::Command::new("git");
        c.arg("--git-dir").arg(self.dir.join("repo.git"))
            .arg("--work-tree").arg(&self.work)
            .env("GIT_INDEX_FILE", self.dir.join("index"))
            .current_dir(&self.work)
            .stdin(std::process::Stdio::null());
        c
    }

    async fn ensure_init(&self) -> Result<()> {
        if self.dir.join("repo.git").join("HEAD").exists() {
            return Ok(());
        }
        tokio::fs::create_dir_all(&self.dir).await?;
        let out = tokio::process::Command::new("git")
            .arg("init")
            .arg("-q")
            .arg("--bare")
            .arg(self.dir.join("repo.git"))
            .output()
            .await
            .context("run git init (is git installed?)")?;
        if !out.status.success() {
            bail!("git init failed: {}", String::from_utf8_lossy(&out.stderr).trim());
        }
        Ok(())
    }

    fn warn_once(&self, msg: &str) {
        if !self.warned.swap(true, Ordering::Relaxed) {
            eprintln!("xencode · checkpoints: {msg} (continuing without checkpoints)");
        }
    }

    /// Commit a snapshot of the current worktree. `Ok(None)` = nothing
    /// changed since the previous snapshot (or the store is unavailable).
    pub async fn snapshot(&self, tool: &str, detail: &str) -> Option<Checkpoint> {
        let _guard = self.lock.lock().await;
        let inner = self.snapshot_locked(tool, detail).await;
        match inner {
            Ok(cp) => cp,
            Err(e) => {
                self.warn_once(&format!("{e:#}"));
                None
            }
        }
    }

    async fn snapshot_locked(&self, tool: &str, detail: &str) -> Result<Option<Checkpoint>> {
        self.ensure_init().await?;

        let msg = format!("checkpoint: {tool} {detail}");
        let add = self.git().arg("add").arg("-A").arg("--").arg(".").args(EXCLUDES).output().await?;
        if !add.status.success() {
            bail!("git add failed: {}", String::from_utf8_lossy(&add.stderr).trim());
        }

        let commit = self
            .git()
            .args(["-c", "user.name=XenCode", "-c", "user.email=checkpoint@xencode.local"])
            .args(["commit", "-q", "-m", &msg])
            .output()
            .await?;
        if !commit.status.success() {
            let err = String::from_utf8_lossy(&commit.stderr);
            if err.contains("nothing to commit") || err.contains("nothing added") {
                return Ok(None); // unchanged worktree — no checkpoint
            }
            bail!("git commit failed: {}", err.trim());
        }

        let hash_out = self.git().args(["rev-parse", "HEAD"]).output().await?;
        let hash = String::from_utf8_lossy(&hash_out.stdout).trim().to_string();

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default();
        let seq = self.list().len() + 1;
        let cp = Checkpoint {
            id: format!("ck_{:013}_{}", now.as_millis(), seq),
            ts_unix_ms: now.as_millis() as u64,
            tool: tool.to_string(),
            detail: detail.chars().take(80).collect(),
            hash,
        };

        // Append to the timeline (JSONL — one checkpoint per line).
        use tokio::io::AsyncWriteExt;
        let mut f = tokio::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(self.dir.join("meta.jsonl"))
            .await?;
        f.write_all(serde_json::to_string(&cp)?.as_bytes()).await?;
        f.write_all(b"\n").await?;
        Ok(Some(cp))
    }

    /// The full timeline, oldest first.
    pub fn list(&self) -> Vec<Checkpoint> {
        let Ok(text) = std::fs::read_to_string(self.dir.join("meta.jsonl")) else {
            return Vec::new();
        };
        text.lines().filter_map(|l| serde_json::from_str(l).ok()).collect()
    }

    /// Resolve `target` (an id, a hash prefix, or `latest`) to a checkpoint.
    pub fn resolve<'a>(&self, entries: &'a [Checkpoint], target: &str) -> Result<&'a Checkpoint> {
        if target == "latest" {
            return entries.last().context("no checkpoints recorded yet");
        }
        if let Some(cp) = entries.iter().find(|c| c.id == target) {
            return Ok(cp);
        }
        if let Some(cp) = entries.iter().find(|c| c.hash.starts_with(target)) {
            return Ok(cp);
        }
        bail!("no checkpoint matches `{target}` — see `xencode --checkpoints`");
    }

    /// Restore the worktree to a checkpoint: snapshot files are rewritten,
    /// files added after the snapshot are deleted, empty dirs are pruned.
    pub async fn rewind(&self, target: &str) -> Result<Checkpoint> {
        let _guard = self.lock.lock().await;
        let entries = self.list();
        let cp = self.resolve(&entries, target)?.clone();

        self.ensure_init().await?;

        let read = self.git().args(["read-tree", &cp.hash]).output().await?;
        if !read.status.success() {
            bail!("git read-tree failed: {}", String::from_utf8_lossy(&read.stderr).trim());
        }
        let checkout = self.git().args(["checkout-index", "-a", "-f"]).output().await?;
        if !checkout.status.success() {
            bail!("git checkout-index failed: {}", String::from_utf8_lossy(&checkout.stderr).trim());
        }

        // Delete files that did not exist at the snapshot point.
        let ls = self.git().args(["ls-files"]).output().await?;
        let tracked: HashSet<String> = String::from_utf8_lossy(&ls.stdout)
            .lines()
            .map(|l| l.trim().replace('\\', "/"))
            .filter(|l| !l.is_empty())
            .collect();

        let mut extras: Vec<PathBuf> = Vec::new();
        walk_extras(&self.work, &self.work, &tracked, &mut extras, 0);
        let mut removed = 0usize;
        for p in extras {
            if tokio::fs::remove_file(&p).await.is_ok() {
                removed += 1;
            }
        }
        prune_empty_dirs(&self.work, 0);

        eprintln!(
            "xencode · rewound to {} ({} — before {} {}); {} file(s) restored, {removed} file(s) removed",
            cp.id,
            &cp.hash[..7.min(cp.hash.len())],
            cp.tool,
            cp.detail,
            tracked.len()
        );
        Ok(cp)
    }
}

/// Collect worktree files missing from the snapshot's tracked set. `root` is
/// the worktree root, `dir` the directory currently being walked.
fn walk_extras(root: &Path, dir: &Path, tracked: &HashSet<String>, out: &mut Vec<PathBuf>, depth: usize) {
    if depth > 16 {
        return;
    }
    let Ok(rel_dir) = dir.strip_prefix(root) else { return };
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for entry in rd.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let path = entry.path();
        let rel = if rel_dir.as_os_str().is_empty() {
            name.clone()
        } else {
            format!("{}/{}", rel_dir.to_string_lossy().replace('\\', "/"), name)
        };
        let Ok(ft) = entry.file_type() else { continue };
        if ft.is_dir() {
            if SKIP_DIRS.contains(&name.as_str()) {
                continue;
            }
            walk_extras(root, &path, tracked, out, depth + 1);
        } else if !tracked.contains(&rel) {
            out.push(path);
        }
    }
}

/// Best-effort removal of now-empty directories (depth-bounded).
fn prune_empty_dirs(dir: &Path, depth: usize) {
    if depth > 16 {
        return;
    }
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for entry in rd.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let path = entry.path();
        if path.is_dir() && !SKIP_DIRS.contains(&name.as_str()) {
            prune_empty_dirs(&path, depth + 1);
            let _ = std::fs::remove_dir(&path); // succeeds only when empty
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-ck-{}-{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[tokio::test]
    async fn snapshots_commit_and_accumulate() {
        let dir = tmp("snap");
        std::fs::write(dir.join("a.txt"), "one").unwrap();

        let store = CheckpointStore::open(&dir);
        let c1 = store.snapshot("write_file", "a.txt").await.expect("first snapshot");
        assert!(c1.id.starts_with("ck_"));
        assert_eq!(c1.tool, "write_file");

        std::fs::write(dir.join("a.txt"), "two").unwrap();
        let c2 = store.snapshot("edit_file", "a.txt").await.expect("second snapshot");
        assert_ne!(c1.hash, c2.hash);

        let timeline = store.list();
        assert_eq!(timeline.len(), 2, "meta.jsonl has both checkpoints");
        assert_eq!(timeline[1].id, c2.id);

        // Unchanged worktree → no checkpoint (no timeline growth).
        let none = store.snapshot("bash", "true").await;
        assert!(none.is_none(), "unchanged state must not checkpoint");
        assert_eq!(store.list().len(), 2);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn rewind_restores_content_and_removes_extras() {
        let dir = tmp("rewind");
        std::fs::write(dir.join("a.txt"), "original").unwrap();
        let store = CheckpointStore::open(&dir);
        let c1 = store.snapshot("write_file", "a.txt").await.unwrap();

        // Mutate: edit a.txt, add b.txt, add + remove dir entry.
        std::fs::create_dir(dir.join("sub")).unwrap();
        std::fs::write(dir.join("a.txt"), "changed").unwrap();
        std::fs::write(dir.join("sub/b.txt"), "extra").unwrap();
        std::fs::write(dir.join("sub/c.txt"), "extra2").unwrap();
        let c2 = store.snapshot("edit_file", "a.txt").await.unwrap();
        assert_ne!(c1.hash, c2.hash);

        // Rewind by id.
        store.rewind(&c1.id).await.unwrap();
        assert_eq!(std::fs::read_to_string(dir.join("a.txt")).unwrap(), "original");
        assert!(!dir.join("sub/b.txt").exists(), "post-snapshot files must be removed");
        assert!(!dir.join("sub/c.txt").exists());

        // Latest still resolves to c2 (timeline is append-only).
        let entries = store.list();
        let latest = store.resolve(&entries, "latest").unwrap();
        assert_eq!(latest.id, c2.id);
        // Hash-prefix resolution works.
        assert_eq!(store.resolve(&entries, &c1.hash[..8]).unwrap().id, c1.id);
        // Unknown target errors.
        assert!(store.rewind("ck_nope").await.is_err());

        // A snapshot after the rewind records the restored state.
        std::fs::write(dir.join("a.txt"), "changed again").unwrap();
        let c3 = store.snapshot("write_file", "a.txt").await.unwrap();
        assert_ne!(c2.hash, c3.hash);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn excluded_paths_are_never_tracked_or_restored() {
        let dir = tmp("excl");
        std::fs::create_dir_all(dir.join(".xencode")).unwrap();
        std::fs::create_dir_all(dir.join("target")).unwrap();
        std::fs::write(dir.join(".xencode/secret"), "local state").unwrap();
        std::fs::write(dir.join("target/artifact.o"), "build junk").unwrap();
        std::fs::write(dir.join("src.txt"), "tracked").unwrap();

        let store = CheckpointStore::open(&dir);
        store.snapshot("write_file", "src.txt").await.unwrap();

        // Mutations inside excluded paths survive a rewind untouched.
        std::fs::write(dir.join(".xencode/secret"), "changed").unwrap();
        std::fs::write(dir.join("target/artifact.o"), "recompiled").unwrap();
        store.rewind("latest").await.unwrap();
        assert_eq!(std::fs::read_to_string(dir.join(".xencode/secret")).unwrap(), "changed");
        assert_eq!(std::fs::read_to_string(dir.join("target/artifact.o")).unwrap(), "recompiled");
        assert_eq!(std::fs::read_to_string(dir.join("src.txt")).unwrap(), "tracked");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn parallel_snapshots_serialize() {
        let dir = tmp("parallel");
        for i in 0..4 {
            std::fs::write(dir.join(format!("f{i}.txt")), format!("v{i}")).unwrap();
        }
        let store = std::sync::Arc::new(CheckpointStore::open(&dir));
        let mut handles = Vec::new();
        for i in 0..4 {
            let store = store.clone();
            handles.push(tokio::spawn(async move {
                let name = format!("f{i}.txt");
                store.snapshot("write_file", &name).await
            }));
        }
        let mut total = 0usize;
        for h in handles {
            if h.await.unwrap().is_some() {
                total += 1;
            }
        }
        assert!(total >= 1, "at least the first snapshot lands");
        assert_eq!(store.list().len(), total, "timeline matches successful snapshots");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
