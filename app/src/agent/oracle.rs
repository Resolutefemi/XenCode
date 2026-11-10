//! Oracle (Phase 5, harness port #13 — from Amp): a stronger-model
//! second-opinion agent, available as a tool to the main loop and as a
//! standalone CLI (`xencode --oracle "question"`).
//!
//! The oracle is a full agent loop of its own — read-only tools only
//! (`read_file`, `grep`, `list_dir`), no session persistence, no hooks, no
//! nested subagents — running on a *different model* than the main loop
//! (resolution order: `XENCODE_ORACLE_MODEL` env, `[oracle] model` in
//! `xen.toml`, then the main provider's model). Its final answer becomes the
//! `oracle` tool result the parent model sees.
//!
//! Events stream to the UI wrapped as a subagent card named `oracle`, so the
//! chat pane needs zero new rendering paths.

use std::path::Path;
use std::sync::Arc;

use anyhow::{Context, Result};
use serde_json::{json, Value};
use tokio::sync::mpsc::UnboundedSender;

use super::compact;
use super::provider::{Provider, ToolDef};
use super::run::{run_agent, AgentEvent, AgentOptions};
use super::subagent::AllowlistGate;

/// The read-only toolkit the oracle may use to investigate the codebase.
pub const ORACLE_TOOLS: &[&str] = &["read_file", "grep", "list_dir"];

const ORACLE_SYSTEM: &str = "You are Oracle, a senior engineer providing a decisive second \
opinion to a working coding agent. Investigate the codebase with the read-only tools you \
have, then answer the question directly: state your recommendation, the evidence for it \
(files and lines), the risks, and what you would do differently. No hedging, no preamble — \
the reader wants a verdict they can act on.";

/// Tool definition registered in the parent loop when an oracle is set up.
pub fn tool_def() -> ToolDef {
    ToolDef {
        name: "oracle".into(),
        description: "Consult a stronger second-opinion model (\"Oracle\") on a hard question: \
            architecture choices, tricky bugs, review of a risky change, or a sanity check of \
            your current plan. Oracle can read the codebase itself. Pass the full question plus \
            any context the reviewer needs; its verdict comes back as the tool result."
            .into(),
        parameters: json!({
            "type": "object",
            "properties": {
                "question": {"type": "string", "description": "The specific question to answer"},
                "context": {"type": "string", "description": "Optional context: what you tried, the current approach, constraints"}
            },
            "required": ["question"]
        }),
    }
}

/// Human summary for tool cards (used by the loop's oracle tool rows).
pub fn describe(args: &Value) -> String {
    let q = args.get("question").and_then(Value::as_str).unwrap_or("consultation");
    let head: String = q.chars().take(48).collect();
    let ellipsis = if q.chars().count() > 48 { "…" } else { "" };
    format!("oracle — {head}{ellipsis}")
}

/// Which model the oracle runs on (resolution order documented at the top).
pub fn model_name(cfg_model: Option<&str>, main_model: &str) -> String {
    std::env::var("XENCODE_ORACLE_MODEL")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| cfg_model.map(str::to_string))
        .unwrap_or_else(|| main_model.to_string())
}

/// A configured oracle: the (usually stronger) provider it runs on, plus the
/// model name for display/telemetry.
pub struct OracleSetup {
    pub provider: Arc<dyn Provider>,
    /// Model id for display/telemetry (the provider carries the same model).
    #[allow(dead_code)]
    pub model: String,
}

