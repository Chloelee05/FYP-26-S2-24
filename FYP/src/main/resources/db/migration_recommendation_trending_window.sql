-- Trending window: how many days of bids "trending" actually counts. Safe to re-run.
--
-- trending() previously ranked on a bid count over all time, so a listing that ran for
-- months stayed permanently "trending" and the card's own wording ("the most bids today")
-- was false. The window is a setting rather than a new hardcoded constant so an admin can
-- widen it on a quiet marketplace without a redeploy.
--
-- RecommendationDAO.DEFAULT_TRENDING_WINDOW_DAYS is the fallback for an unmigrated
-- database and matches the value seeded here.

INSERT INTO recommendation_settings (key, value) VALUES ('trending_window_days', '7')
  ON CONFLICT (key) DO NOTHING;

-- The landing strip subtitle claimed "today" for the same reason. The copy is admin-editable,
-- so only rows still holding the original default are corrected — an admin who has already
-- reworded this keeps their wording. {days} is substituted with the live setting by Home.jsx.
UPDATE landing_content
   SET content_value = 'The listings collecting the most bids in the last {days} days.'
 WHERE content_key = 'section.trending.subtitle'
   AND content_value = 'The listings collecting the most bids today.';

UPDATE landing_content
   SET default_value = 'The listings collecting the most bids in the last {days} days.',
       label         = 'Trending Auctions — subtitle ({days} = trending window)'
 WHERE content_key = 'section.trending.subtitle'
   AND default_value = 'The listings collecting the most bids today.';
