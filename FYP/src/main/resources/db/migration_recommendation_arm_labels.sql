-- Per-arm recommendation measurement: which pipeline stage produced the card that was
-- shown or clicked. Additive and safe to re-run.
--
-- Until now every impression and click landed in one undifferentiated pool, so the CTR on
-- the admin dashboard could not say whether collaborative filtering was earning its place
-- or whether the trending filler was carrying the whole strip.
--
-- Values are the RecommendationProvenance.Reason names (PEER_BIDS, SIMILAR_TASTE,
-- SAME_CATEGORY, SEARCH_KEYWORD, TRENDING) plus TRENDING_CONTROL for the landing page's
-- separate popularity strip, which is recorded as a non-personalised baseline.
--
-- Deliberately no CHECK constraint: the arm names are validated in Java against the enum,
-- and a database-level whitelist would need a migration every time a stage is added.
-- NULL stays legal and means "recorded before this migration, or arm unknown".
ALTER TABLE recommendation_events ADD COLUMN IF NOT EXISTS reason_code VARCHAR(20);

-- Supports the GROUP BY reason_code roll-up behind the per-arm CTR table.
CREATE INDEX IF NOT EXISTS idx_rec_events_reason
  ON recommendation_events (reason_code, event_type);
