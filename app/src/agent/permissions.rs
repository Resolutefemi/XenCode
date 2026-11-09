//! Permission gates: every tool call passes through one before execution.
//! The GUI consent prompt (Phase 2) is just another implementation of this
//! trait; until it lands, headless mode defaults to `AllowAll`.

use serde_json::Value;

pub trait PermissionGate: Send + Sync {
    fn allow(&self, tool: &str, args: &Value) -> bool;
}

/// Allow everything — headless/CI default until the GUI gate lands.
pub struct AllowAll;

impl PermissionGate for AllowAll {
    fn allow(&self, _tool: &str, _args: &Value) -> bool {
        true
    }
}

/// Read-only policy: block anything that mutates the world.
pub struct ReadOnly;

impl PermissionGate for ReadOnly {
    fn allow(&self, tool: &str, _args: &Value) -> bool {
        !matches!(tool, "write_file" | "edit_file" | "bash")
    }
}

/// Deny everything (tests).
pub struct DenyAll;

impl PermissionGate for DenyAll {
    fn allow(&self, _tool: &str, _args: &Value) -> bool {
        false
    }
}
