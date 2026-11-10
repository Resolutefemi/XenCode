//! `xen.toml` — automation configuration: permissions, triggers and hooks.
//!
//! ```toml
//! [hooks]
//! # Agent-loop hooks (Phase 3) — QuickJS scripts, each in a fresh sandbox.
//! pre_tool_use  = [".xencode/hooks/guard.ts"]   # may deny a tool call
//! post_tool_use = [".xencode/hooks/audit.js"]   # may attach feedback
//! stop          = [".xencode/hooks/verify.ts"]  # may block stopping
//! timeout_ms    = 10000
//!
//! [permissions]
//! net  = ["api.example.com"]   # host allowlist, or `true` for all
//! fs   = ["$PROJECT/data"]     # path allowlist ($PROJECT = xen.toml dir)
//! proc = true
//! env  = ["HOME"]
//!
//! [[triggers]]
//! id   = "nightly-report"
//! cron = "0 30 9 * * *"        # sec min hour dom mon dow (optional sec)
//! run  = "scripts/report.ts"
//!
//! [[triggers]]
//! id    = "watch-src"
//! watch = "src/**/*.rs"
//! run   = "scripts/rebuild.js"
//!
//! [[triggers]]
//! id      = "deploy-hook"
//! webhook = "/deploy"          # POST http://127.0.0.1:<port>/deploy
//! run     = "scripts/deploy.js"
//!
//! [[triggers]]
//! id        = "shutdown-sync"
//! lifecycle = "onStop"
//! run       = "scripts/sync.js"
//! ```

use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::permissions::PermissionConfig;

#[derive(Debug, Default, Clone, Deserialize)]
#[serde(default)]
pub struct XenConfig {
    /// Deno-style permission grants (GUI consent on first use).
    pub permissions: PermissionConfig,
    /// Automation triggers — one engine, four kinds.
    pub triggers: Vec<TriggerConfig>,
    /// Agent-loop hooks (`[hooks]`) — PreToolUse / PostToolUse / Stop.
    pub hooks: HooksConfig,
    /// Extra env vars injected into scripts (`[automation.env]`).
    pub automation: AutomationConfig,
}

/// `[hooks]` table of `xen.toml`: the scripts that observe and gate the
/// agent loop. Every entry is a script path relative to the config file;
/// each run executes in its own isolated QuickJS context (fresh sandbox,
/// interrupt guard, per-hook timeout).
#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct HooksConfig {
    /// Run before a tool executes — return `{ decision: "deny", reason }` to block.
    pub pre_tool_use: Vec<String>,
    /// Run after a tool executed — return `{ feedback: "…" }` appended to the result.
    pub post_tool_use: Vec<String>,
    /// Run when the model is about to stop — return `{ decision: "block", reason }`
    /// to push the reason into the loop and continue.
    pub stop: Vec<String>,
    /// Per-hook-run timeout in milliseconds (default 10 000).
    pub timeout_ms: Option<u64>,
}

impl HooksConfig {
    /// True when no hook scripts are configured at all.
    pub fn is_empty(&self) -> bool {
        self.pre_tool_use.is_empty() && self.post_tool_use.is_empty() && self.stop.is_empty()
    }
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct AutomationConfig {
    pub env: std::collections::BTreeMap<String, String>,
}

/// One automation trigger entry (`[[triggers]]` tables).
#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct TriggerConfig {
    /// Stable id (defaults to the run target).
    pub id: Option<String>,
    /// cron expression — e.g. `"0 30 9 * * *"` (sec optional) or `"@daily"`.
    pub cron: Option<String>,
    /// Glob pattern to watch — e.g. `"src/**/*.rs"`.
    pub watch: Option<String>,
    /// Webhook path — e.g. `"/deploy"` → `POST /deploy`.
    pub webhook: Option<String>,
    /// Lifecycle hook name — `"onStart"`, `"onStop"`, `"onError"`.
    pub lifecycle: Option<String>,
    /// Script to run (relative to the config file).
    pub run: String,
    /// Per-run timeout in milliseconds.
    pub timeout_ms: Option<u64>,
}

impl TriggerConfig {
    /// Trigger id or a fallback derived from the kind + target.
    pub fn id_or(&self) -> String {
        self.id.clone().unwrap_or_else(|| {
            let kind = if self.cron.is_some() {
                "cron"
            } else if self.watch.is_some() {
                "watch"
            } else if self.webhook.is_some() {
                "webhook"
            } else if self.lifecycle.is_some() {
                self.lifecycle.as_deref().unwrap_or("lifecycle")
            } else {
                "trigger"
            };
            format!("{kind}:{}", self.run)
        })
    }

