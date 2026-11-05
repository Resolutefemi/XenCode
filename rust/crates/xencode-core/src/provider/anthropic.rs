//! Anthropic Messages protocol (`/v1/messages`, SSE with typed content blocks).

use super::{http_sse_events, truncate};
use crate::chat::{next_json_blob, with_v1, ChatEvent, ChatMessage, ChatTransport, Role, ToolSpec};
use crate::config::ProviderConfig;

const ANTHROPIC_VERSION: &str = "2023-06-01";

pub fn build_body(
    cfg: &ProviderConfig,
    messages: &[ChatMessage],
    tools: &[ToolSpec],
    system: &str,
) -> serde_json::Value {
    // Anthropic wants system as a top-level field and tool results inside user messages.
    let mut msgs: Vec<serde_json::Value> = vec![];
    let mut pending_results: Vec<serde_json::Value> = vec![];
    for m in messages {
        match m.role {
            Role::System => { /* handled via system field */ }
            Role::User => {
                if !pending_results.is_empty() {
                    msgs.push(serde_json::json!({"role": "user", "content": std::mem::take(&mut pending_results)}));
                }
                msgs.push(serde_json::json!({"role": "user", "content": m.content.clone().unwrap_or_default()}));
            }
            Role::Assistant => {
                if !pending_results.is_empty() {
                    msgs.push(serde_json::json!({"role": "user", "content": std::mem::take(&mut pending_results)}));
                }
                let mut content: Vec<serde_json::Value> = vec![];
                if let Some(text) = m.content.as_deref() {
                    if !text.is_empty() {
                        content.push(serde_json::json!({"type": "text", "text": text}));
                    }
                }
                for tc in &m.tool_calls {
                    let input: serde_json::Value =
                        serde_json::from_str(&tc.args_json).unwrap_or(serde_json::json!({}));
                    content.push(serde_json::json!({
                        "type": "tool_use", "id": tc.id, "name": tc.name, "input": input
                    }));
                }
                if content.is_empty() {
                    content.push(serde_json::json!({"type": "text", "text": ""}));
                }
                msgs.push(serde_json::json!({"role": "assistant", "content": content}));
            }
            Role::Tool => {
                pending_results.push(serde_json::json!({
                    "type": "tool_result",
                    "tool_use_id": m.tool_call_id.clone().unwrap_or_default(),
                    "content": m.content.clone().unwrap_or_default(),
                }));
            }
        }
    }
    if !pending_results.is_empty() {
        msgs.push(serde_json::json!({"role": "user", "content": pending_results}));
    }

    let mut body = serde_json::json!({
        "model": cfg.model,
        "max_tokens": cfg.max_tokens.unwrap_or(8192),
        "messages": msgs,
        "stream": true,
    });
    if !system.trim().is_empty() {
        body["system"] = serde_json::json!(system);
    }
    if let Some(t) = cfg.temperature {
        body["temperature"] = serde_json::json!(t);
    }
    if !tools.is_empty() {
        body["tools"] = serde_json::Value::Array(
            tools
                .iter()
                .map(|t| {
                    serde_json::json!({
                        "name": t.name, "description": t.description, "input_schema": t.parameters
                    })
                })
                .collect(),
        );
    }
    body
}

/// Tracks which tool_use block each input_json_delta belongs to.
#[derive(Default)]
pub struct AnthropicAccumulator {
    active_tool: Option<(usize, String, String)>, // (index, id, name)
    args_buf: String,
}

impl AnthropicAccumulator {
    pub fn feed(&mut self, event: &str, data: &str) -> Vec<ChatEvent> {
        let mut out = vec![];
        let data = data.trim();
        if data.is_empty() {
            return out;
        }
        let blob = match next_json_blob(data, 0) {
            Some((b, _)) => b,
            None => return out,
        };
        let v: serde_json::Value = match serde_json::from_str(&blob) {
            Ok(v) => v,
            Err(_) => return out,
        };
        if v.get("type").and_then(|t| t.as_str()) == Some("error") {
            out.push(ChatEvent::Failed {
                message: format!("provider error: {}", truncate(&v.to_string(), 400)),
            });
            return out;
        }
        let kind = event.to_ascii_lowercase();
        let type_field = v.get("type").and_then(|t| t.as_str()).unwrap_or("").to_string();
        match (kind.as_str(), type_field.as_str()) {
            (_, "error") => { /* already emitted */ }
            ("message_start", _) | ("ping", _) => {}
            ("content_block_start", _) | (_, "content_block_start") => {
                let block = v.get("content_block").cloned().unwrap_or(serde_json::Value::Null);
                if block.get("type").and_then(|t| t.as_str()) == Some("tool_use") {
                    let index = v.get("index").and_then(|i| i.as_u64()).unwrap_or(0) as usize;
                    self.active_tool = Some((
                        index,
                        block.get("id").and_then(|i| i.as_str()).unwrap_or("").to_string(),
                        block.get("name").and_then(|n| n.as_str()).unwrap_or("").to_string(),
                    ));
                    self.args_buf.clear();
                }
            }
            ("content_block_delta", _) | (_, "content_block_delta") => {
                let delta = v.get("delta").cloned().unwrap_or(serde_json::Value::Null);
                match delta.get("type").and_then(|t| t.as_str()) {
                    Some("text_delta") => {
                        if let Some(text) = delta.get("text").and_then(|t| t.as_str()) {
                            if !text.is_empty() {
                                out.push(ChatEvent::TextDelta(text.to_string()));
                            }
                        }
                    }
                    Some("input_json_delta") => {
                        if let Some(pj) = delta.get("partial_json").and_then(|t| t.as_str()) {
                            self.args_buf.push_str(pj);
                        }
                    }
                    _ => {}
                }
            }
            ("content_block_stop", _) | (_, "content_block_stop") => {
                if let Some((index, id, name)) = self.active_tool.take() {
                    let stop_index = v.get("index").and_then(|i| i.as_u64()).unwrap_or(index as u64) as usize;
                    if stop_index == index {
                        out.push(ChatEvent::ToolCall {
                            id: if id.is_empty() { format!("toolu_{name}") } else { id },
                            name,
                            args_json: if self.args_buf.trim().is_empty() { "{}".into() } else { std::mem::take(&mut self.args_buf) },
                        });
                    }
                }
            }
            ("message_delta", _) | (_, "message_delta") => {
                if let Some(reason) = v
                    .get("delta")
                    .and_then(|d| d.get("stop_reason"))
                    .and_then(|r| r.as_str())
                {
                    out.push(ChatEvent::Completed { finish_reason: reason.to_string() });
                }
            }
            ("message_stop", _) | (_, "message_stop") => {}
            _ => {}
        }
        out
    }
}

