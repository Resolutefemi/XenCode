//! AgentSession: the streaming tool-use loop.
//!
//! send() drives: stream → accumulate → execute tools (permission gate +
//! hooks + MCP) → feed results back → until a final answer or max rounds.

use crate::chat::{ChatEvent, ChatMessage, ChatTransport, ImagePart, Role, ToolCallReq, ToolSpec};
use crate::config::ProviderConfig;
use crate::hooks::{self, Hooks};
use crate::mcp::McpRegistry;
use crate::permissions::{Decision, PermissionGate, PermissionMode};
use crate::state::events::{types as evt, EventLog};
use crate::telemetry::XpSink;
use crate::tools::compactor;
use crate::tools::LocalTools;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub const MAX_TOOL_ROUNDS: u32 = 20;

/// Front-end hook fired after each tool execution (DB TOOL rows, UI
/// rendering). Args: (tool name, args json, result text).
pub type ToolResultHook = Box<dyn Fn(&str, &str, &str) + Send + Sync>;

/// Provider-reported token usage of one user turn (all tool rounds summed).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TurnUsage {
    pub input_tokens: i64,
    pub output_tokens: i64,
}

pub struct AgentSession {
    pub provider: ProviderConfig,
    transport: Box<dyn ChatTransport>,
    pub tools: Arc<Mutex<LocalTools>>,
    pub workspace_root: PathBuf,
    pub system_prompt: String,
    pub messages: Vec<ChatMessage>,
    pub permission: PermissionGate,
    pub hooks: Hooks,
    pub events: Option<EventLog>,
    pub xp: Option<Arc<dyn XpSink>>,
    pub mcp: Option<Arc<Mutex<McpRegistry>>>,
    pub max_tool_rounds: u32,
    pub tokens_in: u64,
    pub tokens_out: u64,
    pub last_failure: Option<String>,
    /// Provider-reported token usage for the most recent turn (summed across
    /// the tool rounds of that turn); zeros when the provider reported none.
    /// Read by the desktop/CLI persistence layer for llm_call_records and the
    /// chat_sessions token totals.
    pub last_turn_usage: TurnUsage,
    /// Optional front-end hook fired after each tool execution (DB TOOL rows,
    /// UI rendering). Args: (tool name, args json, result text).
    pub on_tool_result: Option<ToolResultHook>,
    /// Enable the `task` subagent tool (disabled inside subagents).
    pub subagents_enabled: bool,
}

