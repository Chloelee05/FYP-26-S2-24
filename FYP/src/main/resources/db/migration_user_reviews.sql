-- Reviews (SCRUM-84). Run after core auction schema exists.
CREATE TABLE IF NOT EXISTS user_reviews (
  id                 BIGSERIAL PRIMARY KEY,
  reviewer_user_id   BIGINT       NOT NULL,
  reviewee_user_id   BIGINT       NOT NULL,
  auction_id         BIGINT,
  rating             SMALLINT     NOT NULL CHECK (rating >= 1 AND rating <= 5),
  comment            TEXT,
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT user_reviews_reviewer_fk FOREIGN KEY (reviewer_user_id) REFERENCES users (id),
  CONSTRAINT user_reviews_reviewee_fk FOREIGN KEY (reviewee_user_id) REFERENCES users (id),
  CONSTRAINT user_reviews_auction_fk  FOREIGN KEY (auction_id)       REFERENCES auction (auction_id),
  CONSTRAINT user_reviews_no_self CHECK (reviewer_user_id <> reviewee_user_id)
);
