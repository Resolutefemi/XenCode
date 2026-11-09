//! The agent loop: prompt → model → tools → model → … → final answer.

use std::path::{Path, PathBuf};
use std::time::Instant;

use anyhow::{bail, Result};
use serde_json::Value;
use tokio::sync::mpsc::UnboundedSender;

use super::permissions::{AllowAll, PermissionGate};
use super::provider::{Message, Provider, ToolDef};
use super::{session, tools};

/// Progress events for a UI or CLI printer.
pub enum AgentEvent {
    Iteration(usize),
    Text(String),
    ToolStart { name: String, summary: String },
    ToolEnd { name: String, ok: bool, ms: u128 },
    Done,
}

pub struct AgentOptions {
    pub max_iterations: usize,
    pub cwd: PathBuf,
    pub gate: Box<dyn PermissionGate>,
    pub session_dir: Option<PathBuf>,
}

impl Default for AgentOptions {
    fn default() -> Self {
        Self {
            max_iterations: 25,
            cwd: std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
            gate: Box::new(AllowAll),
            session_dir: None,
        }
    }
}

/// Drive the agent to completion. Returns the assistant's final text answer.
pub async fn run_agent<P: Provider>(
    provider: &P,
    prompt: &str,
    opts: AgentOptions,
    tx: UnboundedSender<AgentEvent>,
) -> Result<String> {
    let tool_defs: Vec<ToolDef> = tools::tool_defs();
    let ctx = tools::ToolCtx { cwd: opts.cwd.clone() };

    let mut messages = vec![Message::system(system_prompt(&opts.cwd)), Message::user(prompt)];

    for iteration in 1..=opts.max_iterations {
        let _ = tx.send(AgentEvent::Iteration(iteration));
        let assistant = provider.complete(&messages, &tool_defs, None).await?;

        if assistant.tool_calls.is_empty() {
            if let Some(dir) = &opts.session_dir {
                messages.push(assistant.clone());
                let _ = session::Session::from_messages(&messages).save(dir);
            }
            let _ = tx.send(AgentEvent::Done);
            return Ok(assistant.content);
        }

        let calls = assistant.tool_calls.clone();
        messages.push(assistant);

        for call in calls {
            if !opts.gate.allow(&call.name, &call.arguments) {
                let _ = tx.send(AgentEvent::ToolStart {
                    name: call.name.clone(),
                    summary: "(denied by permission gate)".into(),
                });
                let _ = tx.send(AgentEvent::ToolEnd { name: call.name.clone(), ok: false, ms: 0 });
                messages.push(Message::tool_result(&call.id, "Permission denied by the user."));
                continue;
            }

            let _ = tx.send(AgentEvent::ToolStart {
                name: call.name.clone(),
                summary: summarize(&call.arguments),
            });
            let t0 = Instant::now();
            let result = tools::execute(&call.name, &call.arguments, &ctx).await;
            let ms = t0.elapsed().as_millis();
            let ok = result.is_ok();
            let text = result.unwrap_or_else(|e| format!("Error: {e:#}"));
            let _ = tx.send(AgentEvent::ToolEnd { name: call.name.clone(), ok, ms });
            messages.push(Message::tool_result(&call.id, text));
        }
    }

    bail!("agent stopped: reached max iterations ({})", opts.max_iterations)
}

fn system_prompt(cwd: &Path) -> String {
    format!(
        "You are XenCode, a coding agent working inside the user's project at {}.\n\n\
         Rules:\n\
         - Use the provided tools to read, search, edit and run code. Never guess file contents — read first.\n\
         - Prefer edit_file with a minimal old/new pair over rewriting whole files.\n\
         - Verify your changes: run builds or tests with bash when you can.\n\
         - Keep answers short and concrete. When you are done, reply with the final summary only — no tool calls.",
        cwd.display()
    )
}

fn summarize(args: &Value) -> String {
    let value = ["path", "command", "pattern", "todos"]
        .iter()
        .find_map(|k| args.get(*k))
        .cloned()
        .unwrap_or_default();
    let text = value.to_string().trim_matches('"').to_string();
    if text.chars().count() > 60 {
        text.chars().take(60).collect()
    } else {
        text
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::permissions::ReadOnly;
    use crate::agent::provider::{MockProvider, Role, ToolCall};
    use serde_json::json;
    use tokio::sync::mpsc;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-test-{}-{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn tool_turn(id: &str, name: &str, args: Value) -> Message {
        Message {
            role: Role::Assistant,
            content: String::new(),
            tool_calls: vec![ToolCall { id: id.into(), name: name.into(), arguments: args }],
            tool_call_id: None,
        }
    }

    fn final_turn(text: &str) -> Message {
        Message {
            role: Role::Assistant,
            content: text.into(),
            tool_calls: vec![],
            tool_call_id: None,
        }
    }

    #[tokio::test]
    async fn executes_tools_until_final_answer() {
        let dir = tmp("loop");
        std::fs::write(dir.join("hello.txt"), "hello from xencode").unwrap();

        let mock = MockProvider::new(vec![
            tool_turn("t1", "read_file", json!({"path": "hello.txt"})),
            final_turn("The file says hello from xencode"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            session_dir: Some(dir.join(".xencode")),
            ..Default::default()
        };

        let answer = run_agent(&mock, "what does hello.txt say", opts, tx).await.unwrap();
        assert!(answer.contains("hello from xencode"));

        let mut saw_tool_end = false;
        while let Ok(ev) = rx.try_recv() {
            if let AgentEvent::ToolEnd { ok: true, .. } = ev {
                saw_tool_end = true;
            }
        }
        assert!(saw_tool_end, "expected a successful ToolEnd event");

        let sessions = session::Session::list(&dir.join(".xencode")).unwrap();
        assert_eq!(sessions.len(), 1, "session should be saved");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn permission_gate_blocks_writes() {
        let dir = tmp("gate");
        let mock = MockProvider::new(vec![
            tool_turn("t1", "write_file", json!({"path": "evil.txt", "content": "nope"})),
            final_turn("the write was blocked"),
        ]);
        let (tx, _rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            gate: Box::new(ReadOnly),
            ..Default::default()
        };

        let answer = run_agent(&mock, "write evil.txt", opts, tx).await.unwrap();
        assert!(answer.contains("blocked"));
        assert!(!dir.join("evil.txt").exists(), "denied tool must not run");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn tools_read_edit_grep() {
        let dir = tmp("tools");
        std::fs::write(dir.join("a.rs"), "fn alpha() {}\nfn gamma() {}\n").unwrap();
        let ctx = tools::ToolCtx { cwd: dir.clone() };

        let found = tools::execute("grep", &json!({"pattern": "alpha"}), &ctx).await.unwrap();
        assert!(found.contains("a.rs"), "grep should find the match");

        let edited = tools::execute(
            "edit_file",
            &json!({"path": "a.rs", "old": "alpha", "new": "beta"}),
            &ctx,
        )
        .await
        .unwrap();
        assert!(edited.contains("1 occurrence"));

        let content = std::fs::read_to_string(dir.join("a.rs")).unwrap();
        assert!(content.contains("beta") && !content.contains("alpha"));

        let listing = tools::execute("list_dir", &json!({}), &ctx).await.unwrap();
        assert!(listing.contains("a.rs"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
