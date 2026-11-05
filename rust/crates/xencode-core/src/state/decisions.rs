//! Decision cards: agent asks the user a blocking question; resolved later
//! from the CLI, the desktop app or the phone (`.xencode/decisions/*.json`).

use crate::config::write_atomic;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DecisionCard {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub options: Vec<String>,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    /// None while pending; Some(index into options) after resolve.
    #[serde(rename = "chosenIndex")]
    pub chosen_index: Option<usize>,
}

pub struct DecisionStore {
    dir: PathBuf,
}

impl DecisionStore {
    pub fn new(workspace_root: &Path) -> Self {
        Self { dir: workspace_root.join(".xencode").join("decisions") }
    }

    pub fn create(&self, title: &str, body: &str, options: Vec<String>) -> Result<DecisionCard, String> {
        let card = DecisionCard {
            id: crate::short_id(),
            title: title.to_string(),
            body: body.to_string(),
            options: if options.is_empty() { vec!["approve".into(), "reject".into()] } else { options },
            created_at: crate::now_ms(),
            chosen_index: None,
        };
        self.persist(&card)?;
        Ok(card)
    }

    fn persist(&self, card: &DecisionCard) -> Result<(), String> {
        std::fs::create_dir_all(&self.dir).map_err(|e| e.to_string())?;
        let json = serde_json::to_string_pretty(card).map_err(|e| e.to_string())?;
        write_atomic(&self.dir.join(format!("{}.json", card.id)), json.as_bytes()).map_err(|e| e.to_string())
    }

    pub fn pending(&self) -> Vec<DecisionCard> {
        self.all().into_iter().filter(|c| c.chosen_index.is_none()).collect()
    }

    pub fn all(&self) -> Vec<DecisionCard> {
        let mut cards: Vec<DecisionCard> = std::fs::read_dir(&self.dir)
            .into_iter()
            .flatten()
            .filter_map(|e| e.ok())
            .filter_map(|e| {
                let raw = std::fs::read_to_string(e.path()).ok()?;
                serde_json::from_str(&raw).ok()
            })
            .collect();
        cards.sort_by_key(|a| a.created_at);
        cards
    }

    pub fn resolve(&self, id: &str, chosen_index: usize) -> Result<DecisionCard, String> {
        let mut card = self
            .all()
            .into_iter()
            .find(|c| c.id == id)
            .ok_or_else(|| format!("no decision card `{id}`"))?;
        if card.chosen_index.is_some() {
            return Err(format!("card `{id}` already resolved"));
        }
        if chosen_index >= card.options.len() {
            return Err(format!("choice {chosen_index} out of range (0..={})", card.options.len() - 1));
        }
        card.chosen_index = Some(chosen_index);
        self.persist(&card)?;
        Ok(card)
    }

    /// Block up to `timeout` waiting for a card to be resolved.
    pub fn wait_resolution(&self, id: &str, timeout_ms: u64) -> Option<DecisionCard> {
        let deadline = std::time::Instant::now() + std::time::Duration::from_millis(timeout_ms);
        while std::time::Instant::now() < deadline {
            if let Some(card) = self.all().into_iter().find(|c| c.id == id && c.chosen_index.is_some()) {
                return Some(card);
            }
            std::thread::sleep(std::time::Duration::from_millis(250));
        }
        None
    }

    pub fn render_list(&self) -> String {
        let all = self.all();
        if all.is_empty() {
            return "(no decision cards)".into();
        }
        all.iter()
            .map(|c| match c.chosen_index {
                Some(i) => format!("{}  [resolved: {}]  {}", c.id, c.options.get(i).map(|s| s.as_str()).unwrap_or("?"), c.title),
                None => format!("{}  [PENDING]  {} — options: {}", c.id, c.title, c.options.join("/")),
            })
            .collect::<Vec<_>>()
            .join("\n")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_resolve_wait() {
        let dir = tempfile::tempdir().unwrap();
        let store = DecisionStore::new(dir.path());
        let card = store.create("Use nightly?", "needs toolchain pin", vec!["yes".into(), "no".into()]).unwrap();
        assert_eq!(store.pending().len(), 1);
        let resolved = store.resolve(&card.id, 0).unwrap();
        assert_eq!(resolved.chosen_index, Some(0));
        assert!(store.pending().is_empty());
        assert!(store.resolve(&card.id, 0).is_err());
        assert!(store.resolve(&card.id, 99).is_err());
        // wait_resolution returns immediately for resolved card
        let got = store.wait_resolution(&card.id, 100).unwrap();
        assert_eq!(got.id, card.id);
    }
}
