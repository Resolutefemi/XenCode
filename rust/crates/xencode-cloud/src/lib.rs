//! XenCloud library: everything the binary and the integration tests use.

pub mod auth;
pub mod billing;
pub mod config;
pub mod error;
pub mod gateway;
pub mod metering;
pub mod providers;
pub mod routing;
pub mod sessions;
pub mod state;
pub mod store;

use axum::extract::State;
use axum::routing::{get, post};
use axum::Router;

/// Build the full router (used by main and the integration tests).
pub fn app_router(state: state::AppState) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .merge(auth::router())
        .merge(sessions::router())
        .route("/v1/chat/completions", post(gateway::chat_completions))
        .route("/v1/models", get(gateway::models))
        .route("/v1/usage", get(metering::usage_summary))
        .route("/v1/routing", get(routing::routing_table))
        .route("/v1/admin/prompts", post(routing::upsert_prompt))
        .route("/v1/billing/stripe-webhook", post(billing::stripe_webhook))
        .with_state(state)
}

async fn healthz(State(state): State<state::AppState>) -> axum::Json<serde_json::Value> {
    let db_ok = state.store.health().await;
    axum::Json(serde_json::json!({
        "status": "ok",
        "service": "xencode-cloud",
        "version": env!("CARGO_PKG_VERSION"),
        "db": if db_ok { "ok" } else { "down" },
        "mock_llm": state.cfg.use_mock_llm(),
    }))
}
