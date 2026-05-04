-- Run against an existing auction_db that was created before admin moderation columns.
-- Safe to run multiple times on PostgreSQL 12+.

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_status_changed_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;
UPDATE users SET last_status_changed_at = date_created WHERE last_status_changed_at IS NULL;

ALTER TABLE auction ADD COLUMN IF NOT EXISTS report_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE auction ADD COLUMN IF NOT EXISTS moderation_state VARCHAR(20) NOT NULL DEFAULT 'active';

ALTER TABLE auction DROP CONSTRAINT IF EXISTS auction_moderation_state_check;
ALTER TABLE auction ADD CONSTRAINT auction_moderation_state_check
  CHECK (moderation_state IN ('active', 'flagged', 'removed'));

ALTER TABLE auction_details ADD COLUMN IF NOT EXISTS category VARCHAR(100) NOT NULL DEFAULT 'Other';
