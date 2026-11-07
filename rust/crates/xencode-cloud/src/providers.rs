//! Upstream provider adapters.
//!
//! Every adapter normalizes to one stream type: `GatewayEvent`s. Master keys
//! live in server env vars only; the desktop client never sees them.

use crate::routing::{Provider, ProviderTarget};
use crate::state::AppState;
use futures::Stream;
use std::pin::Pin;
use std::time::Duration;

/// Events emitted by every provider adapter.
#[derive(Clone, Debug)]
pub enum GatewayEvent {
    Delta(String),
    Usage { input: i64, output: i64 },
    Done,
}

pub type GatewayStream = Pin<Box<dyn Stream<Item = Result<GatewayEvent, String>> + Send>>;

/// The normalized request sent upstream.
pub struct UpstreamRequest {
    pub messages: Vec<crate::gateway::ChatMessage>,
    pub temperature: Option<f64>,
    pub max_tokens: Option<i64>,
    pub stream: bool,
}

/// Open the upstream stream for a resolved target. Keys are read from the
/// server config here and never leave this function.
pub async fn open_stream(
    state: &AppState,
    target: &ProviderTarget,
    req: UpstreamRequest,
) -> Result<GatewayStream, String> {
    if target.provider == Provider::Mock || state.cfg.use_mock_llm() {
        return Ok(mock_stream(&req));
    }

    let http = state.http.clone();
    let cfg = state.cfg.clone();
    let target = target.clone();

    match target.provider {
        Provider::Anthropic => {
            anthropic_stream(
                http,
                &cfg.anthropic_base,
                cfg.anthropic_key.clone(),
                &target.model,
                req,
            )
            .await
        }
        Provider::OpenAi => {
            openai_stream(
                http,
                &cfg.openai_base,
                cfg.openai_key.clone(),
                &target.model,
                req,
                None,
            )
            .await
        }
        Provider::OpenRouter => {
            openai_stream(
                http,
                &cfg.openrouter_base,
                cfg.openrouter_key.clone(),
                &target.model,
                req,
                Some(("HTTP-Referer", "https://xencode.app")),
            )
            .await
        }
        Provider::Mock => Ok(mock_stream(&req)),
    }
}

async fn openai_stream(
    http: reqwest::Client,
    base: &str,
    key: Option<String>,
    model: &str,
    req: UpstreamRequest,
    extra_header: Option<(&'static str, &'static str)>,
) -> Result<GatewayStream, String> {
    let key = key.ok_or_else(|| "provider key not configured".to_string())?;
    let mut body = serde_json::json!({
        "model": model,
        "messages": req.messages,
        "stream": true,
        "stream_options": { "include_usage": true },
    });
    if let Some(t) = req.temperature {
        body["temperature"] = serde_json::json!(t);
    }
    if let Some(m) = req.max_tokens {
        body["max_tokens"] = serde_json::json!(m);
    }
    let mut builder = http
        .post(format!(
            "{}/v1/chat/completions",
            base.trim_end_matches('/')
        ))
        .timeout(Duration::from_secs(300))
        .bearer_auth(key)
        .json(&body);
    if let Some((name, value)) = extra_header {
        builder = builder.header(name, value);
    }
    let resp = builder.send().await.map_err(|e| e.to_string())?;
    let status = resp.status();
    if !status.is_success() {
        let text = resp.text().await.unwrap_or_default();
        return Err(format!("upstream {status}: {text}"));
    }

    // Parse OpenAI SSE into GatewayEvents.
    let stream = sse_line_stream(resp, move |data| -> Vec<GatewayEvent> {
        let mut out = Vec::new();
        if data.trim() == "[DONE]" {
            out.push(GatewayEvent::Done);
            return out;
        }
        let Ok(v) = serde_json::from_str::<serde_json::Value>(data) else {
            return out;
        };
        if let Some(u) = v.get("usage").and_then(|u| u.as_object()) {
            let input = u.get("prompt_tokens").and_then(|x| x.as_i64()).unwrap_or(0);
            let output = u
                .get("completion_tokens")
                .and_then(|x| x.as_i64())
                .unwrap_or(0);
            if input > 0 || output > 0 {
                out.push(GatewayEvent::Usage { input, output });
            }
        }
        if let Some(delta) = v["choices"][0]["delta"]["content"].as_str() {
            if !delta.is_empty() {
                out.push(GatewayEvent::Delta(delta.to_string()));
            }
        }
        out
    });
    Ok(stream)
}

