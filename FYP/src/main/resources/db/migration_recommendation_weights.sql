-- Interaction weights for user-based collaborative filtering. Safe to re-run.
--
-- These were hardcoded in UserBasedCollaborativeFilter (W_BID = 3, W_WATCHLIST = 2,
-- W_BROWSE = 1), which made the single most consequential judgement in the recommender —
-- how much more a bid says about someone's taste than a page view — unchangeable without
-- a redeploy. The Java constants stay as the fallback for an unmigrated database; the
-- values seeded here are what the ranking actually reads.
--
-- Seeded to match the previous constants exactly, so applying this migration on its own
-- changes no recommendation.

INSERT INTO recommendation_settings (key, value) VALUES ('w_bid', '3.0')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO recommendation_settings (key, value) VALUES ('w_watchlist', '2.0')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO recommendation_settings (key, value) VALUES ('w_browse', '1.0')
  ON CONFLICT (key) DO NOTHING;
