//! The agent loop: prompt → model → tools → model → … → final answer.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;

use anyhow::{bail, Result};
use serde_json::Value;
use tokio::sync::mpsc::UnboundedSender;

use super::compact;
use super::permissions::{Decision, PermissionGate, Request, Response};
use super::provider::{Message, Provider, Role, ToolDef};
use super::{session, tools};

/// Progress events for a UI or CLI printer.
#[derive(Clone)]
pub enum AgentEvent {
    Iteration(usize),
    Text(String),
    ToolStart { name: String, summary: String },
    ToolEnd { name: String, ok: bool, ms: u128 },
    /// A consent prompt was resolved for `tool` (`allowed` = the outcome).
    Ask { tool: String, detail: String, allowed: bool },
    /// Older turns were folded into a summary (chars before → after).
    Compacted { before: usize, after: usize },
    /// The final message list — what a session store / GUI transcript renders.
    Transcript(Arc<Vec<Message>>),
    Done,
}

/// Callback that resolves a permission prompt (stdin in the CLI, a GUI
/// consent card in the chat pane, a scripted answer in tests).
pub trait Asker: Send {
    fn ask(&mut self, req: &Request) -> Response;
}

/// Adapter so closures can act as askers.
pub struct AskFn<F: FnMut(&Request) -> Response + Send>(pub F);

impl<F: FnMut(&Request) -> Response + Send> Asker for AskFn<F> {
    fn ask(&mut self, req: &Request) -> Response {
        (self.0)(req)
    }
}

pub struct AgentOptions {
    pub max_iterations: usize,
    pub cwd: PathBuf,
    pub gate: Box<dyn PermissionGate>,
    pub session_dir: Option<PathBuf>,
    /// Prior conversation to seed the loop (resume/fork). Must already start
    /// with the system message; an empty vec starts a fresh session.
    pub history: Vec<Message>,
    /// Resolver for `Decision::Ask`. Without one, asks fail closed (deny).
    pub asker: Option<Box<dyn Asker>>,
    /// Transcript budget in chars; older turns compact when exceeded.
    /// `None` disables compaction.
    pub compact_chars: Option<usize>,
}

