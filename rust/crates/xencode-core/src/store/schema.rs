//! Byte-exact mirror of the Kotlin app's Room schema
//! (`aicode_agent_db`, `AgentDatabase.SCHEMA_VERSION = 59`,
//! identity `2593e17a5dc5b3e4987445ef1ae57f25`).
//!
//! Source of truth:
//! `app/schemas/com.xencode.feature.agent.data.local.database.AgentDatabase/59.json`
//! (also embedded as a test fixture: `tests/fixtures/room_schema_59.json`).
//!
//! The DDL below is transcribed **verbatim** from the Room-exported schema so
//! that a database file created on the phone opens in XenCode PC and a file
//! created by XenCode PC opens in Room without a migration. On create we also
//! write `room_master_table` + `PRAGMA user_version`, which is exactly what
//! Room checks in `RoomOpenHelper.onOpen` — with a matching identity hash Room
//! accepts the file as its own.

/// Room schema version this mirror tracks.
pub const SCHEMA_VERSION: i64 = 59;

/// `AgentDatabase` v59 identity hash. Room stores it in `room_master_table`
/// and refuses to open the file when it does not match its compiled schema.
pub const IDENTITY_HASH: &str = "2593e17a5dc5b3e4987445ef1ae57f25";

/// Room-managed database file name (relative to the database directory).
pub const DATABASE_NAME: &str = "aicode_agent_db";