impl AgentSession {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        provider: ProviderConfig,
        workspace_root: impl Into<PathBuf>,
        system_prompt: String,
        permission: PermissionGate,
        events: Option<EventLog>,
        xp: Option<Arc<dyn XpSink>>,
        mcp: Option<Arc<Mutex<McpRegistry>>>,
    ) -> Self {
        let workspace_root = workspace_root.into();
        let transport = crate::provider::transport_for(provider.kind);
        Self {
            provider,
            transport,
            tools: Arc::new(Mutex::new(LocalTools::new(&workspace_root))),
            workspace_root: workspace_root.clone(),
            system_prompt,
            messages: vec![],
            permission,
            hooks: Hooks::load(&workspace_root),
            events,
            xp,
            mcp,
            max_tool_rounds: MAX_TOOL_ROUNDS,
            tokens_in: 0,
            tokens_out: 0,
            last_failure: None,
            last_turn_usage: TurnUsage::default(),
            on_tool_result: None,
            subagents_enabled: true,
        }
    }

    pub fn tool_specs(&self) -> Vec<ToolSpec> {
        let mut specs = LocalTools::specs();
        if self.subagents_enabled {
            specs.push(ToolSpec {
                name: "task".into(),
                description: "Spawn a fresh XenCode subagent with its own context to do a focused job and report back. Use for research or self-contained subtasks.".into(),
                parameters: serde_json::json!({
                    "type":"object",
                    "properties":{
                        "prompt":{"type":"string","description":"Full task for the subagent"},
                        "context":{"type":"string","description":"Optional extra context (files, constraints)"}
                    },
                    "required":["prompt"]
                }),
            });
        }
        if let Some(mcp) = &self.mcp {
            specs.extend(mcp.lock().expect("mcp registry poisoned").specs());
        }
        specs
    }

    fn tool_names(&self) -> Vec<String> {
        self.tool_specs().iter().map(|t| t.name.clone()).collect()
    }

    pub fn history_snapshot(&self) -> Vec<ChatMessage> {
        self.messages.clone()
    }

    pub fn clear(&mut self) {
        self.messages.clear();
    }

    /// One user turn (text only). See [`AgentSession::send_with_images`].
    pub async fn send(
        &mut self,
        user_text: &str,
        on_delta: &mut dyn FnMut(&str),
        on_tool_call: &mut dyn FnMut(&ToolCallReq),
    ) -> Option<String> {
        self.send_with_images(user_text, &[], on_delta, on_tool_call).await
    }

    /// One user turn with attached images: streams, executes tools, and loops
    /// until the model gives a final answer. Returns None on failure (see
    /// `last_failure`). In plan mode the plan addendum is appended to the
    /// system prompt for this turn, so flipping modes mid-session takes
    /// effect immediately.
    pub async fn send_with_images(
        &mut self,
        user_text: &str,
        images: &[ImagePart],
        on_delta: &mut dyn FnMut(&str),
        on_tool_call: &mut dyn FnMut(&ToolCallReq),
    ) -> Option<String> {
        if !user_text.is_empty() || !images.is_empty() {
            self.messages.push(ChatMessage::user_with_images(
                if user_text.is_empty() { "(see attached images)" } else { user_text },
                images.to_vec(),
            ));
        }
        let system = if self.permission.mode == PermissionMode::Plan {
            format!("{}\n\n{}", self.system_prompt, crate::prompt::PLAN_MODE_ADDENDUM)
        } else {
            self.system_prompt.clone()
        };
        let mut final_text = String::new();
        let mut round: u32 = 0;
        let mut used_in: i64 = 0;
        let mut used_out: i64 = 0;
        let mut saw_usage = false;

        loop {
            round += 1;
            if round > self.max_tool_rounds {
                self.last_failure = Some(format!("max tool rounds ({}) exceeded", self.max_tool_rounds));
                return None;
            }

            let specs = self.tool_specs();
            let stream = self.transport.stream_chat(
                &self.provider,
                &self.messages,
                &specs,
                &system,
            );
            let mut text = String::new();
            let mut tool_calls: Vec<ToolCallReq> = vec![];
            let mut failure: Option<String> = None;

            {
                use futures_util::StreamExt;
                let mut stream = stream;
                while let Some(event) = stream.next().await {
                    match event {
                        ChatEvent::TextDelta(d) => {
                            text.push_str(&d);
                            on_delta(&d);
                        }
                        ChatEvent::ToolCall { id, name, args_json } => {
                            let tc = ToolCallReq { id, name, args_json };
                            on_tool_call(&tc);
                            tool_calls.push(tc);
                        }
                        ChatEvent::Completed { input_tokens, output_tokens, .. } => {
                            used_in += input_tokens;
                            used_out += output_tokens;
                            if input_tokens > 0 || output_tokens > 0 {
                                saw_usage = true;
                            }
                        }
                        ChatEvent::Failed { message } => failure = Some(message),
                    }
                }
            }
            final_text.clone_from(&text);

            self.last_turn_usage = if saw_usage {
                TurnUsage { input_tokens: used_in, output_tokens: used_out }
            } else {
                TurnUsage::default()
            };
            if let Some(message) = failure {
                self.last_failure = Some(message);
                return None;
            }

            self.messages.push(ChatMessage {
                role: Role::Assistant,
                content: if text.is_empty() { None } else { Some(text) },
                tool_calls: tool_calls.clone(),
                tool_call_id: None,
                name: None,
                images: vec![],
            });

            if tool_calls.is_empty() {
                if let Some(xp) = &self.xp {
                    let label: String = {
                        let s = user_text.trim().replace('\n', " ");
                        if s.is_empty() { "chat turn".to_string() } else { s.chars().take(50).collect() }
                    };
                    xp.award_turn(&label, crate::now_ms());
                }
                if let Some(log) = &self.events {
                    log.emit_simple(evt::TURN_COMPLETED, Some(&format!("{} chars", final_text.len())));
                }
                if saw_usage {
                    self.tokens_in += used_in.max(0) as u64;
                    self.tokens_out += used_out.max(0) as u64;
                } else {
                    self.tokens_out += est_tokens(&final_text);
                }
                return Some(final_text);
            }

            for tc in &tool_calls {
                let result = self.execute_tool(&tc.id, &tc.name, &tc.args_json).await;
                if let Some(hook) = &self.on_tool_result {
                    hook(&tc.name, &tc.args_json, &result);
                }
                self.messages.push(ChatMessage::tool_result(&tc.id, &tc.name, result));
            }
            self.maybe_compact();
        }
    }

    /// Execute one tool call through the permission gate, hooks, local tools,
    /// MCP registry, or subagent spawner. Emits TOOL_CALL / TOOL_RESULT events.
    pub async fn execute_tool(&self, call_id: &str, name: &str, args_json: &str) -> String {
        if let Some(log) = &self.events {
            log.emit(
                evt::TOOL_CALL,
                Some(name),
                serde_json::Map::from_iter([
                    ("tool".to_string(), serde_json::Value::String(name.to_string())),
                    ("id".to_string(), serde_json::Value::String(call_id.to_string())),
                ]),
            );
        }

        let result = self.execute_tool_inner(name, args_json).await;

        if let Some(log) = &self.events {
            log.emit(
                evt::TOOL_RESULT,
                Some(name),
                serde_json::Map::from_iter([
                    ("tool".to_string(), serde_json::Value::String(name.to_string())),
                    ("ok".to_string(), serde_json::Value::Bool(!result.starts_with("ERROR:"))),
                ]),
            );
        }
        result
    }

    async fn execute_tool_inner(&self, name: &str, args_json: &str) -> String {
        // Permission gate first.
        match self.permission.check(name, args_json) {
            Decision::Deny(reason) => return format!("ERROR: permission denied: {reason}"),
            Decision::Ask => {
                // In non-interactive contexts surface the ask as a decision
                // card when an event log exists, else deny with guidance.
                if let Some(log) = &self.events {
                    let decisions = crate::state::decisions::DecisionStore::new(&self.workspace_root);
                    match decisions.create(
                        &format!("Allow `{name}`?"),
                        args_json,
                        vec!["allow".into(), "deny".into()],
                    ) {
                        Ok(card) => {
                            log.emit(
                                evt::DECISION_REQUESTED,
                                Some(&card.title),
                                serde_json::Map::from_iter([("card".to_string(), serde_json::Value::String(card.id.clone()))]),
                            );
                            let resolved = decisions.wait_resolution(&card.id, 120_000);
                            return match resolved.and_then(|c| c.chosen_index) {
                                Some(0) => self.dispatch_tool(name, args_json).await, // allowed
                                _ => "ERROR: user denied this tool call via decision card".to_string(),
                            };
                        }
                        Err(e) => return format!("ERROR: could not create decision card: {e}"),
                    }
                }
                return format!(
                    "ERROR: permission required for `{name}` — rerun in yolo mode or approve interactively"
                );
            }
            Decision::Allow => {}
        }

        // Pre-hooks may block.
        if let Err(reason) = hooks::run_pre_hooks(&self.hooks, name, args_json, &self.workspace_root) {
            return format!("ERROR: {reason}");
        }

        let out = self.dispatch_tool(name, args_json).await;

        let notes = hooks::run_post_hooks(&self.hooks, name, args_json, &self.workspace_root);
        if out.starts_with("ERROR:") || notes.is_empty() {
            out
        } else {
            format!("{out}\n{}", notes.join("\n"))
        }
    }

    async fn dispatch_tool(&self, name: &str, args_json: &str) -> String {
        // MCP tools first (mcp_<server>_<tool>).
        if let Some(mcp) = &self.mcp {
            if let Some(result) = mcp.lock().expect("mcp registry poisoned").call(name, args_json) {
                return result;
            }
        }
        // Subagent tool.
        if name == "task" {
            return self.run_subagent(args_json).await;
        }
        // web_fetch is async (reqwest) — it cannot run on the blocking tool thread.
        if name == "web_fetch" {
            let args: serde_json::Value = serde_json::from_str(args_json).unwrap_or(serde_json::Value::Null);
            let url = args.get("url").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if url.is_empty() {
                return "ERROR: missing url".into();
            }
            let max_chars = args.get("max_chars").and_then(|v| v.as_u64()).unwrap_or(8000).clamp(200, 40_000) as usize;
            return match crate::tools::web::fetch_text(&url, max_chars).await {
                Ok(text) => text,
                Err(e) => format!("ERROR: {e}"),
            };
        }
        // Local tools (blocking work on a blocking thread).
        let tools = self.tools.clone();
        let name = name.to_string();
        let args_json = args_json.to_string();
        tokio::task::spawn_blocking(move || {
            let mut t = tools.lock().expect("tools poisoned");
            t.execute(&name, &args_json)
        })
        .await
        .unwrap_or_else(|e| format!("ERROR: tool thread failed: {e}"))
    }

    async fn run_subagent(&self, args_json: &str) -> String {
        let args: serde_json::Value = serde_json::from_str(args_json).unwrap_or(serde_json::Value::Null);
        let prompt = args.get("prompt").and_then(|p| p.as_str()).unwrap_or("").to_string();
        if prompt.is_empty() {
            return "ERROR: task(prompt) needs a prompt".into();
        }
        let context = args.get("context").and_then(|c| c.as_str()).unwrap_or("");
        let mut sub = AgentSession::new(
            self.provider.clone(),
            self.workspace_root.clone(),
            format!(
                "{}\nYou are a SUBAGENT. Complete the focused task and report the result tersely. Do not spawn further subagents.",
                self.system_prompt
            ),
            PermissionGate::new(self.permission.mode),
            None,
            None,
            self.mcp.clone(),
        );
        sub.subagents_enabled = false;
        sub.max_tool_rounds = 8;
        let full = if context.is_empty() {
            prompt
        } else {
            format!("{prompt}\n\nCONTEXT:\n{context}")
        };
        let mut noop = |_: &str| {};
        match Box::pin(sub.send(&full, &mut noop, &mut |_: &ToolCallReq| {})).await {
            Some(text) => text,
            None => format!("ERROR: subagent failed: {}", sub.last_failure.clone().unwrap_or_default()),
        }
    }

    /// Compact history when it grows past the threshold. Returns true if compacted.
    pub fn maybe_compact(&mut self) -> bool {
        if let Some(compacted) = compactor::compact(&self.messages) {
            self.messages = compacted;
            if let Some(log) = &self.events {
                log.emit_simple(evt::COMPACTED, Some("history folded"));
            }
            return true;
        }
        false
    }
}

