//! In-memory store: the zero-dependency default (dev, CI, sandbox).

use super::*;
use crate::error::{ApiError, ApiResult};
use std::collections::HashMap;
use std::sync::Mutex;

#[derive(Default)]
struct Inner {
    users: HashMap<Uuid, User>,
    by_email: HashMap<String, Uuid>,
    magic_tokens: HashMap<String, (Uuid, DateTime<Utc>, bool)>,
    sessions: HashMap<String, (Uuid, bool)>,
    api_keys: HashMap<String, (Uuid, bool)>,
    api_key_rows: HashMap<Uuid, (Uuid, String)>, // key id -> (user, name)
    conversations: HashMap<Uuid, ConversationRow>,
    messages: Vec<MessageRow>,
    checkpoints: HashMap<Uuid, CheckpointRow>,
    usage: Vec<(Uuid, UsageRow)>,
    credit_events: Vec<(Uuid, i64, String)>,
    prompts: HashMap<String, PromptRow>,
}

pub struct MemoryStore {
    inner: Mutex<Inner>,
}

impl MemoryStore {
    pub fn new() -> Self {
        MemoryStore {
            inner: Mutex::new(Inner::default()),
        }
    }
}

impl Default for MemoryStore {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Store for MemoryStore {
    async fn health(&self) -> bool {
        true
    }

    async fn upsert_user(&self, email: &str) -> ApiResult<User> {
        let mut inner = self.inner.lock().unwrap();
        if let Some(id) = inner.by_email.get(email) {
            return inner
                .users
                .get(id)
                .cloned()
                .ok_or_else(|| ApiError::Internal("user missing".into()));
        }
        let user = User {
            id: Uuid::new_v4(),
            email: email.to_string(),
            suspended: false,
            created_at: Utc::now(),
        };
        inner.by_email.insert(user.email.clone(), user.id);
        inner.users.insert(user.id, user.clone());
        Ok(user)
    }

    async fn user_by_id(&self, id: Uuid) -> ApiResult<Option<User>> {
        Ok(self.inner.lock().unwrap().users.get(&id).cloned())
    }

    async fn set_suspended(&self, id: Uuid, suspended: bool) -> ApiResult<()> {
        let mut inner = self.inner.lock().unwrap();
        if let Some(u) = inner.users.get_mut(&id) {
            u.suspended = suspended;
        }
        Ok(())
    }

    async fn create_magic_token(
        &self,
        user_id: Uuid,
        token_hash: &str,
        expires_at: DateTime<Utc>,
    ) -> ApiResult<()> {
        self.inner
            .lock()
            .unwrap()
            .magic_tokens
            .insert(token_hash.to_string(), (user_id, expires_at, false));
        Ok(())
    }

    async fn consume_magic_token(&self, token_hash: &str) -> ApiResult<Option<Uuid>> {
        let mut inner = self.inner.lock().unwrap();
        match inner.magic_tokens.get_mut(token_hash) {
            None => Ok(None),
            Some((user_id, expires_at, used)) => {
                if *used || *expires_at < Utc::now() {
                    return Ok(None);
                }
                *used = true;
                Ok(Some(*user_id))
            }
        }
    }

    async fn create_session(&self, user_id: Uuid, token_hash: &str) -> ApiResult<()> {
        self.inner
            .lock()
            .unwrap()
            .sessions
            .insert(token_hash.to_string(), (user_id, false));
        Ok(())
    }

    async fn session_user(&self, token_hash: &str) -> ApiResult<Option<User>> {
        let inner = self.inner.lock().unwrap();
        match inner.sessions.get(token_hash) {
            Some((user_id, false)) => Ok(inner.users.get(user_id).cloned()),
            _ => Ok(None),
        }
    }

    async fn revoke_session(&self, token_hash: &str) -> ApiResult<()> {
        if let Some(entry) = self.inner.lock().unwrap().sessions.get_mut(token_hash) {
            entry.1 = true;
        }
        Ok(())
    }

    async fn create_api_key(
        &self,
        user_id: Uuid,
        name: &str,
        key_hash: &str,
        id: Uuid,
    ) -> ApiResult<()> {
        let mut inner = self.inner.lock().unwrap();
        inner
            .api_keys
            .insert(key_hash.to_string(), (user_id, false));
        inner.api_key_rows.insert(id, (user_id, name.to_string()));
        Ok(())
    }

