//! Stripe billing: webhook signature verification and credit events.
//!
//! Flow: a Stripe Checkout session completes -> Stripe POSTs here with
//! `Stripe-Signature: t=<ts>,v1=<hmac>` -> we verify the HMAC-SHA256 of
//! "{t}.{payload}" against the webhook secret, then apply the event:
//! - checkout.session.completed: add credits (from metadata or line items)
//! - customer.subscription.deleted / invoice.payment_failed: suspend

use crate::state::AppState;
use axum::body::Bytes;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use hmac::{Hmac, Mac};
use serde_json::json;
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

/// Verify a Stripe webhook signature (t=...,v1=...). Constant-time compare.
pub fn verify_signature(secret: &str, payload: &str, header: &str) -> bool {
    let mut timestamp = String::new();
    let mut signatures = Vec::new();
    for part in header.split(',') {
        let part = part.trim();
        if let Some(t) = part.strip_prefix("t=") {
            timestamp = t.to_string();
        } else if let Some(v) = part.strip_prefix("v1=") {
            signatures.push(v.to_string());
        }
    }
    if timestamp.is_empty() || signatures.is_empty() {
        return false;
    }
    let signed = format!("{timestamp}.{payload}");
    let Ok(mut mac) = HmacSha256::new_from_slice(secret.as_bytes()) else {
        return false;
    };
    mac.update(signed.as_bytes());
    for sig in signatures {
        let Ok(bytes) = hex::decode(sig.trim()) else {
            continue;
        };
        if mac.clone().verify_slice(&bytes).is_ok() {
            return true;
        }
    }
    false
}

/// Extract the user id and credit amount from a checkout session event.
pub fn credits_from_event(event: &serde_json::Value) -> Option<(uuid::Uuid, i64)> {
    let session = event.get("data")?.get("object")?;
    let user = session.get("metadata")?.get("user_id")?.as_str()?;
    let user = uuid::Uuid::parse_str(user).ok()?;
    let credits = session
        .get("metadata")
        .and_then(|m| m.get("credits"))
        .and_then(|c| c.as_i64())
        .or_else(|| {
            // Fallback: amount_total is in the smallest currency unit.
            let cents = session
                .get("amount_total")
                .and_then(|a| a.as_i64())
                .unwrap_or(0);
            Some(cents) // 1 credit per cent by default
        })?;
    Some((user, credits))
}

/// POST /v1/billing/stripe-webhook
pub async fn stripe_webhook(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<(StatusCode, Json<serde_json::Value>), (StatusCode, Json<serde_json::Value>)> {
    let payload = String::from_utf8_lossy(&body).into_owned();
    let secret = state.cfg.stripe_webhook_secret.clone().unwrap_or_default();
    let header = headers
        .get("stripe-signature")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();

    if !verify_signature(&secret, &payload, header) {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(
                json!({ "error": { "code": "bad_signature", "message": "stripe signature verification failed" } }),
            ),
        ));
    }

    let Ok(event) = serde_json::from_str::<serde_json::Value>(&payload) else {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": { "code": "bad_payload", "message": "invalid json" } })),
        ));
    };

    let event_type = event["type"].as_str().unwrap_or_default();
    match event_type {
        "checkout.session.completed" => {
            if let Some((user, credits)) = credits_from_event(&event) {
                state
                    .store
                    .add_credits(user, credits, "stripe checkout")
                    .await
                    .map_err(internal)?;
                tracing::info!(user = %user, credits, "credits added via stripe");
            }
        }
        "invoice.payment_failed" | "customer.subscription.deleted" => {
            if let Some((user, _)) = credits_from_event(&event) {
                state
                    .store
                    .set_suspended(user, true)
                    .await
                    .map_err(internal)?;
                tracing::warn!(user = %user, "user suspended by stripe event");
            }
        }
        _ => {}
    }
    Ok((StatusCode::OK, Json(json!({ "received": true }))))
}

fn internal(e: crate::error::ApiError) -> (StatusCode, Json<serde_json::Value>) {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        Json(json!({ "error": { "code": "internal_error", "message": e.to_string() } })),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn signed(secret: &str, payload: &str, ts: &str) -> String {
        use hex::ToHex;
        let mut mac = HmacSha256::new_from_slice(secret.as_bytes()).unwrap();
        mac.update(format!("{ts}.{payload}").as_bytes());
        let sig: String = mac.finalize().into_bytes().encode_hex();
        format!("t={ts},v1={sig}")
    }

    #[test]
    fn signature_verifies() {
        let payload = r#"{"type":"checkout.session.completed"}"#;
        let header = signed("whsec_test", payload, "1700000000");
        assert!(verify_signature("whsec_test", payload, &header));
        // Wrong secret, tampered payload, bad header.
        assert!(!verify_signature("whsec_other", payload, &header));
        assert!(!verify_signature(
            "whsec_test",
            "{\"type\":\"evil\"}",
            &header
        ));
        assert!(!verify_signature("whsec_test", payload, "t=1,v2=zz"));
    }

    #[test]
    fn credits_parse_from_metadata() {
        let event = serde_json::json!({
            "type": "checkout.session.completed",
            "data": { "object": {
                "metadata": { "user_id": "11111111-1111-1111-1111-111111111111", "credits": 25000 },
                "amount_total": 9900,
            }}
        });
        let (user, credits) = credits_from_event(&event).unwrap();
        assert_eq!(user.to_string(), "11111111-1111-1111-1111-111111111111");
        assert_eq!(credits, 25000);
    }

    #[test]
    fn credits_fall_back_to_amount() {
        let event = serde_json::json!({
            "type": "checkout.session.completed",
            "data": { "object": {
                "metadata": { "user_id": "11111111-1111-1111-1111-111111111111" },
                "amount_total": 9900,
            }}
        });
        let (_, credits) = credits_from_event(&event).unwrap();
        assert_eq!(credits, 9900);
    }
}
