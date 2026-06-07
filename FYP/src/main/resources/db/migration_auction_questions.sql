-- Auction Q&A (SCRUM-62). Run after auction_db.sql.
-- Buyers ask public questions; sellers reply once per question.
CREATE TABLE IF NOT EXISTS auction_questions (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  auction_id      BIGINT      NOT NULL,
  asker_user_id   BIGINT      NOT NULL,
  question_text   TEXT        NOT NULL,
  answer_text     TEXT,
  answered_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT auction_questions_auction_fk FOREIGN KEY (auction_id)
      REFERENCES auction (auction_id),
  CONSTRAINT auction_questions_asker_fk FOREIGN KEY (asker_user_id)
      REFERENCES users (id)
);

CREATE INDEX IF NOT EXISTS idx_auction_questions_auction
    ON auction_questions (auction_id, created_at);
