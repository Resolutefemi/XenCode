//! Turn persistence into the shared database (`aicode_agent_db`) — the one
//! writer the desktop chat pane and the CLI REPL both call through.
//!
//! Every user turn writes a `chat_sessions` row (once per conversation) plus
//! USER / ASSISTANT / TOOL rows in `agent_messages` (the Android app's
//! `MessageRole` vocabulary), one `llm_call_records` row per turn
//! (provider-reported tokens, duration, status) and bumps the session token
//! totals. All writes are best-effort: a busy or read-only database never
//! breaks the chat itself.

use crate::agent::TurnUsage;
use crate::store::{AgentMessageRow, ChatSessionRow, LlmCallRecordRow, SharedStore};
use std::sync::{Arc, Mutex};

pub struct ChatPersister {
    store: Arc<Mutex<SharedStore>>,
    session_id: String,
    created_at: i64,
    /// Derived from the first prompt; interior-mutable because the tool-row
    /// hook hands out `&Self` to the agent loop.
    title: Mutex<Option<String>>,
}

impl ChatPersister {
    /// Opens the machine-default database; `None` when it cannot be opened
    /// (the caller keeps chatting without persistence).
    pub fn open_default() -> Option<Arc<Self>> {
        Self::open(&SharedStore::default_db_path())
    }

    /// Opens the database at `path` (tests use in-memory stores directly via
    /// [`ChatPersister::new`]).
    pub fn open(path: &std::path::Path) -> Option<Arc<Self>> {
        match SharedStore::open(path) {
            Ok(s) => Some(Arc::new(Self::new(Arc::new(Mutex::new(s))))),
            Err(_) => None,
        }
    }

    pub fn new(store: Arc<Mutex<SharedStore>>) -> Self {
        Self {
            store,
            session_id: format!("s{}", crate::short_id()),
            created_at: crate::now_ms(),
            title: Mutex::new(None),
        }
    }

    pub fn session_id(&self) -> &str {
        &self.session_id
    }

    /// USER row + session upsert, written before the model call so even a
    /// crash keeps the prompt (durable transcript).
    pub fn user_turn(&self, workspace: &str, provider: &str, model: &str, prompt: &str) {
        {
            let mut title = self.title.lock().expect("title poisoned");
            if title.is_none() {
                *title = Some(title_from(prompt));
            }
        }
        let store = self.store.lock().expect("shared store poisoned");
        let now = crate::now_ms();
        let _ = store.session_upsert(&ChatSessionRow {
            id: self.session_id.clone(),
            title: self
                .title
                .lock()
                .expect("title poisoned")
                .clone()
                .unwrap_or_else(|| "New chat".into()),
            created_at: self.created_at,
            updated_at: now,
            workspace_path: workspace.to_string(),
            provider_id: Some(provider.to_string()),
            model: Some(model.to_string()),
            ..Default::default()
        });
        let _ = store.message_append(&AgentMessageRow {
            id: format!("m{}", crate::short_id()),
            session_id: self.session_id.clone(),
            role: "USER".into(),
            content: prompt.to_string(),
            timestamp: now,
            ..Default::default()
        });
    }

    /// TOOL row per executed tool call (status dot data for the app UI).
    pub fn tool_row(&self, name: &str, args_json: &str, result: &str) {
        let store = self.store.lock().expect("shared store poisoned");
        let _ = store.message_append(&AgentMessageRow {
            id: format!("m{}", crate::short_id()),
            session_id: self.session_id.clone(),
            role: "TOOL".into(),
            content: clip(result, 4000),
            timestamp: crate::now_ms(),
            tool_name: Some(name.to_string()),
            tool_args: Some(clip(args_json, 2000)),
            is_error: result.starts_with("ERROR"),
            ..Default::default()
        });
    }

    /// ASSISTANT row + success llm_call_record + session token totals.
    pub fn assistant_turn(
        &self,
        workspace: &str,
        provider: &str,
        model: &str,
        reply: &str,
        usage: TurnUsage,
        duration_millis: i64,
    ) {
        let store = self.store.lock().expect("shared store poisoned");
        let now = crate::now_ms();
        let _ = store.message_append(&AgentMessageRow {
            id: format!("m{}", crate::short_id()),
            session_id: self.session_id.clone(),
            role: "ASSISTANT".into(),
            content: reply.to_string(),
            timestamp: now,
            input_tokens: usage.input_tokens,
            output_tokens: usage.output_tokens,
            ..Default::default()
        });
        let _ = store.llm_call_insert(&LlmCallRecordRow {
            session_id: Some(self.session_id.clone()),
            provider_id: Some(provider.to_string()),
            model: Some(model.to_string()),
            kind: "chat".into(),
            input_tokens: usage.input_tokens,
            output_tokens: usage.output_tokens,
            duration_millis: Some(duration_millis),
            status: "success".into(),
            stop_reason: Some("end_turn".into()),
            created_at: now,
            ..Default::default()
        });
        if usage.input_tokens > 0 || usage.output_tokens > 0 {
            let _ = store.session_add_tokens(&self.session_id, usage.input_tokens, usage.output_tokens);
        }
        let _ = store.session_touch(&self.session_id, now);
        let _ = workspace; // workspace recorded on user_turn; kept for signature symmetry
    }

