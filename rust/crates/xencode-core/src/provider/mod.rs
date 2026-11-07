//! Provider protocol backends. Each module exposes:
//! 1. `build_request(cfg, messages, tools, system) -> Result<Request>` — pure, testable
//! 2. An SSE accumulator that turns `data:` payloads into [`ChatEvent`]s — pure, testable
//! 3. A `ChatTransport` implementation that wires them to reqwest's byte stream.

pub mod anthropic;
pub mod gemini;
pub mod openai;

use crate::chat::{ChatEvent, ChatTransport};
use crate::config::ProviderKind;
/// SSE frame: (event_name, data_payload).
pub(crate) fn parse_sse_frame(frame: &str) -> (Option<String>, String) {
    let mut event = None;
    let mut data_lines: Vec<&str> = vec![];
    for line in frame.lines() {
        if let Some(rest) = line.strip_prefix("event:") {
            event = Some(rest.trim().to_string());
        } else if let Some(rest) = line.strip_prefix("data:") {
            data_lines.push(rest.strip_prefix(' ').unwrap_or(rest));
        }
    }
    (event, data_lines.join("\n"))
}

/// Split a raw SSE byte chunk into complete frames on blank lines, keeping the
/// remainder in `buffer`. Returns the complete frames in order.
pub(crate) fn extract_sse_frames(buffer: &mut String, chunk: &str) -> Vec<String> {
    buffer.push_str(chunk);
    let mut frames = vec![];
    while let Some(pos) = buffer.find("\n\n") {
        let frame = buffer[..pos].to_string();
        *buffer = buffer[pos + 2..].to_string();
        if !frame.trim().is_empty() {
            frames.push(frame);
        }
    }
    // Also tolerate \r\n\r\n separators.
    while let Some(pos) = buffer.find("\r\n\r\n") {
        let frame = buffer[..pos].replace("\r\n", "\n");
        *buffer = buffer[pos + 4..].to_string();
        if !frame.trim().is_empty() {
            frames.push(frame);
        }
    }
    frames
}

/// Pick the transport for a provider kind.
pub fn transport_for(kind: ProviderKind) -> Box<dyn ChatTransport> {
    match kind {
        ProviderKind::OpenAi => Box::new(openai::OpenAiTransport),
        ProviderKind::Anthropic => Box::new(anthropic::AnthropicTransport),
        ProviderKind::Gemini => Box::new(gemini::GeminiTransport),
    }
}

/// Shared HTTP-SSE plumbing: POST and yield ChatEvents from the byte stream.
pub(crate) fn http_sse_events(
    url: String,
    headers: Vec<(String, String)>,
    body: serde_json::Value,
    mut on_frame: impl FnMut(&str, &str) -> Vec<ChatEvent> + Send + 'static,
) -> futures_util::stream::BoxStream<'static, ChatEvent> {
    let client = reqwest::Client::new();
    let stream = async_stream::stream! {
        let mut req = client.post(&url).json(&body);
        for (k, v) in headers {
            req = req.header(k, v);
        }
        let resp = match req.timeout(std::time::Duration::from_secs(300)).send().await {
            Ok(r) => r,
            Err(e) => {
                yield ChatEvent::Failed { message: format!("HTTP error: {e}") };
                return;
            }
        };
        if !resp.status().is_success() {
            let status = resp.status();
            let body_text = resp.text().await.unwrap_or_default();
            yield ChatEvent::Failed { message: format!("HTTP {status}: {}", truncate(&body_text, 600)) };
            return;
        }
        let mut buffer = String::new();
        let mut byte_stream = resp.bytes_stream();
        use futures_util::StreamExt;
        while let Some(chunk) = byte_stream.next().await {
            let chunk = match chunk {
                Ok(c) => c,
                Err(e) => {
                    yield ChatEvent::Failed { message: format!("stream error: {e}") };
                    return;
                }
            };
            let text = String::from_utf8_lossy(&chunk).to_string();
            for frame in extract_sse_frames(&mut buffer, &text) {
                let (event, data) = parse_sse_frame(&frame);
                for ev in on_frame(event.as_deref().unwrap_or(""), &data) {
                    yield ev;
                }
            }
        }
    };
    Box::pin(stream)
}

pub(crate) fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        s.to_string()
    } else {
        let mut cut = max;
        while !s.is_char_boundary(cut) {
            cut -= 1;
        }
        format!("{}… [{} chars truncated]", &s[..cut], s.len() - cut)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sse_frames_split_and_parse() {
        let mut buf = String::new();
        let frames = extract_sse_frames(&mut buf, "event: add\ndata: {\"a\":1}\n\ndata: [1,2]\n\ndata: par");
        assert_eq!(frames.len(), 2);
        // `data: par` is an incomplete line still buffered.
        assert_eq!(buf, "data: par");
        let more = extract_sse_frames(&mut buf, "tial\n\n");
        assert_eq!(more.len(), 1);
        assert_eq!(parse_sse_frame(&more[0]).1, "partial");
        let (ev, data) = parse_sse_frame(&frames[0]);
        assert_eq!(ev.as_deref(), Some("add"));
        assert_eq!(data, "{\"a\":1}");
        let (_, data2) = parse_sse_frame(&frames[1]);
        assert_eq!(data2, "[1,2]");
    }

    #[test]
    fn truncate_is_char_boundary_safe() {
        let s = "你好世界".repeat(100);
        let t = truncate(&s, 7);
        assert!(t.ends_with("chars truncated]"));
    }
}
