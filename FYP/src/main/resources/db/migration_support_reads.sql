-- Per-user read receipts for support chat threads (Telegram-style unread). Safe to re-run.

CREATE TABLE IF NOT EXISTS support_thread_reads (
  thread_id     BIGINT      NOT NULL,
  user_id       BIGINT      NOT NULL,
  last_read_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (thread_id, user_id),
  CONSTRAINT support_thread_reads_thread_fk FOREIGN KEY (thread_id) REFERENCES support_threads (id) ON DELETE CASCADE,
  CONSTRAINT support_thread_reads_user_fk  FOREIGN KEY (user_id)  REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_support_thread_reads_user ON support_thread_reads (user_id);
