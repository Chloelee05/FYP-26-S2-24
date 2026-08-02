-- Recency decay on interaction weights, and a bounded lookback for the content-based
-- stage. Additive and safe to re-run.
--
-- No schema change is needed: bids.bid_time, watchlist.added_at and browse_history.viewed_at
-- already record when each interaction happened, they were simply never read. The ranking
-- now multiplies each interaction weight by exp(-Δdays / recency_tau_days), so a bid from
-- last spring stops counting as much as one from this morning.
--
-- recency_tau_days = 30: a signal a month old keeps about 37% of its weight and a
-- fortnight-old one about 63%. Setting it to 0 switches decay off and restores the flat
-- weighting exactly, which is the escape hatch if the fade turns out to be too aggressive.
--
-- content_window_days = 180 is deliberately generous. The content-based stage collects the
-- viewer's own bids, watchlist and browse history inside this window; a window shorter than
-- the age of the available history empties the stage and takes the SAME_CATEGORY arm with
-- it. Freshness is expressed by the decay above weighting old signals down, not by this
-- window cutting them off.
--
-- Unlike migration_recommendation_weights.sql, these values do change the ranking once the
-- code that reads them is deployed — there is no prior setting to reproduce, because the
-- behaviour being replaced was "every interaction counts the same forever". Applying this
-- migration against an older build changes nothing, since nothing reads the keys yet.

INSERT INTO recommendation_settings (key, value) VALUES ('recency_tau_days', '30.0')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO recommendation_settings (key, value) VALUES ('content_window_days', '180')
  ON CONFLICT (key) DO NOTHING;
