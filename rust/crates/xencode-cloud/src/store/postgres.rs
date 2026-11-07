//! Postgres store (sqlx, runtime queries, embedded migration).

use super::*;
use crate::error::{ApiError, ApiResult};
use sqlx::postgres::PgPoolOptions;
use sqlx::{PgPool, Row};

pub struct PostgresStore {
    pool: PgPool,
}

impl PostgresStore {
    /// Connect and apply migrations. Timeout is short so a bad DATABASE_URL
    /// fails fast instead of hanging the process.
    pub async fn connect(url: &str) -> Result<Self, String> {
        let pool = PgPoolOptions::new()
            .max_connections(10)
            .acquire_timeout(std::time::Duration::from_secs(8))
            .connect(url)
            .await
            .map_err(|e| format!("connect: {e}"))?;
        sqlx::migrate!()
            .run(&pool)
            .await
            .map_err(|e| format!("migrate: {e}"))?;
        Ok(PostgresStore { pool })
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }
}

fn user_from_row(row: &sqlx::postgres::PgRow) -> User {
    User {
        id: row.try_get("id").unwrap_or_default(),
        email: row.try_get("email").unwrap_or_default(),
        suspended: row.try_get("suspended").unwrap_or(false),
        created_at: row.try_get("created_at").unwrap_or_else(|_| Utc::now()),
    }
}

#[async_trait]
impl Store for PostgresStore {
    async fn health(&self) -> bool {
        sqlx::query("SELECT 1").execute(&self.pool).await.is_ok()
    }

    async fn upsert_user(&self, email: &str) -> ApiResult<User> {
        let row = sqlx::query(
            "INSERT INTO users (id, email) VALUES ($1, $2)
             ON CONFLICT (email) DO UPDATE SET email = EXCLUDED.email
             RETURNING id, email, suspended, created_at",
        )
        .bind(Uuid::new_v4())
        .bind(email)
        .fetch_one(&self.pool)
        .await?;
        Ok(user_from_row(&row))
    }

    async fn user_by_id(&self, id: Uuid) -> ApiResult<Option<User>> {
        let row = sqlx::query("SELECT id, email, suspended, created_at FROM users WHERE id = $1")
            .bind(id)
            .fetch_optional(&self.pool)
            .await?;
        Ok(row.map(|r| user_from_row(&r)))
    }

