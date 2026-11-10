//! Subagents — Phase 4 harness port #7 (Claude Code / Amp / ZCode).
//!
//! A subagent is a full agent loop run as a tool call: scoped system prompt,
//! optional tool allowlist, no session persistence, no hooks, no nested
//! `task` calls (recursion is cut off). The parent's registry exposes one
//! `task` tool; several `task` calls in one model turn are fanned out across
//! tokio tasks by the loop (`run.rs::dispatch_subagents`) and run in
//! parallel. The child's final answer becomes the tool result the model
//! sees; its intermediate events stream to the UI wrapped as
//! [`super::run::AgentEvent::Subagent`].
//!
//! Custom agents live in `.xencode/agents/*.md` (project) and
//! `~/.xencode/agents/*.md` (user), project wins on name clashes:
//!
//! ```markdown
//! ---
//! name: code-reviewer
//! description: Reviews code changes for quality and correctness
//! tools: read_file, grep, list_dir
//! ---
//! You are a meticulous code reviewer. Report findings as a numbered list…
//! ```

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use serde_json::Value;
use tokio::sync::mpsc::UnboundedSender;

use super::checkpoint::CheckpointStore;
use super::permissions::{Decision, PermissionGate};
use super::provider::{Provider, ToolCall, ToolDef};
use super::run::{run_agent, AgentEvent, AgentOptions};
use super::compact;

/// The default agent every install has — full tool access.
const BUILTIN_PROMPT: &str = "You are a general-purpose research and implementation agent \
working inside a larger task. Complete the given assignment end to end with the available \
tools, then reply with a concise final report: what you did, what you found, and any files \
you changed. Do not ask questions — decide and act.";

#[derive(Debug, Clone)]
pub struct SubagentDef {
    pub name: String,
    /// Surfaced in tool listings and future GUI agent pickers.
    #[allow(dead_code)]
    pub description: String,
    /// Tool allowlist; `None` = every tool the registry has.
    pub tools: Option<Vec<String>>,
    /// Recorded for forward-compat (a per-agent model selector); the loop
    /// currently runs every agent on the shared provider.
    #[allow(dead_code)]
    pub model: Option<String>,
    /// System prompt (the markdown body).
    pub prompt: String,
    pub scope: &'static str,
}

/// Every agent visible to `cwd`: built-in first, then user scope, then
/// project scope — later definitions shadow earlier ones by name.
pub fn load(cwd: &Path) -> Vec<SubagentDef> {
    let mut out = vec![SubagentDef {
        name: "general-purpose".into(),
        description: "General-purpose agent for researching complex questions, searching code \
            and running multi-step tasks. Use when you are not confident you will find the \
            right match on the first try."
            .into(),
        tools: None,
        model: None,
        prompt: BUILTIN_PROMPT.into(),
        scope: "builtin",
    }];

    let home = std::env::var_os("HOME").map(PathBuf::from);
    for (scope, dir) in [
        ("user", home.as_deref().map(|h| h.join(".xencode").join("agents"))),
        ("project", Some(cwd.join(".xencode").join("agents"))),
    ] {
        let Some(dir) = dir else { continue };
        let Ok(rd) = std::fs::read_dir(&dir) else { continue };
        let mut files: Vec<PathBuf> = rd.flatten().map(|e| e.path()).filter(|p| p.extension().map(|e| e == "md").unwrap_or(false)).collect();
        files.sort();
        for f in files {
            let Ok(text) = std::fs::read_to_string(&f) else { continue };
            let stem = f.file_stem().and_then(|s| s.to_str()).unwrap_or_default().to_string();
            let Some(mut def) = parse_agent_md(&text, &stem) else {
                eprintln!("xencode · agents: {} has no body — skipped", f.display());
                continue;
            };
            def.scope = scope;
            out.retain(|d| d.name != def.name); // shadow by name
            out.push(def);
        }
    }
    out
}

