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
}
