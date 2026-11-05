//! Persisted todo list (`.xencode/todos.json`), twin of the Kotlin TodoStore.

use crate::config::write_atomic;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TodoItem {
    pub content: String,
    #[serde(default = "default_status")]
    pub status: String,
    #[serde(default = "default_priority")]
    pub priority: String,
}

fn default_status() -> String {
    "pending".into()
}
fn default_priority() -> String {
    "medium".into()
}

#[derive(Debug, Clone)]
pub struct TodoStore {
    file: PathBuf,
}

impl TodoStore {
    pub fn new(workspace_root: &Path) -> Self {
        Self { file: workspace_root.join(".xencode").join("todos.json") }
    }

    pub fn from_file(file: PathBuf) -> Self {
        Self { file }
    }

    pub fn load(&self) -> Vec<TodoItem> {
        std::fs::read_to_string(&self.file)
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok())
            .unwrap_or_default()
    }

    pub fn replace(&self, items: Vec<TodoItem>) -> Result<(), String> {
        let normalized: Vec<TodoItem> = items
            .into_iter()
            .map(|mut t| {
                t.status = normalize_status(&t.status);
                t.priority = normalize_priority(&t.priority);
                t
            })
            .collect();
        let json = serde_json::to_string_pretty(&normalized).map_err(|e| e.to_string())?;
        write_atomic(&self.file, json.as_bytes()).map_err(|e| e.to_string())
    }

    pub fn render(&self) -> String {
        let items = self.load();
        if items.is_empty() {
            return "(no todos)".into();
        }
        let mut out = String::new();
        for (i, t) in items.iter().enumerate() {
            let mark = match t.status.as_str() {
                "active" => "▸",
                "completed" => "✓",
                _ => "·",
            };
            out.push_str(&format!("{}. [{}] {} ({}) — {}\n", i + 1, t.priority, mark, t.status, t.content));
        }
        let done = items.iter().filter(|t| t.status == "completed").count();
        out.push_str(&format!("{}/{} completed", done, items.len()));
        out
    }
}

fn normalize_status(s: &str) -> String {
    match s.to_ascii_lowercase().as_str() {
        "active" | "in_progress" | "inprogress" | "doing" => "active".into(),
        "completed" | "done" => "completed".into(),
        _ => "pending".into(),
    }
}

fn normalize_priority(s: &str) -> String {
    match s.to_ascii_lowercase().as_str() {
        "high" | "h" => "high".into(),
        "low" | "l" => "low".into(),
        _ => "medium".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn replace_and_render() {
        let dir = tempfile::tempdir().unwrap();
        let store = TodoStore::new(dir.path());
        store
            .replace(vec![
                TodoItem { content: "a".into(), status: "in_progress".into(), priority: "high".into() },
                TodoItem { content: "b".into(), status: "completed".into(), priority: "zz".into() },
            ])
            .unwrap();
        let items = store.load();
        assert_eq!(items[0].status, "active");
        assert_eq!(items[1].status, "completed");
        assert_eq!(items[1].priority, "medium");
        let r = store.render();
        assert!(r.contains("a") && r.contains("1/2 completed"), "{r}");
    }
}
