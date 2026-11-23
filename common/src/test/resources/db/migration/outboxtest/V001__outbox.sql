CREATE TABLE parent (id INTEGER PRIMARY KEY) STRICT;
CREATE TABLE child (parent_id INTEGER NOT NULL REFERENCES parent(id)) STRICT;
CREATE TABLE outbox (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL CHECK (length(event_id) = 36),
  destination TEXT NOT NULL CHECK (destination IN ('sync','gamification','insight')),
  aggregate TEXT NOT NULL,
  aggregate_id TEXT NOT NULL,
  type TEXT NOT NULL,
  payload BLOB NOT NULL CHECK (length(payload) <= 65536),
  created_at INTEGER NOT NULL,
  published_at INTEGER,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER NOT NULL,
  last_error TEXT,
  dead_at INTEGER,
  UNIQUE (event_id, destination)
) STRICT;
