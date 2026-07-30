-- Recommendation explainability: why an item was recommended, how it performed,
-- and which search keywords surfaced it. Safe to re-run.
--
-- Privacy note: per-user rows live here so the platform can explain itself, but the
-- public landing page only ever reads aggregates (counts) and masked usernames from
-- them. Row-level detail is exposed exclusively through the ADMIN-only endpoint.

-- Keywords typed into search, per user (NULL user_id = signed-out visitor).
-- No FK on user_id: this mirrors recommendation_events, where analytics rows must
-- survive independently of the accounts that produced them.
CREATE TABLE IF NOT EXISTS search_history (
  id         BIGSERIAL    PRIMARY KEY,
  user_id    BIGINT,
  keyword    VARCHAR(120) NOT NULL,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_search_history_user    ON search_history (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_search_history_keyword ON search_history (LOWER(keyword));

-- The keyword an impression/click was attributed to, when the card was surfaced by
-- one of the viewer's recent searches. NULL for CF / content / trending placements.
ALTER TABLE recommendation_events ADD COLUMN IF NOT EXISTS source_keyword VARCHAR(120);
CREATE INDEX IF NOT EXISTS idx_rec_events_keyword
  ON recommendation_events (auction_id, source_keyword);
