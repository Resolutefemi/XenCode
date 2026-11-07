//! Auth: magic-link login, revocable session tokens, hashed API keys.
//!
//! Tokens are opaque random strings; only their SHA-256 hashes are stored,
//! so a database leak does not leak usable credentials. Sessions and API
//! keys are revocable server-side (unlike JWTs).

use crate::error::{ApiError, ApiResult};
use crate::state::AppState;
use axum::extract::{FromRequestParts, State};
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::{Json, Router};
use rand::RngCore;
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub const SESSION_PREFIX: &str = "xst_";
pub const API_KEY_PREFIX: &str = "xk_";

/// SHA-256 hex digest of a token.
pub fn hash_token(token: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    hex::encode(hasher.finalize())
}

/// Random URL-safe token with the given prefix.
pub fn new_token(prefix: &str) -> String {
    let mut bytes = [0u8; 24];
    rand::thread_rng().fill_bytes(&mut bytes);
    format!("{prefix}{}", hex::encode(bytes))
}

/// The authenticated caller.
#[derive(Clone, Debug)]
pub struct Authed {
    pub user: crate::store::User,
}

impl FromRequestParts<AppState> for Authed {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let header = parts
            .headers
            .get(AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| ApiError::Unauthorized("missing Authorization header".into()))?;
        let token = header
            .strip_prefix("Bearer ")
            .ok_or_else(|| ApiError::Unauthorized("expected Bearer token".into()))?
            .trim();
        if token.is_empty() {
            return Err(ApiError::Unauthorized("empty bearer token".into()));
        }

        let hash = hash_token(token);
        let user = if token.starts_with(SESSION_PREFIX) {
            state.store.session_user(&hash).await?
        } else if token.starts_with(API_KEY_PREFIX) {
            state.store.api_key_user(&hash).await?
        } else {
            return Err(ApiError::Unauthorized(
                "token must start with xst_ or xk_".into(),
            ));
        }
        .ok_or_else(|| ApiError::Unauthorized("invalid or revoked token".into()))?;

        if user.suspended {
            return Err(ApiError::Forbidden("account suspended".into()));
        }
        Ok(Authed { user })
    }
}

#[derive(Deserialize)]
pub struct MagicLinkRequest {
    pub email: String,
}

/// POST /v1/auth/magic-link { email }
///
/// In production this emails a login link. With XENCODE_DEV=1 the token is
/// returned inline so the flow is usable from the CLI and tests.
pub async fn request_magic_link(
    State(state): State<AppState>,
    Json(body): Json<MagicLinkRequest>,
) -> ApiResult<axum::response::Response> {
    let email = body.email.trim().to_lowercase();
    if !email.contains('@') || email.len() < 5 {
        return Err(ApiError::BadRequest("invalid email".into()));
    }
    let user = state.store.upsert_user(&email).await?;
    // Signup bonus credits.
    if state.store.credit_balance(user.id).await? == 0 {
        state
            .store
            .add_credits(user.id, state.cfg.signup_credits, "signup bonus")
            .await?;
    }
    let token = new_token("xml_");
    state
        .store
        .create_magic_token(
            user.id,
            &hash_token(&token),
            chrono::Utc::now() + chrono::Duration::minutes(15),
        )
        .await?;
    tracing::info!(user = %user.id, "magic link requested");

    if state.cfg.dev {
        return Ok((
            axum::http::StatusCode::OK,
            Json(json!({ "dev_token": token, "expires_in": 900 })),
        )
            .into_response());
    }
    Ok(axum::http::StatusCode::NO_CONTENT.into_response())
}

#[derive(Deserialize)]
pub struct VerifyRequest {
    pub token: String,
}

/// POST /v1/auth/magic-link/verify { token } -> session token + user
pub async fn verify_magic_link(
    State(state): State<AppState>,
    Json(body): Json<VerifyRequest>,
) -> ApiResult<Json<serde_json::Value>> {
    let user_id = state
        .store
        .consume_magic_token(&hash_token(body.token.trim()))
        .await?
        .ok_or_else(|| ApiError::Unauthorized("invalid or expired magic link".into()))?;
    let user = state
        .store
        .user_by_id(user_id)
        .await?
        .ok_or_else(|| ApiError::Internal("user vanished".into()))?;
    let session = new_token(SESSION_PREFIX);
    state
        .store
        .create_session(user.id, &hash_token(&session))
        .await?;
    Ok(Json(json!({ "session_token": session, "user": user })))
}

