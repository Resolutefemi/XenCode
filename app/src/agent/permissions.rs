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

fn is_read_only_tool(tool: &str) -> bool {
    !matches!(tool, "write_file" | "edit_file" | "bash")
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
}
