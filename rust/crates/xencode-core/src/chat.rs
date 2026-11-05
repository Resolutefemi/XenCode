//! Chat transport abstraction: messages, tool specs, events, and the
//! provider-agnostic streaming interface every protocol backend implements.

use crate::config::ProviderConfig;
use futures_util::stream::BoxStream;
use serde::{Deserialize, Serialize};

/// A tool advertised to the model (JSON-schema parameters).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolSpec {
    pub name: String,
    pub description: String,
    /// JSON schema object, e.g. {"type":"object","properties":{...}}.
    pub parameters: serde_json::Value,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    System,
    User,
    Assistant,
    Tool,
}

impl Role {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::System => "system",
            Self::User => "user",
            Self::Assistant => "assistant",
            Self::Tool => "tool",
        }
    }
}

/// A tool call requested by the model.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCallReq {
    pub id: String,
    pub name: String,
    /// Raw JSON arguments string (as produced by the provider).
    pub args_json: String,
}

/// Provider-neutral conversation message.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: Role,
    #[serde(default)]
    pub content: Option<String>,
    #[serde(default)]
    pub tool_calls: Vec<ToolCallReq>,
    /// For role=tool: which call this result answers.
    #[serde(default)]
    pub tool_call_id: Option<String>,
    /// For role=tool: tool name (some providers require it).
    #[serde(default)]
    pub name: Option<String>,
}

impl ChatMessage {
    pub fn user(text: impl Into<String>) -> Self {
        Self { role: Role::User, content: Some(text.into()), tool_calls: vec![], tool_call_id: None, name: None }
    }
    pub fn assistant(text: impl Into<String>) -> Self {
        Self { role: Role::Assistant, content: Some(text.into()), tool_calls: vec![], tool_call_id: None, name: None }
    }
    pub fn tool_result(call_id: impl Into<String>, tool_name: impl Into<String>, text: impl Into<String>) -> Self {
        Self {
            role: Role::Tool,
            content: Some(text.into()),
            tool_calls: vec![],
            tool_call_id: Some(call_id.into()),
            name: Some(tool_name.into()),
        }
    }
}

/// Streaming chat event — mirrors the Kotlin `ChatEvent` vocabulary.
#[derive(Debug, Clone, PartialEq)]
pub enum ChatEvent {
    TextDelta(String),
    ToolCall { id: String, name: String, args_json: String },
    /// Stream finished. `input_tokens`/`output_tokens` carry the provider's
    /// reported usage when the stream included it (0 when it did not).
    Completed { finish_reason: String, input_tokens: i64, output_tokens: i64 },
    Failed { message: String },
}

/// Provider-agnostic streaming chat transport.
pub trait ChatTransport: Send + Sync {
    fn stream_chat<'a>(
        &'a self,
        cfg: &'a ProviderConfig,
        messages: &'a [ChatMessage],
        tools: &'a [ToolSpec],
        system: &'a str,
    ) -> BoxStream<'a, ChatEvent>;
}

/// URL rules (twin of the Kotlin ChatClient):
/// openai/anthropic append `/v1` unless the base already ends with `/v1`;
/// gemini appends `/v1beta` unless the base already ends with `/v1beta`.
pub fn with_v1(base: &str, path: &str) -> String {
    let base = base.trim_end_matches('/');
    if base.ends_with("/v1") {
        format!("{base}{path}")
    } else {
        format!("{base}/v1{path}")
    }
}

pub fn with_v1beta(base: &str) -> String {
    let base = base.trim_end_matches('/');
    if base.ends_with("/v1beta") {
        base.to_string()
    } else {
        format!("{base}/v1beta")
    }
}

/// Extract the first JSON object/array substring from `s` starting at `from`.
pub(crate) fn next_json_blob(s: &str, from: usize) -> Option<(String, usize)> {
    let bytes = s.as_bytes();
    let mut i = from;
    while i < bytes.len() {
        match bytes[i] {
            b'{' | b'[' => break,
            _ => i += 1,
        }
    }
    if i >= bytes.len() {
        return None;
    }
    let open = bytes[i];
    let close = if open == b'{' { b'}' } else { b']' };
    let mut depth = 0usize;
    let mut in_str = false;
    let mut esc = false;
    let start = i;
    while i < bytes.len() {
        let b = bytes[i];
        if in_str {
            if esc {
                esc = false;
            } else if b == b'\\' {
                esc = true;
            } else if b == b'"' {
                in_str = false;
            }
        } else {
            match b {
                b'"' => in_str = true,
                b if b == open => depth += 1,
                b if b == close => {
                    depth -= 1;
                    if depth == 0 {
                        return Some((s[start..=i].to_string(), i + 1));
                    }
                }
                _ => {}
            }
        }
        i += 1;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_rules_match_kotlin() {
        assert_eq!(with_v1("https://api.x.com", "/chat/completions"), "https://api.x.com/v1/chat/completions");
        assert_eq!(with_v1("https://api.x.com/v1/", "/chat/completions"), "https://api.x.com/v1/chat/completions");
        assert_eq!(with_v1beta("https://g/x"), "https://g/x/v1beta");
        assert_eq!(with_v1beta("https://g/x/v1beta"), "https://g/x/v1beta");
    }

    #[test]
    fn json_blob_extraction_handles_strings_and_nesting() {
        let s = r#"prefix {"a":"}{","b":{"c":[1,2]}} suffix"#;
        let (blob, next) = next_json_blob(s, 0).unwrap();
        let v: serde_json::Value = serde_json::from_str(&blob).unwrap();
        assert_eq!(v["b"]["c"][1], 2);
        assert!(next <= s.len());
        assert!(next_json_blob("no json here", 0).is_none());
    }
}