    /// Errored llm_call_record (the user row is already durable).
    pub fn failed_turn(&self, provider: &str, model: &str, failure: &str, usage: TurnUsage, duration_millis: i64) {
        let store = self.store.lock().expect("shared store poisoned");
        let _ = store.llm_call_insert(&LlmCallRecordRow {
            session_id: Some(self.session_id.clone()),
            provider_id: Some(provider.to_string()),
            model: Some(model.to_string()),
            kind: "chat".into(),
            input_tokens: usage.input_tokens,
            output_tokens: usage.output_tokens,
            duration_millis: Some(duration_millis),
            status: "error".into(),
            error_message: Some(clip(failure, 400)),
            created_at: crate::now_ms(),
            ..Default::default()
        });
    }

    /// Lifetime token totals across every front end on this machine.
    pub fn llm_totals(&self) -> Option<(i64, i64, i64, i64)> {
        self.store.lock().expect("shared store poisoned").llm_totals().ok()
    }
}

/// Short title from the first user message (same rule as the Kotlin line).
pub fn title_from(prompt: &str) -> String {
    let clean = prompt.trim().replace('\n', " ");
    if clean.is_empty() {
        "New chat".into()
    } else if clean.chars().count() <= 60 {
        clean
    } else {
        format!("{}...", clean.chars().take(57).collect::<String>())
    }
}

fn clip(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        format!("{}...", s.chars().take(max).collect::<String>())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::TurnUsage;

    fn persister() -> (ChatPersister, Arc<Mutex<SharedStore>>) {
        let store = Arc::new(Mutex::new(SharedStore::open_in_memory().unwrap()));
        (ChatPersister::new(store.clone()), store)
    }

    #[test]
    fn full_turn_round_trips_through_the_shared_schema() {
        let (p, store) = persister();
        p.user_turn("/tmp/ws", "deepseek", "deepseek-chat", "wire the desktop panes");
        p.tool_row("list_files", "{}", "exit=0\nsrc/");
        p.assistant_turn(
            "/tmp/ws",
            "deepseek",
            "deepseek-chat",
            "wired",
            TurnUsage { input_tokens: 88, output_tokens: 12 },
            640,
        );

        let s = store.lock().unwrap();
        let sessions = s.session_list_all().unwrap();
        assert_eq!(1, sessions.len());
        assert_eq!("wire the desktop panes", sessions[0].title);
        assert_eq!("/tmp/ws", sessions[0].workspace_path);
        assert_eq!(88, sessions[0].total_input_tokens);
        assert_eq!(12, sessions[0].total_output_tokens);

        let rows = s.message_list(&sessions[0].id).unwrap();
        assert_eq!(vec!["USER", "TOOL", "ASSISTANT"], rows.iter().map(|r| r.role.as_str()).collect::<Vec<_>>());
        assert_eq!("list_files", rows[1].tool_name.as_deref().unwrap());
        assert!(!rows[1].is_error);
        assert_eq!(88, rows[2].input_tokens);

        let (calls, errors, in_tok, out_tok) = s.llm_totals().unwrap();
        assert_eq!((1, 0, 88, 12), (calls, errors, in_tok, out_tok));
    }

    #[test]
    fn failed_turn_keeps_user_row_and_records_the_error() {
        let (p, store) = persister();
        p.user_turn("/ws", "p", "m", "this will fail");
        p.failed_turn("p", "m", "HTTP 429: rate limited", TurnUsage::default(), 210);
        let s = store.lock().unwrap();
        let sessions = s.session_list_all().unwrap();
        let rows = s.message_list(&sessions[0].id).unwrap();
        assert_eq!(vec!["USER"], rows.iter().map(|r| r.role.as_str()).collect::<Vec<_>>());
        let (calls, errors, _, _) = s.llm_totals().unwrap();
        assert_eq!((1, 1), (calls, errors));
    }

    #[test]
    fn titles_clip_like_the_kotlin_rule() {
        assert_eq!("New chat", title_from("   "));
        let long = "x".repeat(80);
        let t = title_from(&long);
        assert_eq!(60, t.chars().count());
        assert!(t.ends_with("..."));
    }
}
