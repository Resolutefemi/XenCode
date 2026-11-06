//! Goal DAG: dependency graph of goal nodes (`.xencode/GOAL_DAG.json`).
//! format=1 — the JSON schema is a hard twin contract with the Android app.

pub mod file;
pub mod runner;

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const FORMAT: i64 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum GoalStatus {
    Pending,
    Ready,
    Active,
    Done,
    Failed,
    Cancelled,
}

impl GoalStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Pending => "PENDING",
            Self::Ready => "READY",
            Self::Active => "ACTIVE",
            Self::Done => "DONE",
            Self::Failed => "FAILED",
            Self::Cancelled => "CANCELLED",
        }
    }
    pub fn parse(s: &str) -> Self {
        match s.to_ascii_uppercase().as_str() {
            "READY" => Self::Ready,
            "ACTIVE" => Self::Active,
            "DONE" => Self::Done,
            "FAILED" => Self::Failed,
            "CANCELLED" => Self::Cancelled,
            _ => Self::Pending,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GoalNode {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub detail: String,
    #[serde(rename = "dependsOn", default)]
    pub depends_on: Vec<String>,
    pub status: GoalStatus,
    #[serde(default)]
    pub notes: Vec<String>,
    #[serde(rename = "createdAt", default)]
    pub created_at: i64,
    #[serde(rename = "updatedAt", default)]
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GoalDag {
    pub format: i64,
    #[serde(rename = "nextId")]
    pub next_id: i64,
    #[serde(rename = "updatedAt")]
    pub updated_at: i64,
    #[serde(default)]
    pub nodes: Vec<GoalNode>,
}

impl Default for GoalDag {
    fn default() -> Self {
        Self { format: FORMAT, next_id: 1, updated_at: crate::now_ms(), nodes: vec![] }
    }
}

impl GoalDag {
    pub fn add(&mut self, title: &str, detail: &str, depends_on: Vec<String>) -> Result<GoalNode, String> {
        for dep in &depends_on {
            if dep == &format!("g{}", self.next_id) {
                return Err(format!("node cannot depend on itself (`{dep}` is its own id)"));
            }
            if !self.nodes.iter().any(|n| &n.id == dep) {
                return Err(format!("unknown dependency `{dep}`"));
            }
        }
        let now = crate::now_ms();
        let node = GoalNode {
            id: format!("g{}", self.next_id),
            title: title.to_string(),
            detail: detail.to_string(),
            depends_on,
            status: GoalStatus::Pending,
            notes: vec![],
            created_at: now,
            updated_at: now,
        };
        self.nodes.push(node.clone());
        self.next_id += 1;
        self.updated_at = now;
        Ok(node)
    }

    fn transition(&mut self, id: &str, to: GoalStatus, note: Option<&str>, allowed: fn(&GoalStatus) -> bool) -> Result<GoalNode, String> {
        let node = self
            .nodes
            .iter_mut()
            .find(|n| n.id == id)
            .ok_or_else(|| format!("no node `{id}`"))?;
        if !allowed(&node.status) {
            return Err(format!("illegal transition {} -> {} for `{id}`", node.status.as_str(), to.as_str()));
        }
        node.status = to;
        node.updated_at = crate::now_ms();
        if let Some(note) = note {
            node.notes.push(note.to_string());
        }
        Ok(node.clone())
    }

    pub fn mark_done(&mut self, id: &str, note: Option<&str>) -> Result<GoalNode, String> {
        self.transition(id, GoalStatus::Done, note, |s| matches!(s, GoalStatus::Ready | GoalStatus::Active))
    }

    pub fn mark_failed(&mut self, id: &str, note: Option<&str>) -> Result<GoalNode, String> {
        self.transition(id, GoalStatus::Failed, note, |s| matches!(s, GoalStatus::Ready | GoalStatus::Active))
    }

    pub fn mark_ready(&mut self, id: &str) -> Result<GoalNode, String> {
        self.transition(id, GoalStatus::Ready, None, |s| matches!(s, GoalStatus::Failed | GoalStatus::Pending))
    }

    pub fn mark_active(&mut self, id: &str) -> Result<GoalNode, String> {
        self.transition(id, GoalStatus::Active, None, |s| matches!(s, GoalStatus::Ready))
    }

    pub fn cancel(&mut self, id: &str) -> Result<GoalNode, String> {
        self.transition(id, GoalStatus::Cancelled, None, |s| {
            !matches!(s, GoalStatus::Done | GoalStatus::Cancelled)
        })
    }

    /// Recompute PENDING/READY from dependencies (topological sweep).
    pub fn refresh(&mut self) {
        let mut statuses: BTreeMap<String, GoalStatus> =
            self.nodes.iter().map(|n| (n.id.clone(), n.status)).collect();
        for _ in 0..self.nodes.len() + 1 {
            for node in &self.nodes {
                let cur = statuses.get(&node.id).copied().unwrap_or(node.status);
                if !matches!(cur, GoalStatus::Pending | GoalStatus::Ready) {
                    continue;
                }
                let deps_done = node.depends_on.iter().all(|d| statuses.get(d) == Some(&GoalStatus::Done));
                let want = if deps_done { GoalStatus::Ready } else { GoalStatus::Pending };
                if cur != want {
                    statuses.insert(node.id.clone(), want);
                }
            }
        }
        let now = crate::now_ms();
        for node in &mut self.nodes {
            if let Some(s) = statuses.get(&node.id) {
                if node.status != *s {
                    node.status = *s;
                    node.updated_at = now;
                }
            }
        }
        self.updated_at = now;
    }

    /// Nodes ready to run, ordered by id.
    pub fn ready_nodes(&self) -> Vec<&GoalNode> {
        let mut nodes: Vec<&GoalNode> = self.nodes.iter().filter(|n| n.status == GoalStatus::Ready).collect();
        nodes.sort_by(|a, b| a.id.cmp(&b.id));
        nodes
    }

    pub fn render(&self) -> String {
        if self.nodes.is_empty() {
            return "(goal DAG is empty: add nodes with `xencode goal add`)".into();
        }
        self.nodes
            .iter()
            .map(|n| {
                let deps = if n.depends_on.is_empty() {
                    String::new()
                } else {
                    format!("  deps: {}", n.depends_on.join(","))
                };
                format!("[{:>8}] {}: {}{}", n.status.as_str(), n.id, n.title, deps)
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    pub fn summary_counts(&self) -> String {
        let mut counts: BTreeMap<&str, usize> = BTreeMap::new();
        for n in &self.nodes {
            *counts.entry(n.status.as_str()).or_default() += 1;
        }
        counts
            .into_iter()
            .map(|(k, v)| format!("{k}:{v}"))
            .collect::<Vec<_>>()
            .join(" ")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_and_status_flow() {
        let mut dag = GoalDag::default();
        let a = dag.add("first", "do x", vec![]).unwrap();
        let b = dag.add("second", "do y", vec![a.id.clone()]).unwrap();
        assert_eq!(a.id, "g1");
        assert_eq!(b.id, "g2");
        dag.refresh();
        assert_eq!(dag.nodes[0].status, GoalStatus::Ready);
        assert_eq!(dag.nodes[1].status, GoalStatus::Pending);
        dag.mark_active("g1").unwrap();
        dag.mark_done("g1", Some("ok")).unwrap();
        dag.refresh();
        assert_eq!(dag.nodes[1].status, GoalStatus::Ready);
        assert_eq!(dag.ready_nodes().len(), 1);
        // illegal transitions
        assert!(dag.mark_active("g1").is_err());
        assert!(dag.mark_active("g2").is_ok()); // READY -> ACTIVE legal
        assert_eq!(dag.summary_counts(), "ACTIVE:1 DONE:1");
    }

    #[test]
    fn self_dependency_rejected() {
        let mut dag = GoalDag::default();
        // "g1" is this node's own id -> rejected
        assert!(dag.add("x", "y", vec!["g1".into()]).is_err());
        let n = dag.add("x", "y", vec![]).unwrap();
        assert!(dag.add("y2", "z", vec!["g99".into()]).is_err());
        assert_eq!(n.id, "g1");
    }

    #[test]
    fn parse_roundtrip() {
        let mut dag = GoalDag::default();
        dag.add("a", "", vec![]).unwrap();
        let json = serde_json::to_string(&dag).unwrap();
        assert!(json.contains("\"format\":1"));
        assert!(json.contains("\"nextId\":2"));
        let back: GoalDag = serde_json::from_str(&json).unwrap();
        assert_eq!(back.nodes.len(), 1);
        assert_eq!(GoalStatus::parse("ready"), GoalStatus::Ready);
    }
}