pub struct AnthropicTransport;

impl ChatTransport for AnthropicTransport {
    fn stream_chat<'a>(
        &'a self,
        cfg: &'a ProviderConfig,
        messages: &'a [ChatMessage],
        tools: &'a [ToolSpec],
        system: &'a str,
    ) -> futures_util::stream::BoxStream<'a, ChatEvent> {
        let url = with_v1(&cfg.base_url, "/messages");
        let body = build_body(cfg, messages, tools, system);
        let mut headers = vec![
            ("x-api-key".to_string(), cfg.api_key.clone()),
            ("anthropic-version".to_string(), ANTHROPIC_VERSION.to_string()),
        ];
        for (k, v) in &cfg.extra_headers {
            headers.push((k.clone(), v.clone()));
        }
        let mut acc = AnthropicAccumulator::default();
        Box::pin(http_sse_events(url, headers, body, move |event, data| acc.feed(event, data)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn body_converts_tool_results_to_user_tool_result_blocks() {
        let cfg = ProviderConfig {
            name: "t".into(),
            kind: crate::config::ProviderKind::Anthropic,
            base_url: "https://api.anthropic.com".into(),
            api_key: "k".into(),
            model: "claude-x".into(),
            temperature: None,
            max_tokens: Some(4096),
            extra_headers: Default::default(),
        };
        let msgs = vec![
            ChatMessage::user("hi"),
            ChatMessage {
                role: Role::Assistant,
                content: Some("let me look".into()),
                tool_calls: vec![crate::chat::ToolCallReq {
                    id: "tu_1".into(),
                    name: "grep".into(),
                    args_json: "{\"pattern\":\"x\"}".into(),
                }],
                tool_call_id: None,
                name: None,
            },
            ChatMessage::tool_result("tu_1", "grep", "match"),
        ];
        let body = build_body(&cfg, &msgs, &[], "sys");
        assert_eq!(body["system"], "sys");
        assert_eq!(body["messages"][0]["role"], "user");
        assert_eq!(body["messages"][1]["content"][1]["type"], "tool_use");
        assert_eq!(body["messages"][2]["role"], "user");
        assert_eq!(body["messages"][2]["content"][0]["type"], "tool_result");
        assert_eq!(body["messages"][2]["content"][0]["tool_use_id"], "tu_1");
    }

    #[test]
    fn accumulator_full_stream() {
        let mut acc = AnthropicAccumulator::default();
        let mut out = vec![];
        out.extend(acc.feed("message_start", r#"data: {"type":"message_start"}"#));
        out.extend(acc.feed("content_block_start", r#"data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}"#));
        out.extend(acc.feed("content_block_delta", r#"data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}"#));
        out.extend(acc.feed("content_block_stop", r#"data: {"type":"content_block_stop","index":0}"#));
        out.extend(acc.feed("content_block_start", r#"data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_9","name":"edit_file"}}"#));
        out.extend(acc.feed("content_block_delta", r#"data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"pa"}}"#));
        out.extend(acc.feed("content_block_delta", r#"data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"th\":\"a.rb\"}"}}"#));
        out.extend(acc.feed("content_block_stop", r#"data: {"type":"content_block_stop","index":1}"#));
        out.extend(acc.feed("message_delta", r#"data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}"#));
        let text: String = out.iter().filter_map(|e| match e { ChatEvent::TextDelta(t) => Some(t.as_str()), _ => None }).collect();
        assert_eq!(text, "Hi");
        let tools: Vec<_> = out.iter().filter_map(|e| match e {
            ChatEvent::ToolCall { id, name, args_json } => Some((id.clone(), name.clone(), args_json.clone())), _ => None,
        }).collect();
        assert_eq!(tools, vec![("tu_9".to_string(), "edit_file".to_string(), r#"{"path":"a.rb"}"#.to_string())]);
        assert!(matches!(out.last(), Some(ChatEvent::Completed { finish_reason }) if finish_reason == "tool_use"));
    }
}
