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

/// A user-attached image, carried by path (the file is read when the wire
/// body is built, so history snapshots stay small and re-runnable).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ImagePart {
    /// Absolute or workspace-relative path of the image file.
    pub path: String,
    /// MIME type sent to the provider (image/png, image/jpeg, image/gif,
    /// image/webp — sniffed from the extension in [`ImagePart::from_path`]).
    pub mime: String,
}

impl ImagePart {
    /// Builds a part from a file path, sniffing the MIME type from the
    /// extension. Does not touch the disk; missing files surface later as a
    /// clear error when the body is built.
    pub fn from_path(path: impl Into<String>) -> Self {
        let path = path.into();
        let ext = path.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
        let mime = match ext.as_str() {
            "png" => "image/png",
            "jpg" | "jpeg" => "image/jpeg",
            "gif" => "image/gif",
            "webp" => "image/webp",
            "bmp" => "image/bmp",
            _ => "application/octet-stream",
        };
        Self { path, mime: mime.to_string() }
    }

    /// Reads the file and renders it as a `data:<mime>;base64,…` URL.
    /// Returns None (with the reason in the string) when the file is missing,
    /// too large (> 8 MiB) or unreadable — callers surface that to the model
    /// instead of failing the whole turn.
    pub fn data_url(&self) -> Result<String, String> {
        const MAX_BYTES: u64 = 8 * 1024 * 1024;
        let meta = std::fs::metadata(&self.path).map_err(|e| format!("image `{}`: {e}", self.path))?;
        if meta.len() > MAX_BYTES {
            return Err(format!("image `{}` is {} MiB (max 8 MiB)", self.path, meta.len() / (1024 * 1024)));
        }
        let bytes = std::fs::read(&self.path).map_err(|e| format!("image `{}`: {e}", self.path))?;
        use base64::Engine as _;
        let b64 = base64::engine::general_purpose::STANDARD.encode(bytes);
        Ok(format!("data:{};base64,{}", self.mime, b64))
    }
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
    /// User-attached images on this turn (role=user only). Older serialized
    /// histories predate the field; `#[serde(default)]` keeps them loadable.
    #[serde(default)]
    pub images: Vec<ImagePart>,
}

impl ChatMessage {
    pub fn user(text: impl Into<String>) -> Self {
        Self { role: Role::User, content: Some(text.into()), tool_calls: vec![], tool_call_id: None, name: None, images: vec![] }
    }
    pub fn user_with_images(text: impl Into<String>, images: Vec<ImagePart>) -> Self {
        Self { role: Role::User, content: Some(text.into()), tool_calls: vec![], tool_call_id: None, name: None, images }
    }
    pub fn assistant(text: impl Into<String>) -> Self {
        Self { role: Role::Assistant, content: Some(text.into()), tool_calls: vec![], tool_call_id: None, name: None, images: vec![] }
    }
    pub fn tool_result(call_id: impl Into<String>, tool_name: impl Into<String>, text: impl Into<String>) -> Self {
        Self {
            role: Role::Tool,
            content: Some(text.into()),
            tool_calls: vec![],
            tool_call_id: Some(call_id.into()),
            name: Some(tool_name.into()),
            images: vec![],
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

    #[test]
    fn image_parts_sniff_mime_and_build_data_urls() {
        assert_eq!(ImagePart::from_path("a.PNG").mime, "image/png");
        assert_eq!(ImagePart::from_path("b.jpeg").mime, "image/jpeg");
        assert_eq!(ImagePart::from_path("c.webp").mime, "image/webp");
        assert_eq!(ImagePart::from_path("noext").mime, "application/octet-stream");

        let tmp = tempfile::tempdir().unwrap();
        let f = tmp.path().join("tiny.png");
        std::fs::write(&f, b"\x89PNG\r\n\x1a\nnot-really").unwrap();
        let part = ImagePart::from_path(f.display().to_string());
        let url = part.data_url().unwrap();
        assert!(url.starts_with("data:image/png;base64,"));

        let missing = ImagePart::from_path(tmp.path().join("gone.png").display().to_string());
        assert!(missing.data_url().is_err());
    }

    #[test]
    fn old_serialized_histories_still_deserialize_without_images() {
        let legacy = r#"[{"role":"user","content":"hi"},{"role":"assistant","content":"yo"}]"#;
        let msgs: Vec<ChatMessage> = serde_json::from_str(legacy).unwrap();
        assert_eq!(msgs.len(), 2);
        assert!(msgs[0].images.is_empty());
        let round = serde_json::to_string(&ChatMessage::user_with_images("look", vec![ImagePart::from_path("x.png")])).unwrap();
        assert!(round.contains("\"images\":"));
        assert!(round.contains("\"path\":\"x.png\""));
    }
}
