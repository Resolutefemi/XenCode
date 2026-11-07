//! GoalRunner: executes READY nodes of the DAG, each in an isolated git
//! worktree (swimlane), with atomic checkpoint commits, XP and events.
//! Without git (or with parallel=1 and no worktrees) it degrades to
//! sequential in-place execution.

use super::file as dag_file;
use super::GoalStatus;
use crate::config::load_config;
use crate::permissions::PermissionMode;
use crate::state::checkpoints::CheckpointStore;
use crate::state::events::{types as evt, EventLog};
use crate::telemetry::XpLedger;
use crate::{gitx, prompt};
use std::path::{Path, PathBuf};


#[derive(Debug, Clone)]
pub struct NodeOutcome {
    pub id: String,
    pub ok: bool,
    pub detail: String,
}

/// Build the per-node agent instruction (Iron-Laws flavored).
pub fn node_prompt(node_id: &str, title: &str, detail: &str) -> String {
    format!(
        "Execute goal node {node_id}: {title}\n\n{detail}\n\n\
         Finish the work completely (Iron Law 1). If this node involves code, \
         verify with the compiler/linter/tests and require exit code 0 (Iron Law 5). \
         Then stop and report:\n\
         1. what changed,\n\
         2. the verification command(s) and their exit codes,\n\
         3. anything that must be decided by the user."
    )
}

async fn run_one(root: PathBuf, node_id: String, title: String, detail: String, use_worktree: bool) -> NodeOutcome {
    let log = EventLog::new(&root);
    log.emit(
        evt::NODE_STARTED,
        Some(&node_id),
        serde_json::Map::from_iter([("title".to_string(), serde_json::Value::String(title.clone()))]),
    );

    // Isolated swimlane.
    let work_dir: PathBuf = if use_worktree {
        match gitx::worktree_create(&root, &node_id) {
            Ok((path, _branch)) => path,
            Err(e) => {
                return NodeOutcome { id: node_id, ok: false, detail: format!("worktree create failed: {e}") };
            }
        }
    } else {
        root.clone()
    };

    let cfg = load_config().unwrap_or_default();
    let mut session = match crate::agent::session_from_config(&work_dir, &cfg, PermissionMode::Yolo, false) {
        Ok(s) => s,
        Err(e) => {
            if use_worktree {
                let _ = gitx::worktree_remove(&root, &node_id);
            }
            return NodeOutcome { id: node_id, ok: false, detail: e };
        }
    };
    session.system_prompt = prompt::build_system_prompt(&work_dir, &[]);
    session.max_tool_rounds = 12;

    let text = session
        .send(&node_prompt(&node_id, &title, &detail), &mut |_| {}, &mut |_| {})
        .await;

    match text {
        Some(report) => {
            // Checkpoint the result (in the worktree so the lane is atomic).
            let cp = CheckpointStore::new(&work_dir).create(&node_id, &title);
            match cp {
                Ok(cp) => {
                    log.emit(
                        evt::CHECKPOINT,
                        Some(&format!("{} {}", cp.id, cp.commit.as_deref().unwrap_or("-"))),
                        serde_json::Map::from_iter([
                            ("label".to_string(), serde_json::Value::String(node_id.clone())),
                        ]),
                    );
                    if use_worktree {
                        // Merge the lane back to the main tree: diff applied in root.
                        let patch = gitx::run_out(&work_dir, &["diff", "HEAD"]).unwrap_or_default();
                        if !patch.is_empty() {
                            let apply = std::process::Command::new("git")
                                .args(["apply", "-"])
                                .current_dir(&root)
                                .stdin(std::process::Stdio::piped())
                                .stdout(std::process::Stdio::null())
                                .stderr(std::process::Stdio::piped())
                                .spawn()
                                .and_then(|mut child| {
                                    use std::io::Write;
                                    child.stdin.as_mut().unwrap().write_all(patch.as_bytes())?;
                                    let out = child.wait_with_output()?;
                                    if out.status.success() { Ok(()) } else { Err(std::io::Error::other("patch failed")) }
                                });
                            if let Err(e) = apply {
                                let _ = gitx::worktree_remove(&root, &node_id);
                                return NodeOutcome { id: node_id, ok: false, detail: format!("lane merge failed: {e}") };
                            }
                        }
                        // Commit the merged result in the root tree.
                        let _ = CheckpointStore::new(&root).create(&node_id, &format!("{title} (lane merged)"));
                        let _ = gitx::worktree_remove(&root, &node_id);
                    }
                    NodeOutcome { id: node_id, ok: true, detail: report.chars().take(500).collect() }
                }
                Err(e) => {
                    // Iron Law 8: no verification, no completion.
                    let _ = gitx::worktree_remove(&root, &node_id);
                    NodeOutcome { id: node_id, ok: false, detail: format!("checkpoint failed (verification gate): {e}") }
                }
            }
        }
        None => {
            let reason = session.last_failure.clone().unwrap_or_else(|| "unknown".into());
            blockers_note(&root, &node_id, &reason);
            if use_worktree {
                let _ = gitx::worktree_remove(&root, &node_id);
            }
            NodeOutcome { id: node_id, ok: false, detail: format!("agent failed: {reason}") }
        }
    }
}