async fn anthropic_stream(
    http: reqwest::Client,
    base: &str,
    key: Option<String>,
    model: &str,
    req: UpstreamRequest,
) -> Result<GatewayStream, String> {
    let key = key.ok_or_else(|| "provider key not configured".to_string())?;
    // Convert the OpenAI-shaped messages into Anthropic Messages format:
    // system messages are lifted out, the rest map 1:1.
    let mut system = String::new();
    let mut messages = Vec::new();
    for m in &req.messages {
        match m.role.as_str() {
            "system" => {
                if !system.is_empty() {
                    system.push('\n');
                }
                system.push_str(&m.content);
            }
            other => messages.push(serde_json::json!({ "role": other, "content": m.content })),
        }
    }
    let mut body = serde_json::json!({
        "model": model,
        "messages": messages,
        "stream": true,
        "max_tokens": req.max_tokens.unwrap_or(4096),
    });
    if !system.is_empty() {
        body["system"] = serde_json::json!(system);
    }
    if let Some(t) = req.temperature {
        body["temperature"] = serde_json::json!(t);
    }
    let resp = http
        .post(format!("{}/v1/messages", base.trim_end_matches('/')))
        .timeout(Duration::from_secs(300))
        .header("x-api-key", &key)
        .header("anthropic-version", "2023-06-01")
        .json(&body)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let status = resp.status();
    if !status.is_success() {
        let text = resp.text().await.unwrap_or_default();
        return Err(format!("upstream {status}: {text}"));
    }

    // Anthropic events: message_start carries input usage,
    // content_block_delta carries text, message_delta carries output usage.
    let stream = sse_line_stream(resp, move |data| -> Vec<GatewayEvent> {
        let mut out = Vec::new();
        let Ok(v) = serde_json::from_str::<serde_json::Value>(data) else {
            return out;
        };
        let event_type = v["type"].as_str().unwrap_or_default();
        match event_type {
            "message_start" => {
                let input = v["message"]["usage"]["input_tokens"].as_i64().unwrap_or(0);
                out.push(GatewayEvent::Usage { input, output: 0 });
            }
            "content_block_delta" => {
                if let Some(delta) = v["delta"]["text"].as_str() {
                    if !delta.is_empty() {
                        out.push(GatewayEvent::Delta(delta.to_string()));
                    }
                }
            }
            "message_delta" => {
                let output = v["usage"]["output_tokens"].as_i64().unwrap_or(0);
                out.push(GatewayEvent::Usage { input: 0, output });
                out.push(GatewayEvent::Done);
            }
            "error" => {
                let msg = v["error"]["message"].as_str().unwrap_or("upstream error");
                out.push(GatewayEvent::Delta(format!("\n\n[upstream error: {msg}]")));
                out.push(GatewayEvent::Done);
            }
            _ => {}
        }
        out
    });
    Ok(stream)
}

