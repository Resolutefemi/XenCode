-- Clean up agent_messages rows that exceed the size limit because image-generation or multimodal models
-- returned huge base64 images (SQLiteBlobTooBigException / CursorWindow ~2MB per-row cap crashed startup).
-- Rows with embedded data:image base64: truncate before the base64 starts and append a note.
-- All other oversized fields are truncated to a safe 200000-character threshold.
-- Note: no semicolons may appear inside SQL string literals in this file, or MigrationLoader would split them apart.

UPDATE agent_messages SET content = CASE
    WHEN instr(content, 'base64,') > 0 AND length(content) > 200000 THEN substr(content, 1, instr(content, 'base64,') - 1) || char(10) || '[Image data omitted: embedded image data too large]'
    WHEN length(content) > 200000 THEN substr(content, 1, 200000) || char(10) || '[Content too long, truncated]'
    ELSE content
END;

UPDATE agent_messages SET reasoning = CASE WHEN length(reasoning) > 200000 THEN substr(reasoning, 1, 200000) ELSE reasoning END;

UPDATE agent_messages SET toolArgs = CASE WHEN length(toolArgs) > 200000 THEN substr(toolArgs, 1, 200000) ELSE toolArgs END;

UPDATE agent_messages SET toolCallsJson = CASE WHEN length(toolCallsJson) > 200000 THEN substr(toolCallsJson, 1, 200000) ELSE toolCallsJson END;

UPDATE agent_messages SET attachmentsJson = CASE WHEN length(attachmentsJson) > 200000 THEN substr(attachmentsJson, 1, 200000) ELSE attachmentsJson END;