/// Parse one agent markdown file: optional `key: value` frontmatter between
/// `---` fences, the rest is the system prompt. `fallback_name` is the file
/// stem used when no `name:` is given.
pub fn parse_agent_md(text: &str, fallback_name: &str) -> Option<SubagentDef> {
    let text = text.trim_start_matches('\u{feff}');
    let mut meta: Vec<(String, String)> = Vec::new();
    let mut body = text;

    if let Some(rest) = text.strip_prefix("---") {
        if let Some((fm, after)) = rest.split_once("\n---") {
            for line in fm.lines() {
                let Some((k, v)) = line.split_once(':') else { continue };
                meta.push((k.trim().to_lowercase(), v.trim().to_string()));
            }
            body = after;
        }
    }

    let body = body.trim();
    if body.is_empty() {
        return None; // no prompt → not a usable agent
    }
    let get = |k: &str| meta.iter().find(|(key, _)| key == k).map(|(_, v)| v.clone());
    let name = get("name").filter(|s| !s.is_empty()).unwrap_or_else(|| fallback_name.to_string());
    let tools = get("tools").and_then(|raw| {
        let list: Vec<String> = raw
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
        if list.is_empty() { None } else { Some(list) }
    });
    Some(SubagentDef {
        name,
        description: get("description").unwrap_or_default(),
        tools,
        model: get("model").filter(|s| !s.is_empty()),
        prompt: body.to_string(),
        scope: "project",
    })
}

/// The `task` tool every parent registry carries.
pub fn task_tool_def() -> ToolDef {
    ToolDef {
        name: "task".into(),
        description: "Launch a subagent: an independent agent loop with its own scoped prompt \
            and tool allowlist that completes the assignment and returns a final report. Use \
            for parallelizable research or review work; issue several task calls in one turn \
            to run them concurrently."
            .into(),
        parameters: serde_json::json!({
            "type": "object",
            "properties": {
                "agent": {"type": "string", "description": "Subagent name (general-purpose, or one from .xencode/agents/)"},
                "prompt": {"type": "string", "description": "The full assignment for the subagent"}
            },
            "required": ["agent", "prompt"]
        }),
    }
}

/// Human summary for tool cards: `agent — prompt head`.
pub fn describe(args: &Value) -> String {
    let agent = args.get("agent").and_then(Value::as_str).unwrap_or("general-purpose");
    let prompt = args.get("prompt").and_then(Value::as_str).unwrap_or_default();
    let head: String = prompt.chars().take(48).collect();
    let ellipsis = if prompt.chars().count() > 48 { "…" } else { "" };
    format!("{agent} — {head}{ellipsis}")
}

/// Tool allowlist gate for child loops: allowed tools pass (children never
/// re-prompt — the parent consented to the agent as a whole), everything
/// outside the allowlist is hard-denied.
pub struct AllowlistGate {
    allow: Option<HashSet<String>>,
}

impl AllowlistGate {
    pub fn new(allow: Option<&[String]>) -> Self {
        Self { allow: allow.map(|a| a.iter().cloned().collect()) }
    }
}

impl PermissionGate for AllowlistGate {
    fn review(&self, tool: &str, _args: &Value) -> Decision {
        match &self.allow {
            Some(a) if !a.contains(tool) => Decision::Deny,
            _ => Decision::Allow,
        }
    }

    fn denial_message(&self) -> String {
        "This tool is not in the subagent's tool allowlist.".to_string()
    }
}

