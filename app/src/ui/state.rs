//! UI state — the live conversation the composer drives through the agent
//! loop, rendered by the same native renderer as the static pages.
//!
//! Two sources feed a [`ChatPage`]:
//! - [`Collector`] folds the [`AgentEvent`] stream from a live run (the
//!   composer → loop wiring); this is exactly what the future windowed GUI
//!   consumes turn by turn.
//! - [`ChatPage::from_conversation`] renders a saved session transcript
//!   (`--render-session`, sidebar history).

use std::collections::HashMap;

use serde_json::Value;

use crate::agent::provider::{Message, Role};
use crate::agent::run::{summarize, AgentEvent};

/// One rendered block in the conversation pane.
#[derive(Debug, Clone)]
pub enum Entry {
    /// Right-aligned user bubble.
    User { text: String },
    /// Assistant prose (streamed deltas accumulate here).
    Assistant { text: String },
    /// Tool execution row (`ok` = finished successfully).
    Tool { name: String, summary: String, ok: bool },
    /// Consent card — `allowed` is `None` while the prompt is pending.
    Permission { tool: String, detail: String, allowed: Option<bool> },
    /// Subagent card (Phase 4) — `status` is free-form (running / the tool
    /// it is on / done / failed), `ok` flips when the child loop returns.
    Subagent { name: String, status: String, ok: bool },
    /// Checkpoint notice (Phase 4) — a shadow-git snapshot taken before a
    /// mutating tool ran.
    Checkpoint { tool: String, detail: String },
    /// Context compaction notice.
    Compaction { note: String },
}

/// State of the chat page: entries above the composer.
#[derive(Debug, Default)]
pub struct ChatPage {
    pub title: String,
    pub entries: Vec<Entry>,
    pub composer_text: Option<String>,
    pub streaming: bool,
    pub iteration: usize,
}

/// Lucide icon used in the tool row for each tool name.
pub fn tool_icon(name: &str) -> &'static str {
    if name.starts_with("mcp__") {
        return "cable"; // Phase 4: MCP server tool
    }
    match name {
        "read_file" => "file-text",
        "write_file" => "pencil-ruler",
        "edit_file" => "wrench",
        "bash" => "square-terminal",
        "grep" => "search",
        "list_dir" => "folder-open",
        "todo_write" => "list-filter",
        "task" => "bot", // Phase 4: subagent dispatch
        _ => "bot",
    }
}

impl ChatPage {
    /// Render a saved transcript (session JSON messages) into page state.
    pub fn from_conversation(messages: &[Message]) -> Self {
        // tool_call_id → success (tool results carry no tool name, so pair by id)
        let mut outcome: HashMap<&str, bool> = HashMap::new();
        for m in messages {
            if let (Role::Tool, Some(id)) = (&m.role, &m.tool_call_id) {
                let failed =
                    m.content.starts_with("Error:") || m.content.contains("Permission denied");
                outcome.insert(id.as_str(), !failed);
            }
        }

        let mut entries = Vec::new();
        for m in messages {
            match m.role {
                Role::System => {}
                Role::User => {
                    if m.content.starts_with("[Context summary of earlier turns") {
                        entries.push(Entry::Compaction {
                            note: "earlier turns compacted into this summary".into(),
                        });
                    } else {
                        entries.push(Entry::User { text: m.content.clone() });
                    }
                }
                Role::Assistant => {
                    for tc in &m.tool_calls {
                        let ok = outcome.get(tc.id.as_str()).copied().unwrap_or(true);
                        if tc.name == "task" {
                            // Phase 4: subagent dispatches render as agent cards.
                            let agent = tc
                                .arguments
                                .get("agent")
                                .and_then(Value::as_str)
                                .unwrap_or("general-purpose");
                            entries.push(Entry::Subagent {
                                name: agent.to_string(),
                                status: "report".into(),
                                ok,
                            });
                        } else {
                            entries.push(Entry::Tool {
                                name: tc.name.clone(),
                                summary: summarize(&tc.arguments),
                                ok,
                            });
                        }
                    }
                    if !m.content.is_empty() {
                        entries.push(Entry::Assistant { text: m.content.clone() });
                    }
                }
                Role::Tool => {}
            }
        }

        Self {
            title: "Restored session".into(),
            entries,
            composer_text: None,
            streaming: false,
            iteration: 0,
        }
    }
}

