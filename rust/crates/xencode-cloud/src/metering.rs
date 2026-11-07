//! Token metering, per-user rate limits and credit enforcement.

use crate::error::{ApiError, ApiResult};
use crate::state::AppState;
use crate::store::User;
use axum::extract::State;
use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

/// In-memory sliding-window limiter (per user, per minute).
#[derive(Default)]
pub struct RateLimiter {
    hits: std::sync::Mutex<HashMap<uuid::Uuid, VecDeque<Instant>>>,
}

impl RateLimiter {
    /// Returns Ok(()) or the number of seconds until the window frees up.
    pub fn check(&self, user: &User, limit: u32) -> Result<(), u32> {
        if limit == 0 {
            return Ok(());
        }
        let now = Instant::now();
        let mut map = self.hits.lock().unwrap();
        let window = map.entry(user.id).or_default();
        while let Some(front) = window.front() {
            if now.duration_since(*front) > Duration::from_secs(60) {
                window.pop_front();
            } else {
                break;
            }
        }
        if window.len() >= limit as usize {
            let oldest = window.front().unwrap();
            let retry = 60u32.saturating_sub(now.duration_since(*oldest).as_secs() as u32);
            return Err(retry.max(1));
        }
        window.push_back(now);
        Ok(())
    }
}

/// Enforce the rate limit for a user.
pub fn enforce_rate(state: &AppState, user: &User) -> ApiResult<()> {
    match state.rate.check(user, state.cfg.rate_limit_per_min) {
        Ok(()) => Ok(()),
        Err(retry_after) => Err(ApiError::RateLimited(format!(
            "too many requests; retry in {retry_after}s"
        ))),
    }
}

/// Enforce a positive credit balance.
pub async fn enforce_credits(state: &AppState, user: &User) -> ApiResult<()> {
    let balance = state.store.credit_balance(user.id).await?;
    if balance <= 0 {
        return Err(ApiError::PaymentRequired(
            "credit balance exhausted; top up at /v1/billing (Stripe checkout)".into(),
        ));
    }
    Ok(())
}

/// Record usage and charge credits (called once per completed request).
pub async fn record(
    state: &AppState,
    user: &User,
    model: &str,
    input_tokens: i64,
    output_tokens: i64,
) -> ApiResult<i64> {
    let credits =
        crate::store::charge_for(input_tokens, output_tokens, state.cfg.credits_per_1k_tokens);
    state
        .store
        .record_usage(user.id, model, input_tokens, output_tokens, credits)
        .await?;
    if credits != 0 {
        state
            .store
            .add_credits(user.id, -credits, &format!("usage {model}"))
            .await?;
    }
    Ok(credits)
}

/// GET /v1/usage -> per-model usage rows for the caller.
pub async fn usage_summary(
    State(state): State<AppState>,
    auth: crate::auth::Authed,
) -> ApiResult<axum::Json<serde_json::Value>> {
    let rows = state.store.usage_summary(auth.user.id).await?;
    let credits = state.store.credit_balance(auth.user.id).await?;
    Ok(axum::Json(
        serde_json::json!({ "usage": rows, "credits": credits }),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn limiter_allows_then_blocks() {
        let limiter = RateLimiter::default();
        let user = User {
            id: uuid::Uuid::new_v4(),
            email: "rate@test".into(),
            suspended: false,
            created_at: chrono::Utc::now(),
        };
        for _ in 0..3 {
            assert!(limiter.check(&user, 3).is_ok());
        }
        assert!(limiter.check(&user, 3).is_err());
        // A different user is unaffected.
        let other = User {
            id: uuid::Uuid::new_v4(),
            email: "other@test".into(),
            suspended: false,
            created_at: chrono::Utc::now(),
        };
        assert!(limiter.check(&other, 3).is_ok());
    }
}