    async fn set_suspended(&self, id: Uuid, suspended: bool) -> ApiResult<()> {
        sqlx::query("UPDATE users SET suspended = $2 WHERE id = $1")
            .bind(id)
            .bind(suspended)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    async fn create_magic_token(
        &self,
        user_id: Uuid,
        token_hash: &str,
        expires_at: DateTime<Utc>,
    ) -> ApiResult<()> {
        sqlx::query(
            "INSERT INTO magic_tokens (token_hash, user_id, expires_at) VALUES ($1, $2, $3)",
        )
        .bind(token_hash)
        .bind(user_id)
        .bind(expires_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn consume_magic_token(&self, token_hash: &str) -> ApiResult<Option<Uuid>> {
        let row = sqlx::query(
            "UPDATE magic_tokens SET used = TRUE
             WHERE token_hash = $1 AND used = FALSE AND expires_at > now()
             RETURNING user_id",
        )
        .bind(token_hash)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(|r| r.try_get("user_id").unwrap_or_default()))
    }

    async fn create_session(&self, user_id: Uuid, token_hash: &str) -> ApiResult<()> {
        sqlx::query("INSERT INTO sessions (token_hash, user_id) VALUES ($1, $2)")
            .bind(token_hash)
            .bind(user_id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    async fn session_user(&self, token_hash: &str) -> ApiResult<Option<User>> {
        let row = sqlx::query(
            "SELECT u.id, u.email, u.suspended, u.created_at
             FROM sessions s JOIN users u ON u.id = s.user_id
             WHERE s.token_hash = $1 AND s.revoked = FALSE",
        )
        .bind(token_hash)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(|r| user_from_row(&r)))
    }

    async fn revoke_session(&self, token_hash: &str) -> ApiResult<()> {
        sqlx::query("UPDATE sessions SET revoked = TRUE WHERE token_hash = $1")
            .bind(token_hash)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    async fn create_api_key(
        &self,
        user_id: Uuid,
        name: &str,
        key_hash: &str,
        id: Uuid,
    ) -> ApiResult<()> {
        sqlx::query("INSERT INTO api_keys (id, user_id, name, key_hash) VALUES ($1, $2, $3, $4)")
            .bind(id)
            .bind(user_id)
            .bind(name)
            .bind(key_hash)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    async fn api_key_user(&self, key_hash: &str) -> ApiResult<Option<User>> {
        let row = sqlx::query(
            "SELECT u.id, u.email, u.suspended, u.created_at
             FROM api_keys k JOIN users u ON u.id = k.user_id
             WHERE k.key_hash = $1 AND k.revoked = FALSE",
        )
        .bind(key_hash)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(|r| user_from_row(&r)))
    }

    async fn revoke_api_key(&self, user_id: Uuid, id: Uuid) -> ApiResult<bool> {
        let res = sqlx::query("UPDATE api_keys SET revoked = TRUE WHERE id = $1 AND user_id = $2")
            .bind(id)
            .bind(user_id)
            .execute(&self.pool)
            .await?;
        Ok(res.rows_affected() > 0)
    }

    async fn list_api_keys(&self, user_id: Uuid) -> ApiResult<Vec<ApiKeyRow>> {
        let rows = sqlx::query("SELECT id, name, created_at FROM api_keys WHERE user_id = $1 AND revoked = FALSE ORDER BY created_at")
            .bind(user_id)
            .fetch_all(&self.pool)
            .await?;
        Ok(rows
            .iter()
            .map(|r| ApiKeyRow {
                id: r.try_get("id").unwrap_or_default(),
                name: r.try_get("name").unwrap_or_default(),
                created_at: r.try_get("created_at").unwrap_or_else(|_| Utc::now()),
            })
            .collect())
    }

    async fn create_conversation(
        &self,
        user_id: Uuid,
        title: &str,
        project: &str,
        model: &str,
        id: Uuid,
    ) -> ApiResult<ConversationRow> {
        let row = sqlx::query(
            "INSERT INTO conversations (id, user_id, title, project, model)
             VALUES ($1, $2, $3, $4, $5)
             RETURNING id, user_id, title, project, model, created_at, updated_at",
        )
        .bind(id)
        .bind(user_id)
        .bind(title)
        .bind(project)
        .bind(model)
        .fetch_one(&self.pool)
        .await?;
        Ok(ConversationRow {
            id: row.try_get("id").unwrap_or_default(),
            user_id: row.try_get("user_id").unwrap_or_default(),
            title: row.try_get("title").unwrap_or_default(),
            project: row.try_get("project").unwrap_or_default(),
            model: row.try_get("model").unwrap_or_default(),
            created_at: row.try_get("created_at").unwrap_or_else(|_| Utc::now()),
            updated_at: row.try_get("updated_at").unwrap_or_else(|_| Utc::now()),
        })
    }

    async fn list_conversations(&self, user_id: Uuid) -> ApiResult<Vec<ConversationRow>> {
        let rows = sqlx::query(
            "SELECT id, user_id, title, project, model, created_at, updated_at
             FROM conversations WHERE user_id = $1 ORDER BY updated_at DESC",
        )
        .bind(user_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows
            .iter()
            .map(|row| ConversationRow {
                id: row.try_get("id").unwrap_or_default(),
                user_id: row.try_get("user_id").unwrap_or_default(),
                title: row.try_get("title").unwrap_or_default(),
                project: row.try_get("project").unwrap_or_default(),
                model: row.try_get("model").unwrap_or_default(),
                created_at: row.try_get("created_at").unwrap_or_else(|_| Utc::now()),
                updated_at: row.try_get("updated_at").unwrap_or_else(|_| Utc::now()),
            })
            .collect())
    }

    async fn get_conversation(
        &self,
        user_id: Uuid,
        id: Uuid,
    ) -> ApiResult<Option<ConversationRow>> {
        let row = sqlx::query(
            "SELECT id, user_id, title, project, model, created_at, updated_at
             FROM conversations WHERE id = $1 AND user_id = $2",
        )
        .bind(id)
        .bind(user_id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(|row| ConversationRow {
            id: row.try_get("id").unwrap_or_default(),
            user_id: row.try_get("user_id").unwrap_or_default(),
            title: row.try_get("title").unwrap_or_default(),
            project: row.try_get("project").unwrap_or_default(),
            model: row.try_get("model").unwrap_or_default(),
            created_at: row.try_get("created_at").unwrap_or_else(|_| Utc::now()),
            updated_at: row.try_get("updated_at").unwrap_or_else(|_| Utc::now()),
        }))
    }

    async fn delete_conversation(&self, user_id: Uuid, id: Uuid) -> ApiResult<bool> {
        let res = sqlx::query("DELETE FROM conversations WHERE id = $1 AND user_id = $2")
            .bind(id)
            .bind(user_id)
            .execute(&self.pool)
            .await?;
        Ok(res.rows_affected() > 0)
    }

    async fn append_message(
        &self,
        conversation_id: Uuid,
        role: &str,
        content: &str,
        _id: i64,
    ) -> ApiResult<()> {
        // The caller supplies an id for the memory store; Postgres uses its
        // own BIGSERIAL sequence and ignores it.
        let res = sqlx::query(
            "INSERT INTO messages (conversation_id, role, content) VALUES ($1, $2, $3)",
        )
        .bind(conversation_id)
        .bind(role)
        .bind(content)
        .execute(&self.pool)
        .await?;
        if res.rows_affected() == 0 {
            return Err(ApiError::NotFound("conversation not found".into()));
        }
        sqlx::query("UPDATE conversations SET updated_at = now() WHERE id = $1")
            .bind(conversation_id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    async fn list_messages(&self, conversation_id: Uuid) -> ApiResult<Vec<MessageRow>> {
        let rows = sqlx::query(
            "SELECT id, conversation_id, role, content, created_at
             FROM messages WHERE conversation_id = $1 ORDER BY id",
        )
        .bind(conversation_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows
            .iter()
            .map(|row| MessageRow {
                id: row.try_get("id").unwrap_or_default(),
                conversation_id: row.try_get("conversation_id").unwrap_or_default(),
                role: row.try_get("role").unwrap_or_default(),
                content: row.try_get("content").unwrap_or_default(),
                created_at: row.try_get("created_at").unwrap_or_else(|_| Utc::now()),
            })
            .collect())
    }

    async fn save_checkpoint(
        &self,
        conversation_id: Uuid,
        label: &str,
        snapshot: serde_json::Value,
        id: Uuid,
    ) -> ApiResult<()> {
        sqlx::query("INSERT INTO checkpoints (id, conversation_id, label, snapshot) VALUES ($1, $2, $3, $4)")
            .bind(id)
            .bind(conversation_id)
            .bind(label)
            .bind(snapshot)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    async fn list_checkpoints(&self, conversation_id: Uuid) -> ApiResult<Vec<CheckpointRow>> {
        let rows = sqlx::query(
            "SELECT id, conversation_id, label, snapshot, created_at
             FROM checkpoints WHERE conversation_id = $1 ORDER BY created_at DESC",
        )
        .bind(conversation_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows
            .iter()
            .map(|row| CheckpointRow {
                id: row.try_get("id").unwrap_or_default(),
                conversation_id: row.try_get("conversation_id").unwrap_or_default(),
                label: row.try_get("label").unwrap_or_default(),
                snapshot: row.try_get("snapshot").unwrap_or(serde_json::Value::Null),
                created_at: row.try_get("created_at").unwrap_or_else(|_| Utc::now()),
            })
            .collect())
    }

    async fn record_usage(
        &self,
        user_id: Uuid,
        model: &str,
        input_tokens: i64,
        output_tokens: i64,
        credits: i64,
    ) -> ApiResult<()> {
        sqlx::query(
            "INSERT INTO usage_events (user_id, model, input_tokens, output_tokens, credits)
             VALUES ($1, $2, $3, $4, $5)",
        )
        .bind(user_id)
        .bind(model)
        .bind(input_tokens)
        .bind(output_tokens)
        .bind(credits)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn usage_summary(&self, user_id: Uuid) -> ApiResult<Vec<UsageRow>> {
        let rows = sqlx::query(
            "SELECT model, SUM(input_tokens) AS i, SUM(output_tokens) AS o, SUM(credits) AS c
             FROM usage_events WHERE user_id = $1 GROUP BY model",
        )
        .bind(user_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows
            .iter()
            .map(|r| UsageRow {
                model: r.try_get("model").unwrap_or_default(),
                input_tokens: r.try_get::<i64, _>("i").unwrap_or_default(),
                output_tokens: r.try_get::<i64, _>("o").unwrap_or_default(),
                credits: r.try_get::<i64, _>("c").unwrap_or_default(),
            })
            .collect())
    }

    async fn credit_balance(&self, user_id: Uuid) -> ApiResult<i64> {
        let row = sqlx::query(
            "SELECT COALESCE(SUM(delta), 0) AS balance FROM credit_events WHERE user_id = $1",
        )
        .bind(user_id)
        .fetch_one(&self.pool)
        .await?;
        Ok(row.try_get::<i64, _>("balance").unwrap_or(0))
    }

    async fn add_credits(&self, user_id: Uuid, delta: i64, reason: &str) -> ApiResult<()> {
        sqlx::query("INSERT INTO credit_events (user_id, delta, reason) VALUES ($1, $2, $3)")
            .bind(user_id)
            .bind(delta)
            .bind(reason)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    async fn get_prompt(&self, name: &str) -> ApiResult<Option<PromptRow>> {
        let row =
            sqlx::query("SELECT name, version, content, updated_at FROM prompts WHERE name = $1")
                .bind(name)
                .fetch_optional(&self.pool)
                .await?;
        Ok(row.map(|r| PromptRow {
            name: r.try_get("name").unwrap_or_default(),
            version: r.try_get("version").unwrap_or(1),
            content: r.try_get("content").unwrap_or_default(),
            updated_at: r.try_get("updated_at").unwrap_or_else(|_| Utc::now()),
        }))
    }

    async fn upsert_prompt(&self, name: &str, content: &str) -> ApiResult<PromptRow> {
        let row = sqlx::query(
            "INSERT INTO prompts (name, content) VALUES ($1, $2)
             ON CONFLICT (name) DO UPDATE SET content = EXCLUDED.content, version = prompts.version + 1, updated_at = now()
             RETURNING name, version, content, updated_at",
        )
        .bind(name)
        .bind(content)
        .fetch_one(&self.pool)
        .await?;
        Ok(PromptRow {
            name: row.try_get("name").unwrap_or_default(),
            version: row.try_get("version").unwrap_or(1),
            content: row.try_get("content").unwrap_or_default(),
            updated_at: row.try_get("updated_at").unwrap_or_else(|_| Utc::now()),
        })
    }
}
