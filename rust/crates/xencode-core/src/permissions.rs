//! Permission gate — modes: read-only, confirm (default), yolo.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PermissionMode {
    #[serde(alias = "readonly", alias = "ro")]
    ReadOnly,
    #[serde(alias = "ask", alias = "default")]
    Confirm,
    #[serde(alias = "full", alias = "unsafe")]
    Yolo,
}

impl PermissionMode {
    pub fn parse(s: &str) -> Option<Self> {
        match s.to_ascii_lowercase().as_str() {
            "read-only" | "readonly" | "ro" => Some(Self::ReadOnly),
            "confirm" | "ask" | "default" => Some(Self::Confirm),
            "yolo" | "full" | "unsafe" => Some(Self::Yolo),
            _ => None,
        }
    }
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::ReadOnly => "read-only",
            Self::Confirm => "confirm",
            Self::Yolo => "yolo",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    Allow,
    /// Present the action to the user (CLI prompt / desktop banner / decision card).
    Ask,
    Deny(String),
}

#[derive(Debug, Clone)]
pub struct PermissionGate {
    pub mode: PermissionMode,
    /// Extra always-allowed tool names (beyond the built-in read-only set).
    pub allow: Vec<String>,
    /// Explicitly denied tool names.
    pub deny: Vec<String>,
}

impl Default for PermissionGate {
    fn default() -> Self {
        Self { mode: PermissionMode::Confirm, allow: vec![], deny: vec![] }
    }
}

impl PermissionGate {
    pub const READ_ONLY_TOOLS: &'static [&'static str] = &[
        "read_file",
        "list_files",
        "glob",
        "grep",
        "todo_read",
        "todo_write",
    ];

    pub fn new(mode: PermissionMode) -> Self {
        Self { mode, ..Self::default() }
    }

    pub fn check(&self, tool: &str, _args_json: &str) -> Decision {
        if self.deny.iter().any(|d| d == tool) {
            return Decision::Deny(format!("tool `{tool}` is denied by policy"));
        }
        match self.mode {
            PermissionMode::Yolo => Decision::Allow,
            PermissionMode::ReadOnly => {
                if Self::READ_ONLY_TOOLS.contains(&tool) || self.allow.iter().any(|a| a == tool) {
                    Decision::Allow
                } else {
                    Decision::Deny(format!(
                        "read-only mode: `{tool}` mutates the workspace; switch permission mode to confirm or yolo"
                    ))
                }
            }
            PermissionMode::Confirm => {
                if Self::READ_ONLY_TOOLS.contains(&tool) || self.allow.iter().any(|a| a == tool) {
                    Decision::Allow
                } else {
                    Decision::Ask
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modes_gate_correctly() {
        let ro = PermissionGate::new(PermissionMode::ReadOnly);
        assert!(matches!(ro.check("read_file", "{}"), Decision::Allow));
        assert!(matches!(ro.check("write_file", "{}"), Decision::Deny(_)));

        let confirm = PermissionGate::new(PermissionMode::Confirm);
        assert!(matches!(confirm.check("read_file", "{}"), Decision::Allow));
        assert!(matches!(confirm.check("run_command", "{}"), Decision::Ask));

        let yolo = PermissionGate::new(PermissionMode::Yolo);
        assert!(matches!(yolo.check("run_command", "{}"), Decision::Allow));
    }

    #[test]
    fn deny_and_allow_lists_win() {
        let mut g = PermissionGate::new(PermissionMode::Yolo);
        g.deny.push("delete_file".into());
        assert!(matches!(g.check("delete_file", "{}"), Decision::Deny(_)));
        let mut g2 = PermissionGate::new(PermissionMode::ReadOnly);
        g2.allow.push("run_command".into());
        assert!(matches!(g2.check("run_command", "{}"), Decision::Allow));
    }
}