/// Run one child loop for a `task` tool call. Child events are forwarded to
/// the parent's UI channel wrapped in [`AgentEvent::Subagent`]; the return
/// value is the child's final report (the tool result the model sees).
pub async fn run_child(
    call: &ToolCall,
    provider: std::sync::Arc<dyn Provider>,
    cwd: &Path,
    checkpoints: Option<std::sync::Arc<CheckpointStore>>,
    parent_tx: UnboundedSender<AgentEvent>,
) -> Result<String> {
    let agent = call
        .arguments
        .get("agent")
        .and_then(Value::as_str)
        .unwrap_or("general-purpose");
    let prompt = call
        .arguments
        .get("prompt")
        .and_then(Value::as_str)
        .context("task: missing prompt")?;

    let defs = load(cwd);
    let Some(def) = defs.iter().find(|d| d.name == agent) else {
        let names: Vec<&str> = defs.iter().map(|d| d.name.as_str()).collect();
        bail!("unknown subagent `{agent}` — available: {}", names.join(", "));
    };
    let def = def.clone();

    // Forward child events to the parent UI, wrapped for the card renderer.
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<AgentEvent>();
    let forwarder = {
        let name = def.name.clone();
        let parent_tx = parent_tx.clone();
        tokio::spawn(async move {
            while let Some(ev) = rx.recv().await {
                let _ = parent_tx.send(AgentEvent::Subagent { name: name.clone(), event: Box::new(ev) });
            }
        })
    };

    let opts = AgentOptions {
        max_iterations: 15,
        cwd: cwd.to_path_buf(),
        gate: Box::new(AllowlistGate::new(def.tools.as_deref())),
        // Children are ephemeral: no session, no prompts, no hooks, no
        // nested tasks. Their mutations still hit the checkpoint store.
        session_dir: None,
        history: Vec::new(),
        asker: None,
        compact_chars: Some(compact::DEFAULT_BUDGET_CHARS),
        hooks: None,
        plan_mode: false,
        mcp: None,
        checkpoints,
        system_extra: Some(def.prompt.clone()),
        allow_subagents: false,
    };

    let answer = run_agent(provider, prompt, opts, tx).await;
    drop(parent_tx);
    let _ = forwarder.await; // flush every forwarded child event
    answer
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_frontmatter_and_body() {
        let text = "---\nname: reviewer\ndescription: Reviews diffs\ntools: read_file, grep , list_dir\nmodel: glm-4.6\n---\nYou review code carefully.\nLine two.";
        let def = parse_agent_md(text, "stem").unwrap();
        assert_eq!(def.name, "reviewer");
        assert_eq!(def.description, "Reviews diffs");
        assert_eq!(def.tools.as_ref().unwrap(), &["read_file".to_string(), "grep".to_string(), "list_dir".to_string()]);
        assert_eq!(def.model.as_deref(), Some("glm-4.6"));
        assert!(def.prompt.starts_with("You review code carefully."));
        assert!(def.prompt.ends_with("Line two."));
    }

    #[test]
    fn no_frontmatter_whole_body_is_the_prompt() {
        let def = parse_agent_md("Just be helpful.", "scout").unwrap();
        assert_eq!(def.name, "scout", "file stem is the fallback name");
        assert_eq!(def.prompt, "Just be helpful.");
        assert!(def.tools.is_none());
    }

    #[test]
    fn empty_body_is_rejected() {
        assert!(parse_agent_md("---\nname: x\ndescription: d\n---\n", "x").is_none());
        assert!(parse_agent_md("", "x").is_none());
    }

    #[test]
    fn load_has_builtin_and_shadows_by_name() {
        let dir = std::env::temp_dir().join(format!("xencode-agents-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join(".xencode/agents")).unwrap();
        std::fs::write(
            dir.join(".xencode/agents/reviewer.md"),
            "---\nname: reviewer\ndescription: Project reviewer\n---\nProject review prompt.",
        )
        .unwrap();
        std::fs::write(
            dir.join(".xencode/agents/scout.md"),
            "A scout agent without frontmatter.",
        )
        .unwrap();
        std::fs::write(
            dir.join(".xencode/agents/broken.md"),
            "---\nname: broken\n---\n",
        )
        .unwrap();

        let defs = load(&dir);
        let names: Vec<&str> = defs.iter().map(|d| d.name.as_str()).collect();
        assert!(names.contains(&"general-purpose"), "builtin present");
        assert!(names.contains(&"reviewer"));
        assert!(names.contains(&"scout"));
        assert!(!names.contains(&"broken"), "body-less file skipped");

        let scout = defs.iter().find(|d| d.name == "scout").unwrap();
        assert_eq!(scout.scope, "project");

        // A project `general-purpose.md` would shadow the builtin.
        std::fs::write(
            dir.join(".xencode/agents/general-purpose.md"),
            "---\nname: general-purpose\n---\nCustom generalist.",
        )
        .unwrap();
        let defs = load(&dir);
        let gp = defs.iter().filter(|d| d.name == "general-purpose").collect::<Vec<_>>();
        assert_eq!(gp.len(), 1, "shadowed, not duplicated");
        assert_eq!(gp[0].prompt, "Custom generalist.");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn allowlist_gate_allows_only_listed_tools() {
        let gate = AllowlistGate::new(Some(&["read_file".into(), "grep".into()]));
        assert_eq!(gate.review("read_file", &serde_json::json!({})), Decision::Allow);
        assert_eq!(gate.review("write_file", &serde_json::json!({})), Decision::Deny);
        assert!(gate.denial_message().contains("allowlist"));

        let open = AllowlistGate::new(None);
        assert_eq!(open.review("bash", &serde_json::json!({})), Decision::Allow);
    }

    #[test]
    fn describe_trims_long_prompts() {
        let d = describe(&serde_json::json!({"agent": "scout", "prompt": "find every use of unsafe"}));
        assert_eq!(d, "scout — find every use of unsafe");
        let long = "x".repeat(80);
        let d = describe(&serde_json::json!({"agent": "scout", "prompt": long}));
        assert!(d.chars().count() < 80 && d.ends_with('…'));
    }
}
