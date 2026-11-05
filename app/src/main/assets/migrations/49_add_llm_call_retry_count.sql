-- Add a retry-count column to the LLM call record table, tracking underlying retries within one interaction
ALTER TABLE llm_call_records ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0;
