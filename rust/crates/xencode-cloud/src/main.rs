//! XenCloud: the XenCode backend binary.
//!
//! Auth, LLM gateway with key protection, SSE streaming proxy, token
//! metering and billing, conversation sync and prompt/model routing.
//! See README.md for the endpoint tour and environment variables.

use std::sync::Arc;
use xencode_cloud::state::AppState;
use xencode_cloud::{app_router, config, store};

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "xencode_cloud=info,tower_http=info".into()),
        )
        .init();

    let cfg = config::Config::from_env();

    // Choose the store: postgres when DATABASE_URL is set, else memory.
    let store: Arc<dyn store::Store> = if cfg.db == "postgres" || cfg.database_url.is_some() {
        let url = cfg
            .database_url
            .clone()
            .expect("DATABASE_URL required when XENCODE_DB=postgres");
        match store::postgres::PostgresStore::connect(&url).await {
            Ok(pg) => {
                tracing::info!("connected to postgres, migrations applied");
                Arc::new(pg)
            }
            Err(e) => {
                tracing::error!("postgres unavailable ({e}); falling back to the in-memory store");
                Arc::new(store::memory::MemoryStore::new())
            }
        }
    } else {
        tracing::warn!(
            "XENCODE_DB not set: using the in-memory store (data does not survive restarts)"
        );
        Arc::new(store::memory::MemoryStore::new())
    };

    let state = AppState::new(store, cfg.clone());
    let app = app_router(state);

    let listener = tokio::net::TcpListener::bind(&cfg.bind)
        .await
        .unwrap_or_else(|e| panic!("cannot bind {}: {e}", cfg.bind));
    tracing::info!(
        "XenCloud listening on {} (db: {}, mock_llm: {})",
        cfg.bind,
        cfg.db,
        cfg.use_mock_llm()
    );
    axum::serve(listener, app).await.expect("server error");
}
