-- Trim oversized JSON snapshot columns in agent_messages so a single row stays under the ~2MB SQLite CursorWindow cap.
-- Otherwise reading messages throws SQLiteBlobTooBigException and entering the chat page crashes.
-- toolCallsJson / thinkingBlocksJson / attachmentsJson had no length cap when written,
-- so huge tool inputs (e.g. writeFile with a large file) or long thinking snapshots could blow up the row.
-- After truncation the JSON no longer parses. Readers degrade to empty lists or empty snapshots via runCatching instead of crashing.
UPDATE agent_messages SET toolCallsJson = CASE WHEN length(toolCallsJson) > 200000 THEN substr(toolCallsJson, 1, 200000) ELSE toolCallsJson END;
UPDATE agent_messages SET thinkingBlocksJson = CASE WHEN length(thinkingBlocksJson) > 200000 THEN substr(thinkingBlocksJson, 1, 200000) ELSE thinkingBlocksJson END;
UPDATE agent_messages SET attachmentsJson = CASE WHEN length(attachmentsJson) > 200000 THEN substr(attachmentsJson, 1, 200000) ELSE attachmentsJson END;
