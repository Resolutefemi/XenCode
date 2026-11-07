//! Shared application state.

use crate::config::Config;
use crate::metering::RateLimiter;
use crate::store::Store;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;

#[derive(Clone)]
pub struct AppState {
    pub store: Arc<dyn Store>,
    pub cfg: Arc<Config>,
    pub http: reqwest::Client,
    pub rate: Arc<RateLimiter>,
    msg_seq: Arc<AtomicI64>,
}

impl AppState {
    pub fn new(store: Arc<dyn Store>, cfg: Config) -> Self {
        AppState {
            store,
            cfg: Arc::new(cfg),
            http: reqwest::Client::builder()
                .user_agent(concat!("xencode-cloud/", env!("CARGO_PKG_VERSION")))
                .build()
                .expect("reqwest client"),
            rate: Arc::new(RateLimiter::default()),
            msg_seq: Arc::new(AtomicI64::new(1)),
        }
    }

    /// Monotonic message ids (memory store; Postgres uses its own sequence).
    pub fn next_message_id(&self) -> i64 {
        self.msg_seq.fetch_add(1, Ordering::SeqCst)
    }
}
