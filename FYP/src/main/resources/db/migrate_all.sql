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

\echo '== payment method types (card / PayPal / bank transfer) =='
\ir migration_payment_methods_types.sql

\echo '== buy it now price =='
\ir migration_buy_it_now.sql

\echo '== notification preferences =='
\ir migration_notification_preferences.sql

\echo '== recommendation features (dismiss, metrics, settings) =='
\ir migration_recommendation_features.sql

\echo '== linked third-party accounts (Google sign-in) =='
\ir migration_linked_accounts.sql

\echo '== merged buyer/seller accounts (can_sell capability) =='
\ir migration_seller_capability.sql

\echo '== recommendation explainability (search history, keyword attribution) =='
\ir migration_recommendation_explainability.sql

\echo '== admin-editable landing page copy =='
\ir migration_landing_content.sql

\echo '== landing page head-to-head comparison copy =='
\ir migration_landing_content_comparison.sql

\echo '== recommendations strip subtitle for signed-in users with no history =='
\ir migration_landing_content_recommendation_framing.sql

\echo '== auction card CTA labels (View Auction / View Result) =='
\ir migration_landing_content_card_cta.sql

\echo '== Telegram notifications (links, one-time codes, outbox, preferences, copy) =='
\ir migration_telegram_notifications.sql

\echo '== Telegram alert bodies (outbid / won / lost) =='
\ir migration_telegram_alerts.sql

\echo 'All migrations applied.'
