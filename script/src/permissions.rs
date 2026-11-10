//! Deno-style permissions for scripts, declared in `xen.toml` and consented
//! in the GUI on first use.
//!
//! Grants are either `true` (all), a list of scopes (hostnames for `net`,
//! path globs for `fs`, key names for `env`) or absent (denied). When a
//! script touches a scope that is neither granted nor denied, the host is
//! asked through the [`PromptBridge`] — the CLI asks on stdin, the GUI
//! renders a consent card — and the session decision is cached.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use serde::Deserialize;

/// A grant: all (`true`), a scope list, or nothing (absent).
#[derive(Debug, Clone, Deserialize, Default)]
#[serde(untagged)]
pub enum Grant {
    #[default]
    None,
    All(bool),
    List(Vec<String>),
}

impl Grant {
    pub fn is_empty(&self) -> bool {
        matches!(self, Grant::None)
    }
    pub fn allows_all(&self) -> bool {
        matches!(self, Grant::All(true))
    }
    pub fn list(&self) -> &[String] {
        match self {
            Grant::List(v) => v,
            _ => &[],
        }
    }
}

/// `[permissions]` table of `xen.toml`.
#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct PermissionConfig {
    /// Network hosts, e.g. `["api.example.com"]` or `true`.
    pub net: Grant,
    /// Filesystem scope globs, e.g. `["$PROJECT/data/**"]`.
    pub fs: Grant,
    /// Process spawning: `true` / specific commands.
    pub proc: Grant,
    /// Env vars readable, e.g. `["HOME", "PATH"]`.
    pub env: Grant,
}

impl PermissionConfig {
    pub fn is_empty(&self) -> bool {
        self.net.is_empty() && self.fs.is_empty() && self.proc.is_empty() && self.env.is_empty()
    }
    pub fn net_grants_all(&self) -> Option<bool> {
        if let Grant::All(b) = self.net {
            Some(b)
        } else {
            None
        }
    }
    pub fn net_list(&self) -> Vec<String> {
        self.net.list().to_vec()
    }
    pub fn proc_allows_all(&self) -> bool {
        self.proc.allows_all()
    }
}

/// What the user (or host) decided for a consent prompt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    /// Allow once (ask again next time).
    AllowOnce,
    /// Allow for the whole session (cached in-process).
    AllowSession,
    /// Deny this request.
    Deny,
}

impl Decision {
    pub fn allowed(&self) -> bool {
        !matches!(self, Decision::Deny)
    }
}

/// One permission prompt handed to the host.
#[derive(Debug, Clone)]
pub struct PermissionPrompt {
    /// `net` | `fs` | `proc` | `env`
    pub kind: &'static str,
    /// The concrete scope being requested.
    pub detail: String,
}

pub type PromptFn = Box<dyn Fn(&PermissionPrompt) -> Decision + Send + Sync>;

/// Bridge scripts use to ask the host for consent. The CLI installs a stdin
/// asker; the GUI installs one that renders a consent card in the chat pane.
#[derive(Clone, Default)]
pub struct PromptBridge {
    inner: Arc<Mutex<Option<PromptFn>>>,
}

impl PromptBridge {
    pub fn new() -> Self {
        Self::default()
    }

    /// Install the host-side asker.
    pub fn set(&self, f: PromptFn) {
        *self.inner.lock().unwrap() = Some(f);
    }

    /// Ask; falls back to deny when no asker is installed (headless-safe).
    pub fn ask(&self, prompt: &PermissionPrompt) -> Decision {
        match self.inner.lock().unwrap().as_ref() {
            Some(f) => f(prompt),
            None => Decision::Deny,
        }
    }
}

/// A prompt that resolves asynchronously (used by the GUI pane).
pub type PromptReply = tokio::sync::oneshot::Sender<Decision>;

/// Runtime permission state: parsed grants + session cache + asker.
#[derive(Clone)]
pub struct PermissionState {
    config: PermissionConfig,
    project_root: PathBuf,
    cache: Arc<Mutex<Vec<(String, bool)>>>,
    bridge: PromptBridge,
}

impl PermissionState {
    pub fn new(config: PermissionConfig, project_root: impl Into<PathBuf>, bridge: PromptBridge) -> Self {
        Self {
            config,
            project_root: project_root.into(),
            cache: Arc::new(Mutex::new(Vec::new())),
            bridge,
        }
    }

    pub fn allow_all() -> Self {
        Self::new(
            PermissionConfig {
                net: Grant::All(true),
                fs: Grant::All(true),
                proc: Grant::All(true),
                env: Grant::All(true),
            },
            ".",
            PromptBridge::new(),
        )
    }

    fn cached(&self, key: &str) -> Option<bool> {
        self.cache.lock().unwrap().iter().find(|(k, _)| k == key).map(|(_, v)| *v)
    }

    fn remember(&self, key: &str, ok: bool) {
        self.cache.lock().unwrap().push((key.to_string(), ok));
    }

    /// Check a scope, prompting the host on first use (Deno-style flow).
    fn check(&self, kind: &'static str, scope: &str, key: &str) -> Result<(), String> {
        if self.cached(key).unwrap_or(false) {
            return Ok(());
        }
        let allowed = self.host_grants(kind, scope);
        let allowed = match allowed {
            Some(b) => b,
            None => {
                let prompt = PermissionPrompt { kind, detail: scope.to_string() };
                let d = self.bridge.ask(&prompt);
                if d == Decision::AllowSession {
                    self.remember(key, true);
                }
                d.allowed()
            }
        };
        if allowed {
            Ok(())
        } else {
            Err(format!(
                "{kind} access to `{scope}` denied — add it to the `{kind}` scope in xen.toml or allow it when prompted"
            ))
        }
    }