fn est_tokens(s: &str) -> u64 {
    (s.len() as u64 / 4).max(1)
}

/// Convenience: build a session from config (active provider) with defaults.
pub fn session_from_config(
    workspace_root: &Path,
    cfg: &crate::config::PcConfig,
    mode: crate::permissions::PermissionMode,
    with_events: bool,
) -> Result<AgentSession, String> {
    let provider = cfg
        .active_provider()
        .cloned()
        .ok_or_else(|| "no active provider — run `xencode config set-provider`".to_string())?;
    let log = if with_events { Some(EventLog::new(workspace_root)) } else { None };
    // XP lands in the shared database (xp_events in aicode_agent_db) — the
    // same table the Android app writes; the legacy JSON ledger is imported
    // once on first use. Falls back to the file ledger when the DB cannot be
    // opened (read-only home, corrupt file) so chatting never breaks.
    let xp: Option<Arc<dyn XpSink>> = match crate::store::xp::DbXpLedger::open_default() {
        Ok(db) => Some(Arc::new(db)),
        Err(_) => Some(Arc::new(crate::telemetry::XpLedger::default())),
    };
    let mut session = AgentSession::new(
        provider,
        workspace_root,
        String::new(),
        PermissionGate::new(mode),
        log,
        xp,
        None,
    );
    let names = session.tool_names();
    session.system_prompt = crate::prompt::build_system_prompt(workspace_root, &names);
    Ok(session)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::ProviderKind;
    use std::sync::Mutex as StdMutex;

    /// Scripted transport: pops one event script per stream call (FIFO).
    struct MockTransport {
        scripts: Arc<StdMutex<Vec<Vec<ChatEvent>>>>,
    }
    impl ChatTransport for MockTransport {
        fn stream_chat<'a>(
            &'a self,
            _cfg: &'a ProviderConfig,
            _messages: &'a [ChatMessage],
            _tools: &'a [ToolSpec],
            _system: &'a str,
        ) -> futures_util::stream::BoxStream<'a, ChatEvent> {
            let next = if self.scripts.lock().unwrap().is_empty() {
                vec![]
            } else {
                self.scripts.lock().unwrap().remove(0)
            };
            Box::pin(futures_util::stream::iter(next))
        }
    }

    /// Records the system prompt of every stream call.
    struct SystemCapturingTransport {
        inner: MockTransport,
        seen: Arc<StdMutex<Vec<String>>>,
    }
    impl ChatTransport for SystemCapturingTransport {
        fn stream_chat<'a>(
            &'a self,
            cfg: &'a ProviderConfig,
            messages: &'a [ChatMessage],
            tools: &'a [ToolSpec],
            system: &'a str,
        ) -> futures_util::stream::BoxStream<'a, ChatEvent> {
            self.seen.lock().unwrap().push(system.to_string());
            self.inner.stream_chat(cfg, messages, tools, system)
        }
    }

    fn mock_session(workspace: &Path, scripts: Vec<Vec<ChatEvent>>, mode: crate::permissions::PermissionMode) -> AgentSession {
        let provider = ProviderConfig {
            name: "mock".into(),
            kind: ProviderKind::OpenAi,
            base_url: "http://mock".into(),
            api_key: "k".into(),
            model: "m".into(),
            temperature: None,
            max_tokens: None,
            extra_headers: Default::default(),
        };
        let mut s = AgentSession::new(
            provider,
            workspace,
            "sys".into(),
            PermissionGate::new(mode),
            Some(EventLog::new(workspace)),
            None,
            None,
        );
        s.transport = Box::new(MockTransport { scripts: Arc::new(StdMutex::new(scripts)) });
        s
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn one_shot_text_turn() {
        let dir = tempfile::tempdir().unwrap();
        let mut s = mock_session(dir.path(), vec![vec![
            ChatEvent::TextDelta("Hello".into()),
            ChatEvent::TextDelta(" world".into()),
            ChatEvent::Completed { finish_reason: "stop".into(), input_tokens: 0, output_tokens: 0 },
        ]], crate::permissions::PermissionMode::Yolo);
        let mut seen = String::new();
        let out = s.send("hi", &mut |d| seen.push_str(d), &mut |_| {}).await;
        assert_eq!(out.as_deref(), Some("Hello world"));
        assert_eq!(seen, "Hello world");
        assert_eq!(s.messages.len(), 2);
        assert!(s.last_failure.is_none());
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn turn_usage_is_summed_across_rounds_and_exposed() {
        let dir = tempfile::tempdir().unwrap();
        let mut s = mock_session(dir.path(), vec![vec![
            ChatEvent::ToolCall { id: "c1".into(), name: "list_files".into(), args_json: "{}".into() },
            ChatEvent::Completed { finish_reason: "tool_calls".into(), input_tokens: 10, output_tokens: 4 },
        ], vec![
            ChatEvent::TextDelta("listed".into()),
            ChatEvent::Completed { finish_reason: "stop".into(), input_tokens: 20, output_tokens: 6 },
        ]], crate::permissions::PermissionMode::Yolo);
        let out = s.send("list", &mut |_| {}, &mut |_| {}).await;
        assert_eq!(out.as_deref(), Some("listed"));
        let u = s.last_turn_usage;
        assert_eq!((30, 10), (u.input_tokens, u.output_tokens));
        assert_eq!((30, 10), (s.tokens_in as i64, s.tokens_out as i64));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn tool_results_reach_the_hook() {
        let dir = tempfile::tempdir().unwrap();
        let mut s = mock_session(dir.path(), vec![vec![
            ChatEvent::ToolCall { id: "c1".into(), name: "list_files".into(), args_json: "{}".into() },
            ChatEvent::Completed { finish_reason: "tool_calls".into(), input_tokens: 1, output_tokens: 1 },
        ], vec![
            ChatEvent::TextDelta("done".into()),
            ChatEvent::Completed { finish_reason: "stop".into(), input_tokens: 2, output_tokens: 2 },
        ]], crate::permissions::PermissionMode::Yolo);
        let seen: Arc<StdMutex<Vec<(String, String, String)>>> = Arc::new(StdMutex::new(vec![]));
        let seen2 = seen.clone();
        s.on_tool_result = Some(Box::new(move |name, args, result| {
            seen2.lock().unwrap().push((name.to_string(), args.to_string(), result.to_string()));
        }));
        let out = s.send("list", &mut |_| {}, &mut |_| {}).await;
        assert_eq!(out.as_deref(), Some("done"));
        let seen = seen.lock().unwrap();
        assert_eq!(1, seen.len());
        assert_eq!("list_files", seen[0].0);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn tool_loop_executes_and_continues() {
        let dir = tempfile::tempdir().unwrap();
        let mut s = mock_session(dir.path(), vec![
            vec![
                ChatEvent::TextDelta("writing".into()),
                ChatEvent::ToolCall {
                    id: "c1".into(),
                    name: "write_file".into(),
                    args_json: r#"{"path":"out.txt","content":"made by agent"}"#.into(),
                },
                ChatEvent::Completed { finish_reason: "tool_calls".into(), input_tokens: 0, output_tokens: 0 },
            ],
            vec![
                ChatEvent::TextDelta("done writing".into()),
                ChatEvent::Completed { finish_reason: "stop".into(), input_tokens: 0, output_tokens: 0 },
            ],
        ], crate::permissions::PermissionMode::Yolo);
        let calls: Arc<StdMutex<Vec<String>>> = Arc::new(StdMutex::new(vec![]));
        let calls2 = calls.clone();
        let out = s
            .send("make the file", &mut |_| {}, &mut move |tc: &ToolCallReq| {
                calls2.lock().unwrap().push(tc.name.clone());
            })
            .await;
        assert_eq!(out.as_deref(), Some("done writing"));
        assert!(dir.path().join("out.txt").exists());
        assert_eq!(calls.lock().unwrap().as_slice(), ["write_file"]);
        // history: user, assistant(toolcall), tool(result), assistant(final)
        assert_eq!(s.messages.len(), 4);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn permission_gate_blocks_in_readonly() {
        let dir = tempfile::tempdir().unwrap();
        let s = mock_session(dir.path(), vec![], crate::permissions::PermissionMode::ReadOnly);
        let out = s.execute_tool("x", "write_file", r#"{"path":"a","content":"b"}"#).await;
        assert!(out.contains("read-only mode"), "{out}");
        let out = s.execute_tool("x", "read_file", r#"{"path":"a"}"#).await;
        assert!(!out.contains("permission denied"), "{out}");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn pre_hook_blocks_tool() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".xencode")).unwrap();
        let h = Hooks {
            hooks: vec![hooks::HookRule { matcher: "run_command".into(), command: "exit 2".into(), stage: "pre".into() }],
        };
        h.save(dir.path()).unwrap();
        let s = mock_session(dir.path(), vec![], crate::permissions::PermissionMode::Yolo);
        let out = s.execute_tool("x", "run_command", r#"{"command":"echo hi"}"#).await;
        assert!(out.contains("pre-hook"), "{out}");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn failure_surfaces_in_last_failure() {
        let dir = tempfile::tempdir().unwrap();
        let mut s = mock_session(dir.path(), vec![vec![
            ChatEvent::Failed { message: "HTTP 429: slow down".into() },
        ]], crate::permissions::PermissionMode::Yolo);
        let out = s.send("hi", &mut |_| {}, &mut |_| {}).await;
        assert!(out.is_none());
        assert!(s.last_failure.as_deref().unwrap().contains("429"));
    }

    #[test]
    fn session_from_config_errors_without_provider() {
        let dir = tempfile::tempdir().unwrap();
        let cfg = crate::config::PcConfig::default();
        assert!(session_from_config(dir.path(), &cfg, crate::permissions::PermissionMode::Confirm, false).is_err());
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn plan_mode_addendum_applies_per_turn() {
        let dir = tempfile::tempdir().unwrap();
        let seen: Arc<StdMutex<Vec<String>>> = Arc::new(StdMutex::new(vec![]));
        let mut s = mock_session(
            dir.path(),
            vec![vec![ChatEvent::TextDelta("a".into()), ChatEvent::Completed { finish_reason: "stop".into(), input_tokens: 0, output_tokens: 0 }],
                vec![ChatEvent::TextDelta("b".into()), ChatEvent::Completed { finish_reason: "stop".into(), input_tokens: 0, output_tokens: 0 }]],
            crate::permissions::PermissionMode::Confirm,
        );
        // Swap in the capturing transport.
        let scripts = Arc::new(StdMutex::new(vec![
            vec![ChatEvent::TextDelta("a".into()), ChatEvent::Completed { finish_reason: "stop".into(), input_tokens: 0, output_tokens: 0 }],
            vec![ChatEvent::TextDelta("b".into()), ChatEvent::Completed { finish_reason: "stop".into(), input_tokens: 0, output_tokens: 0 }],
        ]));
        s.transport = Box::new(SystemCapturingTransport {
            inner: MockTransport { scripts },
            seen: seen.clone(),
        });

        s.send("one", &mut |_| {}, &mut |_| {}).await.unwrap();
        assert!(!seen.lock().unwrap()[0].contains("PLAN MODE ACTIVE"));

        s.permission.mode = crate::permissions::PermissionMode::Plan;
        s.send("two", &mut |_| {}, &mut |_| {}).await.unwrap();
        assert!(seen.lock().unwrap()[1].contains("PLAN MODE ACTIVE"));

        s.permission.mode = crate::permissions::PermissionMode::Yolo;
        s.send("three", &mut |_| {}, &mut |_| {}).await.unwrap();
        assert!(!seen.lock().unwrap()[2].contains("PLAN MODE ACTIVE"));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn images_ride_on_the_user_message() {
        let dir = tempfile::tempdir().unwrap();
        let mut s = mock_session(
            dir.path(),
            vec![vec![ChatEvent::TextDelta("saw it".into()), ChatEvent::Completed { finish_reason: "stop".into(), input_tokens: 0, output_tokens: 0 }]],
            crate::permissions::PermissionMode::Yolo,
        );
        let imgs = vec![ImagePart::from_path("shot.png")];
        let out = s.send_with_images("what is this?", &imgs, &mut |_| {}, &mut |_| {}).await;
        assert_eq!(out.as_deref(), Some("saw it"));
        assert_eq!(1, s.messages[0].images.len());
        assert_eq!("shot.png", s.messages[0].images[0].path);
    }
}
