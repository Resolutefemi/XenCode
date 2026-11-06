//! Turn persistence into the shared database (`aicode_agent_db`) — the one
//! writer the desktop chat pane and the CLI REPL both call through.
//!
//! Every user turn writes a `chat_sessions` row (once per conversation) plus
//! USER / ASSISTANT / TOOL rows in `agent_messages` (the Android app's
//! `MessageRole` vocabulary), one `llm_call_records` row per turn
//! (provider-reported tokens, duration, status) and bumps the session token
//! totals. The Android `AgentMode` vocabulary (BUILD / ASK / PLAN) is written
//! to `chat_sessions.mode` so a session created on the PC renders with the
//! right mode badge in the app, and user-attached images land in
//! `agent_messages.attachments` with the exact `AgentAttachment` JSON shape
//! the app decodes. [`ChatPersister::latest_session`] reads rows back, so a
//! conversation started on the phone continues on the PC (and vice versa).
//! All writes are best-effort: a busy or read-only database never breaks the
//! chat itself.

use crate::agent::TurnUsage;
use crate::chat::{ChatMessage, ImagePart};
use crate::permissions::PermissionMode;
use crate::store::{AgentMessageRow, ChatSessionRow, LlmCallRecordRow, SharedStore};
use std::sync::{Arc, Mutex};

pub struct ChatPersister {
    store: Arc<Mutex<SharedStore>>,
    /// Bound session — interior-mutable so a live `Arc<ChatPersister>` (shared
    /// with the agent thread) can adopt an existing shared-DB session on
    /// resume without being rebuilt.
    session_id: Mutex<String>,
    created_at: Mutex<i64>,
    /// Derived from the first prompt; interior-mutable because the tool-row
    /// hook hands out `&Self` to the agent loop.
    title: Mutex<Option<String>>,
    /// Current `chat_sessions.mode` value (Android `AgentMode` vocabulary).
    mode: Mutex<String>,
    /// The mode before entering PLAN; restored when the app leaves plan mode.
    mode_before_plan: Mutex<Option<String>>,
}

