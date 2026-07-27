-- Recommendation system extensions (SCRUM-74/75/76). Safe to re-run.

-- Recommendations a user dismissed ("not interested") — excluded from future results.
CREATE TABLE IF NOT EXISTS dismissed_recommendations (
  id         BIGSERIAL   PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  auction_id BIGINT      NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT dismissed_recs_user_fk    FOREIGN KEY (user_id)    REFERENCES users (id),
  CONSTRAINT dismissed_recs_auction_fk FOREIGN KEY (auction_id) REFERENCES auction (auction_id),
  CONSTRAINT dismissed_recs_unique     UNIQUE (user_id, auction_id)
);

-- Impression / click tracking for recommendation performance metrics (CTR, conversion).
CREATE TABLE IF NOT EXISTS recommendation_events (
  id         BIGSERIAL   PRIMARY KEY,
  user_id    BIGINT,
  auction_id BIGINT      NOT NULL,
  event_type VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT rec_events_type_check CHECK (event_type IN ('IMPRESSION', 'CLICK'))
);
CREATE INDEX IF NOT EXISTS idx_rec_events_type_user ON recommendation_events (event_type, user_id, auction_id);

-- Tunable recommendation parameters (admin-configurable).
CREATE TABLE IF NOT EXISTS recommendation_settings (
  key        VARCHAR(50)  PRIMARY KEY,
  value      VARCHAR(255) NOT NULL,
  updated_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO recommendation_settings (key, value) VALUES ('items_shown', '8')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO recommendation_settings (key, value) VALUES ('similarity_threshold', '0.1')
  ON CONFLICT (key) DO NOTHING;
