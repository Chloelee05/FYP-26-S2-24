-- Seller ratings (SCRUM-XX). Run after migration_user_reviews.sql.
-- Enforces one rating per buyer per auction at the database level.
--
-- Safe to re-run. The guard is not only about second runs: auction_db.sql already
-- declares this constraint inline on user_reviews, so on a database built from the
-- base schema the constraint is present before this file is even reached and an
-- unguarded ADD CONSTRAINT fails on the very first run. It is still needed for
-- older databases whose user_reviews came from migration_user_reviews.sql, which
-- creates the table without it.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'user_reviews_one_per_auction'
      AND conrelid = 'user_reviews'::regclass
  ) THEN
    ALTER TABLE user_reviews
      ADD CONSTRAINT user_reviews_one_per_auction UNIQUE (auction_id, reviewer_user_id);
  END IF;
END $$;