    async fn api_key_user(&self, key_hash: &str) -> ApiResult<Option<User>> {
        let inner = self.inner.lock().unwrap();
        match inner.api_keys.get(key_hash) {
            Some((user_id, false)) => Ok(inner.users.get(user_id).cloned()),
            _ => Ok(None),
        }
    }

    async fn revoke_api_key(&self, user_id: Uuid, id: Uuid) -> ApiResult<bool> {
        let mut inner = self.inner.lock().unwrap();
        match inner.api_key_rows.remove(&id) {
            Some((owner, _)) if owner == user_id => {
                let hash = inner
                    .api_keys
                    .iter()
                    .find(|(_, (u, revoked))| *u == user_id && !*revoked)
                    .map(|(h, _)| h.clone());
                if let Some(hash) = hash {
                    if let Some(entry) = inner.api_keys.get_mut(&hash) {
                        entry.1 = true;
                    }
                }
                Ok(true)
            }
            _ => Ok(false),
        }
    }

    async fn list_api_keys(&self, user_id: Uuid) -> ApiResult<Vec<ApiKeyRow>> {
        let inner = self.inner.lock().unwrap();
        Ok(inner
            .api_key_rows
            .iter()
            .filter(|(_, (owner, _))| *owner == user_id)
            .map(|(id, (_, name))| ApiKeyRow {
                id: *id,
                name: name.clone(),
                created_at: Utc::now(),
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
        let now = Utc::now();
        let row = ConversationRow {
            id,
            user_id,
            title: title.to_string(),
            project: project.to_string(),
            model: model.to_string(),
            created_at: now,
            updated_at: now,
        };
        self.inner
            .lock()
            .unwrap()
            .conversations
            .insert(id, row.clone());
        Ok(row)
    }

    async fn list_conversations(&self, user_id: Uuid) -> ApiResult<Vec<ConversationRow>> {
        let inner = self.inner.lock().unwrap();
        let mut rows: Vec<ConversationRow> = inner
            .conversations
            .values()
            .filter(|c| c.user_id == user_id)
            .cloned()
            .collect();
        rows.sort_by_key(|c| std::cmp::Reverse(c.updated_at));
        Ok(rows)
    }

    async fn get_conversation(
        &self,
        user_id: Uuid,
        id: Uuid,
    ) -> ApiResult<Option<ConversationRow>> {
        Ok(self
            .inner
            .lock()
            .unwrap()
            .conversations
            .get(&id)
            .filter(|c| c.user_id == user_id)
            .cloned())
    }

    async fn delete_conversation(&self, user_id: Uuid, id: Uuid) -> ApiResult<bool> {
        let mut inner = self.inner.lock().unwrap();
        let owned = inner
            .conversations
            .get(&id)
            .map(|c| c.user_id == user_id)
            .unwrap_or(false);
        if !owned {
            return Ok(false);
        }
        inner.conversations.remove(&id);
        inner.messages.retain(|m| m.conversation_id != id);
        inner.checkpoints.retain(|_, c| c.conversation_id != id);
        Ok(true)
    }

    async fn append_message(
        &self,
        conversation_id: Uuid,
        role: &str,
        content: &str,
        id: i64,
    ) -> ApiResult<()> {
        let mut inner = self.inner.lock().unwrap();
        if !inner.conversations.contains_key(&conversation_id) {
            return Err(ApiError::NotFound("conversation not found".into()));
        }
        inner.messages.push(MessageRow {
            id,
            conversation_id,
            role: role.to_string(),
            content: content.to_string(),
            created_at: Utc::now(),
        });
        if let Some(c) = inner.conversations.get_mut(&conversation_id) {
            c.updated_at = Utc::now();
        }
        Ok(())
    }

    async fn list_messages(&self, conversation_id: Uuid) -> ApiResult<Vec<MessageRow>> {
        let inner = self.inner.lock().unwrap();
        let mut rows: Vec<MessageRow> = inner
            .messages
            .iter()
            .filter(|m| m.conversation_id == conversation_id)
            .cloned()
            .collect();
        rows.sort_by_key(|m| m.id);
        Ok(rows)
    }

    async fn save_checkpoint(
        &self,
        conversation_id: Uuid,
        label: &str,
        snapshot: serde_json::Value,
        id: Uuid,
    ) -> ApiResult<()> {
        self.inner.lock().unwrap().checkpoints.insert(
            id,
            CheckpointRow {
                id,
                conversation_id,
                label: label.to_string(),
                snapshot,
                created_at: Utc::now(),
            },
        );
        Ok(())
    }

    async fn list_checkpoints(&self, conversation_id: Uuid) -> ApiResult<Vec<CheckpointRow>> {
        let inner = self.inner.lock().unwrap();
        let mut rows: Vec<CheckpointRow> = inner
            .checkpoints
            .values()
            .filter(|c| c.conversation_id == conversation_id)
            .cloned()
            .collect();
        rows.sort_by_key(|c| std::cmp::Reverse(c.created_at));
        Ok(rows)
    }

    async fn record_usage(
        &self,
        user_id: Uuid,
        model: &str,
        input_tokens: i64,
        output_tokens: i64,
        credits: i64,
    ) -> ApiResult<()> {
        self.inner.lock().unwrap().usage.push((
            user_id,
            UsageRow {
                model: model.to_string(),
                input_tokens,
                output_tokens,
                credits,
            },
        ));
        Ok(())
    }

    async fn usage_summary(&self, user_id: Uuid) -> ApiResult<Vec<UsageRow>> {
        let inner = self.inner.lock().unwrap();
        let mut by_model: std::collections::BTreeMap<String, UsageRow> = Default::default();
        for (uid, row) in &inner.usage {
            if *uid != user_id {
                continue;
            }
            let e = by_model.entry(row.model.clone()).or_insert(UsageRow {
                model: row.model.clone(),
                input_tokens: 0,
                output_tokens: 0,
                credits: 0,
            });
            e.input_tokens += row.input_tokens;
            e.output_tokens += row.output_tokens;
            e.credits += row.credits;
        }
        Ok(by_model.into_values().collect())
    }

    async fn credit_balance(&self, user_id: Uuid) -> ApiResult<i64> {
        let inner = self.inner.lock().unwrap();
        Ok(inner
            .credit_events
            .iter()
            .filter(|(uid, _, _)| *uid == user_id)
            .map(|(_, delta, _)| *delta)
            .sum())
    }

    async fn add_credits(&self, user_id: Uuid, delta: i64, reason: &str) -> ApiResult<()> {
        self.inner
            .lock()
            .unwrap()
            .credit_events
            .push((user_id, delta, reason.to_string()));
        Ok(())
    }

    async fn get_prompt(&self, name: &str) -> ApiResult<Option<PromptRow>> {
        Ok(self.inner.lock().unwrap().prompts.get(name).cloned())
    }

    async fn upsert_prompt(&self, name: &str, content: &str) -> ApiResult<PromptRow> {
        let mut inner = self.inner.lock().unwrap();
        let row = match inner.prompts.get(name) {
            Some(existing) => {
                let mut next = existing.clone();
                next.version += 1;
                next.content = content.to_string();
                next.updated_at = Utc::now();
                next
            }
            None => PromptRow {
                name: name.to_string(),
                version: 1,
                content: content.to_string(),
                updated_at: Utc::now(),
            },
        };
        inner.prompts.insert(name.to_string(), row.clone());
        Ok(row)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn user_and_session_flow() {
        let store = MemoryStore::new();
        let user = store.upsert_user("dev@xencode.app").await.unwrap();
        assert_eq!(user.email, "dev@xencode.app");
        // Upsert is idempotent.
        let again = store.upsert_user("dev@xencode.app").await.unwrap();
        assert_eq!(again.id, user.id);

        store.create_session(user.id, "hash1").await.unwrap();
        assert!(store.session_user("hash1").await.unwrap().is_some());
        store.revoke_session("hash1").await.unwrap();
        assert!(store.session_user("hash1").await.unwrap().is_none());
    }

    #[tokio::test]
    async fn magic_token_single_use() {
        let store = MemoryStore::new();
        let user = store.upsert_user("magic@xencode.app").await.unwrap();
        store
            .create_magic_token(user.id, "tok", Utc::now() + chrono::Duration::minutes(10))
            .await
            .unwrap();
        assert_eq!(
            store.consume_magic_token("tok").await.unwrap(),
            Some(user.id)
        );
        assert_eq!(store.consume_magic_token("tok").await.unwrap(), None);
    }

    #[tokio::test]
    async fn credits_and_usage() {
        let store = MemoryStore::new();
        let user = store.upsert_user("meter@xencode.app").await.unwrap();
        store.add_credits(user.id, 500, "signup").await.unwrap();
        store
            .record_usage(user.id, "mock/local", 100, 200, 1)
            .await
            .unwrap();
        assert_eq!(store.credit_balance(user.id).await.unwrap(), 500);
        let usage = store.usage_summary(user.id).await.unwrap();
        assert_eq!(usage.len(), 1);
        assert_eq!(usage[0].output_tokens, 200);
    }
}
