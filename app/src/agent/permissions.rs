//! Permission gates: every tool call passes through one before execution.
//!
//! A gate answers with a [`Decision`]: `Allow`, `Deny`, or `Ask`. When the
//! loop receives `Ask` it calls the configured asker (a stdin prompter in the
//! CLI, a GUI consent card in the chat pane, an auto-allow in tests). The
//! GUI gate (Phase 2 `xen.toml` scopes) is just another implementation of
//! this trait.

use serde_json::Value;
use std::collections::HashSet;
use std::sync::Mutex;

/// What a gate says about a tool call.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    Allow,
    Ask,
    Deny,
}

/// The user's answer to a permission prompt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Response {
    /// Approve this call only.
    Allow,
    /// Approve and remember the choice for this tool for the whole session.
    AllowAlways,
    /// Reject the call; the model sees "Permission denied by the user."
    Deny,
}

/// A pending permission request, rendered in the chat pane consent card.
#[derive(Debug, Clone)]
pub struct Request {
    pub tool: String,
    pub detail: String,
}

pub trait PermissionGate: Send + Sync {
    /// Legacy predicate — kept for callers that only need allow/deny.
    fn allow(&self, tool: &str, args: &Value) -> bool {
        matches!(self.review(tool, args), Decision::Allow)
    }

    /// Classify a tool call.
    fn review(&self, _tool: &str, _args: &Value) -> Decision {
        Decision::Allow
    }

    /// Record an "always allow <tool>" answer for the rest of the session.
    fn allow_always(&self, _tool: &str) {}

    /// The tool-result text a `Decision::Deny` produces. Gates can
    /// specialize it (plan mode explains what is blocked and why).
    fn denial_message(&self) -> String {
        "Permission denied by the user.".to_string()
    }
}

/// Allow everything — headless/CI default (non-interactive runs).
pub struct AllowAll;

impl PermissionGate for AllowAll {
    fn review(&self, _tool: &str, _args: &Value) -> Decision {
        Decision::Allow
    }
}

/// Read-only policy: mutating tools are hard-denied (no prompt).
pub struct ReadOnly;

/// Read-only tools pass every gate untouched. Mutating: the three file/process
/// built-ins, `task` (a subagent may mutate inside the same worktree), and
/// every MCP tool (the loop cannot know what a server tool does — treat all
/// of them as mutations).
fn is_read_only_tool(tool: &str) -> bool {
    match tool {
        "write_file" | "edit_file" | "bash" | "task" => false,
        t if t.starts_with("mcp__") => false,
        _ => true,
    }
}

impl PermissionGate for ReadOnly {
    fn review(&self, tool: &str, _args: &Value) -> Decision {
        if is_read_only_tool(tool) {
            Decision::Allow
        } else {
            Decision::Deny
        }
    }
}

/// Interactive policy: reads are free, mutations ask for consent.
/// An "always allow" answer is remembered per tool for the session.
#[derive(Default)]
pub struct AskGate {
    always: Mutex<HashSet<String>>,
}

impl AskGate {
    pub fn new() -> Self {
        Self::default()
    }
}

impl PermissionGate for AskGate {
    fn review(&self, tool: &str, _args: &Value) -> Decision {
        if is_read_only_tool(tool) {
            return Decision::Allow;
        }
        if self
            .always
            .lock()
            .expect("ask gate lock")
            .contains(tool)
        {
            Decision::Allow
        } else {
            Decision::Ask
        }
    }

    fn allow_always(&self, tool: &str) {
        self.always.lock().expect("ask gate lock").insert(tool.to_string());
    }
}

/// Deny everything (tests, plan-mode scaffolding).
pub struct DenyAll;

impl PermissionGate for DenyAll {
    fn review(&self, _tool: &str, _args: &Value) -> Decision {
        Decision::Deny
    }
}

/// Plan mode (Phase 3): research freely, mutations hard-denied until the
/// user approves the plan. Approval re-runs the session with the execution
/// gate — this gate never asks, it just blocks.
pub struct PlanGate;

impl PermissionGate for PlanGate {
    fn review(&self, tool: &str, _args: &Value) -> Decision {
        if is_read_only_tool(tool) {
            Decision::Allow
        } else {
            Decision::Deny
        }
    }

    fn denial_message(&self) -> String {
        "Plan mode: mutations are blocked until the plan is approved.".to_string()
    }
}

/// `accept-edits` permission mode: file edits run without prompting,
/// everything else follows the interactive AskGate flow.
pub struct AcceptEdits {
    inner: AskGate,
}

impl AcceptEdits {
    pub fn new() -> Self {
        Self { inner: AskGate::new() }
    }
}

impl Default for AcceptEdits {
    fn default() -> Self {
        Self::new()
    }
}

impl PermissionGate for AcceptEdits {
    fn review(&self, tool: &str, args: &Value) -> Decision {
        match tool {
            "write_file" | "edit_file" => Decision::Allow,
            _ => self.inner.review(tool, args),
        }
    }

    fn allow_always(&self, tool: &str) {
        self.inner.allow_always(tool)
    }
}

