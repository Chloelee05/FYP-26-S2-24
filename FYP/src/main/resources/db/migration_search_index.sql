-- SCRUM-48: Performance index for keyword search on auction_details.title
-- Safe to re-run (IF NOT EXISTS). Apply once against auction_db.

-- Btree on title supports equality and prefix scans.
-- For infix ILIKE ('%keyword%') in production, consider enabling pg_trgm:
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_auction_details_title_trgm
--       ON auction_details USING GIN (LOWER(title) gin_trgm_ops);
-- The plain index below is sufficient for the current dataset size.
CREATE INDEX IF NOT EXISTS idx_auction_details_title
    ON auction_details (LOWER(title));

CREATE INDEX IF NOT EXISTS idx_auction_moderation_end
    ON auction (moderation_state, date_end);
