-- Seller ratings (SCRUM-XX). Run after migration_user_reviews.sql.
-- Enforces one rating per buyer per auction at the database level.
ALTER TABLE user_reviews
  ADD CONSTRAINT user_reviews_one_per_auction UNIQUE (auction_id, reviewer_user_id);
