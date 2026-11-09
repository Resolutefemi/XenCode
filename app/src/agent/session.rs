//! File-backed session store (JSON, one file per session).

use std::path::{Path, PathBuf};

use anyhow::Result;
use serde::{Deserialize, Serialize};

use super::provider::Message;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Session {
    pub id: String,
    pub created_unix: u64,
    pub messages: Vec<Message>,
}

impl Session {
    pub fn from_messages(messages: &[Message]) -> Self {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default();
        Self {
            id: now.as_nanos().to_string(),
            created_unix: now.as_secs(),
            messages: messages.to_vec(),
        }
    }

    pub fn save(&self, dir: &Path) -> Result<PathBuf> {
        std::fs::create_dir_all(dir)?;
        let path = dir.join(format!("{}.json", self.id));
        std::fs::write(&path, serde_json::to_string_pretty(self)?)?;
        Ok(path)
    }

    pub fn load(dir: &Path, id: &str) -> Result<Session> {
        let text = std::fs::read_to_string(dir.join(format!("{id}.json")))?;
        Ok(serde_json::from_str(&text)?)
    }

    pub fn list(dir: &Path) -> Result<Vec<String>> {
        let mut ids = Vec::new();
        if let Ok(rd) = std::fs::read_dir(dir) {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().into_owned();
                if let Some(id) = name.strip_suffix(".json") {
                    ids.push(id.to_string());
                }
            }
        }
        ids.sort();
        Ok(ids)
    }

    /// Branch a session into a fresh copy under a new id — history up to this
    /// point is preserved; the fork continues independently.
    pub fn fork(&self) -> Session {
        let mut s = self.clone();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default();
        s.id = now.as_nanos().to_string();
        s.created_unix = now.as_secs();
        s
    }

    /// Most recent session id in the store (for `--continue`).
    pub fn latest(dir: &Path) -> Result<Option<String>> {
        Ok(Self::list(dir)?.pop())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-sess-{}-{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn save_load_list_fork_roundtrip() {
        let dir = tmp("rt");
        let s = Session::from_messages(&[Message::user("hello"), Message::tool_result("t1", "x")]);
        let path = s.save(&dir).unwrap();
        assert!(path.exists());

        let ids = Session::list(&dir).unwrap();
        assert_eq!(ids, vec![s.id.clone()]);
        assert_eq!(Session::latest(&dir).unwrap().as_deref(), Some(s.id.as_str()));

        let loaded = Session::load(&dir, &s.id).unwrap();
        assert_eq!(loaded.messages.len(), 2);

        let mut forked = loaded.fork();
        assert_ne!(forked.id, s.id, "fork gets a fresh id");
        assert_eq!(forked.messages.len(), 2, "fork keeps history");
        forked.messages.push(Message::user("continue here"));
        forked.save(&dir).unwrap();
        assert_eq!(Session::list(&dir).unwrap().len(), 2);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