/// Run one oracle consultation as a nested agent loop. Events are forwarded
/// to the parent UI wrapped as [`AgentEvent::Subagent`] (name `oracle`); the
/// return value is the oracle's final verdict text.
pub async fn consult(
    setup: &OracleSetup,
    args: &Value,
    cwd: &Path,
    parent_tx: UnboundedSender<AgentEvent>,
) -> Result<String> {
    let question = args
        .get("question")
        .and_then(Value::as_str)
        .context("oracle: missing question")?;
    let context = args.get("context").and_then(Value::as_str).unwrap_or_default();

    let prompt = if context.trim().is_empty() {
        question.to_string()
    } else {
        format!("{question}\n\n## Context from the working agent\n{context}")
    };

    // Child events surface under the oracle card.
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<AgentEvent>();
    let forwarder = {
        let parent_tx = parent_tx.clone();
        tokio::spawn(async move {
            while let Some(ev) = rx.recv().await {
                let _ = parent_tx.send(AgentEvent::Subagent { name: "oracle".into(), event: Box::new(ev) });
            }
        })
    };

    let opts = AgentOptions {
        max_iterations: 8,
        cwd: cwd.to_path_buf(),
        // Read-only allowlist: oracle investigates, never mutates.
        gate: Box::new(AllowlistGate::new(Some(
            &ORACLE_TOOLS.iter().map(|s| s.to_string()).collect::<Vec<_>>(),
        ))),
        session_dir: None,
        history: Vec::new(),
        asker: None,
        compact_chars: Some(compact::DEFAULT_BUDGET_CHARS),
        hooks: None,
        plan_mode: false,
        mcp: None,
        checkpoints: None,
        system_extra: Some(ORACLE_SYSTEM.to_string()),
        allow_subagents: false,
        repo_map: None,
        diagnostics: None,
        telemetry: None,
        oracle: None,
    };

    let answer = run_agent(setup.provider.clone(), &prompt, opts, tx).await;
    drop(parent_tx);
    let _ = forwarder.await;
    answer
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::permissions::{Decision, PermissionGate};
    use crate::agent::provider::{Message, MockProvider, Role, ToolCall};
    use serde_json::json;
    use std::path::PathBuf;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-oracle-{}-{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn tool_def_shape() {
        let def = tool_def();
        assert_eq!(def.name, "oracle");
        assert!(def.description.contains("second-opinion"));
        assert!(def.parameters["required"].as_array().unwrap().len() == 1);
    }

    #[test]
    fn describe_trims() {
        assert_eq!(describe(&json!({"question": "is this sound?"})), "oracle — is this sound?");
        let long = "x".repeat(60);
        assert!(describe(&json!({"question": long})).ends_with('…'));
    }

    #[test]
    fn model_resolution_order() {
        // Without env: config, then main model fallback.
        let m = model_name(Some("glm-4.6-plus"), "glm-4.5");
        assert_eq!(m, "glm-4.6-plus");
        let m = model_name(None, "glm-4.5");
        assert_eq!(m, "glm-4.5");
        // Env wins (set + remove to keep the process clean).
        std::env::set_var("XENCODE_ORACLE_MODEL", "glm-ultra");
        let m = model_name(Some("glm-4.6-plus"), "glm-4.5");
        assert_eq!(m, "glm-ultra");
        std::env::remove_var("XENCODE_ORACLE_MODEL");
    }

    #[test]
    fn oracle_gate_is_read_only() {
        let gate = AllowlistGate::new(Some(
            &ORACLE_TOOLS.iter().map(|s| s.to_string()).collect::<Vec<_>>(),
        ));
        assert_eq!(gate.review("read_file", &json!({})), Decision::Allow);
        assert_eq!(gate.review("grep", &json!({})), Decision::Allow);
        assert_eq!(gate.review("write_file", &json!({})), Decision::Deny);
        assert_eq!(gate.review("bash", &json!({})), Decision::Deny);
    }

    #[tokio::test]
    async fn consult_returns_the_verdict_and_streams_events() {
        let dir = tmp("consult");
        std::fs::write(dir.join("notes.md"), "the answer is 42").unwrap();
        // Oracle loop: read the file, then answer.
        let provider = MockProvider::new(vec![
            Message {
                role: Role::Assistant,
                content: String::new(),
                tool_calls: vec![ToolCall {
                    id: "o1".into(),
                    name: "read_file".into(),
                    arguments: json!({"path": "notes.md"}),
                }],
                tool_call_id: None,
                usage: None,
            },
            Message {
                role: Role::Assistant,
                content: "Verdict: the answer is 42.".into(),
                tool_calls: vec![],
                tool_call_id: None,
                usage: None,
            },
        ]);
        let setup = OracleSetup { provider: Arc::new(provider), model: "mock".into() };

        let (parent_tx, mut parent_rx) = tokio::sync::mpsc::unbounded_channel::<AgentEvent>();
        let verdict = consult(
            &setup,
            &json!({"question": "What is the answer?", "context": "I am stuck."}),
            &dir,
            parent_tx,
        )
        .await
        .unwrap();
        assert_eq!(verdict, "Verdict: the answer is 42.");

        // Parent saw the events wrapped as a subagent card named `oracle`.
        let mut saw_card = false;
        while let Ok(ev) = parent_rx.try_recv() {
            if let AgentEvent::Subagent { name, .. } = ev {
                assert_eq!(name, "oracle");
                saw_card = true;
            }
        }
        assert!(saw_card, "oracle events must be wrapped as subagent cards");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn missing_question_is_an_error() {
        let dir = tmp("noq");
        let provider = MockProvider::new(vec![]);
        let setup = OracleSetup { provider: Arc::new(provider), model: "mock".into() };
        let (parent_tx, _prx) = tokio::sync::mpsc::unbounded_channel::<AgentEvent>();
        let res = consult(&setup, &json!({}), &dir, parent_tx).await;
        assert!(res.is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