/// POST /v1/auth/logout -> revokes the exact token used to call it.
pub async fn logout(
    State(state): State<AppState>,
    headers: axum::http::HeaderMap,
) -> ApiResult<axum::http::StatusCode> {
    let token = headers
        .get(AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(str::trim)
        .ok_or_else(|| ApiError::Unauthorized("missing bearer token".into()))?;
    let hash = hash_token(token);
    if token.starts_with(SESSION_PREFIX) {
        state.store.revoke_session(&hash).await?;
    } else if token.starts_with(API_KEY_PREFIX) {
        // API keys are revoked by id; a logout with an API key is a no-op hint.
        tracing::info!("logout called with an api key; use DELETE /v1/auth/api-keys/{{id}}");
    }
    Ok(axum::http::StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct CreateApiKeyRequest {
    pub name: String,
}

/// POST /v1/auth/api-keys { name } -> the plaintext key (shown once).
pub async fn create_api_key(
    State(state): State<AppState>,
    auth: Authed,
    Json(body): Json<CreateApiKeyRequest>,
) -> ApiResult<Json<serde_json::Value>> {
    let name = body.name.trim();
    if name.is_empty() || name.len() > 80 {
        return Err(ApiError::BadRequest("name must be 1..80 chars".into()));
    }
    let id = Uuid::new_v4();
    let key = new_token(API_KEY_PREFIX);
    state
        .store
        .create_api_key(auth.user.id, name, &hash_token(&key), id)
        .await?;
    Ok(Json(json!({ "id": id, "name": name, "key": key })))
}

/// GET /v1/auth/api-keys -> key metadata (never the key itself).
pub async fn list_api_keys(
    State(state): State<AppState>,
    auth: Authed,
) -> ApiResult<Json<serde_json::Value>> {
    let keys = state.store.list_api_keys(auth.user.id).await?;
    Ok(Json(json!({ "keys": keys })))
}

/// DELETE /v1/auth/api-keys/{id}
pub async fn revoke_api_key(
    State(state): State<AppState>,
    auth: Authed,
    axum::extract::Path(id): axum::extract::Path<Uuid>,
) -> ApiResult<axum::http::StatusCode> {
    let removed = state.store.revoke_api_key(auth.user.id, id).await?;
    if removed {
        Ok(axum::http::StatusCode::NO_CONTENT)
    } else {
        Err(ApiError::NotFound("no such api key".into()))
    }
}

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/v1/auth/magic-link", post(request_magic_link))
        .route("/v1/auth/magic-link/verify", post(verify_magic_link))
        .route("/v1/auth/logout", post(logout))
        .route("/v1/auth/api-keys", post(create_api_key).get(list_api_keys))
        .route(
            "/v1/auth/api-keys/{id}",
            axum::routing::delete(revoke_api_key),
        )
        .route("/v1/me", get(me))
}

/// GET /v1/me -> user + usage + credits.
pub async fn me(State(state): State<AppState>, auth: Authed) -> ApiResult<Json<serde_json::Value>> {
    let usage = state.store.usage_summary(auth.user.id).await?;
    let credits = state.store.credit_balance(auth.user.id).await?;
    Ok(Json(json!({
        "user": auth.user,
        "credits": credits,
        "usage": usage,
    })))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_hash_deterministically() {
        let t = new_token(SESSION_PREFIX);
        assert!(t.starts_with(SESSION_PREFIX));
        assert_eq!(hash_token(&t), hash_token(&t));
        assert_ne!(hash_token(&t), hash_token("other"));
        assert_eq!(hash_token(&t).len(), 64);
    }

    #[test]
    fn api_keys_have_prefix() {
        let k = new_token(API_KEY_PREFIX);
        assert!(k.starts_with(API_KEY_PREFIX));
        assert_eq!(k.len(), API_KEY_PREFIX.len() + 48);
    }
}