/// The five permission modes (Phase 3, harness port #5 — from Claude Code /
/// Codex). Selected with `--permission-mode <name>` (or `--plan`, which is
/// sugar for `plan`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PermissionMode {
    /// Reads free, mutations ask (the interactive default).
    #[default]
    Default,
    /// File edits auto-approved, the rest asks.
    AcceptEdits,
    /// Mutations hard-denied (`--read-only` sugar).
    ReadOnly,
    /// Research-only until the plan is approved.
    Plan,
    /// Allow everything, never ask (CI/bypass).
    Yolo,
}

impl PermissionMode {
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "default" => Some(Self::Default),
            "accept-edits" => Some(Self::AcceptEdits),
            "read-only" | "readonly" => Some(Self::ReadOnly),
            "plan" => Some(Self::Plan),
            "yolo" | "bypass-permissions" => Some(Self::Yolo),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Default => "default",
            Self::AcceptEdits => "accept-edits",
            Self::ReadOnly => "read-only",
            Self::Plan => "plan",
            Self::Yolo => "yolo",
        }
    }

    /// The gate this mode installs for a run.
    pub fn gate(&self) -> Box<dyn PermissionGate> {
        match self {
            Self::Default => Box::new(AskGate::new()),
            Self::AcceptEdits => Box::new(AcceptEdits::new()),
            Self::ReadOnly => Box::new(ReadOnly),
            Self::Plan => Box::new(PlanGate),
            Self::Yolo => Box::new(AllowAll),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn allow_all_still_allows() {
        let g = AllowAll;
        assert!(g.allow("bash", &json!({})));
        assert_eq!(g.review("write_file", &json!({})), Decision::Allow);
    }

    #[test]
    fn read_only_hard_denies_mutations() {
        let g = ReadOnly;
        assert!(g.allow("read_file", &json!({})));
        assert!(g.allow("grep", &json!({})));
        assert_eq!(g.review("bash", &json!({})), Decision::Deny);
        assert_eq!(g.review("edit_file", &json!({})), Decision::Deny);
    }

    #[test]
    fn ask_gate_asks_then_remembers() {
        let g = AskGate::new();
        assert_eq!(g.review("read_file", &json!({})), Decision::Allow);
        assert_eq!(g.review("bash", &json!({})), Decision::Ask);
        assert_eq!(g.review("write_file", &json!({})), Decision::Ask);

        g.allow_always("bash");
        assert_eq!(g.review("bash", &json!({})), Decision::Allow);
        assert_eq!(g.review("write_file", &json!({})), Decision::Ask);
    }

    #[test]
    fn plan_gate_blocks_mutations_with_a_clear_message() {
        let g = PlanGate;
        assert_eq!(g.review("read_file", &json!({})), Decision::Allow);
        assert_eq!(g.review("grep", &json!({})), Decision::Allow);
        assert_eq!(g.review("list_dir", &json!({})), Decision::Allow);
        assert_eq!(g.review("write_file", &json!({})), Decision::Deny);
        assert_eq!(g.review("edit_file", &json!({})), Decision::Deny);
        assert_eq!(g.review("bash", &json!({})), Decision::Deny);
        assert!(g.denial_message().contains("Plan mode"));
    }

    #[test]
    fn accept_edits_auto_allows_file_edits_only() {
        let g = AcceptEdits::new();
        assert_eq!(g.review("write_file", &json!({})), Decision::Allow);
        assert_eq!(g.review("edit_file", &json!({})), Decision::Allow);
        assert_eq!(g.review("bash", &json!({})), Decision::Ask);
        g.allow_always("bash");
        assert_eq!(g.review("bash", &json!({})), Decision::Allow);
    }

    #[test]
    fn permission_modes_parse_and_build_gates() {
        for name in ["default", "accept-edits", "read-only", "plan", "yolo"] {
            let m = PermissionMode::parse(name).unwrap_or_else(|| panic!("{name} must parse"));
            assert_eq!(m.as_str(), name);
            let _ = m.gate(); // every mode installs a gate
        }
        assert!(PermissionMode::parse("bogus").is_none());
        assert!(PermissionMode::parse("bypass-permissions").is_some());
        assert_eq!(PermissionMode::parse("plan"), Some(PermissionMode::Plan));
    }
}

/// Phase 4: `task` and every `mcp__*` tool count as a mutation, so read-only
/// policies (and consent prompts) cover them like file edits.
#[cfg(test)]
mod mcp_and_task_gate_tests {
    use super::*;

    #[test]
    fn task_and_mcp_tools_are_treated_as_mutations() {
        let g = AskGate::new();
        assert_eq!(g.review("task", &serde_json::json!({})), Decision::Ask);
        assert_eq!(g.review("mcp__fs__read", &serde_json::json!({})), Decision::Ask);
        assert_eq!(g.review("read_file", &serde_json::json!({})), Decision::Allow);

        let ro = ReadOnly;
        assert_eq!(ro.review("task", &serde_json::json!({})), Decision::Deny);
        assert_eq!(ro.review("mcp__git__log", &serde_json::json!({})), Decision::Deny);

        let ae = AcceptEdits::new();
        assert_eq!(ae.review("task", &serde_json::json!({})), Decision::Ask);
        g.allow_always("task");
        assert_eq!(g.review("task", &serde_json::json!({})), Decision::Allow);
    }
}
