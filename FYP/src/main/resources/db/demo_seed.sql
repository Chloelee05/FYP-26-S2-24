-- Demo seed data for mid-presentation / FYP demo scenarios.
-- Safe to re-run: skips rows that already exist (matched by email or [DEMO] title prefix).
--
-- Apply after migrate_all.sql:
--   psql -U postgres -d auction_db -f FYP/src/main/resources/db/demo_seed.sql
--
-- Demo accounts (password for all: DemoPass1!)
--   demo_seller@auctionhub.test   — Seller (active)
--   demo_buyer1@auctionhub.test   — Buyer (active, bid history for recommendations)
--   demo_buyer2@auctionhub.test   — Buyer (active, peer for collaborative filtering)
--   pending_user@auctionhub.test  — Buyer (PENDING — admin approval demo)
--   rejected_user@auctionhub.test — Buyer (REJECTED)
--
-- Demo auctions (seller = demo_seller):
--   [DEMO] Standard Ascending Watch
--   [DEMO] Dutch Descending Laptop
--   [DEMO] Blind Sealed Headphones
--   [DEMO] Ended Camera (declare-winner / order flow)

-- Fixed hash for plaintext "DemoPass1!" (SecurityUtil.hashPassword)
-- Regenerate: javac -cp target/classes HashGen.java && java -cp "target/classes;." HashGen

-- ── Users ─────────────────────────────────────────────────────────────────────
INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'demo_seller', 'demo_seller@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 3, 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'demo_seller@auctionhub.test');

INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'demo_buyer1', 'demo_buyer1@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'demo_buyer1@auctionhub.test');

INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'demo_buyer2', 'demo_buyer2@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'demo_buyer2@auctionhub.test');

INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'pending_user', 'pending_user@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 4
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'pending_user@auctionhub.test');

INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'rejected_user', 'rejected_user@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 5
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'rejected_user@auctionhub.test');

-- ── Standard ascending auction ────────────────────────────────────────────────
INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
SELECT 1, u.id, now() - interval '2 hours', now() + interval '3 days', 1, 'active'
FROM users u
WHERE u.email = 'demo_seller@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Standard Ascending Watch');

INSERT INTO auction_details (id, title, description, category, item_condition_id,
                             starting_price, max_price, quantity, cost_price)
SELECT a.auction_id,
       '[DEMO] Standard Ascending Watch',
       'Classic ascending-price demo listing. Place bids in $50 increments.',
       'Collectibles', 2, 500.00, 2000.00, 1, 350.00
FROM auction a
JOIN users u ON u.id = a.seller_id
WHERE u.email = 'demo_seller@auctionhub.test'
  AND a.auction_type = 1
  AND a.date_end > now()
  AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Standard Ascending Watch')
ORDER BY a.auction_id DESC
LIMIT 1;

-- ── Dutch descending auction ──────────────────────────────────────────────────
INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
SELECT 1, u.id, now() - interval '1 hour', now() + interval '2 days', 2, 'active'
FROM users u
WHERE u.email = 'demo_seller@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Dutch Descending Laptop');

INSERT INTO auction_details (id, title, description, category, item_condition_id,
                             starting_price, quantity, cost_price, dutch_floor_price)
SELECT a.auction_id,
       '[DEMO] Dutch Descending Laptop',
       'Price drops from $1,200 toward $400. First buyer to accept wins.',
       'Electronics', 2, 1200.00, 1, 800.00, 400.00
FROM auction a
JOIN users u ON u.id = a.seller_id
WHERE u.email = 'demo_seller@auctionhub.test'
  AND a.auction_type = 2
  AND a.date_end > now()
  AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Dutch Descending Laptop')
ORDER BY a.auction_id DESC
LIMIT 1;

-- ── Blind sealed-bid auction ─────────────────────────────────────────────────
INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
SELECT 1, u.id, now() - interval '30 minutes', now() + interval '4 days', 3, 'active'
FROM users u
WHERE u.email = 'demo_seller@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Blind Sealed Headphones');

INSERT INTO auction_details (id, title, description, category, item_condition_id,
                             starting_price, quantity, cost_price)
SELECT a.auction_id,
       '[DEMO] Blind Sealed Headphones',
       'Submit one hidden bid. Amounts stay secret until the auction closes.',
       'Electronics', 1, 100.00, 1, 60.00
FROM auction a
JOIN users u ON u.id = a.seller_id
WHERE u.email = 'demo_seller@auctionhub.test'
  AND a.auction_type = 3
  AND a.date_end > now()
  AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Blind Sealed Headphones')
ORDER BY a.auction_id DESC
LIMIT 1;

-- ── Ended auction (declare-winner demo) ─────────────────────────────────────
INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
SELECT 1, u.id, now() - interval '5 days', now() - interval '1 hour', 1, 'active'
FROM users u
WHERE u.email = 'demo_seller@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Ended Camera');

INSERT INTO auction_details (id, title, description, category, item_condition_id,
                             starting_price, max_price, quantity, cost_price)
SELECT a.auction_id,
       '[DEMO] Ended Camera',
       'This auction has ended. Seller can declare the winner and create an order.',
       'Electronics', 3, 200.00, 800.00, 1, 120.00
FROM auction a
JOIN users u ON u.id = a.seller_id
WHERE u.email = 'demo_seller@auctionhub.test'
  AND a.date_end < now()
  AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Ended Camera')
ORDER BY a.auction_id DESC
LIMIT 1;

-- ── Recommendation co-occurrence: shared + unique bids ──────────────────────
INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, b.id, 550.00, now() - interval '20 minutes'
FROM auction_details d, users b
WHERE d.title = '[DEMO] Standard Ascending Watch'
  AND b.email = 'demo_buyer1@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = b.id);

INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, b.id, 600.00, now() - interval '15 minutes'
FROM auction_details d, users b
WHERE d.title = '[DEMO] Standard Ascending Watch'
  AND b.email = 'demo_buyer2@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = b.id);

INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, b.id, 650.00, now() - interval '10 minutes'
FROM auction_details d, users b
WHERE d.title = '[DEMO] Dutch Descending Laptop'
  AND b.email = 'demo_buyer2@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = b.id);

INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, b.id, 450.00, now() - interval '2 hours'
FROM auction_details d, users b
WHERE d.title = '[DEMO] Ended Camera'
  AND b.email = 'demo_buyer1@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = b.id);

INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, b.id, 380.00, now() - interval '3 hours'
FROM auction_details d, users b
WHERE d.title = '[DEMO] Ended Camera'
  AND b.email = 'demo_buyer2@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = b.id);

-- ── Watchlist (extra collaborative-filtering signal) ──────────────────────────
INSERT INTO watchlist (user_id, auction_id)
SELECT b.id, d.id
FROM users b, auction_details d
WHERE b.email = 'demo_buyer1@auctionhub.test'
  AND d.title = '[DEMO] Blind Sealed Headphones'
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.user_id = b.id AND w.auction_id = d.id);

INSERT INTO watchlist (user_id, auction_id)
SELECT b.id, d.id
FROM users b, auction_details d
WHERE b.email = 'demo_buyer2@auctionhub.test'
  AND d.title = '[DEMO] Blind Sealed Headphones'
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.user_id = b.id AND w.auction_id = d.id);