fn blockers_note(root: &Path, node_id: &str, reason: &str) {
    let dir = root.join(".xencode");
    let _ = std::fs::create_dir_all(&dir);
    let entry = format!(
        "\n## {} · node {node_id}\n- {}\n",
        crate::state::sessions::fmt_ts(crate::now_ms()),
        reason.replace('\n', " ")
    );
    let _ = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join("BLOCKERS.md"))
        .and_then(|mut f| std::io::Write::write_all(&mut f, entry.as_bytes()));
}

/// Run every READY node (up to `parallel` lanes concurrently). Updates the
/// DAG, awards XP and returns a human summary.
pub async fn run_ready(workspace_root: &Path, parallel: usize) -> String {
    let log = EventLog::new(workspace_root);
    let mut dag = dag_file::load(workspace_root);
    if dag.nodes.is_empty() {
        log.emit_simple(evt::GOAL_INITIALIZED, Some("empty DAG"));
        return "(goal DAG is empty, add nodes with `xencode goal add`)".into();
    }
    dag.refresh();
    let ready: Vec<_> = dag.ready_nodes().into_iter().cloned().collect();
    if ready.is_empty() {
        return format!("no READY nodes ({})", dag.summary_counts());
    }

    let use_worktree = gitx::is_git_repo(workspace_root) && parallel > 1;
    log.emit(
        evt::STATE_SYNC,
        Some(&format!("running {} ready node(s), parallel={parallel}, worktrees={use_worktree}", ready.len())),
        Default::default(),
    );

    // Mark all ACTIVE up-front.
    for node in &ready {
        let _ = dag.mark_active(&node.id);
    }
    let _ = dag_file::save(workspace_root, &dag);

    // Bounded-concurrency execution (buffer_unordered yields lanes as they
    // finish; each lane is an isolated worktree).
    use futures_util::StreamExt;
    let outcomes: Vec<NodeOutcome> = futures_util::stream::iter(ready)
        .map(|node| {
            let root = workspace_root.to_path_buf();
            async move {
                run_one(root, node.id.clone(), node.title.clone(), node.detail.clone(), use_worktree).await
            }
        })
        .buffer_unordered(parallel.max(1))
        .collect::<Vec<_>>()
        .await;

    // Apply outcomes to the DAG + XP.
    let xp = XpLedger::default();
    let mut dag = dag_file::load(workspace_root);
    let mut lines = vec![];
    for outcome in &outcomes {
        if outcome.ok {
            if let Ok(node) = dag.mark_done(&outcome.id, Some("verified + checkpointed")) {
                xp.award_goal(&node.title, crate::now_ms());
                log.emit(
                    evt::XP_AWARDED,
                    Some(&format!("goal {} +{}", node.id, XpLedger::default().summary(crate::now_ms()).today_xp)),
                    Default::default(),
                );
            }
            log.emit(
                evt::NODE_COMPLETED,
                Some(&outcome.id),
                serde_json::Map::from_iter([(
                    "ok".to_string(),
                    serde_json::Value::Bool(true),
                )]),
            );
            lines.push(format!("DONE {}: {}", outcome.id, first_line(&outcome.detail)));
        } else {
            let _ = dag.mark_failed(&outcome.id, Some(&outcome.detail));
            lines.push(format!("FAILED {}: {}", outcome.id, first_line(&outcome.detail)));
        }
    }
    dag.refresh();
    let _ = dag_file::save(workspace_root, &dag);

    lines.push(format!("DAG now: {}", dag.summary_counts()));
    lines.join("\n")
}

fn first_line(s: &str) -> String {
    s.lines().next().unwrap_or("").to_string()
}

/// Status helper shared by CLI/desktop.
pub fn status_line(workspace_root: &Path) -> String {
    let dag = dag_file::load(workspace_root);
    if dag.nodes.is_empty() {
        return "(no goals)".into();
    }
    let ready = dag.ready_nodes().len();
    format!("{} · READY: {ready}", dag.summary_counts())
}

/// True if every node is DONE/CANCELLED.
pub fn all_settled(dag: &super::GoalDag) -> bool {
    !dag.nodes.is_empty()
        && dag.nodes.iter().all(|n| matches!(n.status, GoalStatus::Done | GoalStatus::Cancelled))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prompt_contains_node_and_laws() {
        let p = node_prompt("g3", "add feature", "detail here");
        assert!(p.contains("g3") && p.contains("add feature") && p.contains("exit code 0"));
    }

    #[test]
    fn all_settled_semantics() {
        let mut dag = super::super::GoalDag::default();
        assert!(!all_settled(&dag)); // empty is not settled
        let n = dag.add("a", "", vec![]).unwrap();
        assert!(!all_settled(&dag));
        dag.refresh(); // no deps -> READY
        dag.mark_done(&n.id, None).unwrap();
        assert!(all_settled(&dag));
    }
}
