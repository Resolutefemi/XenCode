//! Hooks: user-defined commands that run before/after tool calls
//! (`.xencode/hooks.json`). A pre-hook exiting non-zero blocks the tool.

use crate::config::write_atomic;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HookRule {
    /// Tool name this hook applies to, or "*" for all tools.
    #[serde(default = "default_matcher")]
    pub matcher: String,
    /// Shell command to run. Receives XENCODE_TOOL, XENCODE_TOOL_ARGS env vars.
    pub command: String,
    #[serde(default = "default_stage")]
    pub stage: String, // "pre" | "post"
}

fn default_matcher() -> String {
    "*".into()
}
fn default_stage() -> String {
    "pre".into()
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Hooks {
    #[serde(default)]
    pub hooks: Vec<HookRule>,
}

impl Hooks {
    pub fn path(workspace_root: &Path) -> PathBuf {
        workspace_root.join(".xencode").join("hooks.json")
    }

    pub fn load(workspace_root: &Path) -> Self {
        std::fs::read_to_string(Self::path(workspace_root))
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, workspace_root: &Path) -> Result<(), String> {
        let json = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        write_atomic(&Self::path(workspace_root), json.as_bytes()).map_err(|e| e.to_string())
    }

    pub fn for_tool<'a>(&'a self, tool: &str, stage: &str) -> Vec<&'a HookRule> {
        self.hooks
            .iter()
            .filter(|h| h.stage == stage && (h.matcher == "*" || h.matcher == tool))
            .collect()
    }
}

/// Run pre-tool hooks. Returns Err(block reason) when a pre-hook fails.
pub fn run_pre_hooks(
    hooks: &Hooks,
    tool: &str,
    args_json: &str,
    workspace_root: &Path,
) -> Result<(), String> {
    for hook in hooks.for_tool(tool, "pre") {
        let env = vec![
            ("XENCODE_TOOL", tool),
            ("XENCODE_TOOL_ARGS", args_json),
        ];
        let out = crate::tools::run_shell_env(&hook.command, workspace_root, 30, &env)
            .unwrap_or_else(|e| format!("exit=-1\nERROR: {e}"));
        let code = out
            .lines()
            .next()
            .and_then(|l| l.strip_prefix("exit="))
            .and_then(|c| c.parse::<i32>().ok())
            .unwrap_or(-1);
        if code != 0 {
            return Err(format!("pre-hook `{}` blocked `{tool}`: {}", hook.command, &out[..out.len().min(500)]));
        }
    }
    Ok(())
}

/// Run post-tool hooks (failures are recorded but never block).
pub fn run_post_hooks(hooks: &Hooks, tool: &str, args_json: &str, workspace_root: &Path) -> Vec<String> {
    let mut notes = vec![];
    for hook in hooks.for_tool(tool, "post") {
        let env = vec![("XENCODE_TOOL", tool), ("XENCODE_TOOL_ARGS", args_json)];
        let out = crate::tools::run_shell_env(&hook.command, workspace_root, 30, &env)
            .unwrap_or_else(|e| format!("exit=-1\nERROR: {e}"));
        notes.push(format!("post-hook {}: {}", hook.command, out.lines().next().unwrap_or("")));
    }
    notes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pre_hook_failure_blocks_tool() {
        let dir = tempfile::tempdir().unwrap();
        let hooks = Hooks {
            hooks: vec![HookRule { matcher: "run_command".into(), command: "exit 9".into(), stage: "pre".into() }],
        };
        let res = run_pre_hooks(&hooks, "run_command", "{}", dir.path());
        assert!(res.is_err());
        // Post hooks never block.
        let notes = run_post_hooks(&hooks, "run_command", "{}", dir.path());
        assert_eq!(notes.len(), 0); // stage=pre, so no post hooks matched
        let post = Hooks {
            hooks: vec![HookRule { matcher: "*".into(), command: "echo done".into(), stage: "post".into() }],
        };
        let notes = run_post_hooks(&post, "write_file", "{}", dir.path());
        assert_eq!(notes.len(), 1);
        assert!(notes[0].contains("done"));
    }

    #[test]
    fn load_missing_file_is_default() {
        let dir = tempfile::tempdir().unwrap();
        let h = Hooks::load(dir.path());
        assert!(h.hooks.is_empty());
    }
}
