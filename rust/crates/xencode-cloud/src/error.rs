//! API error type mapped onto HTTP responses.

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::json;

#[derive(Debug)]
pub enum ApiError {
    BadRequest(String),
    Unauthorized(String),
    Forbidden(String),
    NotFound(String),
    RateLimited(String),
    PaymentRequired(String),
    Upstream(String),
    Internal(String),
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ApiError::BadRequest(m) => write!(f, "bad request: {m}"),
            ApiError::Unauthorized(m) => write!(f, "unauthorized: {m}"),
            ApiError::Forbidden(m) => write!(f, "forbidden: {m}"),
            ApiError::NotFound(m) => write!(f, "not found: {m}"),
            ApiError::RateLimited(m) => write!(f, "rate limited: {m}"),
            ApiError::PaymentRequired(m) => write!(f, "payment required: {m}"),
            ApiError::Upstream(m) => write!(f, "upstream: {m}"),
            ApiError::Internal(m) => write!(f, "internal: {m}"),
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, code) = match &self {
            ApiError::BadRequest(_) => (StatusCode::BAD_REQUEST, "bad_request"),
            ApiError::Unauthorized(_) => (StatusCode::UNAUTHORIZED, "unauthorized"),
            ApiError::Forbidden(_) => (StatusCode::FORBIDDEN, "forbidden"),
            ApiError::NotFound(_) => (StatusCode::NOT_FOUND, "not_found"),
            ApiError::RateLimited(_) => (StatusCode::TOO_MANY_REQUESTS, "rate_limited"),
            ApiError::PaymentRequired(_) => (StatusCode::PAYMENT_REQUIRED, "payment_required"),
            ApiError::Upstream(_) => (StatusCode::BAD_GATEWAY, "upstream_error"),
            ApiError::Internal(_) => (StatusCode::INTERNAL_SERVER_ERROR, "internal_error"),
        };
        (
            status,
            Json(json!({ "error": { "code": code, "message": self.to_string() } })),
        )
            .into_response()
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(e: sqlx::Error) -> Self {
        ApiError::Internal(format!("database: {e}"))
    }
}

pub type ApiResult<T> = Result<T, ApiError>;
