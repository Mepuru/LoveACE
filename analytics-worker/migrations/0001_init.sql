CREATE TABLE IF NOT EXISTS analytics_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id TEXT NOT NULL,
  platform TEXT NOT NULL,
  app_version TEXT NOT NULL,
  build TEXT,
  os_version TEXT,
  device_model TEXT,
  grade_prefix TEXT,
  student_hash TEXT,
  event_name TEXT NOT NULL,
  event_time TEXT NOT NULL,
  properties TEXT NOT NULL,
  ip_hash TEXT,
  user_agent TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_analytics_events_created_at ON analytics_events(created_at);
CREATE INDEX IF NOT EXISTS idx_analytics_events_name ON analytics_events(event_name);
CREATE INDEX IF NOT EXISTS idx_analytics_events_client_id ON analytics_events(client_id);
CREATE INDEX IF NOT EXISTS idx_analytics_events_student_hash ON analytics_events(student_hash);

CREATE TABLE IF NOT EXISTS analytics_nonces (
  nonce TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS analytics_rate_limits (
  key TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (key, window_start)
);
