CREATE TABLE user_settings (
  user_id TEXT PRIMARY KEY CHECK (length(user_id) = 36),
  level TEXT NOT NULL DEFAULT 'A1' CHECK (level IN ('A1','A2','B1')),
  daily_goal INTEGER NOT NULL DEFAULT 10 CHECK (daily_goal IN (5,10,15,20)),
  updated_at INTEGER NOT NULL, server_seq INTEGER NOT NULL
) STRICT;

CREATE TABLE user_stats (
  user_id TEXT PRIMARY KEY,
  total_xp INTEGER NOT NULL DEFAULT 0 CHECK (total_xp >= 0),
  current_streak INTEGER NOT NULL DEFAULT 0, longest_streak INTEGER NOT NULL DEFAULT 0,
  last_study_date TEXT, updated_at INTEGER NOT NULL
) STRICT;

CREATE TABLE decks (
  uuid TEXT PRIMARY KEY, user_id TEXT NOT NULL,
  name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 50), description TEXT,
  language_code TEXT NOT NULL, icon TEXT,
  is_default INTEGER NOT NULL DEFAULT 0 CHECK (is_default IN (0,1)), sort_order INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)), server_seq INTEGER NOT NULL
) STRICT;

CREATE TABLE words (
  uuid TEXT PRIMARY KEY, user_id TEXT NOT NULL,
  deck_uuid TEXT NOT NULL, term TEXT NOT NULL, reading TEXT, part_of_speech TEXT,
  meaning_vi TEXT NOT NULL, example TEXT, example_translation TEXT,
  sort_order INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)), server_seq INTEGER NOT NULL
) STRICT;

CREATE TABLE card_progress (
  user_id TEXT NOT NULL, word_uid TEXT NOT NULL,
  box INTEGER NOT NULL CHECK (box BETWEEN 1 AND 5),
  next_review_date TEXT NOT NULL, first_learned_date TEXT NOT NULL,
  last_reviewed_at INTEGER, review_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL, server_seq INTEGER NOT NULL,
  PRIMARY KEY (user_id, word_uid)
) STRICT;

CREATE TABLE favorites (
  user_id TEXT NOT NULL, word_uid TEXT NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)), server_seq INTEGER NOT NULL,
  PRIMARY KEY (user_id, word_uid)
) STRICT;

CREATE TABLE study_log (
  uuid TEXT PRIMARY KEY, user_id TEXT NOT NULL,
  activity_type TEXT NOT NULL CHECK (activity_type IN ('LEARN','REVIEW','QUIZ','EXAM')),
  ref_uuid TEXT, study_date TEXT NOT NULL, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL, server_seq INTEGER NOT NULL
) STRICT;

CREATE TABLE quiz_attempts (
  uuid TEXT PRIMARY KEY, user_id TEXT NOT NULL,
  source_type TEXT NOT NULL CHECK (source_type = 'DECK'), deck_ref TEXT,
  score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
  correct_count INTEGER NOT NULL, total_count INTEGER NOT NULL CHECK (total_count BETWEEN 1 AND 200),
  duration_sec INTEGER NOT NULL, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL, server_seq INTEGER NOT NULL
) STRICT;

CREATE TABLE exam_attempts (
  uuid TEXT PRIMARY KEY, user_id TEXT NOT NULL,
  test_code TEXT NOT NULL, score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
  correct_count INTEGER NOT NULL, total_count INTEGER NOT NULL CHECK (total_count BETWEEN 1 AND 200),
  duration_sec INTEGER NOT NULL, started_at INTEGER NOT NULL, submitted_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL, server_seq INTEGER NOT NULL
) STRICT;

CREATE TABLE xp_events (
  uuid TEXT PRIMARY KEY, user_id TEXT NOT NULL,
  source_type TEXT NOT NULL CHECK (source_type IN ('CARD_SESSION','QUIZ','EXAM')),
  ref_uuid TEXT,
  correct_count INTEGER NOT NULL CHECK (correct_count BETWEEN 0 AND 200),
  wrong_count INTEGER NOT NULL CHECK (wrong_count BETWEEN 0 AND 200),
  total_count INTEGER NOT NULL CHECK (total_count = correct_count + wrong_count),
  xp_awarded INTEGER NOT NULL CHECK (xp_awarded >= 0),
  occurred_at INTEGER NOT NULL, received_at INTEGER NOT NULL,
  local_date TEXT NOT NULL, week_start TEXT NOT NULL, server_seq INTEGER NOT NULL
) STRICT;

CREATE TABLE sync_seq (id INTEGER PRIMARY KEY CHECK (id = 1), value INTEGER NOT NULL) STRICT;
INSERT INTO sync_seq VALUES (1, 0);

CREATE TABLE purged_users (user_id TEXT PRIMARY KEY, purged_at INTEGER NOT NULL) STRICT;

CREATE INDEX idx_settings_seq ON user_settings(user_id, server_seq);
CREATE INDEX idx_decks_user_seq ON decks(user_id, server_seq);
CREATE INDEX idx_words_user_seq ON words(user_id, server_seq);
CREATE INDEX idx_progress_user_seq ON card_progress(user_id, server_seq);
CREATE INDEX idx_fav_user_seq ON favorites(user_id, server_seq);
CREATE INDEX idx_log_user_seq ON study_log(user_id, server_seq);
CREATE INDEX idx_quiz_user_seq ON quiz_attempts(user_id, server_seq);
CREATE INDEX idx_exam_user_seq ON exam_attempts(user_id, server_seq);
CREATE INDEX idx_xp_user_seq ON xp_events(user_id, server_seq);
CREATE INDEX idx_xp_user_date ON xp_events(user_id, local_date);
CREATE INDEX idx_xp_seq ON xp_events(server_seq);
CREATE INDEX idx_log_user_date ON study_log(user_id, study_date);

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
