//! OpenAI chat-completions protocol (also works with every OpenAI-compatible
//! endpoint: vLLM, Ollama /v1, OpenRouter, DeepSeek, Groq, ...).

use super::{http_sse_events, truncate};
use crate::chat::{next_json_blob, with_v1, ChatEvent, ChatMessage, ChatTransport, Role, ToolSpec};
use crate::config::ProviderConfig;

/// Renders one user message as an OpenAI `content` value: a plain string when
/// there are no images, otherwise the multimodal parts array
/// (`[{type:"text"},{type:"image_url",image_url:{url:"data:…"}}]`).
fn user_content(m: &ChatMessage) -> serde_json::Value {
    if m.images.is_empty() {
        return serde_json::json!(m.content.clone().unwrap_or_default());
    }
    let mut parts: Vec<serde_json::Value> = vec![serde_json::json!({
        "type": "text",
        "text": m.content.clone().unwrap_or_default(),
    })];
    for img in &m.images {
        match img.data_url() {
            Ok(url) => parts.push(serde_json::json!({
                "type": "image_url",
                "image_url": { "url": url },
            })),
            Err(reason) => parts.push(serde_json::json!({
                "type": "text",
                "text": format!("(attachment unavailable: {reason})"),
            })),
        }
    }
    serde_json::Value::Array(parts)
}

pub fn build_body(
    cfg: &ProviderConfig,
    messages: &[ChatMessage],
    tools: &[ToolSpec],
    system: &str,
) -> serde_json::Value {
    let mut msgs: Vec<serde_json::Value> = vec![];
    if !system.trim().is_empty() {
        msgs.push(serde_json::json!({"role": "system", "content": system}));
    }
    for m in messages {
        match m.role {
            Role::System | Role::Assistant => {
                let mut v = serde_json::json!({"role": m.role.as_str(), "content": m.content.clone().unwrap_or_default()});
                if m.role == Role::Assistant && !m.tool_calls.is_empty() {
                    v["tool_calls"] = serde_json::Value::Array(
                        m.tool_calls
                            .iter()
                            .map(|tc| {
                                serde_json::json!({
                                    "id": tc.id, "type": "function",
                                    "function": {"name": tc.name, "arguments": tc.args_json}
                                })
                            })
                            .collect(),
                    );
                    if m.content.as_deref().unwrap_or("").is_empty() {
                        v["content"] = serde_json::Value::Null;
                    }
                }
                msgs.push(v);
            }
            Role::User => {
                msgs.push(serde_json::json!({"role": "user", "content": user_content(m)}));
            }
            Role::Tool => {
                msgs.push(serde_json::json!({
                    "role": "tool",
                    "tool_call_id": m.tool_call_id.clone().unwrap_or_default(),
                    "content": m.content.clone().unwrap_or_default(),
                }));
            }
        }
    }
    let mut body = serde_json::json!({ "model": cfg.model, "messages": msgs, "stream": true });
    // OpenAI-documented opt-in: the final chunk then carries the usage object.
    // Gateways that predate the field ignore it (usage stays 0/0).
    body["stream_options"] = serde_json::json!({ "include_usage": true });
    if let Some(t) = cfg.temperature {
        body["temperature"] = serde_json::json!(t);
    }
    if let Some(mt) = cfg.max_tokens {
        body["max_tokens"] = serde_json::json!(mt);
    }
    if !tools.is_empty() {
        body["tools"] = serde_json::Value::Array(
            tools
                .iter()
                .map(|t| {
                    serde_json::json!({
                        "type": "function",
                        "function": {"name": t.name, "description": t.description, "parameters": t.parameters}
                    })
                })
                .collect(),
        );
    }
    body
}

/// Accumulates streaming deltas into ChatEvents. Pure — unit-testable.
#[derive(Default)]
pub struct OpenAiAccumulator {
    tools: std::collections::BTreeMap<u64, (String, String, String)>, // idx -> (id, name, args)
    input_tokens: i64,
    output_tokens: i64,
    completed_seen: bool,
    /// True once a `usage` object has been delivered as a supplementary
    /// Completed event (guards against gateways that repeat usage per chunk).
    usage_emitted: bool,
}