    /// `Some(true/false)` when the static config decides; `None` = ask.
    fn host_grants(&self, kind: &'static str, scope: &str) -> Option<bool> {
        let grant = match kind {
            "net" => &self.config.net,
            "fs" => &self.config.fs,
            "proc" => &self.config.proc,
            "env" => &self.config.env,
            _ => return Some(false),
        };
        match grant {
            Grant::All(true) => Some(true),
            Grant::All(false) => Some(false),
            Grant::None => None,
            Grant::List(items) => {
                if kind == "fs" {
                    Some(items.iter().any(|pat| self.fs_scope_matches(pat, scope)))
                } else {
                    Some(items.iter().any(|it| scope_matches_host(it, scope)))
                }
            }
        }
    }

    /// Match an fs scope pattern (`$PROJECT/**`, `$PROJECT/data`, plain globs).
    fn fs_scope_matches(&self, pattern: &str, path: &str) -> bool {
        let expanded = pattern.replace("$PROJECT", &self.project_root.to_string_lossy());
        let p = Path::new(path);
        let pat = Path::new(&expanded);
        // exact file/dir match or prefix match for dir scopes
        if p.starts_with(pat) {
            return true;
        }
        glob::Pattern::new(&expanded)
            .map(|pat| pat.matches(path))
            .unwrap_or(false)
    }

    // ---- public checks used by the host bridge ----

    pub fn check_net(&self, host: &str) -> Result<(), String> {
        let host = host.split(':').next().unwrap_or(host);
        self.check("net", host, &format!("net:{host}"))
    }

    /// Resolve a script-supplied fs path to an absolute scope path
    /// (relative paths are project-root relative, like Deno's cwd rule).
    fn resolve_fs_path(&self, path: &str) -> String {
        let p = Path::new(path);
        if p.is_absolute() {
            return path.to_string();
        }
        let root = if self.project_root.as_os_str().is_empty() {
            std::env::current_dir().unwrap_or_default()
        } else if self.project_root.is_absolute() {
            self.project_root.clone()
        } else {
            std::env::current_dir()
                .unwrap_or_default()
                .join(&self.project_root)
        };
        root.join(p).to_string_lossy().to_string()
    }

    pub fn check_fs_read(&self, path: &str) -> Result<(), String> {
        let scope = self.resolve_fs_path(path);
        self.check("fs", &scope, &format!("fs:read:{scope}"))
    }

    pub fn check_fs_write(&self, path: &str) -> Result<(), String> {
        let scope = self.resolve_fs_path(path);
        self.check("fs", &scope, &format!("fs:write:{scope}"))
    }

    pub fn check_proc(&self, program: &str) -> Result<(), String> {
        self.check("proc", program, &format!("proc:{program}"))
    }

    pub fn check_env(&self, name: &str) -> Result<(), String> {
        self.check("env", name, &format!("env:{name}"))
    }

    /// The prompt bridge (host installs its asker here).
    pub fn bridge(&self) -> &PromptBridge {
        &self.bridge
    }

    pub fn project_root(&self) -> &Path {
        &self.project_root
    }
}

/// Host allowlist matching: exact host, `*.example.com`, or `*`.
fn scope_matches_host(pattern: &str, host: &str) -> bool {
    if pattern == "*" || pattern.eq_ignore_ascii_case(host) {
        return true;
    }
    if let Some(suffix) = pattern.strip_prefix("*.") {
        return host.ends_with(suffix) && !host.eq_ignore_ascii_case(suffix);
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state(cfg: &str) -> PermissionState {
        let cfg: PermissionConfig = toml::from_str(cfg).unwrap();
        PermissionState::new(cfg, "/tmp/proj", PromptBridge::new())
    }

    #[test]
    fn net_allowlist() {
        let s = state("net = [\"api.example.com\", \"*.cdn.net\"]");
        assert!(s.check_net("api.example.com").is_ok());
        assert!(s.check_net("images.cdn.net").is_ok());
        assert!(s.check_net("evil.com").is_err());
        assert!(s.check_net("api.example.com:443").is_ok());
    }

    #[test]
    fn net_all_and_none() {
        assert!(state("net = true").check_net("anywhere.io").is_ok());
        assert!(state("net = false").check_net("anywhere.io").is_err());
    }

    #[test]
    fn fs_project_scopes() {
        let s = state("fs = [\"$PROJECT/data\", \"$PROJECT/*.log\"]");
        assert!(s.check_fs_read("/tmp/proj/data/out.json").is_ok());
        assert!(s.check_fs_read("/tmp/proj/run.log").is_ok());
        assert!(s.check_fs_read("/tmp/proj/etc/passwd").is_err());
        assert!(s.check_fs_write("/tmp/proj/data/nested/deep.txt").is_ok());
    }

    #[test]
    fn proc_and_env() {
        assert!(state("proc = true").check_proc("cargo").is_ok());
        assert!(state("proc = false").check_proc("cargo").is_err());
        let s = state("env = [\"HOME\"]");
        assert!(s.check_env("HOME").is_ok());
        assert!(s.check_env("AWS_SECRET").is_err());
    }

    #[test]
    fn prompt_bridge_ask_and_cache() {
        let cfg: PermissionConfig = toml::from_str("").unwrap();
        let bridge = PromptBridge::new();
        bridge.set(Box::new(|p| {
            assert_eq!(p.kind, "net");
            Decision::AllowSession
        }));
        let s = PermissionState::new(cfg, ".", bridge);
        assert!(s.check_net("example.org").is_ok());
        // second ask served from the session cache (asker removed → still ok)
        assert!(s.check_net("example.org").is_ok());
    }

    #[test]
    fn no_asker_denies() {
        let s = state("");
        assert!(s.check_net("example.org").is_err());
    }
}
