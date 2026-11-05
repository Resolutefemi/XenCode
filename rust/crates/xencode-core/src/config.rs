//! Provider + machine configuration.
//!
//! On-disk contract (twin of the Kotlin line): `~/.xencode/pc-config.json`
//! ```json
//! {"providers":[{"name":"..","kind":"openai","baseUrl":"..","apiKey":"..",
//!   "model":"..","temperature":null,"maxTokens":null,"extraHeaders":{}}],
//!  "active":"..","defaultWorkspace":".."}
//! ```

use crate::error::{Result, XeError};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const XENCODE_HOME_ENV: &str = "XENCODE_HOME";
/// Legacy env contract kept on purpose (user: env vars stay `AICODE_*`).
pub const AICODE_HOME_ENV: &str = "AICODE_HOME";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProviderConfig {
    pub name: String,
    #[serde(rename = "kind", default = "default_kind")]
    pub kind: ProviderKind,
    /// Field names mirror the Kotlin/Gson schema byte-for-byte so one config
    /// file works with both lines.
    #[serde(rename = "baseUrl", default)]
    pub base_url: String,
    #[serde(rename = "apiKey", default)]
    pub api_key: String,
    #[serde(default)]
    pub model: String,
    #[serde(default)]
    pub temperature: Option<f64>,
    #[serde(rename = "maxTokens", default)]
    pub max_tokens: Option<u32>,
    #[serde(rename = "extraHeaders", default)]
    pub extra_headers: std::collections::BTreeMap<String, String>,
}

