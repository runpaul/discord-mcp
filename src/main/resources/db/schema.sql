PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL,              -- ISO-8601 UTC
  type TEXT NOT NULL,            -- see list below
  guild_id TEXT, channel_id TEXT, user_id TEXT, user_name TEXT,
  payload TEXT NOT NULL DEFAULT '{}'   -- compact JSON
);
CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts);
CREATE INDEX IF NOT EXISTS idx_events_type ON events(type);
CREATE TABLE IF NOT EXISTS pending_actions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  requested_at TEXT NOT NULL, expires_at TEXT NOT NULL,
  tool TEXT NOT NULL, input_json TEXT NOT NULL, summary TEXT NOT NULL,
  approval_message_id TEXT, status TEXT NOT NULL,   -- pending|approved|rejected|expired|failed|done
  decided_by TEXT, result TEXT
);
-- Event types: member_join member_leave voice_join voice_leave voice_move
-- activity_start activity_end (PLAYING/STREAMING only) message message_delete
-- member_ban member_unban member_timeout member_role_change
-- channel_create channel_delete bot_mention
-- message payload: {len, has_attachments, mentions_bot, content_preview (<=300 chars)}