/// Shared SSE parser: chunks -> `data:` lines -> typed events.
///
/// A queue-based state machine: each unfold step drains queued events first,
/// then parses buffered lines, then pulls the next HTTP chunk.
fn sse_line_stream<F>(resp: reqwest::Response, parse: F) -> GatewayStream
where
    F: Fn(&str) -> Vec<GatewayEvent> + Send + 'static,
{
    struct SseState<F> {
        resp: reqwest::Response,
        buf: String,
        queue: std::collections::VecDeque<Result<GatewayEvent, String>>,
        done: bool,
        parse: F,
    }
    let state = SseState {
        resp,
        buf: String::new(),
        queue: std::collections::VecDeque::new(),
        done: false,
        parse,
    };
    Box::pin(futures::stream::unfold(state, |mut s| async move {
        loop {
            if let Some(ev) = s.queue.pop_front() {
                return Some((ev, s));
            }
            if s.done {
                return None;
            }
            if let Some(pos) = s.buf.find('\n') {
                let line: String = s.buf.drain(..=pos).collect();
                let line = line.trim_end();
                if let Some(data) = line.strip_prefix("data: ") {
                    for ev in (s.parse)(data) {
                        s.queue.push_back(Ok(ev));
                    }
                }
                continue;
            }
            match s.resp.chunk().await {
                Ok(Some(chunk)) => {
                    s.buf.push_str(&String::from_utf8_lossy(&chunk));
                }
                Ok(None) => {
                    s.done = true;
                    s.queue.push_back(Ok(GatewayEvent::Done));
                }
                Err(e) => {
                    s.done = true;
                    s.queue.push_back(Err(e.to_string()));
                    s.queue.push_back(Ok(GatewayEvent::Done));
                }
            }
        }
    }))
}

/// The mock provider: a canned markdown reply streamed word by word, with
/// honest usage estimates. Keeps the whole pipeline demoable without keys.
fn mock_stream(req: &UpstreamRequest) -> GatewayStream {
    let last_user = req
        .messages
        .iter()
        .rev()
        .find(|m| m.role == "user")
        .map(|m| m.content.clone())
        .unwrap_or_default();
    let input_est = crate::store::estimate_tokens(
        &req.messages
            .iter()
            .map(|m| m.content.clone())
            .collect::<String>(),
    );
    let reply = format!(
        "Streaming through **XenCloud** (mock provider; no upstream keys are configured).\n\n\
You said:\n\n> {}\n\n\
To stream real model replies, set `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` or `OPENROUTER_API_KEY` \
on the server and restart. Everything else in this pipeline is live: auth, rate limits, \
credit metering and conversation sync are all enforced on this request.",
        last_user.chars().take(160).collect::<String>()
    );
    let words: Vec<String> = reply.split_inclusive(' ').map(String::from).collect();
    let output_est = crate::store::estimate_tokens(&reply);
    Box::pin(futures::stream::unfold(
        (
            words.into_iter(),
            0usize,
            Some(input_est),
            Some(output_est),
            false,
            false,
        ),
        |(mut words, mut idx, input, output, usage_sent, finished)| async move {
            if finished {
                return None;
            }
            if idx == 0 && !usage_sent {
                // Emit usage up front, then stream.
                idx += 1;
                return Some((
                    Ok(GatewayEvent::Usage {
                        input: input.unwrap_or(0),
                        output: output.unwrap_or(0),
                    }),
                    (words, idx, input, output, true, false),
                ));
            }
            if let Some(word) = words.next() {
                Some((
                    Ok(GatewayEvent::Delta(word)),
                    (words, idx + 1, input, output, usage_sent, false),
                ))
            } else {
                Some((
                    Ok(GatewayEvent::Done),
                    (words, idx, input, output, usage_sent, true),
                ))
            }
        },
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::StreamExt;

    #[tokio::test]
    async fn mock_stream_emits_usage_deltas_done() {
        let req = UpstreamRequest {
            messages: vec![crate::gateway::ChatMessage {
                role: "user".into(),
                content: "hello".into(),
            }],
            temperature: None,
            max_tokens: None,
            stream: true,
        };
        let mut stream = mock_stream(&req);
        let mut deltas = 0;
        let mut usage = 0;
        let mut done = false;
        while let Some(ev) = stream.next().await {
            match ev.unwrap() {
                GatewayEvent::Delta(_) => deltas += 1,
                GatewayEvent::Usage { .. } => usage += 1,
                GatewayEvent::Done => {
                    done = true;
                    break;
                }
            }
        }
        assert!(done);
        assert!(deltas > 3);
        assert_eq!(usage, 1);
    }
}