/// All entity tables + indices, in the exact order Room exports them.
pub const CREATE_TABLES: &[&str] = &[
    // agent_messages (AgentMessageEntity)
    "CREATE TABLE IF NOT EXISTS `agent_messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, \
     `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, \
     `toolCallsJson` TEXT, `toolCallId` TEXT, `toolName` TEXT, `toolArgs` TEXT, \
     `isError` INTEGER NOT NULL, `reasoning` TEXT, `signature` TEXT, `attachmentsJson` TEXT, \
     `isCompacted` INTEGER NOT NULL, `isContextSummary` INTEGER NOT NULL, \
     `isCompactionMarker` INTEGER NOT NULL, `inputTokens` INTEGER NOT NULL, \
     `outputTokens` INTEGER NOT NULL, `thinkingBlocksJson` TEXT, `cachedInputTokens` INTEGER NOT NULL, \
     `isContextExcluded` INTEGER NOT NULL DEFAULT 0, `compactedBySummaryId` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_agent_messages_sessionId` ON `agent_messages` (`sessionId`)",
    // chat_sessions (ChatSessionEntity)
    "CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, \
     `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `workspacePath` TEXT NOT NULL, \
     `mode` TEXT NOT NULL, `modeBeforePlan` TEXT, `reasoningEffort` TEXT NOT NULL, \
     `providerId` TEXT, `model` TEXT, `totalInputTokens` INTEGER NOT NULL, \
     `totalOutputTokens` INTEGER NOT NULL, `lastInputTokens` INTEGER NOT NULL, \
     `isPinned` INTEGER NOT NULL, `parentId` TEXT, `subagentType` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_chat_sessions_workspacePath` ON `chat_sessions` (`workspacePath`)",
    // ai_providers (AIProviderEntity)
    "CREATE TABLE IF NOT EXISTS `ai_providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, \
     `type` TEXT NOT NULL, `apiKey` TEXT NOT NULL, `multiKeyEnabled` INTEGER NOT NULL, \
     `apiKeys` TEXT NOT NULL, `keyRotationStrategy` TEXT NOT NULL, `keyFailoverThreshold` INTEGER NOT NULL, \
     `keyCooldownMinutes` INTEGER NOT NULL, `keySwitchStatusCodes` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, \
     `defaultModel` TEXT NOT NULL, `models` TEXT NOT NULL, `selectedModel` TEXT NOT NULL, \
     `isEnabled` INTEGER NOT NULL, `useFullUrl` INTEGER NOT NULL, `useResponseApi` INTEGER NOT NULL, \
     `anthropicCacheBreakpoints` INTEGER NOT NULL, `openaiChatCacheKey` INTEGER NOT NULL, \
     `balanceScriptPath` TEXT NOT NULL, `balanceRefreshInterval` INTEGER NOT NULL, \
     `userAgent` TEXT NOT NULL, `customHeaders` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, \
     `proxyEnabled` INTEGER NOT NULL, `proxyType` TEXT NOT NULL, `proxyHost` TEXT NOT NULL, \
     `proxyPort` INTEGER NOT NULL, `proxyUsername` TEXT NOT NULL, `proxyPassword` TEXT NOT NULL, \
     `scriptParams` TEXT NOT NULL, PRIMARY KEY(`id`))",
    // remote_connections (RemoteConnectionEntity)
    "CREATE TABLE IF NOT EXISTS `remote_connections` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, \
     `protocol` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, \
     `authType` TEXT NOT NULL, `authData` TEXT NOT NULL, `passphrase` TEXT, `created_at` INTEGER NOT NULL, \
     PRIMARY KEY(`id`))",
    // remote_mounts (RemoteMountEntity)
    "CREATE TABLE IF NOT EXISTS `remote_mounts` (`id` TEXT NOT NULL, `connectionId` TEXT NOT NULL, \
     `remotePath` TEXT NOT NULL, `localMountPath` TEXT NOT NULL, `isActive` INTEGER NOT NULL, \
     `autoConnect` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), \
     FOREIGN KEY(`connectionId`) REFERENCES `remote_connections`(`id`) \
     ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_remote_mounts_connectionId` ON `remote_mounts` (`connectionId`)",
    // todo_items (TodoItemEntity)
    "CREATE TABLE IF NOT EXISTS `todo_items` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, \
     `subject` TEXT NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, \
     `priority` INTEGER NOT NULL, `order` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, \
     `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    // session_checkpoints (CheckpointEntity)
    "CREATE TABLE IF NOT EXISTS `session_checkpoints` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, \
     `userMessageId` TEXT NOT NULL, `promptSnippet` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, \
     PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_session_checkpoints_sessionId` ON `session_checkpoints` (`sessionId`)",
    // checkpoint_file_snapshots (CheckpointFileSnapshotEntity)
    "CREATE TABLE IF NOT EXISTS `checkpoint_file_snapshots` (`id` TEXT NOT NULL, `checkpointId` TEXT NOT NULL, \
     `filePath` TEXT NOT NULL, `snapshotRelativePath` TEXT NOT NULL, `changeType` TEXT NOT NULL, \
     `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_checkpoint_file_snapshots_checkpointId` ON `checkpoint_file_snapshots` (`checkpointId`)",
    // llm_call_records (LlmCallRecordEntity)
    "CREATE TABLE IF NOT EXISTS `llm_call_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, \
     `sessionId` TEXT, `providerId` TEXT, `model` TEXT, `reasoningEffort` TEXT, `kind` TEXT NOT NULL, \
     `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `cachedInputTokens` INTEGER NOT NULL, \
     `cacheCreationTokens` INTEGER NOT NULL, `ttfbMillis` INTEGER, `durationMillis` INTEGER, \
     `status` TEXT NOT NULL, `errorMessage` TEXT, `stopReason` TEXT, `retryCount` INTEGER NOT NULL, \
     `createdAt` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_llm_call_records_createdAt` ON `llm_call_records` (`createdAt`)",
    "CREATE INDEX IF NOT EXISTS `index_llm_call_records_provider_model` ON `llm_call_records` (`providerId`, `model`)",
    // xp_events (XpEventEntity) — created by migration 59_add_xp_events.sql on the Kotlin side
    "CREATE TABLE IF NOT EXISTS `xp_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, \
     `sessionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `amount` INTEGER NOT NULL, `label` TEXT NOT NULL, \
     `createdAt` INTEGER NOT NULL)",
];

/// The `room_master_table` Room creates in `onOpen` to stamp its identity.
pub const CREATE_ROOM_MASTER: &str =
    "CREATE TABLE IF NOT EXISTS room_master_table (identity TEXT NOT NULL)";

/// Stamp statement (parameter = [`IDENTITY_HASH`]); mirrors Room's
/// `INSERT OR REPLACE INTO room_master_table (identity) VALUES (?)`.
pub const STAMP_IDENTITY_SQL: &str =
    "INSERT OR REPLACE INTO room_master_table (identity) VALUES (?1)";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constants_are_coherent() {
        assert_eq!(SCHEMA_VERSION, 59);
        assert_eq!(IDENTITY_HASH.len(), 32);
        assert_eq!(DATABASE_NAME, "aicode_agent_db");
        // 10 entity tables => 10 CREATE TABLE statements; the rest are indices.
        let tables = CREATE_TABLES
            .iter()
            .filter(|s| s.starts_with("CREATE TABLE"))
            .count();
        assert_eq!(tables, 10);
        assert!(CREATE_ROOM_MASTER.starts_with("CREATE TABLE IF NOT EXISTS room_master_table"));
    }
}