/// A session read back from the shared database for resuming.
#[derive(Debug, Clone)]
pub struct ResumedSession {
    pub session: ChatSessionRow,
    pub rows: Vec<AgentMessageRow>,
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
            session_id: Mutex::new(format!("s{}", crate::short_id())),
            created_at: Mutex::new(crate::now_ms()),
            title: Mutex::new(None),
            mode: Mutex::new("BUILD".to_string()),
            mode_before_plan: Mutex::new(None),
        }
    }

    /// Adopts an existing shared-DB session (row sync in): the next turns
    /// append to it instead of minting a new one, keeping its title, mode and
    /// creation timestamp. Safe to call on a live persister shared with the
    /// agent thread.
    pub fn resume(&self, session: &ChatSessionRow) {
        *self.session_id.lock().expect("session id poisoned") = session.id.clone();
        *self.created_at.lock().expect("created_at poisoned") = session.created_at;
        *self.title.lock().expect("title poisoned") = Some(session.title.clone());
        *self.mode.lock().expect("mode poisoned") = session.mode.clone();
        *self.mode_before_plan.lock().expect("mode poisoned") = session.mode_before_plan.clone();
    }

    pub fn session_id(&self) -> String {
        self.session_id.lock().expect("session id poisoned").clone()
    }

    /// Tracks the current permission mode in the Android `AgentMode`
    /// vocabulary on `chat_sessions.mode`; entering PLAN remembers the
    /// previous mode in `mode_before_plan` (the app restores it when leaving
    /// plan mode).
    pub fn set_permission_mode(&self, mode: PermissionMode) {
        self.set_mode(mode.agent_mode_string());
    }

    /// Raw `AgentMode` setter (BUILD / ASK / PLAN).
    pub fn set_mode(&self, agent_mode: &str) {
        let mut m = self.mode.lock().expect("mode poisoned");
        if agent_mode == "PLAN" && *m != "PLAN" {
            *self.mode_before_plan.lock().expect("mode poisoned") = Some(m.clone());
        }
        *m = agent_mode.to_string();
    }

    /// The `AgentAttachment` JSON the Android app decodes — camelCase field
    /// names, `containerPath` empty on the PC line (no container), `localPath`
    /// the PC path so a resumed phone session can re-attach the file.
    fn attachments_json(images: &[ImagePart]) -> Option<String> {
        if images.is_empty() {
            return None;
        }
        let arr: Vec<serde_json::Value> = images
            .iter()
            .map(|img| {
                let name = img.path.rsplit('/').next().unwrap_or(&img.path).to_string();
                let size = std::fs::metadata(&img.path).map(|m| m.len()).unwrap_or(0);
                serde_json::json!({
                    "fileName": name,
                    "containerPath": "",
                    "localPath": img.path,
                    "mimeType": img.mime,
                    "sizeBytes": size,
                    "isImage": true,
                })
            })
            .collect();
        serde_json::to_string(&arr).ok()
    }

    /// USER row + session upsert, written before the model call so even a
    /// crash keeps the prompt (durable transcript).
    pub fn user_turn(&self, workspace: &str, provider: &str, model: &str, prompt: &str) {
        self.user_turn_with_attachments(workspace, provider, model, prompt, &[]);
    }

    /// USER row with attached images (persisted as the app's
    /// `AgentAttachment` JSON on `agent_messages.attachments`).
    pub fn user_turn_with_attachments(
        &self,
        workspace: &str,
        provider: &str,
        model: &str,
        prompt: &str,
        images: &[ImagePart],
    ) {
        {
            let mut title = self.title.lock().expect("title poisoned");
            if title.is_none() {
                *title = Some(title_from(prompt));
            }
        }
        let store = self.store.lock().expect("shared store poisoned");
        let now = crate::now_ms();
        let session_id = self.session_id.lock().expect("session id poisoned").clone();
        let _ = store.session_upsert(&ChatSessionRow {
            id: session_id.clone(),
            title: self
                .title
                .lock()
                .expect("title poisoned")
                .clone()
                .unwrap_or_else(|| "New chat".into()),
            created_at: *self.created_at.lock().expect("created_at poisoned"),
            updated_at: now,
            workspace_path: workspace.to_string(),
            mode: self.mode.lock().expect("mode poisoned").clone(),
            mode_before_plan: self.mode_before_plan.lock().expect("mode poisoned").clone(),
            provider_id: Some(provider.to_string()),
            model: Some(model.to_string()),
            ..Default::default()
        });
        let _ = store.message_append(&AgentMessageRow {
            id: format!("m{}", crate::short_id()),
            session_id,
            role: "USER".into(),
            content: prompt.to_string(),
            timestamp: now,
            attachments_json: Self::attachments_json(images),
            ..Default::default()
        });
    }

    /// TOOL row per executed tool call (status dot data for the app UI).
    pub fn tool_row(&self, name: &str, args_json: &str, result: &str) {
        let store = self.store.lock().expect("shared store poisoned");
        let _ = store.message_append(&AgentMessageRow {
            id: format!("m{}", crate::short_id()),
            session_id: self.session_id(),
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
        let session_id = self.session_id();
        let _ = store.message_append(&AgentMessageRow {
            id: format!("m{}", crate::short_id()),
            session_id,
            role: "ASSISTANT".into(),
            content: reply.to_string(),
            timestamp: now,
            input_tokens: usage.input_tokens,
            output_tokens: usage.output_tokens,
            ..Default::default()
        });
        let _ = store.llm_call_insert(&LlmCallRecordRow {
            session_id: Some(self.session_id()),
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
            let _ = store.session_add_tokens(&self.session_id(), usage.input_tokens, usage.output_tokens);
        }
        let _ = store.session_touch(&self.session_id(), now);
        let _ = workspace; // workspace recorded on user_turn; kept for signature symmetry
    }

    /// Errored llm_call_record (the user row is already durable).
    pub fn failed_turn(&self, provider: &str, model: &str, failure: &str, usage: TurnUsage, duration_millis: i64) {
        let store = self.store.lock().expect("shared store poisoned");
        let _ = store.llm_call_insert(&LlmCallRecordRow {
            session_id: Some(self.session_id()),
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

    /// Reads back the most recently updated root session (row sync in) — the
    /// conversation to offer when the desktop or CLI starts.
    pub fn latest_session(&self) -> Option<ResumedSession> {
        let store = self.store.lock().expect("shared store poisoned");
        let sessions = store.session_list_all().ok()?;
        let session = sessions
            .into_iter()
            .filter(|s| s.subagent_type.is_none())
            .max_by_key(|s| s.updated_at)?;
        let rows = store.message_list(&session.id).ok()?;
        Some(ResumedSession { session, rows })
    }

    /// Reads one session back by id.
    pub fn session_by_id(&self, id: &str) -> Option<ResumedSession> {
        let store = self.store.lock().expect("shared store poisoned");
        let session = store.session_get(id).ok()??;
        let rows = store.message_list(&session.id).ok()?;
        Some(ResumedSession { session, rows })
    }

    /// Newest-first session list for the pickers (`/resume`, desktop).
    pub fn recent_sessions(&self, limit: usize) -> Vec<ChatSessionRow> {
        let Ok(sessions) = self.store.lock().expect("shared store poisoned").session_list_all() else {
            return vec![];
        };
        let mut roots: Vec<ChatSessionRow> =
            sessions.into_iter().filter(|s| s.subagent_type.is_none()).collect();
        roots.sort_by_key(|s| std::cmp::Reverse(s.updated_at));
        roots.truncate(limit);
        roots
    }

    /// Rebuilds a model-usable history from shared-DB rows: USER turns (with
    /// their images re-attached when the files still exist) and text ASSISTANT
    /// turns. TOOL rows and tool-call assistant rows are dropped — without the
    /// paired wire shapes they would only confuse providers — as are rows the
    /// compactor already folded or excluded.
    pub fn rows_to_history(rows: &[AgentMessageRow]) -> Vec<ChatMessage> {
        rows.iter()
            .filter(|r| !r.is_compacted && !r.is_context_summary && !r.is_compaction_marker && !r.is_context_excluded)
            .filter_map(|r| match r.role.as_str() {
                "USER" => {
                    let images = attachments_from_json(r.attachments_json.as_deref());
                    Some(ChatMessage::user_with_images(r.content.clone(), images))
                }
                "ASSISTANT" if r.tool_calls_json.is_none() && !r.content.trim().is_empty() => {
                    Some(ChatMessage::assistant(r.content.clone()))
                }
                _ => None,
            })
            .collect()
    }
}

/// Decodes `agent_messages.attachments` (the app's `AgentAttachment` JSON)
/// back into image parts, keeping only files that still exist on disk.
/// Public so the desktop transcript can re-render thumbnails from rows.
pub fn attachments_from_json(json: Option<&str>) -> Vec<ImagePart> {
    let raw = match json {
        Some(s) if !s.trim().is_empty() => s,
        _ => return vec![],
    };
    serde_json::from_str::<Vec<serde_json::Value>>(raw)
        .unwrap_or_default()
        .into_iter()
        .filter(|a| a.get("isImage").and_then(|v| v.as_bool()).unwrap_or(false))
        .filter_map(|a| {
            let path = a.get("localPath").and_then(|v| v.as_str())?.to_string();
            if path.is_empty() || !std::path::Path::new(&path).is_file() {
                return None;
            }
            let mime = a.get("mimeType").and_then(|v| v.as_str()).unwrap_or("image/png").to_string();
            Some(ImagePart { path, mime })
        })
        .collect()
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

    #[test]
    fn plan_mode_round_trips_through_chat_sessions_mode() {
        let (p, store) = persister();
        p.user_turn("/ws", "p", "m", "build something");
        p.set_permission_mode(crate::permissions::PermissionMode::Plan);
        p.user_turn("/ws", "p", "m", "now plan it");

        let s = store.lock().unwrap();
        let sessions = s.session_list_all().unwrap();
        assert_eq!(1, sessions.len());
        assert_eq!("PLAN", sessions[0].mode);
        assert_eq!(Some("BUILD".to_string()), sessions[0].mode_before_plan);
    }

    #[test]
    fn attachments_use_the_android_agent_attachment_shape() {
        let (p, store) = persister();
        let tmp = tempfile::tempdir().unwrap();
        let png = tmp.path().join("screen shot.png");
        std::fs::write(&png, b"\x89PNG\r\n\x1a\n1234").unwrap();
        p.user_turn_with_attachments(
            "/ws",
            "p",
            "m",
            "what is this?",
            &[ImagePart::from_path(png.display().to_string())],
        );

        let s = store.lock().unwrap();
        let sessions = s.session_list_all().unwrap();
        let rows = s.message_list(&sessions[0].id).unwrap();
        let json = rows[0].attachments_json.as_deref().unwrap();
        let arr: Vec<serde_json::Value> = serde_json::from_str(json).unwrap();
        assert_eq!(1, arr.len());
        assert_eq!("screen shot.png", arr[0]["fileName"]);
        assert_eq!("", arr[0]["containerPath"]);
        assert_eq!(png.display().to_string(), arr[0]["localPath"]);
        assert_eq!("image/png", arr[0]["mimeType"]);
        assert_eq!(12, arr[0]["sizeBytes"]); // "\x89PNG\r\n\x1a\n1234"
        assert_eq!(true, arr[0]["isImage"]);
    }

    #[test]
    fn latest_session_reads_back_and_resume_appends_to_the_same_rows() {
        let (p, store) = persister();
        p.user_turn("/ws", "p", "m", "first question");
        p.assistant_turn("/ws", "p", "m", "first answer", TurnUsage::default(), 10);

        // A brand-new persister (like the next app launch) reads the rows back.
        let p2 = ChatPersister::new(store.clone());
        let resumed = p2.latest_session().expect("a session should exist");
        assert_eq!("first question", resumed.title_of_first_user());
        assert_eq!(2, resumed.rows.len());
        let history = ChatPersister::rows_to_history(&resumed.rows);
        assert_eq!(2, history.len());
        assert_eq!(crate::chat::Role::User, history[0].role);
        assert_eq!("first question", history[0].content.as_deref().unwrap());
        assert_eq!(crate::chat::Role::Assistant, history[1].role);

        // Resuming binds the old session: the next turn appends, not mints.
        p2.resume(&resumed.session);
        p2.user_turn("/ws", "p", "m", "follow-up");
        let s = store.lock().unwrap();
        let sessions = s.session_list_all().unwrap();
        assert_eq!(1, sessions.len(), "resume must not mint a second session");
        let rows = s.message_list(&sessions[0].id).unwrap();
        assert_eq!(3, rows.len());
        assert_eq!("follow-up", rows[2].content);
        assert!(rows[2].timestamp >= rows[1].timestamp);
    }

    #[test]
    fn rows_to_history_drops_tool_rows_and_compacted_rows() {
        let (p, store) = persister();
        p.user_turn("/ws", "p", "m", "go");
        p.tool_row("list_files", "{}", "exit=0\nsrc/");
        p.assistant_turn("/ws", "p", "m", "done", TurnUsage::default(), 5);
        let s = store.lock().unwrap();
        let sessions = s.session_list_all().unwrap();
        let rows = s.message_list(&sessions[0].id).unwrap();
        let history = ChatPersister::rows_to_history(&rows);
        assert_eq!(2, history.len());
        assert!(history.iter().all(|m| m.role != crate::chat::Role::Tool));
    }

    impl ResumedSession {
        /// Test helper: the first USER row's content.
        fn title_of_first_user(&self) -> String {
            self.rows
                .iter()
                .find(|r| r.role == "USER")
                .map(|r| r.content.clone())
                .unwrap_or_default()
        }
    }
}