impl Default for AgentOptions {
    fn default() -> Self {
        Self {
            max_iterations: 25,
            cwd: std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
            gate: Box::new(super::permissions::AllowAll),
            session_dir: None,
            history: Vec::new(),
            asker: None,
            compact_chars: Some(compact::DEFAULT_BUDGET_CHARS),
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
    let AgentOptions {
        max_iterations,
        cwd,
        gate,
        ref session_dir,
        history,
        mut asker,
        compact_chars,
    } = opts;
    let tool_defs: Vec<ToolDef> = tools::tool_defs();
    let ctx = tools::ToolCtx { cwd: cwd.clone() };

    // Seed the loop: fresh sessions get system+prompt; resumes append to history.
    let mut messages = if history.is_empty() {
        vec![Message::system(system_prompt(&cwd)), Message::user(prompt)]
    } else {
        let mut h = history;
        if !h.iter().any(|m| m.role == Role::System) {
            h.insert(0, Message::system(system_prompt(&cwd)));
        }
        h.push(Message::user(prompt));
        h
    };

    for iteration in 1..=max_iterations {
        // Compact when the transcript outgrows the budget.
        if let Some(budget) = compact_chars {
            let size = compact::estimate_chars(&messages);
            if size > budget && messages.len() >= 6 {
                match compact::compact(provider, &messages, budget / 2).await {
                    Ok(next) if compact::estimate_chars(&next) < size => {
                        let after = compact::estimate_chars(&next);
                        messages = next;
                        let _ = tx.send(AgentEvent::Compacted { before: size, after });
                    }
                    _ => {} // summarization failed or did not shrink; keep going
                }
            }
        }

        let _ = tx.send(AgentEvent::Iteration(iteration));

        // Forward the provider's streaming deltas to the UI as Text events.
        let (delta_tx, mut delta_rx) = tokio::sync::mpsc::unbounded_channel::<String>();
        let pump = {
            let tx = tx.clone();
            tokio::spawn(async move {
                while let Some(s) = delta_rx.recv().await {
                    let _ = tx.send(AgentEvent::Text(s));
                }
            })
        };
        let assistant = provider.complete(&messages, &tool_defs, Some(&delta_tx)).await?;
        drop(delta_tx);
        let _ = pump.await;

        if assistant.tool_calls.is_empty() {
            if let Some(dir) = session_dir {
                messages.push(assistant.clone());
                let _ = session::Session::from_messages(&messages).save(dir);
            }
            let _ = tx.send(AgentEvent::Transcript(Arc::new(messages.clone())));
            let _ = tx.send(AgentEvent::Done);
            return Ok(assistant.content);
        }

        let calls = assistant.tool_calls.clone();
        messages.push(assistant);

        for call in calls {
            let detail = summarize(&call.arguments);
            let mut decision = gate.review(&call.name, &call.arguments);
            let mut prompted = false;
            if decision == Decision::Ask {
                prompted = true;
                decision = match asker.as_mut() {
                    Some(a) => match a.ask(&Request { tool: call.name.clone(), detail: detail.clone() }) {
                        Response::Allow => Decision::Allow,
                        Response::AllowAlways => {
                            gate.allow_always(&call.name);
                            Decision::Allow
                        }
                        Response::Deny => Decision::Deny,
                    },
                    // No way to ask a human — fail closed.
                    None => Decision::Deny,
                };
            }
            // Only real prompts (and their outcomes) reach the UI as cards;
            // policy allow/deny never surfaces a consent card.
            if prompted {
                let _ = tx.send(AgentEvent::Ask {
                    tool: call.name.clone(),
                    detail: detail.clone(),
                    allowed: decision == Decision::Allow,
                });
            }

            if decision == Decision::Deny {
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
                summary: detail,
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

    bail!("agent stopped: reached max iterations ({})", max_iterations)
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

pub(crate) fn summarize(args: &Value) -> String {
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
    use crate::agent::permissions::{AskGate, ReadOnly};
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
    async fn ask_gate_prompts_and_remember_always_allow() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        use std::sync::Arc as StdArc;

        let dir = tmp("ask");
        let mock = MockProvider::new(vec![
            tool_turn("t1", "write_file", json!({"path": "denied.txt", "content": "no"})),
            tool_turn("t2", "write_file", json!({"path": "allowed.txt", "content": "yes"})),
            final_turn("done"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();

        let asks = StdArc::new(AtomicUsize::new(0));
        let counter = asks.clone();
        let asker = AskFn(move |_req: &Request| {
            let n = counter.fetch_add(1, Ordering::SeqCst);
            if n == 0 {
                Response::Deny
            } else {
                Response::AllowAlways
            }
        });
        let opts = AgentOptions {
            cwd: dir.clone(),
            gate: Box::new(AskGate::new()),
            asker: Some(Box::new(asker)),
            ..Default::default()
        };

        let answer = run_agent(&mock, "write two files", opts, tx).await.unwrap();
        assert_eq!(answer, "done");
        assert!(!dir.join("denied.txt").exists(), "denied prompt must not run");
        assert!(dir.join("allowed.txt").exists(), "approved prompt must run");
        assert_eq!(asks.load(Ordering::SeqCst), 2, "asked twice (deny, then always)");

        let mut saw_ask = false;
        while let Ok(ev) = rx.try_recv() {
            if let AgentEvent::Ask { allowed, .. } = ev {
                saw_ask = true;
                let _ = allowed;
            }
        }
        assert!(saw_ask, "Ask events must reach the UI channel");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn ask_without_asker_fails_closed() {
        let dir = tmp("closed");
        let mock = MockProvider::new(vec![
            tool_turn("t1", "bash", json!({"command": "echo nope"})),
            final_turn("could not run"),
        ]);
        let (tx, _rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            gate: Box::new(AskGate::new()),
            asker: None, // cannot ask → deny
            ..Default::default()
        };
        run_agent(&mock, "run echo", opts, tx).await.unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn resume_seeds_history_and_saves_extended_session() {
        let dir = tmp("resume");
        let session_dir = dir.join(".xencode");

        let mock = MockProvider::new(vec![final_turn("continued fine")]);
        let (tx, _rx) = mpsc::unbounded_channel();
        let history = vec![
            Message::system("system prompt"),
            Message::user("earlier question"),
            Message {
                role: Role::Assistant,
                content: "earlier answer".into(),
                tool_calls: vec![],
                tool_call_id: None,
            },
        ];
        let opts = AgentOptions {
            cwd: dir.clone(),
            session_dir: Some(session_dir.clone()),
            history,
            ..Default::default()
        };
        let answer = run_agent(&mock, "now continue", opts, tx).await.unwrap();
        assert_eq!(answer, "continued fine");

        let id = session::Session::latest(&session_dir).unwrap().unwrap();
        let s = session::Session::load(&session_dir, &id).unwrap();
        assert_eq!(s.messages[1].content, "earlier question", "history preserved");
        assert_eq!(s.messages[3].content, "now continue", "new prompt appended");
        assert_eq!(s.messages[4].content, "continued fine");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn compaction_triggers_when_transcript_outgrows_budget() {
        let dir = tmp("compact");
        let big = "y".repeat(2_000);

        // Order matters: the compaction summarizer consumes the first turn,
        // the loop's first model call consumes the second.
        let mock = MockProvider::new(vec![
            Message {
                role: Role::Assistant,
                content: "User asked about big.txt; it was read; task continues.".into(),
                tool_calls: vec![],
                tool_call_id: None,
            },
            final_turn("all done"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let history = vec![
            Message::system("system prompt"),
            Message::user(format!("task {big}")),
            Message::user("second turn {".to_string() + &big + "}"),
            Message::user("third turn {".to_string() + &big + "}"),
            Message::user("fourth turn {".to_string() + &big + "}"),
        ];
        let opts = AgentOptions {
            cwd: dir,
            session_dir: None,
            history,
            compact_chars: Some(1_000), // force the trigger
            ..Default::default()
        };
        let answer = run_agent(&mock, "finish it", opts, tx).await.unwrap();
        assert_eq!(answer, "all done");

        let mut compacted = false;
        let mut summary_in_transcript = false;
        while let Ok(ev) = rx.try_recv() {
            match ev {
                AgentEvent::Compacted { before, after } => {
                    compacted = true;
                    assert!(after < before);
                }
                AgentEvent::Transcript(msgs) => {
                    summary_in_transcript = msgs
                        .iter()
                        .any(|m| m.content.starts_with("[Context summary of earlier turns"));
                }
                _ => {}
            }
        }
        assert!(compacted, "Compacted event must fire");
        assert!(summary_in_transcript, "summary message must be in the final transcript");
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
