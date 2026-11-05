-- Append-only XP ledger for telemetry (turn/goal awards, daily streak bonuses).
-- Amounts and kinds are plain data; level curves are derived at read time so they can evolve.
-- Table only: no migration of existing data.
CREATE TABLE IF NOT EXISTS xp_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sessionId TEXT NOT NULL,
    kind TEXT NOT NULL,
    amount INTEGER NOT NULL,
    label TEXT NOT NULL,
    createdAt INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_xp_events_createdAt ON xp_events(createdAt);
CREATE INDEX IF NOT EXISTS index_xp_events_sessionId ON xp_events(sessionId);
