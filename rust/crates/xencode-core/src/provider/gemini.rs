//! Gemini native protocol (`models/{model}:streamGenerateContent?alt=sse`).

use super::{http_sse_events, truncate};
use crate::chat::{next_json_blob, with_v1beta, ChatEvent, ChatMessage, ChatTransport, Role, ToolSpec};
use crate::config::ProviderConfig;

/// One user turn as Gemini `parts`: `[{text}, {inline_data:{mime_type,data}}]`.
/// Text-only turns stay `[{text}]`, exactly the pre-image wire shape.
fn user_parts(m: &ChatMessage) -> Vec<serde_json::Value> {
    let mut parts: Vec<serde_json::Value> =
        vec![serde_json::json!({ "text": m.content.clone().unwrap_or_default() })];
    for img in &m.images {
        match img.data_url() {
            Ok(data_url) => {
                let (mime, data) = data_url
                    .strip_prefix("data:")
                    .and_then(|rest| rest.split_once(";base64,"))
                    .map(|(mt, b64)| (mt.to_string(), b64.to_string()))
                    .unwrap_or((img.mime.clone(), String::new()));
                parts.push(serde_json::json!({ "inline_data": { "mime_type": mime, "data": data } }));
            }
            Err(reason) => parts.push(serde_json::json!({
                "text": format!("(attachment unavailable: {reason})"),
            })),
        }
    }
    parts
}

pub fn build_url(cfg: &ProviderConfig) -> String {
    let root = with_v1beta(&cfg.base_url);
    format!("{root}/models/{}:streamGenerateContent?alt=sse", cfg.model)
}

pub fn build_body(
    cfg: &ProviderConfig,
    messages: &[ChatMessage],
    tools: &[ToolSpec],
    system: &str,
) -> serde_json::Value {
    // Gemini: contents alternate user/model; function calls & results are parts.
    let mut contents: Vec<serde_json::Value> = vec![];
    for m in messages {
        match m.role {
            Role::System => { /* via systemInstruction */ }
            Role::User => {
                contents.push(serde_json::json!({"role": "user", "parts": user_parts(m)}));
            }
            Role::Assistant => {
                let mut parts: Vec<serde_json::Value> = vec![];
                if let Some(text) = m.content.as_deref() {
                    if !text.is_empty() {
                        parts.push(serde_json::json!({"text": text}));
                    }
                }
                for tc in &m.tool_calls {
                    let args: serde_json::Value =
                        serde_json::from_str(&tc.args_json).unwrap_or(serde_json::json!({}));
                    parts.push(serde_json::json!({"functionCall": {"name": tc.name, "args": args}}));
                }
                if parts.is_empty() {
                    parts.push(serde_json::json!({"text": ""}));
                }
                contents.push(serde_json::json!({"role": "model", "parts": parts}));
            }
            Role::Tool => {
                // functionResponse needs the function name; we carry it in `name`.
                let resp: serde_json::Value = serde_json::from_str(m.content.as_deref().unwrap_or("{}"))
                    .unwrap_or(serde_json::json!({"output": m.content.clone().unwrap_or_default()}));
                contents.push(serde_json::json!({
                    "role": "user",
                    "parts": [{"functionResponse": {"name": m.name.clone().unwrap_or_default(), "response": resp}}]
                }));
            }
        }
    }
    let mut body = serde_json::json!({ "contents": contents });
    if !system.trim().is_empty() {
        body["systemInstruction"] = serde_json::json!({"parts": [{"text": system}]});
    }
    if let Some(t) = cfg.temperature {
        body["generationConfig"] = serde_json::json!({"temperature": t});
    }
    if !tools.is_empty() {
        body["tools"] = serde_json::json!([{
            "functionDeclarations": tools.iter().map(|t| serde_json::json!({
                "name": t.name, "description": t.description, "parameters": t.parameters
            })).collect::<Vec<_>>()
        }]);
    }
    body
}

/// Carries the last seen usageMetadata across chunks (final chunk wins).
#[derive(Default)]
pub struct GeminiAccumulator {
    input_tokens: i64,
    output_tokens: i64,
}

impl GeminiAccumulator {
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
        if let Some(candidates) = v.get("candidates").and_then(|c| c.as_array()) {
            for cand in candidates {
                if let Some(parts) = cand
                    .get("content")
                    .and_then(|c| c.get("parts"))
                    .and_then(|p| p.as_array())
                {
                    for part in parts {
                        if let Some(text) = part.get("text").and_then(|t| t.as_str()) {
                            if !text.is_empty() {
                                out.push(ChatEvent::TextDelta(text.to_string()));
                            }
                        }
                        if let Some(fc) = part.get("functionCall") {
                            let name = fc.get("name").and_then(|n| n.as_str()).unwrap_or("").to_string();
                            let args = fc.get("args").cloned().unwrap_or(serde_json::json!({}));
                            out.push(ChatEvent::ToolCall {
                                id: format!("gem_{name}"),
                                name,
                                args_json: args.to_string(),
                            });
                        }
                    }
                }
                // usageMetadata rides on every chunk; the last one carries the totals.
                if let Some(u) = v.get("usageMetadata") {
                    if let Some(n) = u.get("promptTokenCount").and_then(|n| n.as_i64()) {
                        self.input_tokens = n;
                    }
                    if let Some(n) = u.get("candidatesTokenCount").and_then(|n| n.as_i64()) {
                        self.output_tokens = n;
                    }
                }
                if let Some(reason) = cand.get("finishReason").and_then(|f| f.as_str()) {
                    if !reason.is_empty() {
                        out.push(ChatEvent::Completed {
                            finish_reason: reason.to_string(),
                            input_tokens: self.input_tokens,
                            output_tokens: self.output_tokens,
                        });
                    }
                }
            }
        }
        out
    }
}

