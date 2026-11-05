//! Sessions: persisted conversations in `.xencode/sessions/*.json`.

use crate::chat::ChatMessage;
use crate::config::write_atomic;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionMeta {
    pub id: String,
    pub title: String,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "updatedAt")]
    pub updated_at: i64,
    #[serde(rename = "turns")]
    pub turns: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Session {
    #[serde(flatten)]
    pub meta: SessionMeta,
    #[serde(default)]
    pub messages: Vec<ChatMessage>,
    /// Cumulative token usage accounting (Rust-line addition; older files omit).
    #[serde(rename = "tokensIn", default)]
    pub tokens_in: u64,
    #[serde(rename = "tokensOut", default)]
    pub tokens_out: u64,
}

pub struct SessionStore {
    dir: PathBuf,
}

impl SessionStore {
    pub fn new(workspace_root: &Path) -> Self {
        Self { dir: workspace_root.join(".xencode").join("sessions") }
    }

    pub fn list(&self) -> Vec<SessionMeta> {
        let mut metas: Vec<SessionMeta> = std::fs::read_dir(&self.dir)
            .into_iter()
            .flatten()
            .filter_map(|e| e.ok())
            .filter_map(|e| {
                let p = e.path();
                if p.extension().and_then(|x| x.to_str()) != Some("json") {
                    return None;
                }
                let raw = std::fs::read_to_string(&p).ok()?;
                let s: Session = serde_json::from_str(&raw).ok()?;
                Some(s.meta)
            })
            .collect();
        metas.sort_by_key(|m| std::cmp::Reverse(m.updated_at));
        metas
    }

    pub fn save(&self, session: &mut Session) -> Result<(), String> {
        if session.meta.id.is_empty() {
            session.meta.id = crate::short_id();
        }
        session.meta.updated_at = crate::now_ms();
        std::fs::create_dir_all(&self.dir).map_err(|e| e.to_string())?;
        let json = serde_json::to_string_pretty(session).map_err(|e| e.to_string())?;
        write_atomic(&self.dir.join(format!("{}.json", session.meta.id)), json.as_bytes())
            .map_err(|e| e.to_string())
    }

    pub fn load(&self, id: &str) -> Option<Session> {
        let raw = std::fs::read_to_string(self.dir.join(format!("{id}.json"))).ok()?;
        serde_json::from_str(&raw).ok()
    }

    pub fn delete(&self, id: &str) -> Result<bool, String> {
        let p = self.dir.join(format!("{id}.json"));
        if p.exists() {
            std::fs::remove_file(&p).map_err(|e| e.to_string())?;
            Ok(true)
        } else {
            Ok(false)
        }
    }

    pub fn render_list(&self) -> String {
        let metas = self.list();
        if metas.is_empty() {
            return "(no sessions)".into();
        }
        metas
            .iter()
            .map(|m| format!("{}  {}  {} turns  {}", m.id, fmt_ts(m.updated_at), m.turns, m.title))
            .collect::<Vec<_>>()
            .join("\n")
    }
}

pub fn fmt_ts(ms: i64) -> String {
    // UTC timestamp, no external chrono dependency.
    let secs = ms.div_euclid(1000);
    let millis = ms.rem_euclid(1000);
    let days = secs.div_euclid(86_400);
    let tod = secs.rem_euclid(86_400);
    let (h, m, s) = (tod / 3600, (tod % 3600) / 60, tod % 60);
    let (y, mo, d) = civil_from_days(days);
    format!("{y:04}-{mo:02}-{d:02} {h:02}:{m:02}:{s:02}.{millis:03}Z")
}

/// Howard Hinnant's civil-from-days algorithm.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn save_list_load_delete() {
        let dir = tempfile::tempdir().unwrap();
        let store = SessionStore::new(dir.path());
        let mut s = Session {
            meta: SessionMeta { id: String::new(), title: "test session".into(), created_at: 1, updated_at: 1, turns: 0 },
            messages: vec![ChatMessage::user("hello")],
            tokens_in: 0,
            tokens_out: 0,
        };
        store.save(&mut s).unwrap();
        assert!(!s.meta.id.is_empty());
        assert_eq!(store.list().len(), 1);
        let loaded = store.load(&s.meta.id).unwrap();
        assert_eq!(loaded.messages[0].content.as_deref(), Some("hello"));
        assert!(store.delete(&s.meta.id).unwrap());
        assert!(store.list().is_empty());
    }

    #[test]
    fn timestamp_format() {
        let ts = fmt_ts(0);
        assert_eq!(ts, "1970-01-01 00:00:00.000Z");
    }
}
