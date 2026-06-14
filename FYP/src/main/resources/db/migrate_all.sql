-- Apply all incremental migrations to an existing auction_db.
-- Usage (from project root):
--   psql -U postgres -h localhost -p 5432 -d auction_db -f FYP/src/main/resources/db/migrate_all.sql
--
-- Safe to re-run: individual scripts use IF NOT EXISTS / ON CONFLICT DO NOTHING.

\echo '== lookup seed + seller columns =='
\ir migration_lookup_seed_data.sql

\echo '== categories =='
\ir migration_categories.sql

\echo '== seller features =='
\ir migration_seller_features.sql

\echo '== admin moderation =='
\ir migration_admin_moderation.sql

\echo '== auction Q&A =='
\ir migration_auction_questions.sql

\echo '== auto-bids =='
\ir migration_auto_bids.sql

\echo '== auto-bid increment (per-buyer step) =='
\ir migration_auto_bid_increment.sql

\echo '== watchlist =='
\ir migration_watchlist.sql

\echo '== user reviews =='
\ir migration_user_reviews.sql

\echo '== seller ratings =='
\ir migration_seller_ratings.sql

\echo '== account reports =='
\ir migration_account_reports.sql

\echo '== seller/listing reports =='
\ir migration_seller_reports.sql

\echo '== search indexes =='
\ir migration_search_index.sql

\echo '== minimum-requirements (payments, notifications, orders, strategy, approval) =='
\ir migration_min_requirements.sql

\echo '== demo seed (optional — strategy auctions, pending users, recommendation signals) =='
\ir demo_seed.sql

\echo '== admin extras (report replies, support chat) =='
\ir migration_admin_extras.sql

\echo '== order shipping tracking =='
\ir migration_orders_shipping.sql

\echo '== order refund requests =='
\ir migration_orders_refund.sql

\echo '== order messages (buyer <-> seller) + refund decisions =='
\ir migration_order_messages.sql

\echo '== support chat read receipts =='
\ir migration_support_reads.sql

\echo '== browse history + platform revenue =='
\ir migration_browse_history_and_revenue.sql

\echo 'All migrations applied.'
