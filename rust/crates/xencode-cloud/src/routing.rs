//! Prompt and model routing.
//!
//! The gateway injects a server-side system prompt and resolves model
//! aliases: `fast` / `smart` / `xencode-auto` (task classification) or an
//! explicit `provider/model` id. Updating prompts or routing rules on the
//! server changes agent behavior instantly, without shipping a new desktop
//! build.

use crate::error::ApiResult;
use crate::state::AppState;
use axum::extract::State;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::json;

/// The canonical system prompt injected for desktop agent sessions.
pub const DEFAULT_DESKTOP_PROMPT: &str = "You are XenCode, a desktop coding agent. \
Work in the user's workspace: read files, run commands and edit code through your tools. \
Be concise. Prefer showing diffs and exact commands over prose. \
When a task is ambiguous, ask one clarifying question. \
Never fabricate file contents or command output.";

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ModelClass {
    Fast,
    Smart,
}

/// Classify a task from its messages: long or architecture-shaped prompts go
/// to the flagship model; quick edits and questions go to the fast one.
pub fn classify(messages: &[crate::gateway::ChatMessage]) -> ModelClass {
    let user_text: String = messages
        .iter()
        .filter(|m| m.role == "user")
        .map(|m| m.content.as_str())
        .collect::<Vec<_>>()
        .join("\n")
        .to_lowercase();
    let total_chars: usize = messages.iter().map(|m| m.content.len()).sum();

    let heavy_words = [
        "architect",
        "refactor",
        "design",
        "review",
        "migrate",
        "rewrite",
        "performance",
        "security",
        "test plan",
        "why",
        "explain",
        "document",
    ];
    let light_words = [
        "rename",
        "typo",
        "fix the failing",
        "lint",
        "format",
        "bump",
        "quick",
        "todo",
        "what does this line",
        "git status",
        "list ",
    ];

    let heavy = heavy_words.iter().any(|w| user_text.contains(w));
    let light = light_words.iter().any(|w| user_text.contains(w));
    if total_chars > 4000 || messages.len() > 12 {
        return ModelClass::Smart;
    }
    if heavy && !light {
        return ModelClass::Smart;
    }
    ModelClass::Fast
}

