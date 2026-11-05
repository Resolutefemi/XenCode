-- Store the number of server-side cached tokens (Anthropic cache_creation_input_tokens), charged at its own rate in cost estimates.
ALTER TABLE llm_call_records ADD COLUMN cacheCreationTokens INTEGER NOT NULL DEFAULT 0
