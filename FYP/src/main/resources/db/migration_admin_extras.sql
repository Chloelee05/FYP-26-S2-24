-- Admin extras: report replies, support chat with admin.
-- Safe to re-run.

ALTER TABLE account_reports
    ADD COLUMN IF NOT EXISTS admin_reply TEXT;

ALTER TABLE seller_reports
    ADD COLUMN IF NOT EXISTS admin_reply TEXT;

CREATE TABLE IF NOT EXISTS support_threads (
  id          BIGSERIAL   PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  subject     VARCHAR(255) NOT NULL DEFAULT 'Support request',
  status      VARCHAR(20) NOT NULL DEFAULT 'OPEN'
              CHECK (status IN ('OPEN', 'CLOSED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT support_threads_user_fk FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX IF NOT EXISTS idx_support_threads_user ON support_threads (user_id);
CREATE INDEX IF NOT EXISTS idx_support_threads_status ON support_threads (status);

CREATE TABLE IF NOT EXISTS support_messages (
  id          BIGSERIAL   PRIMARY KEY,
  thread_id   BIGINT      NOT NULL,
  sender_id   BIGINT      NOT NULL,
  body        TEXT        NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT support_messages_thread_fk FOREIGN KEY (thread_id) REFERENCES support_threads (id),
  CONSTRAINT support_messages_sender_fk FOREIGN KEY (sender_id) REFERENCES users (id)
);
CREATE INDEX IF NOT EXISTS idx_support_messages_thread ON support_messages (thread_id);

ALTER TABLE support_messages
    ADD COLUMN IF NOT EXISTS attachment_url TEXT;