pub struct GeminiTransport;

impl ChatTransport for GeminiTransport {
    fn stream_chat<'a>(
        &'a self,
        cfg: &'a ProviderConfig,
        messages: &'a [ChatMessage],
        tools: &'a [ToolSpec],
        system: &'a str,
    ) -> futures_util::stream::BoxStream<'a, ChatEvent> {
        let url = build_url(cfg);
        let body = build_body(cfg, messages, tools, system);
        let mut headers = vec![("x-goog-api-key".to_string(), cfg.api_key.clone())];
        for (k, v) in &cfg.extra_headers {
            headers.push((k.clone(), v.clone()));
        }
        let mut acc = GeminiAccumulator::default();
        Box::pin(http_sse_events(url, headers, body, move |_event, data| acc.feed(data)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usage_metadata_last_chunk_wins() {
        let mut acc = GeminiAccumulator::default();
        let mut out = vec![];
        out.extend(acc.feed(r#"data: {"candidates":[{"content":{"parts":[{"text":"Hi "}]}},{"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":14}}"#));
        let done = out.iter().filter_map(|e| match e {
            ChatEvent::Completed { input_tokens, output_tokens, .. } => Some((*input_tokens, *output_tokens)),
            _ => None,
        }).collect::<Vec<_>>();
        assert_eq!(1, done.len(), "{done:?}");
        assert_eq!((50, 14), done[0]);
    }

    #[test]
    fn url_and_body_shape() {
        let cfg = ProviderConfig {
            name: "g".into(),
            kind: crate::config::ProviderKind::Gemini,
            base_url: "https://generativelanguage.googleapis.com".into(),
            api_key: "k".into(),
            model: "gemini-2.0-flash".into(),
            temperature: Some(0.3),
            max_tokens: None,
            extra_headers: Default::default(),
        };
        assert_eq!(
            build_url(&cfg),
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse"
        );
        let tools = vec![ToolSpec {
            name: "run_command".into(),
            description: "d".into(),
            parameters: serde_json::json!({"type":"object","properties":{"command":{"type":"string"}}}),
        }];
        let msgs = vec![
            ChatMessage::user("list rust files"),
            ChatMessage {
                role: Role::Assistant,
                content: None,
                tool_calls: vec![crate::chat::ToolCallReq {
                    id: "gem_glob".into(),
                    name: "glob".into(),
                    args_json: "{\"pattern\":\"*.rs\"}".into(),
                }],
                tool_call_id: None,
                name: None,
            images: vec![],
            },
            ChatMessage::tool_result("gem_glob", "glob", "{\"files\":[\"a.rs\"]}"),
        ];
        let body = build_body(&cfg, &msgs, &tools, "be terse");
        assert_eq!(body["systemInstruction"]["parts"][0]["text"], "be terse");
        assert_eq!(body["contents"][0]["role"], "user");
        assert_eq!(body["contents"][1]["role"], "model");
        assert_eq!(body["contents"][1]["parts"][0]["functionCall"]["name"], "glob");
        assert_eq!(body["contents"][2]["parts"][0]["functionResponse"]["name"], "glob");
        assert_eq!(body["tools"][0]["functionDeclarations"][0]["name"], "run_command");
        assert_eq!(body["generationConfig"]["temperature"], 0.3);
    }

    #[test]
    fn accumulator_text_and_function_call() {
        let mut acc = GeminiAccumulator::default();
        let mut out = vec![];
        out.extend(acc.feed(r#"data: {"candidates":[{"content":{"parts":[{"text":"Looking"}],"role":"model"}}]}"#));
        out.extend(acc.feed(r#"data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"glob","args":{"pattern":"*.rs"}}}],"role":"model"}},{"finishReason":"STOP"}]}"#));
        assert!(matches!(&out[0], ChatEvent::TextDelta(t) if t == "Looking"));
        assert!(matches!(&out[1], ChatEvent::ToolCall { name, args_json, .. } if name == "glob" && args_json.contains("*.rs")));
        assert!(matches!(out.last(), Some(ChatEvent::Completed { finish_reason, .. }) if finish_reason == "STOP"));
    }

    #[test]
    fn user_images_become_inline_data_parts() {
        let tmp = tempfile::tempdir().unwrap();
        let jpg = tmp.path().join("shot.jpg");
        std::fs::write(&jpg, b"\xff\xd8\xff\xe0xx").unwrap();
        let cfg = ProviderConfig {
            name: "t".into(),
            kind: crate::config::ProviderKind::Gemini,
            base_url: "https://generativelanguage.googleapis.com".into(),
            api_key: "k".into(),
            model: "gemini-test".into(),
            temperature: Some(0.3),
            max_tokens: None,
            extra_headers: Default::default(),
        };
        let msgs = vec![ChatMessage::user_with_images(
            "describe",
            vec![crate::chat::ImagePart::from_path(jpg.display().to_string())],
        )];
        let body = build_body(&cfg, &msgs, &[], "");
        let parts = &body["contents"][0]["parts"];
        assert_eq!(parts[0]["text"], "describe");
        assert_eq!(parts[1]["inline_data"]["mime_type"], "image/jpeg");
        assert!(!parts[1]["inline_data"]["data"].as_str().unwrap().is_empty());

        let body = build_body(&cfg, &[ChatMessage::user("plain")], &[], "");
        assert_eq!(body["contents"][0]["parts"].as_array().unwrap().len(), 1);
        assert_eq!(body["contents"][0]["parts"][0]["text"], "plain");
    }
}
