//! Shared SQLite store — the Kotlin app's own `aicode_agent_db` (Room v59),
//! opened directly by the Rust PC line.
//!
//! This is the single brain for **both** lines per project decision
//! (see `.xencode/DECISIONS.md`, D-003): mobile (Room) and PC (rusqlite) read
//! and write the same database file with the same tables, columns, defaults,
//! indices and `room_master_table` identity stamp. Sessions, messages, todos,
//! checkpoints, LLM call records, XP events, provider configs and remote
//! connections created on one platform are first-class on the other.

pub mod schema;

use crate::error::{Result, XeError};
use crate::{now_ms, short_id};
use rusqlite::{params, Connection, OptionalExtension, Row};
use serde::{Deserialize, Serialize};
use std::path::Path;

pub use schema::{CREATE_ROOM_MASTER, CREATE_TABLES, DATABASE_NAME, IDENTITY_HASH, SCHEMA_VERSION,
                 STAMP_IDENTITY_SQL};

// ---------------------------------------------------------------------------
// Row types — field-for-field mirrors of the Room entities (camelCase kept so
// the serde JSON shape also matches the Kotlin DTO layer).
// ---------------------------------------------------------------------------

fn sql_err(e: rusqlite::Error) -> XeError {
    XeError::Db(e.to_string())
}

/// Mirror of [`crate::store`] `AgentMessageEntity` → `agent_messages`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
#[derive(Default)]
pub struct AgentMessageRow {
    pub id: String,
    pub session_id: String,
    pub role: String,
    pub content: String,
    pub timestamp: i64,
    pub tool_calls_json: Option<String>,
    pub tool_call_id: Option<String>,
    pub tool_name: Option<String>,
    pub tool_args: Option<String>,
    pub is_error: bool,
    pub reasoning: Option<String>,
    pub signature: Option<String>,
    pub attachments_json: Option<String>,
    pub is_compacted: bool,
    pub is_context_summary: bool,
    pub is_compaction_marker: bool,
    pub input_tokens: i64,
    pub output_tokens: i64,
    pub thinking_blocks_json: Option<String>,
    pub cached_input_tokens: i64,
    pub is_context_excluded: bool,
    pub compacted_by_summary_id: Option<String>,
}


/// Mirror of `ChatSessionEntity` → `chat_sessions`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct ChatSessionRow {
    pub id: String,
    pub title: String,
    pub created_at: i64,
    pub updated_at: i64,
    pub workspace_path: String,
    pub mode: String,
    pub mode_before_plan: Option<String>,
    pub reasoning_effort: String,
    pub provider_id: Option<String>,
    pub model: Option<String>,
    pub total_input_tokens: i64,
    pub total_output_tokens: i64,
    pub last_input_tokens: i64,
    pub is_pinned: bool,
    pub parent_id: Option<String>,
    pub subagent_type: Option<String>,
}

impl Default for ChatSessionRow {
    fn default() -> Self {
        Self {
            id: short_id(),
            title: "New chat".into(),
            created_at: now_ms(),
            updated_at: now_ms(),
            workspace_path: String::new(),
            mode: "BUILD".into(),
            mode_before_plan: None,
            reasoning_effort: "MEDIUM".into(),
            provider_id: None,
            model: None,
            total_input_tokens: 0,
            total_output_tokens: 0,
            last_input_tokens: 0,
            is_pinned: false,
            parent_id: None,
            subagent_type: None,
        }
    }
}

/// Mirror of `AIProviderEntity` → `ai_providers` (the shared provider config
/// both lines call through the same wire formats).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct AIProviderRow {
    pub id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub api_key: String,
    pub multi_key_enabled: bool,
    pub api_keys: String,
    pub key_rotation_strategy: String,
    pub key_failover_threshold: i64,
    pub key_cooldown_minutes: i64,
    pub key_switch_status_codes: String,
    pub base_url: String,
    pub default_model: String,
    pub models: String,
    pub selected_model: String,
    pub is_enabled: bool,
    pub use_full_url: bool,
    pub use_response_api: bool,
    pub anthropic_cache_breakpoints: bool,
    pub openai_chat_cache_key: bool,
    pub balance_script_path: String,
    pub balance_refresh_interval: i64,
    pub user_agent: String,
    pub custom_headers: String,
    pub sort_order: i64,
    pub proxy_enabled: bool,
    pub proxy_type: String,
    pub proxy_host: String,
    pub proxy_port: i64,
    pub proxy_username: String,
    pub proxy_password: String,
    pub script_params: String,
}

impl Default for AIProviderRow {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            kind: String::new(),
            api_key: String::new(),
            multi_key_enabled: false,
            api_keys: String::new(),
            key_rotation_strategy: "SEQUENTIAL".into(),
            key_failover_threshold: 2,
            key_cooldown_minutes: 5,
            key_switch_status_codes: String::new(),
            base_url: String::new(),
            default_model: String::new(),
            models: String::new(),
            selected_model: String::new(),
            is_enabled: true,
            use_full_url: false,
            use_response_api: false,
            anthropic_cache_breakpoints: true,
            openai_chat_cache_key: false,
            balance_script_path: String::new(),
            balance_refresh_interval: 5,
            user_agent: String::new(),
            custom_headers: String::new(),
            sort_order: 0,
            proxy_enabled: false,
            proxy_type: "HTTP".into(),
            proxy_host: String::new(),
            proxy_port: 0,
            proxy_username: String::new(),
            proxy_password: String::new(),
            script_params: String::new(),
        }
    }
}

/// Mirror of `TodoItemEntity` → `todo_items`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct TodoItemRow {
    pub id: String,
    pub session_id: String,
    pub subject: String,
    pub description: String,
    pub status: String,
    pub priority: i64,
    #[serde(rename = "order")]
    pub order: i64,
    pub created_at: i64,
    pub updated_at: i64,
}

impl Default for TodoItemRow {
    fn default() -> Self {
        Self {
            id: short_id(),
            session_id: String::new(),
            subject: String::new(),
            description: String::new(),
            status: "PENDING".into(),
            priority: 0,
            order: 0,
            created_at: now_ms(),
            updated_at: now_ms(),
        }
    }
}

/// Mirror of `CheckpointEntity` → `session_checkpoints`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct CheckpointRow {
    pub id: String,
    pub session_id: String,
    pub user_message_id: String,
    pub prompt_snippet: String,
    pub created_at: i64,
}

