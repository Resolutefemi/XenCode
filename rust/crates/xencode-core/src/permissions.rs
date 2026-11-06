//! Permission gate — modes: read-only, confirm (default), yolo, plan.
//!
//! `plan` is the Claude-Code/codex-style planning mode: research is allowed
//! (read/search/glob/grep, todo_write for drafting the plan, web_fetch,
//! load_skill, `task` research subagents) while every mutating tool is
//! denied with plan-mode guidance until the user switches the mode back.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PermissionMode {
    #[serde(alias = "readonly", alias = "ro")]
    ReadOnly,
    #[serde(alias = "ask", alias = "default", alias = "build")]
    Confirm,
    Yolo,
    Plan,
}

impl PermissionMode {
    pub fn parse(s: &str) -> Option<Self> {
        match s.to_ascii_lowercase().as_str() {
            "read-only" | "readonly" | "ro" => Some(Self::ReadOnly),
            "confirm" | "ask" | "default" | "build" => Some(Self::Confirm),
            "yolo" | "full" | "unsafe" => Some(Self::Yolo),
            "plan" | "planning" => Some(Self::Plan),
            _ => None,
        }
    }
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::ReadOnly => "read-only",
            Self::Confirm => "confirm",
            Self::Yolo => "yolo",
            Self::Plan => "plan",
        }
    }

    /// The `chat_sessions.mode` value the Android app understands
    /// (`AgentMode`: BUILD / ASK / PLAN). Plan maps 1:1; read-only research
    /// maps to ASK; confirm/yolo are the building modes.
    pub fn agent_mode_string(&self) -> &'static str {
        match self {
            Self::ReadOnly => "ASK",
            Self::Confirm | Self::Yolo => "BUILD",
            Self::Plan => "PLAN",
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

    /// Research tools additionally allowed in plan mode (never mutate the
    /// workspace): web pages, skill instructions, and `task` subagents that
    /// inherit the same plan gate.
    pub const PLAN_EXTRA_TOOLS: &'static [&'static str] = &["web_fetch", "load_skill", "task"];

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
            PermissionMode::Plan => {
                if Self::READ_ONLY_TOOLS.contains(&tool)
                    || Self::PLAN_EXTRA_TOOLS.contains(&tool)
                    || self.allow.iter().any(|a| a == tool)
                {
                    Decision::Allow
                } else {
                    Decision::Deny(format!(
                        "plan mode: `{tool}` is disabled — research the workspace, then present a numbered plan; switch out of plan mode to execute it"
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
    fn plan_mode_allows_research_and_blocks_mutations() {
        let plan = PermissionGate::new(PermissionMode::Plan);
        for tool in ["read_file", "glob", "grep", "todo_write", "web_fetch", "load_skill", "task"] {
            assert!(matches!(plan.check(tool, "{}"), Decision::Allow), "{tool} should be allowed in plan mode");
        }
        for tool in ["write_file", "edit_file", "delete_file", "run_command"] {
            match plan.check(tool, "{}") {
                Decision::Deny(reason) => assert!(reason.contains("plan mode"), "{tool}: {reason}"),
                other => panic!("{tool} should be denied in plan mode, got {other:?}"),
            }
        }
        // An allow-listed MCP tool stays available (explicit user opt-in).
        let mut g = PermissionGate::new(PermissionMode::Plan);
        g.allow.push("mcp_docs_search".into());
        assert!(matches!(g.check("mcp_docs_search", "{}"), Decision::Allow));
    }

    #[test]
    fn mode_vocabulary_maps_to_agent_modes() {
        assert_eq!(PermissionMode::Plan.agent_mode_string(), "PLAN");
        assert_eq!(PermissionMode::Confirm.agent_mode_string(), "BUILD");
        assert_eq!(PermissionMode::Yolo.agent_mode_string(), "BUILD");
        assert_eq!(PermissionMode::ReadOnly.agent_mode_string(), "ASK");
        assert_eq!("plan", PermissionMode::Plan.as_str());
        assert_eq!(Some(PermissionMode::Plan), PermissionMode::parse("PLAN"));
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
