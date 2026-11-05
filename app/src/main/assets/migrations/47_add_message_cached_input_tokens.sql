-- Persist the input tokens served from the server-side cache for this call, shown under each bubble as the cache hit rate.
ALTER TABLE agent_messages ADD COLUMN cachedInputTokens INTEGER NOT NULL DEFAULT 0;