impl Default for CheckpointRow {
    fn default() -> Self {
        Self {
            id: String::new(),
            session_id: String::new(),
            user_message_id: String::new(),
            prompt_snippet: String::new(),
            created_at: now_ms(),
        }
    }
}

/// Mirror of `CheckpointFileSnapshotEntity` → `checkpoint_file_snapshots`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct CheckpointFileSnapshotRow {
    pub id: String,
    pub checkpoint_id: String,
    pub file_path: String,
    pub snapshot_relative_path: String,
    pub change_type: String,
    pub created_at: i64,
}

impl Default for CheckpointFileSnapshotRow {
    fn default() -> Self {
        Self {
            id: String::new(),
            checkpoint_id: String::new(),
            file_path: String::new(),
            snapshot_relative_path: String::new(),
            change_type: String::new(),
            created_at: now_ms(),
        }
    }
}

/// Mirror of `LlmCallRecordEntity` → `llm_call_records`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct LlmCallRecordRow {
    pub id: i64,
    pub session_id: Option<String>,
    pub provider_id: Option<String>,
    pub model: Option<String>,
    pub reasoning_effort: Option<String>,
    pub kind: String,
    pub input_tokens: i64,
    pub output_tokens: i64,
    pub cached_input_tokens: i64,
    pub cache_creation_tokens: i64,
    pub ttfb_millis: Option<i64>,
    pub duration_millis: Option<i64>,
    pub status: String,
    pub error_message: Option<String>,
    pub stop_reason: Option<String>,
    pub retry_count: i64,
    pub created_at: i64,
}

impl Default for LlmCallRecordRow {
    fn default() -> Self {
        Self {
            id: 0,
            session_id: None,
            provider_id: None,
            model: None,
            reasoning_effort: None,
            kind: "chat".into(),
            input_tokens: 0,
            output_tokens: 0,
            cached_input_tokens: 0,
            cache_creation_tokens: 0,
            ttfb_millis: None,
            duration_millis: None,
            status: "success".into(),
            error_message: None,
            stop_reason: None,
            retry_count: 0,
            created_at: now_ms(),
        }
    }
}

/// Mirror of `XpEventEntity` → `xp_events`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct XpEventRow {
    pub id: i64,
    pub session_id: String,
    pub kind: String,
    pub amount: i64,
    pub label: String,
    pub created_at: i64,
}

impl Default for XpEventRow {
    fn default() -> Self {
        Self {
            id: 0,
            session_id: String::new(),
            kind: String::new(),
            amount: 0,
            label: String::new(),
            created_at: now_ms(),
        }
    }
}

/// Mirror of `RemoteConnectionEntity` → `remote_connections`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct RemoteConnectionRow {
    pub id: String,
    pub name: String,
    pub protocol: String,
    pub host: String,
    pub port: i64,
    pub username: String,
    pub auth_type: String,
    pub auth_data: String,
    pub passphrase: Option<String>,
    #[serde(rename = "created_at")]
    pub created_at: i64,
}

impl Default for RemoteConnectionRow {
    fn default() -> Self {
        Self {
            id: uuid::Uuid::new_v4().to_string(),
            name: String::new(),
            protocol: String::new(),
            host: String::new(),
            port: 0,
            username: String::new(),
            auth_type: "password".into(),
            auth_data: String::new(),
            passphrase: None,
            created_at: 0,
        }
    }
}

/// Mirror of `RemoteMountEntity` → `remote_mounts`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct RemoteMountRow {
    pub id: String,
    pub connection_id: String,
    pub remote_path: String,
    pub local_mount_path: String,
    pub is_active: bool,
    pub auto_connect: bool,
    #[serde(rename = "created_at")]
    pub created_at: i64,
}

impl Default for RemoteMountRow {
    fn default() -> Self {
        Self {
            id: uuid::Uuid::new_v4().to_string(),
            connection_id: String::new(),
            remote_path: String::new(),
            local_mount_path: String::new(),
            is_active: false,
            auto_connect: true,
            created_at: 0,
        }
    }
}

// ---------------------------------------------------------------------------
// SharedStore
// ---------------------------------------------------------------------------

/// A rusqlite connection to the shared `aicode_agent_db`, schema-compatible
/// with Room v59 on both create and open.
pub struct SharedStore {
    conn: Connection,
}