/// Where a request is actually sent.
#[derive(Clone, Debug)]
pub struct ProviderTarget {
    pub provider: Provider,
    pub model: String,
    /// The model id as seen by the client (alias or explicit).
    pub requested: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Provider {
    Anthropic,
    OpenAi,
    OpenRouter,
    Mock,
}

/// Resolve the requested model against configured providers.
pub fn resolve(
    state: &AppState,
    requested: &str,
    messages: &[crate::gateway::ChatMessage],
) -> ApiResult<ProviderTarget> {
    let cfg = &state.cfg;
    let mock = cfg.use_mock_llm();
    let req = requested.trim();

    // Explicit provider routes: "anthropic/claude-...", "openai/gpt-5", ...
    if let Some((provider, model)) = req.split_once('/') {
        let (provider, model) = (provider.trim(), model.trim());
        let target = match provider.to_ascii_lowercase().as_str() {
            "anthropic" if cfg.anthropic_key.is_some() => {
                Some((Provider::Anthropic, model.to_string()))
            }
            "openai" if cfg.openai_key.is_some() => Some((Provider::OpenAi, model.to_string())),
            "openrouter" if cfg.openrouter_key.is_some() => {
                Some((Provider::OpenRouter, model.to_string()))
            }
            "mock" => Some((Provider::Mock, model.to_string())),
            _ => None,
        };
        if let Some((provider, model)) = target {
            return Ok(ProviderTarget {
                provider,
                model,
                requested: req.to_string(),
            });
        }
        return Err(crate::error::ApiError::BadRequest(format!(
            "provider '{provider}' is not configured on this gateway"
        )));
    }

    // Aliases.
    let pick = |prefer_fast: bool| -> (Provider, String) {
        let anthropic = cfg.anthropic_key.as_ref().map(|_| {
            (
                Provider::Anthropic,
                if prefer_fast {
                    "claude-haiku"
                } else {
                    "claude-sonnet"
                }
                .to_string(),
            )
        });
        let openai = cfg.openai_key.as_ref().map(|_| {
            (
                Provider::OpenAi,
                if prefer_fast { "gpt-5-mini" } else { "gpt-5" }.to_string(),
            )
        });
        let openrouter = cfg.openrouter_key.as_ref().map(|_| {
            (
                Provider::OpenRouter,
                if prefer_fast {
                    "openai/gpt-5-mini"
                } else {
                    "anthropic/claude-sonnet-4.5"
                }
                .to_string(),
            )
        });
        anthropic
            .or(openai)
            .or(openrouter)
            .unwrap_or((Provider::Mock, "local".to_string()))
    };

    let (provider, model) = match req.to_ascii_lowercase().as_str() {
        "" | "xencode-auto" | "auto" => {
            let class = classify(messages);
            let (provider, model) = pick(class == ModelClass::Fast);
            (provider, model)
        }
        "fast" | "small" | "mini" => pick(true),
        "smart" | "big" | "flagship" => pick(false),
        other => return Err(crate::error::ApiError::BadRequest(format!(
            "unknown model '{other}'; use an alias (xencode-auto, fast, smart) or provider/model"
        ))),
    };
    let _ = mock;
    Ok(ProviderTarget {
        provider,
        model,
        requested: req.to_string(),
    })
}

/// The system prompt to inject (store override, else the built-in default).
pub async fn system_prompt(state: &AppState) -> String {
    state
        .store
        .get_prompt("desktop-default")
        .await
        .ok()
        .flatten()
        .map(|p| p.content)
        .unwrap_or_else(|| DEFAULT_DESKTOP_PROMPT.to_string())
}

/// GET /v1/routing -> the routing table (public, no secrets).
pub async fn routing_table(State(state): State<AppState>) -> Json<serde_json::Value> {
    let mut providers = Vec::new();
    if state.cfg.anthropic_key.is_some() {
        providers.push("anthropic");
    }
    if state.cfg.openai_key.is_some() {
        providers.push("openai");
    }
    if state.cfg.openrouter_key.is_some() {
        providers.push("openrouter");
    }
    Json(json!({
        "aliases": {
            "xencode-auto": "classifies the task, then picks fast or smart",
            "fast": "smallest configured model (quick edits)",
            "smart": "flagship model (architecture, review)",
        },
        "providers": providers,
        "mock": state.cfg.use_mock_llm(),
        "prompt": "server-side system prompt, updatable via POST /v1/admin/prompts",
    }))
}

/// POST /v1/admin/prompts { name, content } (X-Admin-Token).
pub async fn upsert_prompt(
    State(state): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(body): Json<serde_json::Value>,
) -> ApiResult<axum::response::Response> {
    let admin = state
        .cfg
        .admin_token
        .clone()
        .ok_or_else(|| crate::error::ApiError::Forbidden("admin endpoints disabled".into()))?;
    let given = headers
        .get("x-admin-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();
    if given != admin {
        return Err(crate::error::ApiError::Forbidden("bad admin token".into()));
    }
    let name = body["name"].as_str().unwrap_or_default().trim().to_string();
    let content = body["content"].as_str().unwrap_or_default().to_string();
    if name.is_empty() || content.is_empty() {
        return Err(crate::error::ApiError::BadRequest(
            "name and content required".into(),
        ));
    }
    let row = state.store.upsert_prompt(&name, &content).await?;
    Ok((
        axum::http::StatusCode::OK,
        axum::Json(json!({ "prompt": row })),
    )
        .into_response())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(role: &str, content: &str) -> crate::gateway::ChatMessage {
        crate::gateway::ChatMessage {
            role: role.into(),
            content: content.into(),
        }
    }

    #[test]
    fn quick_edits_route_fast() {
        let messages = vec![msg("user", "rename the variable foo to bar")];
        assert_eq!(classify(&messages), ModelClass::Fast);
    }

    #[test]
    fn architecture_routes_smart() {
        let messages = vec![msg(
            "user",
            "review the authentication architecture and refactor it",
        )];
        assert_eq!(classify(&messages), ModelClass::Smart);
    }

    #[test]
    fn long_context_routes_smart() {
        let long = "x".repeat(5000);
        let messages = vec![msg("user", &long)];
        assert_eq!(classify(&messages), ModelClass::Smart);
    }
}
