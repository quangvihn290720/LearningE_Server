CREATE TABLE users (
  id INTEGER PRIMARY KEY,
  uuid TEXT NOT NULL UNIQUE CHECK (length(uuid) = 36),
  email TEXT NOT NULL UNIQUE COLLATE NOCASE CHECK (length(email) BETWEEN 5 AND 254),
  nickname TEXT NOT NULL UNIQUE COLLATE NOCASE CHECK (length(nickname) BETWEEN 3 AND 20),
  display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 2 AND 30),
  password_hash TEXT NOT NULL, password_salt TEXT NOT NULL,
  failed_login_count INTEGER NOT NULL DEFAULT 0, login_lock_count INTEGER NOT NULL DEFAULT 0,
  login_locked_until INTEGER, login_last_fail_at INTEGER,
  failed_reset_count INTEGER NOT NULL DEFAULT 0, reset_lock_count INTEGER NOT NULL DEFAULT 0,
  reset_locked_until INTEGER, reset_last_fail_at INTEGER,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
) STRICT;

CREATE TABLE auth_tokens (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64),
  device_name TEXT CHECK (device_name IS NULL OR length(device_name) <= 60),
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
  last_used_at INTEGER, revoked_at INTEGER
) STRICT;
CREATE INDEX idx_tokens_user ON auth_tokens(user_id);
CREATE INDEX idx_tokens_expiry ON auth_tokens(expires_at);

CREATE TABLE security_answers (
  user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  question_id INTEGER NOT NULL CHECK (question_id BETWEEN 1 AND 5),
  answer_hash TEXT NOT NULL, answer_salt TEXT NOT NULL, updated_at INTEGER NOT NULL
) STRICT;

-- Bộ đếm khóa tăng dần cho email KHÔNG tồn tại (cùng thuật toán, cùng kết quả như email có thật — BR-076)
CREATE TABLE auth_attempts (
  subject TEXT NOT NULL CHECK (length(subject) = 64),
  scope TEXT NOT NULL CHECK (scope IN ('LOGIN','RESET')),
  failed_count INTEGER NOT NULL DEFAULT 0, lock_count INTEGER NOT NULL DEFAULT 0,
  locked_until INTEGER, last_fail_at INTEGER NOT NULL,
  PRIMARY KEY (subject, scope)
) STRICT;
CREATE INDEX idx_attempts_last ON auth_attempts(last_fail_at);

CREATE TABLE server_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) STRICT;

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
CREATE INDEX idx_outbox_pending ON outbox(destination, id) WHERE published_at IS NULL AND dead_at IS NULL;
CREATE INDEX idx_outbox_published ON outbox(published_at) WHERE published_at IS NOT NULL;