impl SharedStore {
    /// Open (or create) the database at `path`.
    ///
    /// - Fresh file → creates the full v59 schema, stamps `room_master_table`
    ///   with the Room identity hash and sets `PRAGMA user_version = 59`, so
    ///   the Kotlin app opens it as a native Room database.
    /// - Existing file → leaves data untouched; only verifies the Room stamp
    ///   is present (writing it when the file predates it is safe because the
    ///   schema DDL above is byte-identical to Room's).
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent).map_err(XeError::Io)?;
            }
        }
        let conn = Connection::open(path).map_err(sql_err)?;
        Self::init(conn)
    }

    /// In-memory store (tests, dry runs, `xencode db doctor --memory`).
    pub fn open_in_memory() -> Result<Self> {
        Self::init(Connection::open_in_memory().map_err(sql_err)?)
    }

    fn init(conn: Connection) -> Result<Self> {
        // Match Room's operating mode: WAL journal + foreign keys enforced.
        conn.pragma_update(None, "journal_mode", "WAL").map_err(sql_err)?;
        conn.pragma_update(None, "foreign_keys", "ON").map_err(sql_err)?;
        // 5s busy timeout: phone and PC may hold the file simultaneously.
        conn.busy_timeout(std::time::Duration::from_secs(5)).map_err(sql_err)?;

        let version: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0)).map_err(sql_err)?;
        let is_fresh = version == 0;
        if is_fresh {
            for stmt in CREATE_TABLES {
                conn.execute_batch(stmt).map_err(sql_err)?;
            }
            conn.execute_batch(CREATE_ROOM_MASTER).map_err(sql_err)?;
        }
        // Stamp (or verify) the Room identity — this is what makes the file
        // openable by the Kotlin app.
        conn.execute(STAMP_IDENTITY_SQL, params![IDENTITY_HASH]).map_err(sql_err)?;
        if is_fresh {
            conn.pragma_update(None, "user_version", SCHEMA_VERSION).map_err(sql_err)?;
        }
        Ok(Self { conn })
    }

    /// Row counts for every entity table (used by `xencode db doctor`).
    pub fn table_counts(&self) -> Result<Vec<(String, i64)>> {
        let mut out = Vec::new();
        for stmt in CREATE_TABLES {
            if !stmt.starts_with("CREATE TABLE") {
                continue; // skip index statements
            }
            let Some(table) = stmt.split('`').nth(1) else { continue };
            let sql = format!("SELECT COUNT(*) FROM `{table}`");
            let n: i64 = self
                .conn
                .query_row(&sql, [], |r| r.get(0))
                .map_err(sql_err)?;
            out.push((table.to_string(), n));
        }
        Ok(out)
    }

    /// The identity hash stamped in `room_master_table` (None when missing).
    pub fn stamped_identity(&self) -> Result<Option<String>> {
        self.conn
            .query_row("SELECT identity FROM room_master_table LIMIT 1", [], |r| r.get(0))
            .optional()
            .map_err(sql_err)
    }

    /// The Room schema version recorded in the file.
    pub fn user_version(&self) -> Result<i64> {
        self.conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .map_err(sql_err)
    }

    /// Raw connection access (metadata assertions in tests, advanced tooling).
    pub fn raw(&self) -> &Connection {
        &self.conn
    }

    fn map_session(r: &Row) -> rusqlite::Result<ChatSessionRow> {
        Ok(ChatSessionRow {
            id: r.get(0)?,
            title: r.get(1)?,
            created_at: r.get(2)?,
            updated_at: r.get(3)?,
            workspace_path: r.get(4)?,
            mode: r.get(5)?,
            mode_before_plan: r.get(6)?,
            reasoning_effort: r.get(7)?,
            provider_id: r.get(8)?,
            model: r.get(9)?,
            total_input_tokens: r.get(10)?,
            total_output_tokens: r.get(11)?,
            last_input_tokens: r.get(12)?,
            is_pinned: r.get::<_, i64>(13)? != 0,
            parent_id: r.get(14)?,
            subagent_type: r.get(15)?,
        })
    }

    const SESSION_COLS: &'static str =
        "id, title, createdAt, updatedAt, workspacePath, mode, modeBeforePlan, reasoningEffort, \
         providerId, model, totalInputTokens, totalOutputTokens, lastInputTokens, isPinned, \
         parentId, subagentType";

    /// Insert or replace a session (mirrors Room `@Upsert`).
    pub fn session_upsert(&self, s: &ChatSessionRow) -> Result<()> {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO chat_sessions
                 (id, title, createdAt, updatedAt, workspacePath, mode, modeBeforePlan,
                  reasoningEffort, providerId, model, totalInputTokens, totalOutputTokens,
                  lastInputTokens, isPinned, parentId, subagentType)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16)",
                params![
                    s.id, s.title, s.created_at, s.updated_at, s.workspace_path, s.mode,
                    s.mode_before_plan, s.reasoning_effort, s.provider_id, s.model,
                    s.total_input_tokens, s.total_output_tokens, s.last_input_tokens,
                    s.is_pinned as i64, s.parent_id, s.subagent_type
                ],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// `SELECT * FROM chat_sessions WHERE id = :id`
    pub fn session_get(&self, id: &str) -> Result<Option<ChatSessionRow>> {
        let sql = format!("SELECT {} FROM chat_sessions WHERE id = ?1", Self::SESSION_COLS);
        self.conn
            .query_row(&sql, params![id], Self::map_session)
            .optional()
            .map_err(sql_err)
    }

    /// `WHERE workspacePath = ? ORDER BY isPinned DESC, updatedAt DESC` (DAO parity).
    pub fn session_list(&self, workspace_path: &str) -> Result<Vec<ChatSessionRow>> {
        let sql = format!(
            "SELECT {} FROM chat_sessions WHERE workspacePath = ?1
             ORDER BY isPinned DESC, updatedAt DESC",
            Self::SESSION_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map(params![workspace_path], Self::map_session)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// All sessions regardless of workspace — the Kotlin `getAllSessions()`
    /// query (`SELECT * FROM chat_sessions`); display-ordered newest-first.
    pub fn session_list_all(&self) -> Result<Vec<ChatSessionRow>> {
        let sql = format!(
            "SELECT {} FROM chat_sessions ORDER BY updatedAt DESC",
            Self::SESSION_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map([], Self::map_session)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// Root sessions only (`parentId IS NULL`), DAO parity.
    pub fn session_list_roots(&self, workspace_path: &str) -> Result<Vec<ChatSessionRow>> {
        let sql = format!(
            "SELECT {} FROM chat_sessions WHERE workspacePath = ?1 AND parentId IS NULL
             ORDER BY isPinned DESC, updatedAt DESC",
            Self::SESSION_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map(params![workspace_path], Self::map_session)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// `UPDATE chat_sessions SET title = ? WHERE id = ?`
    pub fn session_set_title(&self, id: &str, title: &str) -> Result<()> {
        self.conn
            .execute("UPDATE chat_sessions SET title = ?1 WHERE id = ?2", params![title, id])
            .map_err(sql_err)?;
        Ok(())
    }

    /// `UPDATE chat_sessions SET updatedAt = ? WHERE id = ?`
    pub fn session_touch(&self, id: &str, updated_at: i64) -> Result<()> {
        self.conn
            .execute("UPDATE chat_sessions SET updatedAt = ?1 WHERE id = ?2", params![updated_at, id])
            .map_err(sql_err)?;
        Ok(())
    }

    /// `UPDATE chat_sessions SET isPinned = ? WHERE id = ?`
    pub fn session_set_pinned(&self, id: &str, pinned: bool) -> Result<()> {
        self.conn
            .execute(
                "UPDATE chat_sessions SET isPinned = ?1 WHERE id = ?2",
                params![pinned as i64, id],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// `UPDATE chat_sessions SET providerId = ?, model = ?, lastInputTokens = 0 WHERE id = ?`
    pub fn session_set_provider_model(&self, id: &str, provider_id: &str, model: &str) -> Result<()> {
        self.conn
            .execute(
                "UPDATE chat_sessions SET providerId = ?1, model = ?2, lastInputTokens = 0 WHERE id = ?3",
                params![provider_id, model, id],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// `UPDATE chat_sessions SET reasoningEffort = ? WHERE id = ?`
    pub fn session_set_reasoning_effort(&self, id: &str, effort: &str) -> Result<()> {
        self.conn
            .execute(
                "UPDATE chat_sessions SET reasoningEffort = ?1 WHERE id = ?2",
                params![effort, id],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// `UPDATE chat_sessions SET totalInputTokens = totalInputTokens + ?, totalOutputTokens = totalOutputTokens + ?`
    pub fn session_add_tokens(&self, id: &str, input_tokens: i64, output_tokens: i64) -> Result<()> {
        self.conn
            .execute(
                "UPDATE chat_sessions
                 SET totalInputTokens = totalInputTokens + ?1, totalOutputTokens = totalOutputTokens + ?2
                 WHERE id = ?3",
                params![input_tokens, output_tokens, id],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// `DELETE FROM chat_sessions WHERE id = ?`
    pub fn session_delete(&self, id: &str) -> Result<()> {
        self.conn
            .execute("DELETE FROM chat_sessions WHERE id = ?1", params![id])
            .map_err(sql_err)?;
        Ok(())
    }

    const MESSAGE_COLS: &'static str =
        "id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs, \
         isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, \
         isCompactionMarker, inputTokens, outputTokens, thinkingBlocksJson, cachedInputTokens, \
         isContextExcluded, compactedBySummaryId";

    fn map_message(r: &Row) -> rusqlite::Result<AgentMessageRow> {
        Ok(AgentMessageRow {
            id: r.get(0)?,
            session_id: r.get(1)?,
            role: r.get(2)?,
            content: r.get(3)?,
            timestamp: r.get(4)?,
            tool_calls_json: r.get(5)?,
            tool_call_id: r.get(6)?,
            tool_name: r.get(7)?,
            tool_args: r.get(8)?,
            is_error: r.get::<_, i64>(9)? != 0,
            reasoning: r.get(10)?,
            signature: r.get(11)?,
            attachments_json: r.get(12)?,
            is_compacted: r.get::<_, i64>(13)? != 0,
            is_context_summary: r.get::<_, i64>(14)? != 0,
            is_compaction_marker: r.get::<_, i64>(15)? != 0,
            input_tokens: r.get(16)?,
            output_tokens: r.get(17)?,
            thinking_blocks_json: r.get(18)?,
            cached_input_tokens: r.get(19)?,
            is_context_excluded: r.get::<_, i64>(20)? != 0,
            compacted_by_summary_id: r.get(21)?,
        })
    }

    /// Append a message row (ids are generated client-side, exactly like Room).
    pub fn message_append(&self, m: &AgentMessageRow) -> Result<()> {
        self.conn
            .execute(
                "INSERT INTO agent_messages
                 (id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName,
                  toolArgs, isError, reasoning, signature, attachmentsJson, isCompacted,
                  isContextSummary, isCompactionMarker, inputTokens, outputTokens,
                  thinkingBlocksJson, cachedInputTokens, isContextExcluded, compactedBySummaryId)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20,?21,?22)",
                params![
                    m.id, m.session_id, m.role, m.content, m.timestamp, m.tool_calls_json,
                    m.tool_call_id, m.tool_name, m.tool_args, m.is_error as i64, m.reasoning,
                    m.signature, m.attachments_json, m.is_compacted as i64,
                    m.is_context_summary as i64, m.is_compaction_marker as i64,
                    m.input_tokens, m.output_tokens, m.thinking_blocks_json,
                    m.cached_input_tokens, m.is_context_excluded as i64,
                    m.compacted_by_summary_id
                ],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// `SELECT * FROM agent_messages WHERE sessionId = ? ORDER BY timestamp ASC` (DAO parity).
    pub fn message_list(&self, session_id: &str) -> Result<Vec<AgentMessageRow>> {
        let sql = format!(
            "SELECT {} FROM agent_messages WHERE sessionId = ?1 ORDER BY timestamp ASC",
            Self::MESSAGE_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map(params![session_id], Self::map_message)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// Mark messages compacted (context compaction twin of
    /// `AgentMessageDao`): `UPDATE agent_messages SET isCompacted = 1 WHERE id IN (...)`.
    pub fn message_mark_compacted(&self, ids: &[String]) -> Result<usize> {
        let mut n = 0;
        for id in ids {
            n += self
                .conn
                .execute(
                    "UPDATE agent_messages SET isCompacted = 1 WHERE id = ?1",
                    params![id],
                )
                .map_err(sql_err)?;
        }
        Ok(n)
    }

    /// `DELETE FROM agent_messages WHERE sessionId = ?` (DAO parity).
    pub fn message_delete_session(&self, session_id: &str) -> Result<usize> {
        self.conn
            .execute("DELETE FROM agent_messages WHERE sessionId = ?1", params![session_id])
            .map_err(sql_err)
    }

    // -- todo_items ----------------------------------------------------------

    /// Insert or replace a todo item.
    pub fn todo_upsert(&self, t: &TodoItemRow) -> Result<()> {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO todo_items
                 (id, sessionId, subject, description, status, priority, `order`, createdAt, updatedAt)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
                params![
                    t.id, t.session_id, t.subject, t.description, t.status, t.priority, t.order,
                    t.created_at, t.updated_at
                ],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// Todos of a session ordered like the DAO (subject ASC on ties, insertion order first).
    pub fn todo_list(&self, session_id: &str) -> Result<Vec<TodoItemRow>> {
        let mut st = self
            .conn
            .prepare(
                "SELECT id, sessionId, subject, description, status, priority, `order`, createdAt, updatedAt
                 FROM todo_items WHERE sessionId = ?1 ORDER BY `order` ASC, createdAt ASC",
            )
            .map_err(sql_err)?;
        let rows = st
            .query_map(params![session_id], |r| {
                Ok(TodoItemRow {
                    id: r.get(0)?,
                    session_id: r.get(1)?,
                    subject: r.get(2)?,
                    description: r.get(3)?,
                    status: r.get(4)?,
                    priority: r.get(5)?,
                    order: r.get(6)?,
                    created_at: r.get(7)?,
                    updated_at: r.get(8)?,
                })
            })
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// `DELETE FROM todo_items WHERE id = ?`
    pub fn todo_delete(&self, id: &str) -> Result<usize> {
        self.conn
            .execute("DELETE FROM todo_items WHERE id = ?1", params![id])
            .map_err(sql_err)
    }

    /// `DELETE FROM todo_items WHERE sessionId = ?`
    pub fn todo_delete_session(&self, session_id: &str) -> Result<usize> {
        self.conn
            .execute("DELETE FROM todo_items WHERE sessionId = ?1", params![session_id])
            .map_err(sql_err)
    }

    // -- checkpoints ----------------------------------------------------------

    /// Insert a checkpoint row (id client-generated, Kotlin parity).
    pub fn checkpoint_insert(&self, c: &CheckpointRow) -> Result<()> {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO session_checkpoints
                 (id, sessionId, userMessageId, promptSnippet, createdAt) VALUES (?1,?2,?3,?4,?5)",
                params![c.id, c.session_id, c.user_message_id, c.prompt_snippet, c.created_at],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// `WHERE sessionId = ? ORDER BY createdAt ASC` (DAO parity).
    pub fn checkpoint_list(&self, session_id: &str) -> Result<Vec<CheckpointRow>> {
        let mut st = self
            .conn
            .prepare(
                "SELECT id, sessionId, userMessageId, promptSnippet, createdAt
                 FROM session_checkpoints WHERE sessionId = ?1 ORDER BY createdAt ASC",
            )
            .map_err(sql_err)?;
        let rows = st
            .query_map(params![session_id], |r| {
                Ok(CheckpointRow {
                    id: r.get(0)?,
                    session_id: r.get(1)?,
                    user_message_id: r.get(2)?,
                    prompt_snippet: r.get(3)?,
                    created_at: r.get(4)?,
                })
            })
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// Insert a file snapshot row.
    pub fn snapshot_insert(&self, s: &CheckpointFileSnapshotRow) -> Result<()> {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO checkpoint_file_snapshots
                 (id, checkpointId, filePath, snapshotRelativePath, changeType, createdAt)
                 VALUES (?1,?2,?3,?4,?5,?6)",
                params![
                    s.id, s.checkpoint_id, s.file_path, s.snapshot_relative_path, s.change_type,
                    s.created_at
                ],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// `WHERE checkpointId = ?` (unordered, DAO parity).
    pub fn snapshot_list(&self, checkpoint_id: &str) -> Result<Vec<CheckpointFileSnapshotRow>> {
        let mut st = self
            .conn
            .prepare(
                "SELECT id, checkpointId, filePath, snapshotRelativePath, changeType, createdAt
                 FROM checkpoint_file_snapshots WHERE checkpointId = ?1",
            )
            .map_err(sql_err)?;
        let rows = st
            .query_map(params![checkpoint_id], |r| {
                Ok(CheckpointFileSnapshotRow {
                    id: r.get(0)?,
                    checkpoint_id: r.get(1)?,
                    file_path: r.get(2)?,
                    snapshot_relative_path: r.get(3)?,
                    change_type: r.get(4)?,
                    created_at: r.get(5)?,
                })
            })
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    // -- llm_call_records ------------------------------------------------------

    /// Insert one call record; returns the auto-generated rowid (DAO parity:
    /// `id` is `AUTOINCREMENT`, callers pass `id = 0`).
    pub fn llm_call_insert(&self, c: &LlmCallRecordRow) -> Result<i64> {
        self.conn
            .execute(
                "INSERT INTO llm_call_records
                 (sessionId, providerId, model, reasoningEffort, kind, inputTokens, outputTokens,
                  cachedInputTokens, cacheCreationTokens, ttfbMillis, durationMillis, status,
                  errorMessage, stopReason, retryCount, createdAt)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16)",
                params![
                    c.session_id, c.provider_id, c.model, c.reasoning_effort, c.kind,
                    c.input_tokens, c.output_tokens, c.cached_input_tokens,
                    c.cache_creation_tokens, c.ttfb_millis, c.duration_millis, c.status,
                    c.error_message, c.stop_reason, c.retry_count, c.created_at
                ],
            )
            .map_err(sql_err)?;
        Ok(self.conn.last_insert_rowid())
    }

    fn map_llm_call(r: &Row) -> rusqlite::Result<LlmCallRecordRow> {
        Ok(LlmCallRecordRow {
            id: r.get(0)?,
            session_id: r.get(1)?,
            provider_id: r.get(2)?,
            model: r.get(3)?,
            reasoning_effort: r.get(4)?,
            kind: r.get(5)?,
            input_tokens: r.get(6)?,
            output_tokens: r.get(7)?,
            cached_input_tokens: r.get(8)?,
            cache_creation_tokens: r.get(9)?,
            ttfb_millis: r.get(10)?,
            duration_millis: r.get(11)?,
            status: r.get(12)?,
            error_message: r.get(13)?,
            stop_reason: r.get(14)?,
            retry_count: r.get(15)?,
            created_at: r.get(16)?,
        })
    }

    const LLM_CALL_COLS: &'static str =
        "id, sessionId, providerId, model, reasoningEffort, kind, inputTokens, outputTokens, \
         cachedInputTokens, cacheCreationTokens, ttfbMillis, durationMillis, status, \
         errorMessage, stopReason, retryCount, createdAt";

    /// Most recent `limit` call records (newest first).
    pub fn llm_calls_recent(&self, limit: i64) -> Result<Vec<LlmCallRecordRow>> {
        let sql = format!(
            "SELECT {} FROM llm_call_records ORDER BY createdAt DESC, id DESC LIMIT ?1",
            Self::LLM_CALL_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map(params![limit], Self::map_llm_call)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// Call records since a timestamp (ASC), for stats pages.
    pub fn llm_calls_since(&self, since: i64) -> Result<Vec<LlmCallRecordRow>> {
        let sql = format!(
            "SELECT {} FROM llm_call_records WHERE createdAt >= ?1 ORDER BY createdAt ASC, id ASC",
            Self::LLM_CALL_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map(params![since], Self::map_llm_call)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    // -- xp_events --------------------------------------------------------------

    /// Append one XP event; returns the auto-generated rowid.
    pub fn xp_insert(&self, x: &XpEventRow) -> Result<i64> {
        self.conn
            .execute(
                "INSERT INTO xp_events (sessionId, kind, amount, label, createdAt)
                 VALUES (?1,?2,?3,?4,?5)",
                params![x.session_id, x.kind, x.amount, x.label, x.created_at],
            )
            .map_err(sql_err)?;
        Ok(self.conn.last_insert_rowid())
    }

    fn map_xp(r: &Row) -> rusqlite::Result<XpEventRow> {
        Ok(XpEventRow {
            id: r.get(0)?,
            session_id: r.get(1)?,
            kind: r.get(2)?,
            amount: r.get(3)?,
            label: r.get(4)?,
            created_at: r.get(5)?,
        })
    }

    const XP_COLS: &'static str = "id, sessionId, kind, amount, label, createdAt";

    /// All events, `ORDER BY createdAt ASC, id ASC` (DAO parity).
    pub fn xp_list_all(&self) -> Result<Vec<XpEventRow>> {
        let sql = format!(
            "SELECT {} FROM xp_events ORDER BY createdAt ASC, id ASC",
            Self::XP_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map([], Self::map_xp)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// Events for one session (DAO parity ordering).
    pub fn xp_list_session(&self, session_id: &str) -> Result<Vec<XpEventRow>> {
        let sql = format!(
            "SELECT {} FROM xp_events WHERE sessionId = ?1 ORDER BY createdAt ASC, id ASC",
            Self::XP_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map(params![session_id], Self::map_xp)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// `SELECT COALESCE(SUM(amount), 0) FROM xp_events` (DAO parity).
    pub fn xp_total(&self) -> Result<i64> {
        self.conn
            .query_row("SELECT COALESCE(SUM(amount), 0) FROM xp_events", [], |r| r.get(0))
            .map_err(sql_err)
    }

    /// `SELECT COALESCE(SUM(amount), 0) FROM xp_events WHERE createdAt >= ?` (DAO parity).
    pub fn xp_total_since(&self, since: i64) -> Result<i64> {
        self.conn
            .query_row(
                "SELECT COALESCE(SUM(amount), 0) FROM xp_events WHERE createdAt >= ?1",
                params![since],
                |r| r.get(0),
            )
            .map_err(sql_err)
    }

    // -- ai_providers ------------------------------------------------------------

    fn map_provider(r: &Row) -> rusqlite::Result<AIProviderRow> {
        Ok(AIProviderRow {
            id: r.get(0)?,
            name: r.get(1)?,
            kind: r.get(2)?,
            api_key: r.get(3)?,
            multi_key_enabled: r.get::<_, i64>(4)? != 0,
            api_keys: r.get(5)?,
            key_rotation_strategy: r.get(6)?,
            key_failover_threshold: r.get(7)?,
            key_cooldown_minutes: r.get(8)?,
            key_switch_status_codes: r.get(9)?,
            base_url: r.get(10)?,
            default_model: r.get(11)?,
            models: r.get(12)?,
            selected_model: r.get(13)?,
            is_enabled: r.get::<_, i64>(14)? != 0,
            use_full_url: r.get::<_, i64>(15)? != 0,
            use_response_api: r.get::<_, i64>(16)? != 0,
            anthropic_cache_breakpoints: r.get::<_, i64>(17)? != 0,
            openai_chat_cache_key: r.get::<_, i64>(18)? != 0,
            balance_script_path: r.get(19)?,
            balance_refresh_interval: r.get(20)?,
            user_agent: r.get(21)?,
            custom_headers: r.get(22)?,
            sort_order: r.get(23)?,
            proxy_enabled: r.get::<_, i64>(24)? != 0,
            proxy_type: r.get(25)?,
            proxy_host: r.get(26)?,
            proxy_port: r.get(27)?,
            proxy_username: r.get(28)?,
            proxy_password: r.get(29)?,
            script_params: r.get(30)?,
        })
    }

    const PROVIDER_COLS: &'static str =
        "id, name, type, apiKey, multiKeyEnabled, apiKeys, keyRotationStrategy, \
         keyFailoverThreshold, keyCooldownMinutes, keySwitchStatusCodes, baseUrl, defaultModel, \
         models, selectedModel, isEnabled, useFullUrl, useResponseApi, anthropicCacheBreakpoints, \
         openaiChatCacheKey, balanceScriptPath, balanceRefreshInterval, userAgent, customHeaders, \
         sortOrder, proxyEnabled, proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword, \
         scriptParams";

    /// Insert or replace a provider row — lets the PC register a provider that
    /// the phone then uses, and vice versa (shared API config).
    pub fn provider_upsert(&self, p: &AIProviderRow) -> Result<()> {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO ai_providers
                 (id, name, type, apiKey, multiKeyEnabled, apiKeys, keyRotationStrategy,
                  keyFailoverThreshold, keyCooldownMinutes, keySwitchStatusCodes, baseUrl,
                  defaultModel, models, selectedModel, isEnabled, useFullUrl, useResponseApi,
                  anthropicCacheBreakpoints, openaiChatCacheKey, balanceScriptPath,
                  balanceRefreshInterval, userAgent, customHeaders, sortOrder, proxyEnabled,
                  proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword, scriptParams)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20,?21,?22,?23,?24,?25,?26,?27,?28,?29,?30,?31)",
                params![
                    p.id, p.name, p.kind, p.api_key, p.multi_key_enabled as i64, p.api_keys,
                    p.key_rotation_strategy, p.key_failover_threshold, p.key_cooldown_minutes,
                    p.key_switch_status_codes, p.base_url, p.default_model, p.models,
                    p.selected_model, p.is_enabled as i64, p.use_full_url as i64,
                    p.use_response_api as i64, p.anthropic_cache_breakpoints as i64,
                    p.openai_chat_cache_key as i64, p.balance_script_path,
                    p.balance_refresh_interval, p.user_agent, p.custom_headers, p.sort_order,
                    p.proxy_enabled as i64, p.proxy_type, p.proxy_host, p.proxy_port,
                    p.proxy_username, p.proxy_password, p.script_params
                ],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// `SELECT * FROM ai_providers ORDER BY sortOrder ASC, id ASC` (DAO parity).
    pub fn provider_list(&self) -> Result<Vec<AIProviderRow>> {
        let sql = format!(
            "SELECT {} FROM ai_providers ORDER BY sortOrder ASC, id ASC",
            Self::PROVIDER_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map([], Self::map_provider)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// Enabled providers only, DAO ordering preserved.
    pub fn provider_list_enabled(&self) -> Result<Vec<AIProviderRow>> {
        let sql = format!(
            "SELECT {} FROM ai_providers WHERE isEnabled = 1 ORDER BY sortOrder ASC, id ASC",
            Self::PROVIDER_COLS
        );
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map([], Self::map_provider)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// `SELECT * FROM ai_providers WHERE id = ? LIMIT 1`
    pub fn provider_get(&self, id: &str) -> Result<Option<AIProviderRow>> {
        let sql = format!("SELECT {} FROM ai_providers WHERE id = ?1 LIMIT 1", Self::PROVIDER_COLS);
        self.conn
            .query_row(&sql, params![id], Self::map_provider)
            .optional()
            .map_err(sql_err)
    }

    /// `DELETE FROM ai_providers WHERE id = ?`
    pub fn provider_delete(&self, id: &str) -> Result<usize> {
        self.conn
            .execute("DELETE FROM ai_providers WHERE id = ?1", params![id])
            .map_err(sql_err)
    }

    // -- remote_connections / remote_mounts ---------------------------------------

    fn map_remote_conn(r: &Row) -> rusqlite::Result<RemoteConnectionRow> {
        Ok(RemoteConnectionRow {
            id: r.get(0)?,
            name: r.get(1)?,
            protocol: r.get(2)?,
            host: r.get(3)?,
            port: r.get(4)?,
            username: r.get(5)?,
            auth_type: r.get(6)?,
            auth_data: r.get(7)?,
            passphrase: r.get(8)?,
            created_at: r.get(9)?,
        })
    }

    const REMOTE_CONN_COLS: &'static str =
        "id, name, protocol, host, port, username, authType, authData, passphrase, created_at";

    /// Insert or replace a remote connection.
    pub fn remote_connection_upsert(&self, c: &RemoteConnectionRow) -> Result<()> {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO remote_connections
                 (id, name, protocol, host, port, username, authType, authData, passphrase, created_at)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
                params![
                    c.id, c.name, c.protocol, c.host, c.port, c.username, c.auth_type, c.auth_data,
                    c.passphrase, c.created_at
                ],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// All remote connections (DAO parity order: name ASC).
    pub fn remote_connection_list(&self) -> Result<Vec<RemoteConnectionRow>> {
        let sql =
            format!("SELECT {} FROM remote_connections ORDER BY name ASC", Self::REMOTE_CONN_COLS);
        let mut st = self.conn.prepare(&sql).map_err(sql_err)?;
        let rows = st
            .query_map([], Self::map_remote_conn)
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }

    /// `DELETE FROM remote_connections WHERE id = ?` (cascades to mounts, FK ON).
    pub fn remote_connection_delete(&self, id: &str) -> Result<usize> {
        self.conn
            .execute("DELETE FROM remote_connections WHERE id = ?1", params![id])
            .map_err(sql_err)
    }

    /// Insert or replace a remote mount.
    pub fn remote_mount_upsert(&self, m: &RemoteMountRow) -> Result<()> {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO remote_mounts
                 (id, connectionId, remotePath, localMountPath, isActive, autoConnect, created_at)
                 VALUES (?1,?2,?3,?4,?5,?6,?7)",
                params![
                    m.id, m.connection_id, m.remote_path, m.local_mount_path, m.is_active as i64,
                    m.auto_connect as i64, m.created_at
                ],
            )
            .map_err(sql_err)?;
        Ok(())
    }

    /// Mounts for one connection.
    pub fn remote_mount_list(&self, connection_id: &str) -> Result<Vec<RemoteMountRow>> {
        let mut st = self
            .conn
            .prepare(
                "SELECT id, connectionId, remotePath, localMountPath, isActive, autoConnect, created_at
                 FROM remote_mounts WHERE connectionId = ?1",
            )
            .map_err(sql_err)?;
        let rows = st
            .query_map(params![connection_id], |r| {
                Ok(RemoteMountRow {
                    id: r.get(0)?,
                    connection_id: r.get(1)?,
                    remote_path: r.get(2)?,
                    local_mount_path: r.get(3)?,
                    is_active: r.get::<_, i64>(4)? != 0,
                    auto_connect: r.get::<_, i64>(5)? != 0,
                    created_at: r.get(6)?,
                })
            })
            .map_err(sql_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(sql_err)?;
        Ok(rows)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fresh_store_is_room_stamped() {
        let s = SharedStore::open_in_memory().unwrap_or_else(|e| panic!("open: {e}"));
        assert_eq!(s.user_version().unwrap_or(0), 59);
        assert_eq!(
            s.stamped_identity().unwrap_or_default().as_deref(),
            Some(IDENTITY_HASH)
        );
        let counts = s.table_counts().unwrap_or_default();
        assert_eq!(counts.len(), 10, "10 entity tables expected, got {counts:?}");
        assert!(counts.iter().all(|(_, n)| *n == 0));
    }

    #[test]
    fn session_message_roundtrip_ordering() {
        let s = SharedStore::open_in_memory().unwrap_or_else(|e| panic!("open: {e}"));
        let sess = ChatSessionRow {
            workspace_path: "/tmp/ws".into(),
            title: "roundtrip".into(),
            ..Default::default()
        };
        s.session_upsert(&sess).unwrap_or_else(|e| panic!("upsert: {e}"));

        for i in 0..3i64 {
            let m = AgentMessageRow {
                id: format!("m{i}"),
                session_id: sess.id.clone(),
                role: if i == 0 { "USER" } else { "ASSISTANT" }.into(),
                content: format!("hello {i}"),
                timestamp: 100 - i, // intentionally out of order on insert
                ..Default::default()
            };
            s.message_append(&m).unwrap_or_else(|e| panic!("append: {e}"));
        }
        let got = s.message_list(&sess.id).unwrap_or_default();
        assert_eq!(got.len(), 3);
        // DAO parity: ORDER BY timestamp ASC regardless of insert order.
        assert_eq!(got[0].id, "m2");
        assert_eq!(got[2].id, "m0");

        // Token accounting mirrors the DAO SQL.
        s.session_add_tokens(&sess.id, 10, 3).unwrap_or_else(|e| panic!("tokens: {e}"));
        let after = s.session_get(&sess.id).unwrap_or_default().expect("session exists");
        assert_eq!((after.total_input_tokens, after.total_output_tokens), (10, 3));
    }

    #[test]
    fn xp_math_and_ordering_match_dao() {
        let s = SharedStore::open_in_memory().unwrap_or_else(|e| panic!("open: {e}"));
        for (i, amount) in [5i64, 10, 20].iter().enumerate() {
            let x = XpEventRow {
                session_id: "s1".into(),
                kind: "TURN".into(),
                amount: *amount,
                label: format!("t{i}"),
                created_at: 200 - (i as i64) * 10, // reverse insert order
                ..Default::default()
            };
            s.xp_insert(&x).unwrap_or_else(|e| panic!("xp: {e}"));
        }
        assert_eq!(s.xp_total().unwrap_or(-1), 35);
        assert_eq!(s.xp_total_since(150).unwrap_or(-1), 35);
        assert_eq!(s.xp_total_since(195).unwrap_or(-1), 5); // only the earliest
        let rows = s.xp_list_session("s1").unwrap_or_default();
        assert_eq!(rows.len(), 3);
        // createdAt values: 200, 190, 180 → ASC ordering yields 180 (amount 20) first.
        assert_eq!(rows[0].amount, 20, "ORDER BY createdAt ASC, id ASC");
    }

    #[test]
    fn checkpoint_with_snapshots_roundtrip() {
        let s = SharedStore::open_in_memory().unwrap_or_else(|e| panic!("open: {e}"));
        let c = CheckpointRow {
            id: "c1".into(),
            session_id: "s1".into(),
            user_message_id: "m1".into(),
            prompt_snippet: "fix the bug".into(),
            ..Default::default()
        };
        s.checkpoint_insert(&c).unwrap_or_else(|e| panic!("cp: {e}"));
        for (i, ct) in ["MODIFIED", "ADDED"].iter().enumerate() {
            let snap = CheckpointFileSnapshotRow {
                id: format!("sn{i}"),
                checkpoint_id: "c1".into(),
                file_path: format!("src/f{i}.rs"),
                snapshot_relative_path: format!("snaps/{i}"),
                change_type: ct.to_string(),
                ..Default::default()
            };
            s.snapshot_insert(&snap).unwrap_or_else(|e| panic!("snap: {e}"));
        }
        let snaps = s.snapshot_list("c1").unwrap_or_default();
        assert_eq!(snaps.len(), 2);
        assert_eq!(snaps[0].change_type, "MODIFIED");
    }

    #[test]
    fn provider_rows_drive_shared_config() {
        let s = SharedStore::open_in_memory().unwrap_or_else(|e| panic!("open: {e}"));
        let p = AIProviderRow {
            id: "prov-1".into(),
            name: "Main".into(),
            kind: "ANTHROPIC".into(),
            api_key: "sk-test".into(),
            base_url: "https://api.anthropic.com".into(),
            default_model: "claude-sonnet".into(),
            selected_model: "claude-sonnet".into(),
            sort_order: 1,
            ..Default::default()
        };
        s.provider_upsert(&p).unwrap_or_else(|e| panic!("prov: {e}"));
        let off = AIProviderRow {
            id: "prov-2".into(),
            name: "Backup".into(),
            kind: "OPENAI".into(),
            is_enabled: false,
            sort_order: 2,
            ..Default::default()
        };
        s.provider_upsert(&off).unwrap_or_else(|e| panic!("prov2: {e}"));

        let all = s.provider_list().unwrap_or_default();
        assert_eq!(all.len(), 2);
        assert_eq!(all[0].id, "prov-1", "sortOrder ASC, id ASC");
        let enabled = s.provider_list_enabled().unwrap_or_default();
        assert_eq!(enabled.len(), 1);
        assert_eq!(s.provider_get("prov-1").unwrap_or_default().expect("row").api_key, "sk-test");
    }

    #[test]
    fn remote_mount_cascade_delete() {
        let s = SharedStore::open_in_memory().unwrap_or_else(|e| panic!("open: {e}"));
        let c = RemoteConnectionRow {
            name: "box".into(),
            protocol: "SSH".into(),
            host: "10.0.0.2".into(),
            port: 22,
            username: "root".into(),
            auth_data: "/keys/id_ed25519".into(),
            auth_type: "key".into(),
            ..Default::default()
        };
        s.remote_connection_upsert(&c).unwrap_or_else(|e| panic!("conn: {e}"));
        let m = RemoteMountRow {
            connection_id: c.id.clone(),
            remote_path: "/srv".into(),
            local_mount_path: "/mnt/srv".into(),
            ..Default::default()
        };
        s.remote_mount_upsert(&m).unwrap_or_else(|e| panic!("mount: {e}"));
        assert_eq!(s.remote_mount_list(&c.id).unwrap_or_default().len(), 1);
        s.remote_connection_delete(&c.id).unwrap_or_else(|e| panic!("del: {e}"));
        assert!(
            s.remote_mount_list(&c.id).unwrap_or_default().is_empty(),
            "FK ON DELETE CASCADE must remove mounts"
        );
    }

    #[test]
    fn llm_call_records_autoincrement_and_recent() {
        let s = SharedStore::open_in_memory().unwrap_or_else(|e| panic!("open: {e}"));
        for i in 0..4 {
            let c = LlmCallRecordRow {
                session_id: Some("s1".into()),
                provider_id: Some("prov-1".into()),
                model: Some("gpt-x".into()),
                input_tokens: i * 10,
                output_tokens: i,
                created_at: 1000 + i,
                ..Default::default()
            };
            s.llm_call_insert(&c).unwrap_or_else(|e| panic!("insert: {e}"));
        }
        let recent = s.llm_calls_recent(2).unwrap_or_default();
        assert_eq!(recent.len(), 2);
        assert_eq!(recent[0].input_tokens, 30, "newest first");
        let since = s.llm_calls_since(1002).unwrap_or_default();
        assert_eq!(since.len(), 2);
        assert_eq!(since[0].input_tokens, 20);
    }

    #[test]
    fn reopen_preserves_data_and_restamps_cleanly() {
        let dir = tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"));
        let db = dir.path().join(DATABASE_NAME);
        {
            let s = SharedStore::open(&db).unwrap_or_else(|e| panic!("open: {e}"));
            let sess = ChatSessionRow { title: "persisted".into(), ..Default::default() };
            s.session_upsert(&sess).unwrap_or_else(|e| panic!("upsert: {e}"));
        }
        {
            let s = SharedStore::open(&db).unwrap_or_else(|e| panic!("reopen: {e}"));
            assert_eq!(s.user_version().unwrap_or(0), 59);
            let got = s.session_list("").unwrap_or_default();
            assert_eq!(got.len(), 1);
            assert_eq!(got[0].title, "persisted");
        }
    }
}
