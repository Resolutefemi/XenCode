//! LLM provider layer: message types, the `Provider` trait, the GLM (z.ai,
//! OpenAI-compatible) streaming client and a scripted mock for tests.

use std::collections::{BTreeMap, VecDeque};

use anyhow::{Context, Result};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

/// Chat role. Serialized lowercase to match the OpenAI wire format.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    System,
    User,
    Assistant,
    Tool,
}

/// A tool invocation requested by the model.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub arguments: Value,
}

/// One conversation message (internal format, provider-agnostic).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    pub role: Role,
    #[serde(default)]
    pub content: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tool_calls: Vec<ToolCall>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    /// Token usage for this turn, when known (Phase 5 telemetry). Skipped in
    /// serialization when absent so old session files load unchanged.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub usage: Option<super::telemetry::TokenUsage>,
}

impl Message {
    pub fn system(content: impl Into<String>) -> Self {
        Self { role: Role::System, content: content.into(), tool_calls: vec![], tool_call_id: None, usage: None }
    }

    pub fn user(content: impl Into<String>) -> Self {
        Self { role: Role::User, content: content.into(), tool_calls: vec![], tool_call_id: None, usage: None }
    }

    pub fn tool_result(call_id: &str, content: impl Into<String>) -> Self {
        Self { role: Role::Tool, content: content.into(), tool_calls: vec![], tool_call_id: Some(call_id.to_string()), usage: None }
    }
}

/// JSON-schema tool definition handed to the model.
#[derive(Debug, Clone)]
pub struct ToolDef {
    pub name: String,
    pub description: String,
    pub parameters: Value,
}

/// Sink for streaming text deltas.
pub type DeltaTx = tokio::sync::mpsc::UnboundedSender<String>;

/// A chat-completions backend. Dyn-compatible (`Arc<dyn Provider>`) so the
/// agent loop can share one provider across parallel subagent loops.
pub trait Provider: Send + Sync {
    /// Run one model turn: stream text deltas through `tx` and return the
    /// complete assistant message (possibly carrying tool calls).
    fn complete<'a>(
        &'a self,
        messages: &'a [Message],
        tools: &'a [ToolDef],
        tx: Option<&'a DeltaTx>,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Message>> + Send + 'a>>;

    /// Model identifier for telemetry (empty = unknown).
    fn model(&self) -> &str {
        ""
    }
}

/// `Arc<P>` forwards to `P` — lets generic helpers (compaction) take the
/// shared provider the loop already holds.
impl<T: Provider + ?Sized> Provider for std::sync::Arc<T> {
    fn complete<'a>(
        &'a self,
        messages: &'a [Message],
        tools: &'a [ToolDef],
        tx: Option<&'a DeltaTx>,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Message>> + Send + 'a>> {
        (**self).complete(messages, tools, tx)
    }

    fn model(&self) -> &str {
        (**self).model()
    }
}

// ---------- GLM (z.ai, OpenAI-compatible) ----------

pub struct GlmProvider {
    http: reqwest::Client,
    base_url: String,
    api_key: String,
    model: String,
}

impl GlmProvider {
    pub fn from_env() -> Result<Self> {
        let api_key = std::env::var("XENCODE_API_KEY")
            .context("XENCODE_API_KEY is not set (export your z.ai API key)")?;
        let base_url = std::env::var("XENCODE_BASE_URL")
            .unwrap_or_else(|_| "https://api.z.ai/api/paas/v4".into());
        let model = std::env::var("XENCODE_MODEL").unwrap_or_else(|_| "glm-4.6".into());
        Ok(Self { http: reqwest::Client::new(), base_url, api_key, model })
    }

    /// A provider pinned to a specific model (Phase 5: the Oracle runs on a
    /// stronger model while the main loop keeps the default). Same
    /// base URL / key as [`Self::from_env`].
    pub fn from_env_with_model(model: &str) -> Result<Self> {
        let api_key = std::env::var("XENCODE_API_KEY")
            .context("XENCODE_API_KEY is not set (export your z.ai API key)")?;
        let base_url = std::env::var("XENCODE_BASE_URL")
            .unwrap_or_else(|_| "https://api.z.ai/api/paas/v4".into());
        Ok(Self { http: reqwest::Client::new(), base_url, api_key, model: model.to_string() })
    }