    pub fn kind(&self) -> &'static str {
        if self.cron.is_some() {
            "cron"
        } else if self.watch.is_some() {
            "watch"
        } else if self.webhook.is_some() {
            "webhook"
        } else if self.lifecycle.is_some() {
            "lifecycle"
        } else {
            "manual"
        }
    }
}

impl XenConfig {
    /// Load `xen.toml` from a file (missing file → default config).
    pub fn load(path: &Path) -> anyhow::Result<Self> {
        let text = match std::fs::read_to_string(path) {
            Ok(t) => t,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Self::default()),
            Err(e) => return Err(e.into()),
        };
        let cfg: XenConfig = toml::from_str(&text)
            .map_err(|e| anyhow::anyhow!("{}: {e}", path.display()))?;
        Ok(cfg)
    }

    /// Search for `xen.toml` in `dir` and its parents (like package managers
    /// do). The returned config path is always absolute so `$PROJECT` scopes
    /// resolve regardless of how the script was invoked.
    pub fn discover(dir: &Path) -> anyhow::Result<(Self, PathBuf)> {
        let dir = if dir.is_absolute() {
            dir.to_path_buf()
        } else {
            std::env::current_dir().unwrap_or_default().join(dir)
        };
        let mut cur = dir.clone();
        loop {
            let candidate = cur.join("xen.toml");
            if candidate.is_file() {
                let abs = candidate.canonicalize().unwrap_or(candidate);
                return Ok((Self::load(&abs)?, abs));
            }
            if !cur.pop() {
                return Ok((Self::default(), dir.join("xen.toml")));
            }
        }
    }

    /// Resolve a trigger's `run` path relative to the config file location.
    pub fn resolve_run(&self, config_path: &Path, run: &str) -> PathBuf {
        let base = config_path.parent().unwrap_or(Path::new("."));
        base.join(run)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_permissions_and_triggers() {
        let cfg: XenConfig = toml::from_str(
            r#"
            [permissions]
            net = ["api.example.com"]
            fs = ["$PROJECT/data"]
            proc = true
            env = ["HOME"]

            [[triggers]]
            id = "nightly"
            cron = "0 30 9 * * *"
            run = "scripts/report.ts"
            timeout_ms = 60000

            [[triggers]]
            watch = "src/**/*.rs"
            run = "scripts/rebuild.js"
            "#,
        )
        .unwrap();
        assert_eq!(cfg.triggers.len(), 2);
        assert_eq!(cfg.triggers[0].id_or(), "nightly");
        assert_eq!(cfg.triggers[0].kind(), "cron");
        assert_eq!(cfg.triggers[1].kind(), "watch");
        assert_eq!(cfg.triggers[1].timeout_ms, None);
        // net is a list here → not an all-grant
        assert!(cfg.permissions.net_grants_all().is_none());
        assert_eq!(cfg.permissions.net_list(), vec!["api.example.com"]);
        assert!(cfg.permissions.proc_allows_all());
    }

    #[test]
    fn empty_config_is_default() {
        let cfg: XenConfig = toml::from_str("").unwrap();
        assert!(cfg.triggers.is_empty());
        assert!(cfg.hooks.is_empty());
        assert!(cfg.permissions.is_empty());
    }

    #[test]
    fn parses_hooks_table() {
        let cfg: XenConfig = toml::from_str(
            r#"
            [hooks]
            pre_tool_use = [".xencode/hooks/guard.ts", ".xencode/hooks/second.js"]
            stop = [".xencode/hooks/verify.ts"]
            timeout_ms = 5000
            "#,
        )
        .unwrap();
        assert!(!cfg.hooks.is_empty());
        assert_eq!(cfg.hooks.pre_tool_use.len(), 2);
        assert_eq!(cfg.hooks.post_tool_use.len(), 0);
        assert_eq!(cfg.hooks.stop.len(), 1);
        assert_eq!(cfg.hooks.timeout_ms, Some(5000));
    }

    #[test]
    fn unknown_fields_are_ignored() {
        let cfg: XenConfig = toml::from_str("[permissions]\nfuture_flag = true\n").unwrap();
        assert!(cfg.permissions.is_empty());
    }
}
