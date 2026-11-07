//! The LLM gateway: OpenAI-compatible `/v1/chat/completions` (SSE streaming
//! or JSON) plus an Anthropic-style passthrough. Injects the server-side
//! system prompt, resolves routing, enforces rate limits and credits, meters
//! usage when the stream completes, and never exposes master keys.

use crate::auth::Authed;
use crate::error::{ApiError, ApiResult};
use crate::providers::{open_stream, GatewayEvent, GatewayStream, UpstreamRequest};
use crate::routing;
use crate::state::AppState;
use axum::extract::State;
use axum::response::sse::{Event as SseEvent, KeepAlive, Sse};
use axum::response::IntoResponse;
use axum::Json;
use futures::{Stream, StreamExt};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

#[derive(Deserialize)]
pub struct ChatRequest {
    pub model: String,
    pub messages: Vec<ChatMessage>,
    #[serde(default)]
    pub stream: bool,
    #[serde(default)]
    pub temperature: Option<f64>,
    #[serde(default)]
    pub max_tokens: Option<i64>,
    /// Optional reasoning effort (off/low/medium/high), forwarded upstream
    /// where supported.
    #[serde(default)]
    pub reasoning_effort: Option<String>,
}

#[derive(Serialize)]
struct ChatChoiceDelta {
    index: usize,
    delta: serde_json::Value,
    finish_reason: Option<String>,
}

fn chunk_event(id: &str, model: &str, delta: serde_json::Value, finish: Option<&str>) -> SseEvent {
    let payload = serde_json::json!({
        "id": id,
        "object": "chat.completion.chunk",
        "model": model,
        "choices": [ChatChoiceDelta {
            index: 0,
            delta,
            finish_reason: finish.map(String::from),
        }],
    });
    SseEvent::default().data(payload.to_string())
}

/// POST /v1/chat/completions
pub async fn chat_completions(
    State(state): State<AppState>,
    auth: Authed,
    Json(body): Json<ChatRequest>,
) -> ApiResult<axum::response::Response> {
    if body.messages.is_empty() {
        return Err(ApiError::BadRequest("messages must not be empty".into()));
    }
    crate::metering::enforce_rate(&state, &auth.user)?;
    crate::metering::enforce_credits(&state, &auth.user).await?;

    // Routing + server-side prompt injection.
    let target = routing::resolve(&state, &body.model, &body.messages)?;
    let prompt = routing::system_prompt(&state).await;
    let mut messages = Vec::with_capacity(body.messages.len() + 1);
    messages.push(ChatMessage {
        role: "system".into(),
        content: prompt,
    });
    for m in &body.messages {
        if m.role == "system" {
            continue; // the server-side prompt wins
        }
        messages.push(m.clone());
    }

    let upstream = UpstreamRequest {
        messages,
        temperature: body.temperature,
        max_tokens: body.max_tokens,
        stream: body.stream,
    };
    let stream = open_stream(&state, &target, upstream)
        .await
        .map_err(ApiError::Upstream)?;

    let gateway_model = format!("{}/{}", provider_name(target.provider), target.model);

    if !body.stream {
        return non_streaming(state, auth, stream, &gateway_model).await;
    }

    // Streaming: OpenAI-shaped SSE with usage metering at the end.
    let meter_state = Arc::new(Mutex::new(MeterState {
        input: 0,
        output: 0,
        metered: false,
        acc: String::new(),
        request_model: target.requested.clone(),
    }));
    let sse = Sse::new(MeteredStream {
        inner: stream,
        state: state.clone(),
        user: auth.user.clone(),
        model: gateway_model.clone(),
        meter: meter_state,
        finished: false,
    })
    .keep_alive(KeepAlive::default());

    Ok(sse.into_response())
}

fn provider_name(p: routing::Provider) -> &'static str {
    match p {
        routing::Provider::Anthropic => "anthropic",
        routing::Provider::OpenAi => "openai",
        routing::Provider::OpenRouter => "openrouter",
        routing::Provider::Mock => "mock",
    }
}

struct MeterState {
    input: i64,
    output: i64,
    metered: bool,
    acc: String,
    request_model: String,
}

/// Wraps the provider stream: transforms to SSE chunks, accumulates output,
/// and records usage exactly once when the stream ends (even without a
/// provider-reported usage event, a char-based estimate is charged).
struct MeteredStream {
    inner: GatewayStream,
    state: AppState,
    user: crate::store::User,
    model: String,
    meter: Arc<Mutex<MeterState>>,
    finished: bool,
}

impl Stream for MeteredStream {
    type Item = Result<SseEvent, std::convert::Infallible>;

