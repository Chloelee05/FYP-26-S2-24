-- System-wide announcements: admin broadcasts for maintenance and policy updates.
--
-- An announcement is the admin-authored master record. Delivery is a fan-out: broadcasting
-- writes one row per targeted user into `notifications` (type = 'ANNOUNCEMENT'), so an
-- announcement rides the notification feed users already poll — the unread badge, per-user
-- read state and the notification history all keep working with no extra plumbing.
--
-- The master record is kept for audit: who sent what, to whom, and how many users it reached.
-- Safe to re-run.

CREATE TABLE IF NOT EXISTS announcements (
  id              BIGSERIAL    PRIMARY KEY,
  title           VARCHAR(150) NOT NULL,
  message         TEXT         NOT NULL,
  -- Who receives it: every active user, or only one role.
  audience        VARCHAR(20)  NOT NULL DEFAULT 'ALL',
  -- How urgent it is; drives the email subject prefix and (later) the in-app styling.
  severity        VARCHAR(20)  NOT NULL DEFAULT 'INFO',
  -- Optional in-app destination, e.g. /profile. Internal paths only — the notification
  -- bell hands this to the SPA router, which cannot navigate to an external URL.
  link            VARCHAR(512),
  -- Admin who sent it; NULL once that account is anonymised (PDPA account deletion).
  created_by      BIGINT,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- Notification rows written at broadcast time; the reach shown in the admin history.
  recipient_count INT          NOT NULL DEFAULT 0 CHECK (recipient_count >= 0),
  CONSTRAINT announcements_audience_check CHECK (audience IN ('ALL', 'BUYERS', 'SELLERS')),
  CONSTRAINT announcements_severity_check CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  CONSTRAINT announcements_created_by_fk FOREIGN KEY (created_by) REFERENCES users (id)
);

-- The admin history reads the newest announcements first.
CREATE INDEX IF NOT EXISTS idx_announcements_created_at ON announcements (created_at DESC);