impl OpenAiAccumulator {
    pub fn feed(&mut self, data: &str) -> Vec<ChatEvent> {
        let mut out = vec![];
        let data = data.trim();
        if data.is_empty() || data == "[DONE]" {
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
        if let Some(err) = v.get("error") {
            out.push(ChatEvent::Failed {
                message: format!("provider error: {}", truncate(&err.to_string(), 400)),
            });
            return out;
        }
        if let Some(choices) = v.get("choices").and_then(|c| c.as_array()) {
            for choice in choices {
                if let Some(delta) = choice.get("delta") {
                    if let Some(text) = delta.get("content").and_then(|c| c.as_str()) {
                        if !text.is_empty() {
                            out.push(ChatEvent::TextDelta(text.to_string()));
                        }
                    }
                    if let Some(tcs) = delta.get("tool_calls").and_then(|t| t.as_array()) {
                        for tc in tcs {
                            let idx = tc.get("index").and_then(|i| i.as_u64()).unwrap_or(0);
                            let entry = self.tools.entry(idx).or_insert_with(|| (String::new(), String::new(), String::new()));
                            if let Some(id) = tc.get("id").and_then(|i| i.as_str()) {
                                entry.0 = id.to_string();
                            }
                            if let Some(fname) = tc.get("function").and_then(|f| f.get("name")).and_then(|n| n.as_str()) {
                                if !fname.is_empty() {
                                    entry.1 = fname.to_string();
                                }
                            }
                            if let Some(args) = tc.get("function").and_then(|f| f.get("arguments")).and_then(|a| a.as_str()) {
                                entry.2.push_str(args);
                            }
                        }
                    }
                }
                if let Some(reason) = choice.get("finish_reason").and_then(|f| f.as_str()) {
                    if !reason.is_empty() {
                        let reason = reason.to_string();
                        for (_, (id, name, args)) in std::mem::take(&mut self.tools) {
                            out.push(ChatEvent::ToolCall {
                                id: if id.is_empty() { format!("call_{idx}", idx = name) } else { id },
                                name,
                                args_json: if args.trim().is_empty() { "{}".into() } else { args },
                            });
                        }
                        self.completed_seen = true;
                        out.push(ChatEvent::Completed { finish_reason: reason, input_tokens: self.input_tokens, output_tokens: self.output_tokens });
                    }
                }
            }
        }
        // `stream_options.include_usage` makes the final chunk carry a usage
        // object with no choices at all: flush it as one supplementary
        // Completed so the agent's summed usage picks the tokens up.
        if let Some(usage) = v.get("usage").and_then(|u| u.as_object()) {
            let prompt = usage.get("prompt_tokens").and_then(|x| x.as_i64()).unwrap_or(0);
            let completion = usage.get("completion_tokens").and_then(|x| x.as_i64()).unwrap_or(0);
            if (prompt > 0 || completion > 0) && !self.usage_emitted {
                self.usage_emitted = true;
                self.input_tokens = prompt;
                self.output_tokens = completion;
                out.push(ChatEvent::Completed {
                    finish_reason: "usage".into(),
                    input_tokens: prompt,
                    output_tokens: completion,
                });
            }
        }
        out
    }
}

pub struct OpenAiTransport;

impl ChatTransport for OpenAiTransport {
    fn stream_chat<'a>(
        &'a self,
        cfg: &'a ProviderConfig,
        messages: &'a [ChatMessage],
        tools: &'a [ToolSpec],
        system: &'a str,
    ) -> futures_util::stream::BoxStream<'a, ChatEvent> {
        let url = with_v1(&cfg.base_url, "/chat/completions");
        let body = build_body(cfg, messages, tools, system);
        let mut headers = vec![("authorization".to_string(), format!("Bearer {}", cfg.api_key))];
        for (k, v) in &cfg.extra_headers {
            headers.push((k.clone(), v.clone()));
        }
        let mut acc = OpenAiAccumulator::default();
        Box::pin(http_sse_events(url, headers, body, move |_event, data| acc.feed(data)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chat::ImagePart;

    fn cfg() -> ProviderConfig {
        ProviderConfig {
            name: "t".into(),
            kind: crate::config::ProviderKind::OpenAi,
            base_url: "https://api.openai.com".into(),
            api_key: "k".into(),
            model: "gpt-test".into(),
            temperature: Some(0.1),
            max_tokens: Some(100),
            extra_headers: Default::default(),
        }
    }

    #[test]
    fn body_has_system_tools_and_tool_role() {
        let tools = vec![ToolSpec {
            name: "read_file".into(),
            description: "d".into(),
            parameters: serde_json::json!({"type":"object"}),
        }];
        let msgs = vec![
            ChatMessage::user("hi"),
            ChatMessage::assistant("calling"),
            ChatMessage {
                role: Role::Assistant,
                content: None,
                tool_calls: vec![crate::chat::ToolCallReq {
                    id: "c1".into(),
                    name: "read_file".into(),
                    args_json: "{\"path\":\"a\"}".into(),
                }],
                tool_call_id: None,
                name: None,
            images: vec![],
            },
            ChatMessage::tool_result("c1", "read_file", "data"),
        ];
        let body = build_body(&cfg(), &msgs, &tools, "be nice");
        assert_eq!(body["messages"][0]["role"], "system");
        assert_eq!(body["messages"][4]["role"], "tool");
        assert_eq!(body["messages"][3]["tool_calls"][0]["id"], "c1");
        assert_eq!(body["tools"][0]["function"]["name"], "read_file");
        assert_eq!(body["temperature"], 0.1);
    }

    #[test]
    fn accumulator_streaming_tool_call_fragments() {
        let mut acc = OpenAiAccumulator::default();
        let mut out = vec![];
        out.extend(acc.feed(r#"data: {"choices":[{"delta":{"content":"Hel"}}]}"#));
        out.extend(acc.feed(r#"data: {"choices":[{"delta":{"content":"lo"}}]}"#));
        out.extend(acc.feed(r#"data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"glob","arguments":"{\"pa"}}]}}]}"#));
        out.extend(acc.feed(r#"data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ttern\":\"*.rs\"}"}}]}}]}"#));
        out.extend(acc.feed(r#"data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"#));
        let text: String = out.iter().filter_map(|e| match e { ChatEvent::TextDelta(t) => Some(t.as_str()), _ => None }).collect();
        assert_eq!(text, "Hello");
        let tool: Vec<_> = out.iter().filter_map(|e| match e {
            ChatEvent::ToolCall { id, name, args_json } => Some((id.clone(), name.clone(), args_json.clone())),
            _ => None,
        }).collect();
        assert_eq!(tool.len(), 1);
        assert_eq!(tool[0].0, "call_1");
        assert_eq!(tool[0].1, "glob");
        assert_eq!(tool[0].2, r#"{"pattern":"*.rs"}"#);
        assert!(matches!(out.last(), Some(ChatEvent::Completed { .. })));
    }

    #[test]
    fn body_requests_stream_options_include_usage() {
        let body = build_body(&cfg(), &[], &[], "sys");
        assert_eq!(body["stream_options"]["include_usage"], true);
    }

    #[test]
    fn usage_chunk_after_finish_reason_flushes_a_supplementary_completed() {
        let mut acc = OpenAiAccumulator::default();
        let mut out = vec![];
        out.extend(acc.feed(r#"data: {"choices":[{"delta":{"content":"hey"}}]}"#));
        out.extend(acc.feed(r#"data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"#));
        // Usage arrives in its own final chunk (no choices).
        out.extend(acc.feed(r#"data: {"usage":{"prompt_tokens":111,"completion_tokens":22}}"#));
        out.extend(acc.feed("data: [DONE]"));
        let completed: Vec<_> = out.iter().filter_map(|e| match e {
            ChatEvent::Completed { input_tokens, output_tokens, .. } => Some((*input_tokens, *output_tokens)),
            _ => None,
        }).collect();
        // The finish_reason Completed carries 0/0; the usage chunk flushes 111/22.
        // The agent sums tokens across all Completed events.
        assert_eq!(2, completed.len(), "{completed:?}");
        assert_eq!((111, 22), completed[1]);
    }

    #[test]
    fn usage_missing_defaults_to_zero() {
        let mut acc = OpenAiAccumulator::default();
        let mut out = vec![];
        out.extend(acc.feed(r#"data: {"choices":[{"delta":{"content":"hey"}}]}"#));
        out.extend(acc.feed(r#"data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"#));
        let done = out.iter().filter_map(|e| match e {
            ChatEvent::Completed { input_tokens, output_tokens, .. } => Some((*input_tokens, *output_tokens)),
            _ => None,
        }).collect::<Vec<_>>();
        assert_eq!(1, done.len());
        assert_eq!((0, 0), done[0]);
    }

    #[test]
    fn user_images_become_multimodal_content_parts() {
        let tmp = tempfile::tempdir().unwrap();
        let png = tmp.path().join("shot.png");
        std::fs::write(&png, b"\x89PNG\r\n\x1a\nxx").unwrap();
        let msg = ChatMessage::user_with_images(
            "what is this?",
            vec![ImagePart::from_path(png.display().to_string())],
        );
        let body = build_body(&cfg(), &[msg], &[], "sys");
        let content = &body["messages"][1]["content"];
        assert!(content.is_array(), "content should be a parts array: {content}");
        assert_eq!(content[0]["type"], "text");
        assert_eq!(content[0]["text"], "what is this?");
        assert_eq!(content[1]["type"], "image_url");
        let url = content[1]["image_url"]["url"].as_str().unwrap();
        assert!(url.starts_with("data:image/png;base64,"));

        // A missing file degrades to a text note instead of failing the turn.
        let missing = ChatMessage::user_with_images("x", vec![ImagePart::from_path("gone.png")]);
        let body = build_body(&cfg(), &[missing], &[], "sys");
        assert!(body["messages"][1]["content"][1]["text"]
            .as_str()
            .unwrap()
            .contains("attachment unavailable"));

        // Plain text users stay a plain string (byte-identical to before).
        let body = build_body(&cfg(), &[ChatMessage::user("plain")], &[], "sys");
        assert_eq!(body["messages"][1]["content"], "plain");
    }

    #[test]
    fn accumulator_tolerates_done_and_errors() {
        let mut acc = OpenAiAccumulator::default();
        assert!(acc.feed("[DONE]").is_empty());
        let out = acc.feed(r#"data: {"error":{"message":"boom"}}"#);
        assert!(matches!(out.first(), Some(ChatEvent::Failed { .. })));
    }
}