    fn poll_next(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Self::Item>> {
        use std::pin::Pin;
        use std::task::Poll;
        let this = self.get_mut();
        if this.finished {
            return std::task::Poll::Ready(None);
        }
        loop {
            match Pin::new(&mut this.inner).poll_next(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(None) => {
                    finalize_metering(this);
                    this.finished = true;
                    return Poll::Ready(Some(Ok(SseEvent::default().data("[DONE]"))));
                }
                Poll::Ready(Some(Err(e))) => {
                    // Surface the error as a final chunk, still meter.
                    finalize_metering(this);
                    this.finished = true;
                    let err_event = SseEvent::default()
                        .data(serde_json::json!({ "error": { "message": e } }).to_string());
                    return Poll::Ready(Some(Ok(err_event)));
                }
                Poll::Ready(Some(Ok(GatewayEvent::Delta(d)))) => {
                    if let Ok(mut m) = this.meter.lock() {
                        m.acc.push_str(&d);
                    }
                    let model = this.model.clone();
                    let ev = chunk_event(
                        "xencloud",
                        &model,
                        serde_json::json!({ "content": d }),
                        None,
                    );
                    return Poll::Ready(Some(Ok(ev)));
                }
                Poll::Ready(Some(Ok(GatewayEvent::Usage { input, output }))) => {
                    if let Ok(mut m) = this.meter.lock() {
                        m.input += input;
                        m.output += output;
                    }
                    // Usage events are recorded but produce no SSE output.
                    continue;
                }
                Poll::Ready(Some(Ok(GatewayEvent::Done))) => {
                    // Provider signaled completion; keep pulling until the
                    // inner stream ends so trailing usage events still flow.
                    continue;
                }
            }
        }
    }
}

/// Record usage once; fall back to an estimate when the upstream did not
/// report usage numbers.
fn finalize_metering(stream: &mut MeteredStream) {
    let Ok(mut m) = stream.meter.lock() else {
        return;
    };
    if m.metered {
        return;
    }
    m.metered = true;
    let input = if m.input > 0 {
        m.input
    } else {
        crate::store::estimate_tokens(&m.request_model) + 24
    };
    let output = if m.output > 0 {
        m.output
    } else {
        crate::store::estimate_tokens(&m.acc)
    };
    let state = stream.state.clone();
    let user = stream.user.clone();
    let model = stream.model.clone();
    tokio::spawn(async move {
        match crate::metering::record(&state, &user, &model, input, output).await {
            Ok(credits) => {
                tracing::info!(user = %user.id, model = %model, input, output, credits, "metered");
            }
            Err(e) => tracing::error!(user = %user.id, "metering failed: {e}"),
        }
    });
}

/// Aggregate the stream into one JSON completion (non-streaming clients).
async fn non_streaming(
    state: AppState,
    auth: Authed,
    mut stream: GatewayStream,
    model: &str,
) -> ApiResult<axum::response::Response> {
    let mut text = String::new();
    let mut input = 0i64;
    let mut output = 0i64;
    while let Some(ev) = stream.next().await {
        match ev {
            Ok(GatewayEvent::Delta(d)) => text.push_str(&d),
            Ok(GatewayEvent::Usage {
                input: i,
                output: o,
            }) => {
                input += i;
                output += o;
            }
            Ok(GatewayEvent::Done) => {}
            Err(e) => return Err(ApiError::Upstream(e)),
        }
    }
    let input = if input > 0 {
        input
    } else {
        crate::store::estimate_tokens(model) + 24
    };
    let output = if output > 0 {
        output
    } else {
        crate::store::estimate_tokens(&text)
    };
    let credits = crate::metering::record(&state, &auth.user, model, input, output).await?;

    let body = serde_json::json!({
        "id": "xencloud",
        "object": "chat.completion",
        "model": model,
        "choices": [{
            "index": 0,
            "message": { "role": "assistant", "content": text },
            "finish_reason": "stop",
        }],
        "usage": {
            "prompt_tokens": input,
            "completion_tokens": output,
            "total_tokens": input + output,
            "credits_charged": credits,
        },
    });
    Ok((axum::http::StatusCode::OK, Json(body)).into_response())
}

/// GET /v1/models -> the models this gateway exposes.
pub async fn models(
    State(state): State<AppState>,
    _auth: Authed,
) -> ApiResult<Json<serde_json::Value>> {
    let data: Vec<serde_json::Value> = state
        .cfg
        .available_models()
        .into_iter()
        .map(|m| serde_json::json!({ "id": m.id, "provider": m.provider, "note": m.note }))
        .collect();
    Ok(Json(serde_json::json!({ "object": "list", "data": data })))
}
