-- Fallback cleanup for agent_messages rows whose total text exceeds the limit.
-- When the sum of a row's large-text columns passes the safe SQLite CursorWindow budget (~2MB), the row may fail
-- to load into the window and reading messages throws IllegalStateException "Couldn't read row N, col 0 from CursorWindow",
-- crashing the chat page. Writers now cap each column by UTF-8 bytes; this cleans up historical rows.
-- The character cap is a third of the byte budget (a CJK char takes up to 3 bytes), guaranteeing the real byte count fits.
UPDATE agent_messages SET
    content = substr(content, 1, 50000),
    toolCallsJson = CASE WHEN toolCallsJson IS NULL THEN NULL ELSE substr(toolCallsJson, 1, 33333) END,
    toolArgs = CASE WHEN toolArgs IS NULL THEN NULL ELSE substr(toolArgs, 1, 666) END,
    reasoning = CASE WHEN reasoning IS NULL THEN NULL ELSE substr(reasoning, 1, 33333) END,
    signature = CASE WHEN signature IS NULL THEN NULL ELSE substr(signature, 1, 33333) END,
    attachmentsJson = CASE WHEN attachmentsJson IS NULL THEN NULL ELSE substr(attachmentsJson, 1, 6666) END,
    thinkingBlocksJson = CASE WHEN thinkingBlocksJson IS NULL THEN NULL ELSE substr(thinkingBlocksJson, 1, 33333) END
WHERE (length(CAST(content AS BLOB))
    + coalesce(length(CAST(toolCallsJson AS BLOB)), 0)
    + coalesce(length(CAST(toolArgs AS BLOB)), 0)
    + coalesce(length(CAST(reasoning AS BLOB)), 0)
    + coalesce(length(CAST(signature AS BLOB)), 0)
    + coalesce(length(CAST(attachmentsJson AS BLOB)), 0)
    + coalesce(length(CAST(thinkingBlocksJson AS BLOB)), 0)) > 600000;
