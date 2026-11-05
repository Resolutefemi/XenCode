-- Verbatim snapshots (JSON text) of Anthropic thinking / redacted_thinking content blocks and Gemini model-turn parts.
-- These blocks must be returned unmodified and in order. A single signature field cannot carry redacted opaque data and part-level signatures.
ALTER TABLE agent_messages ADD COLUMN thinkingBlocksJson TEXT DEFAULT NULL