fn default_kind() -> ProviderKind {
    ProviderKind::OpenAi
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ProviderKind {
    OpenAi,
    Anthropic,
    Gemini,
}

impl ProviderKind {
    pub fn parse(s: &str) -> Result<Self> {
        match s.to_ascii_lowercase().as_str() {
            "openai" => Ok(Self::OpenAi),
            "anthropic" | "claude" => Ok(Self::Anthropic),
            "gemini" | "google" => Ok(Self::Gemini),
            other => Err(XeError::Config(format!(
                "unknown provider kind `{other}` (expected openai | anthropic | gemini)"
            ))),
        }
    }
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::OpenAi => "openai",
            Self::Anthropic => "anthropic",
            Self::Gemini => "gemini",
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PcConfig {
    #[serde(default)]
    pub providers: Vec<ProviderConfig>,
    #[serde(default)]
    pub active: Option<String>,
    #[serde(rename = "defaultWorkspace", default)]
    pub default_workspace: Option<String>,
}

impl PcConfig {
    pub fn active_provider(&self) -> Option<&ProviderConfig> {
        let name = self.active.as_deref()?;
        self.providers.iter().find(|p| p.name == name)
    }

    pub fn validate(&self) -> Result<()> {
        if self.providers.is_empty() {
            return Err(XeError::Config(
                "no providers configured — run `xencode config set-provider` or edit ~/.xencode/pc-config.json".into(),
            ));
        }
        let names: Vec<_> = self.providers.iter().map(|p| p.name.clone()).collect();
        if names.len() != names.iter().collect::<std::collections::BTreeSet<_>>().len() {
            return Err(XeError::Config("duplicate provider names in config".into()));
        }
        if let Some(active) = &self.active {
            if !names.contains(active) {
                return Err(XeError::Config(format!(
                    "active provider `{active}` not among [{}]",
                    names.join(", ")
                )));
            }
        }
        Ok(())
    }
}

/// The `~/.xencode` home directory (overridable via `XENCODE_HOME`, legacy
/// `AICODE_HOME` kept as a fallback for existing installs).
pub fn xencode_home() -> PathBuf {
    if let Ok(p) = std::env::var(XENCODE_HOME_ENV) {
        if !p.trim().is_empty() {
            return PathBuf::from(p);
        }
    }
    if let Ok(p) = std::env::var(AICODE_HOME_ENV) {
        if !p.trim().is_empty() {
            return PathBuf::from(p);
        }
    }
    dirs_home().join(".xencode")
}

fn dirs_home() -> PathBuf {
    if let Ok(p) = std::env::var("HOME") {
        if !p.trim().is_empty() {
            return PathBuf::from(p);
        }
    }
    if let Ok(p) = std::env::var("USERPROFILE") {
        if !p.trim().is_empty() {
            return PathBuf::from(p);
        }
    }
    PathBuf::from(".")
}

pub fn config_path() -> PathBuf {
    xencode_home().join("pc-config.json")
}

pub fn xp_ledger_path() -> PathBuf {
    xencode_home().join("pc-xp-ledger.json")
}

/// Path of the **shared** agent database — the Kotlin app's own Room database
/// (`aicode_agent_db`, schema v59), opened directly by the PC line so mobile
/// and PC read/write the same brain.
///
/// Resolution order: `XENCODE_DB` → `AICODE_DB` → `~/.xencode/aicode_agent_db`.
/// The new `*_DB` variables are additive; the established `AICODE_*` contract
/// is unchanged.
pub fn db_path() -> PathBuf {
    if let Ok(p) = std::env::var("XENCODE_DB") {
        if !p.trim().is_empty() {
            return PathBuf::from(p);
        }
    }
    if let Ok(p) = std::env::var("AICODE_DB") {
        if !p.trim().is_empty() {
            return PathBuf::from(p);
        }
    }
    xencode_home().join(crate::store::DATABASE_NAME)
}

/// Load config, returning `Ok(default)` when the file does not exist yet.
pub fn load_config() -> Result<PcConfig> {
    let path = config_path();
    if !path.exists() {
        return Ok(PcConfig::default());
    }
    let raw = std::fs::read_to_string(&path)?;
    let cfg: PcConfig = serde_json::from_str(&raw)?;
    Ok(cfg)
}

pub fn save_config(cfg: &PcConfig) -> Result<()> {
    let path = config_path();
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let json = serde_json::to_string_pretty(cfg)?;
    write_atomic(&path, json.as_bytes())?;
    Ok(())
}

/// Atomic file write: temp file + rename, falling back to direct write when
/// rename across devices fails.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    std::fs::create_dir_all(parent)?;
    let tmp = parent.join(format!(
        ".{}.tmp",
        path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default()
    ));
    std::fs::write(&tmp, bytes)?;
    if std::fs::rename(&tmp, path).is_err() {
        std::fs::write(path, bytes)?;
        let _ = std::fs::remove_file(&tmp);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_config_json_matches_kotlin_schema() {
        let cfg = PcConfig {
            providers: vec![ProviderConfig {
                name: "main".into(),
                kind: ProviderKind::Anthropic,
                base_url: "https://api.anthropic.com".into(),
                api_key: "sk".into(),
                model: "claude-sonnet-4".into(),
                temperature: Some(0.2),
                max_tokens: Some(8192),
                extra_headers: Default::default(),
            }],
            active: Some("main".into()),
            default_workspace: Some("/tmp/ws".into()),
        };
        let s = serde_json::to_string(&cfg).unwrap();
        assert!(s.contains("\"kind\":\"anthropic\""));
        assert!(s.contains("\"baseUrl\":\"https://api.anthropic.com\""));
        assert!(s.contains("\"maxTokens\":8192"));
        assert!(s.contains("\"defaultWorkspace\":\"/tmp/ws\""));
        // Round-trips through the Kotlin/Gson document too.
        let legacy = r#"{"providers":[{"name":"m","baseUrl":"https://x","apiKey":"k","model":"g","extraHeaders":{}}],"active":"m"}"#;
        let parsed: PcConfig = serde_json::from_str(legacy).unwrap();
        assert_eq!(parsed.providers[0].base_url, "https://x");
        assert_eq!(parsed.active_provider().unwrap().name, "m");
    }

    #[test]
    fn provider_kind_parse() {
        assert_eq!(ProviderKind::parse("OpenAI").unwrap(), ProviderKind::OpenAi);
        assert_eq!(ProviderKind::parse("claude").unwrap(), ProviderKind::Anthropic);
        assert!(ProviderKind::parse("bogus").is_err());
    }

    #[test]
    fn write_atomic_creates_file() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("sub").join("f.json");
        write_atomic(&p, b"{}").unwrap();
        assert_eq!(std::fs::read(&p).unwrap(), b"{}");
    }
}