/// Folds a live [`AgentEvent`] stream into a [`ChatPage`]. The composer's
/// text becomes the user entry; the loop's events append below it.
pub struct Collector {
    pub page: ChatPage,
    streaming_idx: Option<usize>,
}

impl Collector {
    pub fn new(prompt: &str, title: &str) -> Self {
        Self {
            page: ChatPage {
                title: title.into(),
                entries: vec![Entry::User { text: prompt.to_string() }],
                composer_text: Some(prompt.to_string()),
                streaming: false,
                iteration: 0,
            },
            streaming_idx: None,
        }
    }

    pub fn on(&mut self, ev: &AgentEvent) {
        match ev {
            AgentEvent::Iteration(n) => self.page.iteration = *n,
            AgentEvent::Text(s) => {
                self.page.streaming = true;
                match self.streaming_idx {
                    Some(i) => {
                        if let Some(Entry::Assistant { text }) = self.page.entries.get_mut(i) {
                            text.push_str(s);
                        }
                    }
                    None => {
                        self.page.entries.push(Entry::Assistant { text: s.clone() });
                        self.streaming_idx = Some(self.page.entries.len() - 1);
                    }
                }
            }
            AgentEvent::ToolStart { name, summary } => {
                self.streaming_idx = None;
                if name == "task" {
                    // Phase 4: one agent card per task call; summary is
                    // `agent — prompt head` (subagent::describe).
                    let agent = summary.split(" — ").next().unwrap_or("general-purpose").to_string();
                    self.page.entries.push(Entry::Subagent {
                        name: agent,
                        status: "running".into(),
                        ok: false,
                    });
                } else {
                    self.page.entries.push(Entry::Tool {
                        name: name.clone(),
                        summary: summary.clone(),
                        ok: false,
                    });
                }
            }
            AgentEvent::ToolEnd { name, ok, .. } => {
                if name == "task" {
                    // End events arrive in call order — close the oldest
                    // pending agent card first.
                    for e in self.page.entries.iter_mut() {
                        if let Entry::Subagent { status, ok: o, .. } = e {
                            if !*o {
                                *o = *ok;
                                *status = if *ok { "done".into() } else { "failed".into() };
                                break;
                            }
                        }
                    }
                    return;
                }
                for e in self.page.entries.iter_mut().rev() {
                    if let Entry::Tool { name: n, ok: o, .. } = e {
                        if n == name && !*o {
                            *o = *ok;
                            break;
                        }
                    }
                }
            }
            AgentEvent::Subagent { name, event } => {
                // Child-loop activity updates the matching pending card's
                // status line; the card itself stays one row.
                let status = match &**event {
                    AgentEvent::ToolStart { name: tool, .. } => format!("running · {tool}"),
                    AgentEvent::ToolEnd { ok: true, .. } => "working".to_string(),
                    AgentEvent::Iteration(n) => format!("iteration {n}"),
                    _ => return,
                };
                for e in self.page.entries.iter_mut().rev() {
                    if let Entry::Subagent { name: n, status: s, ok } = e {
                        if n == name && !*ok {
                            *s = status;
                            break;
                        }
                    }
                }
            }
            AgentEvent::Checkpoint { tool, detail } => {
                self.streaming_idx = None;
                self.page.entries.push(Entry::Checkpoint {
                    tool: tool.clone(),
                    detail: detail.clone(),
                });
            }
            AgentEvent::Ask { tool, detail, allowed } => {
                self.streaming_idx = None;
                self.page.entries.push(Entry::Permission {
                    tool: tool.clone(),
                    detail: detail.clone(),
                    allowed: Some(*allowed),
                });
            }
            // Hook runs render as tool rows (`hook:PreToolUse guard.ts`),
            // hook console output stays a CLI-only detail.
            AgentEvent::HookRun { kind, script, ok } => {
                self.streaming_idx = None;
                self.page.entries.push(Entry::Tool {
                    name: format!("hook:{kind}"),
                    summary: script.clone(),
                    ok: *ok,
                });
            }
            AgentEvent::HookLog(_) => {}
            AgentEvent::Compacted { before, after } => {
                self.page.entries.push(Entry::Compaction {
                    note: format!("context compacted: {before} → {after} chars"),
                });
            }
            AgentEvent::Transcript(_) | AgentEvent::Done => {
                self.page.streaming = false;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn collector_builds_a_live_transcript() {
        let mut col = Collector::new("fix the build", "Agent run");
        col.on(&AgentEvent::Iteration(1));
        col.on(&AgentEvent::Text("Reading the manifest…".into()));
        col.on(&AgentEvent::Ask {
            tool: "bash".into(),
            detail: "cargo build".into(),
            allowed: true,
        });
        col.on(&AgentEvent::ToolStart { name: "bash".into(), summary: "cargo build".into() });
        col.on(&AgentEvent::ToolEnd { name: "bash".into(), ok: true, ms: 12 });
        col.on(&AgentEvent::HookRun { kind: "PreToolUse".into(), script: "guard.ts".into(), ok: true });
        col.on(&AgentEvent::HookLog("hook console noise".into()));
        col.on(&AgentEvent::Text("Build is green.".into()));
        col.on(&AgentEvent::Done);

        let p = &col.page;
        assert_eq!(p.iteration, 1);
        assert!(!p.streaming, "Done clears the streaming flag");
        assert_eq!(p.composer_text.as_deref(), Some("fix the build"));

        assert!(matches!(&p.entries[0], Entry::User { text } if text == "fix the build"));
        assert!(matches!(&p.entries[1], Entry::Assistant { text } if text.contains("Reading")));
        assert!(matches!(&p.entries[2], Entry::Permission { allowed: Some(true), tool, .. } if tool == "bash"));
        assert!(matches!(&p.entries[3], Entry::Tool { ok: true, .. }));
        assert!(
            matches!(&p.entries[4], Entry::Tool { name, summary, ok: true, .. }
                if name == "hook:PreToolUse" && summary == "guard.ts"),
            "hook runs render as tool rows"
        );
        assert!(matches!(&p.entries[5], Entry::Assistant { text } if text.contains("green")));
    }

    #[test]
    fn from_conversation_maps_tool_results_by_id() {
        let msgs = vec![
            Message::system("sys"),
            Message::user("read a.rs"),
            Message {
                role: Role::Assistant,
                content: String::new(),
                tool_calls: vec![crate::agent::provider::ToolCall {
                    id: "t1".into(),
                    name: "read_file".into(),
                    arguments: json!({"path": "a.rs"}),
                }],
                tool_call_id: None,
            },
            Message::tool_result("t1", "1 | fn main() {}"),
            Message {
                role: Role::Assistant,
                content: "It defines main.".into(),
                tool_calls: vec![],
                tool_call_id: None,
            },
        ];
        let page = ChatPage::from_conversation(&msgs);
        assert_eq!(page.entries.len(), 3);
        assert!(matches!(&page.entries[0], Entry::User { .. }));
        assert!(matches!(&page.entries[1], Entry::Tool { ok: true, name, .. } if name == "read_file"));
        assert!(matches!(&page.entries[2], Entry::Assistant { .. }));

        // A denied tool result flips the row to failed.
        let msgs2 = vec![
            Message::system("sys"),
            Message::user("go"),
            Message {
                role: Role::Assistant,
                content: String::new(),
                tool_calls: vec![crate::agent::provider::ToolCall {
                    id: "t9".into(),
                    name: "bash".into(),
                    arguments: json!({"command": "rm -rf /"}),
                }],
                tool_call_id: None,
            },
            Message::tool_result("t9", "Permission denied by the user."),
        ];
        let page2 = ChatPage::from_conversation(&msgs2);
        assert!(matches!(&page2.entries[1], Entry::Tool { ok: false, .. }));
    }

    #[test]
    fn compaction_messages_render_as_notes() {
        let msgs = vec![
            Message::system("sys"),
            Message::user("[Context summary of earlier turns — restored after compaction]\nthe task"),
            Message::user("continue"),
        ];
        let page = ChatPage::from_conversation(&msgs);
        assert!(matches!(&page.entries[0], Entry::Compaction { .. }));
        assert!(matches!(&page.entries[1], Entry::User { .. }));
    }

    // ------------------------------------------------------------------
    // Phase 4: subagent cards, checkpoint rows, MCP icons
    // ------------------------------------------------------------------

    #[test]
    fn subagent_cards_follow_the_task_lifecycle() {
        let mut col = Collector::new("review it", "Agent run");
        col.on(&AgentEvent::ToolStart {
            name: "task".into(),
            summary: "code-reviewer — review the auth diff".into(),
        });
        assert!(matches!(&col.page.entries[1],
            Entry::Subagent { name, status, ok: false }
                if name == "code-reviewer" && status == "running"),
            "task ToolStart opens an agent card"
        );

        // Child activity updates the card status without adding rows.
        col.on(&AgentEvent::Subagent {
            name: "code-reviewer".into(),
            event: Box::new(AgentEvent::ToolStart { name: "grep".into(), summary: "TODO".into() }),
        });
        assert!(matches!(&col.page.entries[1],
            Entry::Subagent { status, .. } if status.contains("grep")));

        // The parent ToolEnd closes the card.
        col.on(&AgentEvent::ToolEnd { name: "task".into(), ok: true, ms: 10 });
        assert!(matches!(&col.page.entries[1],
            Entry::Subagent { ok: true, status, .. } if status == "done"));
    }

    #[test]
    fn parallel_task_cards_close_lifo() {
        let mut col = Collector::new("two agents", "Agent run");
        col.on(&AgentEvent::ToolStart { name: "task".into(), summary: "a — first".into() });
        col.on(&AgentEvent::ToolStart { name: "task".into(), summary: "b — second".into() });
        col.on(&AgentEvent::ToolEnd { name: "task".into(), ok: true, ms: 5 });
        col.on(&AgentEvent::ToolEnd { name: "task".into(), ok: false, ms: 5 });
        assert!(matches!(&col.page.entries[1], Entry::Subagent { name, ok: true, .. } if name == "a"));
        assert!(matches!(&col.page.entries[2], Entry::Subagent { name, ok: false, .. } if name == "b"));
    }

    #[test]
    fn checkpoint_events_render_as_rows() {
        let mut col = Collector::new("edit", "Agent run");
        col.on(&AgentEvent::Checkpoint {
            tool: "edit_file".into(),
            detail: "src/main.rs".into(),
        });
        assert!(matches!(&col.page.entries[1],
            Entry::Checkpoint { tool, detail } if tool == "edit_file" && detail == "src/main.rs"));
    }

    #[test]
    fn mcp_tools_get_the_cable_icon_and_task_gets_bot() {
        assert_eq!(tool_icon("mcp__fs__read"), "cable");
        assert_eq!(tool_icon("task"), "bot");
        assert_eq!(tool_icon("read_file"), "file-text");
    }

    #[test]
    fn restored_transcripts_map_task_calls_to_agent_cards() {
        let msgs = vec![
            Message::system("sys"),
            Message::user("go"),
            Message {
                role: Role::Assistant,
                content: String::new(),
                tool_calls: vec![crate::agent::provider::ToolCall {
                    id: "t1".into(),
                    name: "task".into(),
                    arguments: serde_json::json!({"agent": "scout", "prompt": "look around"}),
                }],
                tool_call_id: None,
            },
            Message::tool_result("t1", "the report"),
        ];
        let page = ChatPage::from_conversation(&msgs);
        assert!(matches!(&page.entries[1],
            Entry::Subagent { name, ok: true, .. } if name == "scout"));
    }
}