    fn wire_messages(messages: &[Message]) -> Vec<Value> {
        messages
            .iter()
            .map(|m| match m.role {
                Role::Assistant => {
                    let mut o = json!({ "role": "assistant", "content": m.content });
                    if !m.tool_calls.is_empty() {
                        o["tool_calls"] = Value::Array(
                            m.tool_calls
                                .iter()
                                .map(|tc| {
                                    json!({
                                        "id": tc.id,
                                        "type": "function",
                                        "function": {
                                            "name": tc.name,
                                            "arguments": tc.arguments.to_string(),
                                        },
                                    })
                                })
                                .collect(),
                        );
                    }
                    o
                }
                Role::Tool => json!({
                    "role": "tool",
                    "tool_call_id": m.tool_call_id.clone().unwrap_or_default(),
                    "content": m.content,
                }),
                other => json!({ "role": other, "content": m.content }),
            })
            .collect()
    }
}

impl Provider for GlmProvider {
    fn complete<'a>(
        &'a self,
        messages: &'a [Message],
        tools: &'a [ToolDef],
        tx: Option<&'a DeltaTx>,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Message>> + Send + 'a>> {
        Box::pin(async move {
        let mut body = json!({
            "model": self.model,
            "messages": Self::wire_messages(messages),
            "stream": true,
        });
        if !tools.is_empty() {
            body["tools"] = Value::Array(
                tools
                    .iter()
                    .map(|t| {
                        json!({
                            "type": "function",
                            "function": {
                                "name": t.name,
                                "description": t.description,
                                "parameters": t.parameters,
                            },
                        })
                    })
                    .collect(),
            );
        }

        let resp = self
            .http
            .post(format!("{}/chat/completions", self.base_url))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .await
            .context("GLM request failed")?
            .error_for_status()
            .context("GLM returned an error status")?;

        let mut stream = resp.bytes_stream();
        let mut buf: Vec<u8> = Vec::new();
        let mut content = String::new();
        // tool-call index -> (id, name, arguments-so-far)
        let mut calls: BTreeMap<u32, (String, String, String)> = BTreeMap::new();
        // Provider-reported token usage (final SSE chunk, when present).
        let mut usage: Option<super::telemetry::TokenUsage> = None;

        while let Some(chunk) = stream.next().await {
            buf.extend_from_slice(&chunk?);
            for data in drain_sse(&mut buf) {
                if data == "[DONE]" {
                    continue;
                }
                let Ok(ev) = serde_json::from_str::<SseChunk>(&data) else { continue };
                if let Some(u) = ev.usage {
                    usage = Some(super::telemetry::TokenUsage {
                        input: u.prompt_tokens.unwrap_or(0),
                        output: u.completion_tokens.unwrap_or(0),
                        estimated: false,
                    });
                }
                let Some(choice) = ev.choices.first() else { continue };
                if let Some(text) = &choice.delta.content {
                    if !text.is_empty() {
                        content.push_str(text);
                        if let Some(tx) = tx {
                            let _ = tx.send(text.clone());
                        }
                    }
                }
                for tc in &choice.delta.tool_calls {
                    let entry = calls.entry(tc.index).or_default();
                    if let Some(id) = &tc.id {
                        entry.0 = id.clone();
                    }
                    if let Some(name) = &tc.function.name {
                        entry.1 = name.clone();
                    }
                    if let Some(args) = &tc.function.arguments {
                        entry.2.push_str(args);
                    }
                }
            }
        }

        let tool_calls = calls
            .into_iter()
            .map(|(_, (id, name, args))| ToolCall {
                id,
                name,
                arguments: serde_json::from_str(&args).unwrap_or(Value::String(args)),
            })
            .collect();

        // When the API did not report usage, estimate from text sizes so
        // cost accounting never silently reads zero (marked `estimated`).
        let usage = usage.unwrap_or_else(|| super::telemetry::TokenUsage {
            input: content.chars().count() as u64 / 4,
            output: content.chars().count() as u64 / 4,
            estimated: true,
        });

        Ok(Message { role: Role::Assistant, content, tool_calls, tool_call_id: None, usage: Some(usage) })
        })
    }

    fn model(&self) -> &str {
        &self.model
    }
}

// ---------- SSE plumbing ----------

