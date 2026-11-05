//! Append-only NDJSON event log (`.xencode/events.ndjson`).
//!
//! This is the bi-directional sync channel the Android app also reads:
//! one JSON object per line, `{"xencode_event": "<TYPE>", "at": ms,
//! "summary": str?, "fields": {...}}`.

use crate::config::write_atomic;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::io::Write;
use std::path::{Path, PathBuf};

/// Well-known event types (the protocol vocabulary — twin of Kotlin).
pub mod types {
    pub const GOAL_INITIALIZED: &str = "GOAL_INITIALIZED";
    pub const NODE_STARTED: &str = "NODE_STARTED";
    pub const NODE_COMPLETED: &str = "NODE_COMPLETED";
    pub const SPEC_LOCKED: &str = "SPEC_LOCKED";
    pub const STATE_SYNC: &str = "STATE_SYNC";
    pub const TOOL_CALL: &str = "TOOL_CALL";
    pub const TOOL_RESULT: &str = "TOOL_RESULT";
    pub const VERIFICATION: &str = "VERIFICATION";
    pub const CHECKPOINT: &str = "CHECKPOINT";
    pub const DECISION_REQUESTED: &str = "DECISION_REQUESTED";
    pub const DECISION_RESOLVED: &str = "DECISION_RESOLVED";
    pub const XP_AWARDED: &str = "XP_AWARDED";
    pub const SESSION_SAVED: &str = "SESSION_SAVED";
    pub const SESSION_RESUMED: &str = "SESSION_RESUMED";
    pub const MCP_STATUS: &str = "MCP_STATUS";
    pub const MJS_RUN: &str = "MJS_RUN";
    pub const COMPACTED: &str = "COMPACTED";
    pub const TURN_COMPLETED: &str = "TURN_COMPLETED";
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event {
    pub xencode_event: String,
    pub at: i64,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub fields: serde_json::Map<String, Value>,
}

pub struct EventLog {
    file: PathBuf,
}

impl EventLog {
    pub fn new(workspace_root: &Path) -> Self {
        Self { file: workspace_root.join(".xencode").join("events.ndjson") }
    }

    pub fn from_file(file: PathBuf) -> Self {
        Self { file }
    }

    /// Append an event; returns its line id (`e` + base36 millis).
    pub fn emit(&self, event_type: &str, summary: Option<&str>, fields: serde_json::Map<String, Value>) -> String {
        let at = crate::now_ms();
        let ev = Event {
            xencode_event: event_type.to_string(),
            at,
            summary: summary.map(|s| s.to_string()),
            fields,
        };
        if let Some(parent) = self.file.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&self.file) {
            let _ = writeln!(f, "{}", serde_json::to_string(&ev).unwrap_or_default());
        }
        format!("e{}", crate::base36(at))
    }

    pub fn emit_simple(&self, event_type: &str, summary: Option<&str>) -> String {
        self.emit(event_type, summary, Default::default())
    }

    pub fn tail(&self, n: usize) -> Vec<Event> {
        let raw = match std::fs::read_to_string(&self.file) {
            Ok(r) => r,
            Err(_) => return vec![],
        };
        let mut events: Vec<Event> = raw
            .lines()
            .filter_map(|l| serde_json::from_str(l).ok())
            .collect();
        let len = events.len();
        if len > n {
            events.drain(..len - n);
        }
        events
    }

    pub fn render_tail(&self, n: usize) -> String {
        let events = self.tail(n);
        if events.is_empty() {
            return "(no events)".into();
        }
        events
            .iter()
            .map(|e| {
                let extras: Vec<String> = e
                    .fields
                    .iter()
                    .map(|(k, v)| format!("{k}={v}"))
                    .take(3)
                    .collect();
                format!(
                    "{} {} {} {}",
                    e.at,
                    e.xencode_event,
                    e.summary.as_deref().unwrap_or("-"),
                    extras.join(" ")
                )
                .trim()
                .to_string()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// Replace the whole log (used by compaction / tests).
    pub fn rewrite(&self, events: &[Event]) -> Result<(), String> {
        let mut buf = String::new();
        for e in events {
            buf.push_str(&serde_json::to_string(e).map_err(|e| e.to_string())?);
            buf.push('\n');
        }
        write_atomic(&self.file, buf.as_bytes()).map_err(|e| e.to_string())
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emit_tail_render() {
        let dir = tempfile::tempdir().unwrap();
        let log = EventLog::new(dir.path());
        let mut fields = serde_json::Map::new();
        fields.insert("label".into(), Value::String("main".into()));
        log.emit(types::CHECKPOINT, Some("cp main"), fields);
        log.emit_simple(types::NODE_STARTED, Some("g1"));
        let tail = log.tail(10);
        assert_eq!(tail.len(), 2);
        assert_eq!(tail[0].xencode_event, "CHECKPOINT");
        let r = log.render_tail(1);
        assert!(r.contains("NODE_STARTED"), "{r}");
        let empty = EventLog::from_file(dir.path().join("none.ndjson")).tail(5);
        assert!(empty.is_empty());
    }
}
