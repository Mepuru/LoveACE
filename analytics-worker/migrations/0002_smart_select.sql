CREATE TABLE IF NOT EXISTS smart_select_sessions (
  session_id TEXT PRIMARY KEY,
  web_token_hash TEXT NOT NULL,
  pairing_token_hash TEXT NOT NULL,
  status TEXT NOT NULL,
  schema_version INTEGER NOT NULL DEFAULT 1,
  selected_term_code TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TEXT NOT NULL,
  connected_at TEXT,
  ready_at TEXT,
  last_heartbeat_at TEXT,
  error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_smart_select_sessions_expires_at ON smart_select_sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_smart_select_sessions_status ON smart_select_sessions(status);

CREATE TABLE IF NOT EXISTS smart_select_payloads (
  session_id TEXT NOT NULL,
  dataset TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id, dataset)
);

CREATE TABLE IF NOT EXISTS smart_select_actions (
  session_id TEXT PRIMARY KEY,
  selected_courses_json TEXT NOT NULL DEFAULT '[]',
  removed_courses_json TEXT NOT NULL DEFAULT '[]',
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
