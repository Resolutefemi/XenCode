//! Storage layer: one trait, two implementations.
//!
//! - `MemoryStore`: zero-dependency default (dev, CI, sandbox), mirrors the
//!   Postgres schema in memory.
//! - `PostgresStore`: production store backed by sqlx with runtime queries
//!   and the embedded migration in `migrations/`.

pub mod memory;
pub mod postgres;

use crate::error::ApiResult;
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    pub suspended: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize)]
pub struct ConversationRow {
    pub id: Uuid,
    pub user_id: Uuid,
    pub title: String,
    pub project: String,
    pub model: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize)]
pub struct MessageRow {
    pub id: i64,
    pub conversation_id: Uuid,
    pub role: String,
    pub content: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize)]
pub struct CheckpointRow {
    pub id: Uuid,
    pub conversation_id: Uuid,
    pub label: String,
    pub snapshot: serde_json::Value,
    pub created_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize)]
pub struct ApiKeyRow {
    pub id: Uuid,
    pub name: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize)]
pub struct UsageRow {
    pub model: String,
    pub input_tokens: i64,
    pub output_tokens: i64,
    pub credits: i64,
}

#[derive(Clone, Debug, Serialize)]
pub struct PromptRow {
    pub name: String,
    pub version: i32,
    pub content: String,
    pub updated_at: DateTime<Utc>,
}

/// Everything the API needs from storage.
#[async_trait]
pub trait Store: Send + Sync {
    async fn health(&self) -> bool;

    // Users, magic links, sessions, API keys
    async fn upsert_user(&self, email: &str) -> ApiResult<User>;
    async fn user_by_id(&self, id: Uuid) -> ApiResult<Option<User>>;
    async fn set_suspended(&self, id: Uuid, suspended: bool) -> ApiResult<()>;

    async fn create_magic_token(
        &self,
        user_id: Uuid,
        token_hash: &str,
        expires_at: DateTime<Utc>,
    ) -> ApiResult<()>;
    async fn consume_magic_token(&self, token_hash: &str) -> ApiResult<Option<Uuid>>;

    async fn create_session(&self, user_id: Uuid, token_hash: &str) -> ApiResult<()>;
    async fn session_user(&self, token_hash: &str) -> ApiResult<Option<User>>;
    async fn revoke_session(&self, token_hash: &str) -> ApiResult<()>;

    async fn create_api_key(
        &self,
        user_id: Uuid,
        name: &str,
        key_hash: &str,
        id: Uuid,
    ) -> ApiResult<()>;
    async fn api_key_user(&self, key_hash: &str) -> ApiResult<Option<User>>;
    async fn revoke_api_key(&self, user_id: Uuid, id: Uuid) -> ApiResult<bool>;
    async fn list_api_keys(&self, user_id: Uuid) -> ApiResult<Vec<ApiKeyRow>>;

    // Conversations, messages, checkpoints
    async fn create_conversation(
        &self,
        user_id: Uuid,
        title: &str,
        project: &str,
        model: &str,
        id: Uuid,
    ) -> ApiResult<ConversationRow>;
    async fn list_conversations(&self, user_id: Uuid) -> ApiResult<Vec<ConversationRow>>;
    async fn get_conversation(&self, user_id: Uuid, id: Uuid)
        -> ApiResult<Option<ConversationRow>>;
    async fn delete_conversation(&self, user_id: Uuid, id: Uuid) -> ApiResult<bool>;
    async fn append_message(
        &self,
        conversation_id: Uuid,
        role: &str,
        content: &str,
        id: i64,
    ) -> ApiResult<()>;
    async fn list_messages(&self, conversation_id: Uuid) -> ApiResult<Vec<MessageRow>>;
    async fn save_checkpoint(
        &self,
        conversation_id: Uuid,
        label: &str,
        snapshot: serde_json::Value,
        id: Uuid,
    ) -> ApiResult<()>;
    async fn list_checkpoints(&self, conversation_id: Uuid) -> ApiResult<Vec<CheckpointRow>>;

    // Metering and credits
    async fn record_usage(
        &self,
        user_id: Uuid,
        model: &str,
        input_tokens: i64,
        output_tokens: i64,
        credits: i64,
    ) -> ApiResult<()>;
    async fn usage_summary(&self, user_id: Uuid) -> ApiResult<Vec<UsageRow>>;
    async fn credit_balance(&self, user_id: Uuid) -> ApiResult<i64>;
    async fn add_credits(&self, user_id: Uuid, delta: i64, reason: &str) -> ApiResult<()>;

    // Server-side prompts
    async fn get_prompt(&self, name: &str) -> ApiResult<Option<PromptRow>>;
    async fn upsert_prompt(&self, name: &str, content: &str) -> ApiResult<PromptRow>;
}

/// Charge computation shared by both stores and the gateway.
pub fn charge_for(input_tokens: i64, output_tokens: i64, credits_per_1k: i64) -> i64 {
    if credits_per_1k <= 0 {
        return 0;
    }
    ((input_tokens + output_tokens) + 999) / 1000 * credits_per_1k
}

/// Rough token estimate used when the upstream reports no usage.
pub fn estimate_tokens(text: &str) -> i64 {
    (text.len() as i64) / 4 + 1
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn charge_rounds_up_per_1k() {
        assert_eq!(charge_for(1, 0, 1), 1);
        assert_eq!(charge_for(1000, 0, 1), 1);
        assert_eq!(charge_for(1001, 0, 1), 2);
        assert_eq!(charge_for(500, 500, 2), 2);
        assert_eq!(charge_for(0, 0, 1), 0);
        assert_eq!(charge_for(10_000, 0, 0), 0);
    }

    #[test]
    fn estimates_from_chars() {
        assert!(estimate_tokens("hello world, this is a test") > 0);
        assert_eq!(estimate_tokens(""), 1);
    }
}
