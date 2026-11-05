//! DAG persistence: `.xencode/GOAL_DAG.json` (format=1 twin schema).

use super::{GoalDag, FORMAT};
use crate::config::write_atomic;
use std::path::{Path, PathBuf};

pub fn dag_path(workspace_root: &Path) -> PathBuf {
    workspace_root.join(".xencode").join("GOAL_DAG.json")
}

pub fn load(workspace_root: &Path) -> GoalDag {
    let raw = match std::fs::read_to_string(dag_path(workspace_root)) {
        Ok(raw) => raw,
        Err(_) => return GoalDag::default(),
    };
    match serde_json::from_str::<GoalDag>(&raw) {
        Ok(mut dag) => {
            dag.format = FORMAT;
            dag
        }
        Err(_) => GoalDag::default(),
    }
}

pub fn save(workspace_root: &Path, dag: &GoalDag) -> Result<(), String> {
    let json = serde_json::to_string_pretty(dag).map_err(|e| e.to_string())?;
    write_atomic(&dag_path(workspace_root), json.as_bytes()).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn load_save_roundtrip_and_empty() {
        let dir = tempfile::tempdir().unwrap();
        let empty = load(dir.path());
        assert_eq!(empty.next_id, 1);

        let mut dag = GoalDag::default();
        dag.add("n1", "detail", vec![]).unwrap();
        save(dir.path(), &dag).unwrap();
        let loaded = load(dir.path());
        assert_eq!(loaded.nodes.len(), 1);
        assert_eq!(loaded.format, 1);
        // Kotlin writes "status":"PENDING" uppercase via Gson enum name.
        let raw = std::fs::read_to_string(dag_path(dir.path())).unwrap();
        assert!(raw.contains("\"format\": 1"));
        let parsed: serde_json::Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(parsed["nodes"][0]["status"], "PENDING");
        // The Kotlin/Gson-written form is byte-compatible.
        let kotlin_form = r#"{"format":1,"nextId":3,"updatedAt":123,"nodes":[{"id":"g1","title":"t","detail":"","dependsOn":[],"status":"DONE","notes":[],"createdAt":1,"updatedAt":2}]}"#;
        let from_kotlin: GoalDag = serde_json::from_str(kotlin_form).unwrap();
        assert_eq!(from_kotlin.nodes[0].status, super::super::GoalStatus::Done);
    }
}