/// Pull every complete `data:` payload out of the buffer, consuming the
/// finished events (separated by a blank line) and leaving partials in place.
fn drain_sse(buf: &mut Vec<u8>) -> Vec<String> {
    let mut out = Vec::new();
    loop {
        let Some(pos) = find_subslice(buf, b"\n\n") else { break };
        let event: Vec<u8> = buf.drain(..pos + 2).collect();
        for line in String::from_utf8_lossy(&event).lines() {
            let data = line
                .strip_prefix("data: ")
                .or_else(|| line.strip_prefix("data:"));
            if let Some(data) = data {
                out.push(data.trim().to_string());
            }
        }
    }
    out
}

fn find_subslice(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}

#[derive(Deserialize)]
struct SseChunk {
    #[serde(default)]
    choices: Vec<SseChoice>,
    #[serde(default)]
    usage: Option<SseUsage>,
}

#[derive(Deserialize)]
struct SseUsage {
    #[serde(default)]
    prompt_tokens: Option<u64>,
    #[serde(default)]
    completion_tokens: Option<u64>,
}

#[derive(Deserialize)]
struct SseChoice {
    #[serde(default)]
    delta: SseDelta,
}

#[derive(Deserialize, Default)]
struct SseDelta {
    #[serde(default)]
    content: Option<String>,
    #[serde(default)]
    tool_calls: Vec<SseToolCall>,
}

#[derive(Deserialize)]
struct SseToolCall {
    #[serde(default)]
    index: u32,
    #[serde(default)]
    id: Option<String>,
    #[serde(default)]
    function: SseFn,
}

#[derive(Deserialize, Default)]
struct SseFn {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    arguments: Option<String>,
}

// ---------- Mock (tests) ----------

/// Scripted provider for tests: returns pre-baked assistant turns in order.
pub struct MockProvider {
    turns: std::sync::Mutex<VecDeque<Message>>,
}

impl MockProvider {
    pub fn new(turns: Vec<Message>) -> Self {
        Self { turns: std::sync::Mutex::new(turns.into()) }
    }
}

impl Provider for MockProvider {
    fn complete<'a>(
        &'a self,
        _messages: &'a [Message],
        _tools: &'a [ToolDef],
        tx: Option<&'a DeltaTx>,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Message>> + Send + 'a>> {
        Box::pin(async move {
            let msg = {
                let mut q = self.turns.lock().expect("mock lock");
                q.pop_front().context("mock provider: no scripted turns left")?
            };
            if let Some(tx) = tx {
                let chars: Vec<char> = msg.content.chars().collect();
                for chunk in chars.chunks(16) {
                    let s: String = chunk.iter().collect();
                    let _ = tx.send(s);
                    tokio::time::sleep(std::time::Duration::from_millis(1)).await;
                }
            }
            Ok(msg)
        })
    }

    fn model(&self) -> &str {
        "mock"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sse_drain_keeps_partials() {
        let mut buf: Vec<u8> = b"data: {\"a\":1}\n\ndata: {\"b\"".to_vec();
        let events = drain_sse(&mut buf);
        assert_eq!(events, vec!["{\"a\":1}".to_string()]);
        assert_eq!(buf, b"data: {\"b\"");
    }

    #[test]
    fn wire_format_roundtrip() {
        let msgs = vec![
            Message::system("sys"),
            Message::user("hi"),
            Message {
                role: Role::Assistant,
                content: String::new(),
                tool_calls: vec![ToolCall {
                    id: "t1".into(),
                    name: "read_file".into(),
                    arguments: json!({"path": "a.rs"}),
                }],
                tool_call_id: None,
                usage: None,
            },
            Message::tool_result("t1", "contents"),
        ];
        let wire = GlmProvider::wire_messages(&msgs);
        assert_eq!(wire[0]["role"], "system");
        assert_eq!(wire[2]["tool_calls"][0]["function"]["name"], "read_file");
        assert_eq!(wire[3]["role"], "tool");
        assert_eq!(wire[3]["tool_call_id"], "t1");
    }

    #[test]
    fn usage_field_is_skipped_when_absent() {
        let msg = Message::user("hi");
        let json = serde_json::to_string(&msg).unwrap();
        assert!(!json.contains("usage"), "absent usage must not appear: {json}");
        let with = Message { usage: Some(super::super::telemetry::TokenUsage { input: 1, output: 2, estimated: false }), ..Message::user("hi") };
        let json = serde_json::to_string(&with).unwrap();
        assert!(json.contains("usage"));
    }
}
