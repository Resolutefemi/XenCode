//! Conversation and session sync: chat histories, project metadata and
//! checkpoint snapshots, resumable across machines.

use crate::auth::Authed;
use crate::error::{ApiError, ApiResult};
use crate::state::AppState;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::json;
use uuid::Uuid;

#[derive(Deserialize)]
pub struct CreateConversation {
    pub title: String,
    #[serde(default)]
    pub project: String,
    #[serde(default)]
    pub model: String,
}

/// POST /v1/conversations
pub async fn create_conversation(
    State(state): State<AppState>,
    auth: Authed,
    Json(body): Json<CreateConversation>,
) -> ApiResult<Json<serde_json::Value>> {
    let title = body.title.trim();
    if title.is_empty() || title.len() > 200 {
        return Err(ApiError::BadRequest("title must be 1..200 chars".into()));
    }
    let row = state
        .store
        .create_conversation(
            auth.user.id,
            title,
            body.project.trim(),
            body.model.trim(),
            Uuid::new_v4(),
        )
        .await?;
    Ok(Json(json!({ "id": row.id, "conversation": row })))
}

/// GET /v1/conversations
pub async fn list_conversations(
    State(state): State<AppState>,
    auth: Authed,
) -> ApiResult<Json<serde_json::Value>> {
    let rows = state.store.list_conversations(auth.user.id).await?;
    Ok(Json(json!({ "conversations": rows })))
}

/// GET /v1/conversations/{id}
pub async fn get_conversation(
    State(state): State<AppState>,
    auth: Authed,
    Path(id): Path<Uuid>,
) -> ApiResult<Json<serde_json::Value>> {
    let row = state
        .store
        .get_conversation(auth.user.id, id)
        .await?
        .ok_or_else(|| ApiError::NotFound("conversation not found".into()))?;
    let messages = state.store.list_messages(id).await?;
    Ok(Json(json!({ "conversation": row, "messages": messages })))
}

/// DELETE /v1/conversations/{id}
pub async fn delete_conversation(
    State(state): State<AppState>,
    auth: Authed,
    Path(id): Path<Uuid>,
) -> ApiResult<StatusCode> {
    let removed = state.store.delete_conversation(auth.user.id, id).await?;
    if removed {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(ApiError::NotFound("conversation not found".into()))
    }
}

#[derive(Deserialize)]
pub struct AppendMessage {
    pub role: String,
    pub content: String,
}

/// POST /v1/conversations/{id}/messages
pub async fn append_message(
    State(state): State<AppState>,
    auth: Authed,
    Path(id): Path<Uuid>,
    Json(body): Json<AppendMessage>,
) -> ApiResult<Json<serde_json::Value>> {
    if !matches!(body.role.as_str(), "user" | "assistant" | "system") {
        return Err(ApiError::BadRequest(
            "role must be user, assistant or system".into(),
        ));
    }
    if body.content.len() > 200_000 {
        return Err(ApiError::BadRequest("content too large".into()));
    }
    state
        .store
        .get_conversation(auth.user.id, id)
        .await?
        .ok_or_else(|| ApiError::NotFound("conversation not found".into()))?;
    let msg_id = state.next_message_id();
    state
        .store
        .append_message(id, &body.role, &body.content, msg_id)
        .await?;
    Ok(Json(json!({ "id": msg_id })))
}

/// GET /v1/conversations/{id}/messages
pub async fn list_messages(
    State(state): State<AppState>,
    auth: Authed,
    Path(id): Path<Uuid>,
) -> ApiResult<Json<serde_json::Value>> {
    state
        .store
        .get_conversation(auth.user.id, id)
        .await?
        .ok_or_else(|| ApiError::NotFound("conversation not found".into()))?;
    let rows = state.store.list_messages(id).await?;
    Ok(Json(json!({ "messages": rows })))
}

#[derive(Deserialize)]
pub struct SaveCheckpoint {
    pub label: String,
    #[serde(default)]
    pub snapshot: serde_json::Value,
}

/// POST /v1/conversations/{id}/checkpoints
pub async fn save_checkpoint(
    State(state): State<AppState>,
    auth: Authed,
    Path(id): Path<Uuid>,
    Json(body): Json<SaveCheckpoint>,
) -> ApiResult<Json<serde_json::Value>> {
    state
        .store
        .get_conversation(auth.user.id, id)
        .await?
        .ok_or_else(|| ApiError::NotFound("conversation not found".into()))?;
    let cp_id = Uuid::new_v4();
    state
        .store
        .save_checkpoint(id, body.label.trim(), body.snapshot, cp_id)
        .await?;
    Ok(Json(json!({ "id": cp_id })))
}

/// GET /v1/conversations/{id}/checkpoints
pub async fn list_checkpoints(
    State(state): State<AppState>,
    auth: Authed,
    Path(id): Path<Uuid>,
) -> ApiResult<Json<serde_json::Value>> {
    state
        .store
        .get_conversation(auth.user.id, id)
        .await?
        .ok_or_else(|| ApiError::NotFound("conversation not found".into()))?;
    let rows = state.store.list_checkpoints(id).await?;
    Ok(Json(json!({ "checkpoints": rows })))
}

pub fn router() -> Router<AppState> {
    Router::new()
        .route(
            "/v1/conversations",
            post(create_conversation).get(list_conversations),
        )
        .route(
            "/v1/conversations/{id}",
            get(get_conversation).delete(delete_conversation),
        )
        .route(
            "/v1/conversations/{id}/messages",
            post(append_message).get(list_messages),
        )
        .route(
            "/v1/conversations/{id}/checkpoints",
            post(save_checkpoint).get(list_checkpoints),
        )
}
