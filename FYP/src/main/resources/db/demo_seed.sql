-- Demo seed data for the FYP demo scenarios: the four recommender arms, the three
-- auction strategies, the admin approval queue, and the ended-auction order flow.
--
-- Safe to re-run any number of times: every statement is guarded, so a second run
-- inserts nothing and errors nothing. Included by migrate_all.sql (\ir demo_seed.sql)
-- and also applicable on its own:
--   psql -U postgres -d auction_db -f FYP/src/main/resources/db/demo_seed.sql
--
-- All timestamps are relative (now() ± interval), and every interaction falls inside
-- the last two weeks so the 7-day trending window and the "recent activity" panels
-- have something to show.
--
-- ── Demo accounts (password for all: DemoPass1!) ─────────────────────────────
--   demo_seller@auctionhub.test    Seller, can_sell — lists L1–L4, L9, L10
--   demo_seller2@auctionhub.test   Seller, can_sell — lists L5–L8
--   demo_buyer1@auctionhub.test    Buyer  — THE VIEWER; log in as this account
--   demo_buyer2@auctionhub.test    Buyer  — peer group A
--   peer_a2@auctionhub.test        Buyer  — peer group A
--   peer_b1@auctionhub.test        Buyer  — peer group B
--   peer_b2@auctionhub.test        Buyer  — peer group B
--   pending_user@auctionhub.test   Buyer  — PENDING (admin approval demo)
--   rejected_user@auctionhub.test  Buyer  — REJECTED
--
-- ── Demo listings ────────────────────────────────────────────────────────────
--   L1  Standard Ascending Watch   Collectibles    Price Up  live   ends +3d
--   L2  Dutch Descending Laptop    Electronics     Dutch     live   ends +4d
--   L3  Blind Sealed Headphones    Electronics     Blind     live   ends +5d
--   L4  Vintage Vinyl Crate        Collectibles    Price Up  live   ends +6d
--   L5  Trail Running Shoes        Sports          Price Up  live   ends +7d
--   L6  Carbon Road Bike           Sports          Price Up  live   ends +8d
--   L7  Cast Iron Planter Set      Home & Garden   Dutch     live   ends +9d
--   L8  Sealed Trading Card Box    Collectibles    Price Up  live   ends +10d
--   L9  Ended Camera               Electronics     Price Up  ENDED  -2 hours
--   L10 Ended Garden Bench         Home & Garden   Price Up  ENDED  -1 day
--
-- Four categories are used (Collectibles, Electronics, Sports, Home & Garden) and
-- each of them has at least one live listing, so the earlier failure mode — every
-- listing in a category having ended, leaving the content arm nothing to return —
-- cannot recur. The ended listings keep status_id = 1 so the seller can still
-- declare a winner and create an order from them.
--
-- ── Peer-group design (why the recommender returns mixed reason codes) ────────
-- Viewer = demo_buyer1. Its own signals are:
--   bids       L1 (Watch), L9 (Ended Camera)
--   watchlist  L2 (Dutch Laptop)
--   browse     L1, L2, L10 (Ended Garden Bench — browse only, no bid or watch)
-- so my_items = {L1, L9, L2} and my_signals = {L1, L9, L2, L10}, which puts the
-- viewer's category history at Collectibles, Electronics and Home & Garden.
--
-- Group A — demo_buyer2, peer_a2 → drives PEER_BIDS.
--   These two share bids/watchlist rows with the viewer's my_items:
--   demo_buyer2 bids on L1 and L9, peer_a2 watchlists L2. That puts them in the
--   peer set of RecommendationDAO.collaborativeFiltering(), which builds its peer
--   set from bids and watchlist only. Their other items are L3 and L4, so those
--   two come back as PEER_BIDS (score 2 each, ordered by soonest end: L3 then L4).
--
-- Group B — peer_b1, peer_b2 → drives SIMILAR_TASTE.
--   These two overlap with the viewer through browse_history ONLY. That is the
--   whole point: collaborativeFiltering() never reads browse_history, so group B
--   stays out of the PEER_BIDS peer set, while loadInteractionVectors() does union
--   browse history (weight 1) with bids (3) and watchlist (2), so group B still
--   clears the 0.1 cosine threshold and reaches the user-based CF arm. If group B
--   shared a single bid or watchlist row with the viewer it would collapse into
--   group A and the two arms would return the same items.
--   With the default weights the vectors are:
--     viewer  {L1:3, L9:3, L2:2, L10:1}          norm sqrt(23) = 4.796
--     peer_b1 {L1:1, L2:1, L10:1, L5:3, L6:2}    norm 4       dot 6  cos 0.313
--     peer_b2 {L2:1, L10:1, L5:2, L6:3}          norm sqrt(15) dot 3  cos 0.162
--   Both are above the 0.1 threshold, and their unseen items are L5 and L6, which
--   is what SIMILAR_TASTE returns (L5 scores 1.26, L6 scores 1.11). Group A is
--   similar to the viewer too, but its items were already taken by the PEER_BIDS
--   stage and the pipeline excludes them, so it contributes nothing here.
--   The two groups are disjoint: no account appears in both.
--
-- Reserved for SAME_CATEGORY — L7 (Home & Garden) and L8 (Collectibles).
--   Both are live, both sit in a category the viewer has history in (L10 browse for
--   Home & Garden, L1 bid for Collectibles), and NO peer bids on, watchlists or
--   browses either of them. So no earlier stage can claim them and the content-based
--   stage has something left to return.
--
-- Expected result when logging in as demo_buyer1 (default limit 8):
--   PEER_BIDS      L3, L4
--   SIMILAR_TASTE  L5, L6
--   SAME_CATEGORY  L7, L8
--   TRENDING       filler only, from whatever else is live on the instance
--
-- No rows are inserted into recommendation_events. Impressions and clicks must be
-- earned by actually using the site, otherwise the click-through-rate and
-- conversion figures on the admin metrics page would be reporting seeded numbers.
--
-- Fixed hash for plaintext "DemoPass1!" (SecurityUtil.hashPassword)
-- Regenerate: javac -cp target/classes HashGen.java && java -cp "target/classes;." HashGen

-- ── Prerequisites ────────────────────────────────────────────────────────────
-- migrate_all.sql includes this file before migration_browse_history_and_revenue.sql
-- and migration_seller_capability.sql, so on a brand-new database neither
-- browse_history nor users.can_sell exists yet at this point. Both definitions are
-- repeated verbatim here and are IF NOT EXISTS, which makes them no-ops on the
-- already-migrated deployment and on the later migrations themselves.
CREATE TABLE IF NOT EXISTS browse_history (
    id          BIGSERIAL PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auction_id  BIGINT  NOT NULL REFERENCES auction(auction_id) ON DELETE CASCADE,
    viewed_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS can_sell BOOLEAN NOT NULL DEFAULT FALSE;

-- ── Users ─────────────────────────────────────────────────────────────────────
INSERT INTO users (username, email, password, role_id, status_id, can_sell)
SELECT 'demo_seller', 'demo_seller@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 3, 1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'demo_seller@auctionhub.test');

INSERT INTO users (username, email, password, role_id, status_id, can_sell)
SELECT 'demo_seller2', 'demo_seller2@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 3, 1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'demo_seller2@auctionhub.test');

-- The viewer. Every "expected result" comment above is written from this account.
INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'demo_buyer1', 'demo_buyer1@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'demo_buyer1@auctionhub.test');

-- Peer group A: shares bids / watchlist rows with the viewer → PEER_BIDS.
INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'demo_buyer2', 'demo_buyer2@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'demo_buyer2@auctionhub.test');

INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'peer_a2', 'peer_a2@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'peer_a2@auctionhub.test');

-- Peer group B: overlaps the viewer through browse_history only → SIMILAR_TASTE.
INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'peer_b1', 'peer_b1@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'peer_b1@auctionhub.test');

INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'peer_b2', 'peer_b2@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'peer_b2@auctionhub.test');

INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'pending_user', 'pending_user@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 4
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'pending_user@auctionhub.test');

INSERT INTO users (username, email, password, role_id, status_id)
SELECT 'rejected_user', 'rejected_user@auctionhub.test',
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=', 2, 5
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'rejected_user@auctionhub.test');

-- Repairs demo sellers created by an earlier version of this file, which inserted
-- them before can_sell existed and left them unable to list anything.
UPDATE users
   SET can_sell = TRUE
 WHERE email IN ('demo_seller@auctionhub.test', 'demo_seller2@auctionhub.test')
   AND can_sell = FALSE;

-- ── Listings ─────────────────────────────────────────────────────────────────
-- auction.auction_id is GENERATED ALWAYS AS IDENTITY, so it is never named in an
-- insert. The auction row and its auction_details row are written by a single
-- statement, with the details insert reading the generated id straight out of the
-- RETURNING clause: one statement is atomic even under psql's autocommit, so an
-- interrupted run can never leave an auction row with no details row behind. The
-- guard is the [DEMO] title, which is what makes the whole section re-runnable.

-- L1 — the viewer bids here, and so does peer group A. Its Collectibles category is
-- also what qualifies L8 for the content-based stage.
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '8 days', now() + interval '3 days', 1, 'active'
    FROM users u
    WHERE u.email = 'demo_seller@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Standard Ascending Watch')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, max_price, quantity, cost_price)
SELECT auction_id,
       '[DEMO] Standard Ascending Watch',
       'Classic ascending-price demo listing. Place bids in $50 increments.',
       'Collectibles', 2, 500.00, 2000.00, 1, 350.00
FROM new_auction;

-- L2 — Dutch strategy demo, and the viewer's watchlist entry.
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '7 days', now() + interval '4 days', 2, 'active'
    FROM users u
    WHERE u.email = 'demo_seller@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Dutch Descending Laptop')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, quantity, cost_price, dutch_floor_price)
SELECT auction_id,
       '[DEMO] Dutch Descending Laptop',
       'Price drops from $1,200 toward $400. First buyer to accept wins.',
       'Electronics', 2, 1200.00, 1, 800.00, 400.00
FROM new_auction;

-- L3 — blind strategy demo, and the first PEER_BIDS candidate: group A bids on it
-- and watchlists it, the viewer has never touched it.
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '6 days', now() + interval '5 days', 3, 'active'
    FROM users u
    WHERE u.email = 'demo_seller@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Blind Sealed Headphones')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, quantity, cost_price)
SELECT auction_id,
       '[DEMO] Blind Sealed Headphones',
       'Submit one hidden bid. Amounts stay secret until the auction closes.',
       'Electronics', 1, 100.00, 1, 60.00
FROM new_auction;

-- L4 — second PEER_BIDS candidate, same shape as L3 but a different category so the
-- arm is visibly not just "more Electronics".
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '6 days', now() + interval '6 days', 1, 'active'
    FROM users u
    WHERE u.email = 'demo_seller@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Vintage Vinyl Crate')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, max_price, quantity, cost_price)
SELECT auction_id,
       '[DEMO] Vintage Vinyl Crate',
       'Forty records, mixed genres. Ascending bids, reserve at $400.',
       'Collectibles', 3, 80.00, 400.00, 1, 45.00
FROM new_auction;

-- L5 — group B item, first SIMILAR_TASTE candidate. Sports is a category the viewer
-- has no history in, so nothing but the cosine arm can surface it.
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '5 days', now() + interval '7 days', 1, 'active'
    FROM users u
    WHERE u.email = 'demo_seller2@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Trail Running Shoes')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, max_price, quantity, cost_price)
SELECT auction_id,
       '[DEMO] Trail Running Shoes',
       'Unworn, boxed, UK 9. Ascending bids from $60.',
       'Sports', 1, 60.00, 300.00, 1, 38.00
FROM new_auction;

-- L6 — group B item, second SIMILAR_TASTE candidate.
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '5 days', now() + interval '8 days', 1, 'active'
    FROM users u
    WHERE u.email = 'demo_seller2@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Carbon Road Bike')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, max_price, quantity, cost_price)
SELECT auction_id,
       '[DEMO] Carbon Road Bike',
       'Full carbon frame, serviced last month. Ascending bids from $900.',
       'Sports', 2, 900.00, 3000.00, 1, 620.00
FROM new_auction;

-- L7 — reserved for SAME_CATEGORY. Home & Garden matches the viewer's browse of L10,
-- and no peer interacts with it, so the two collaborative stages cannot claim it.
-- Deliberately left with zero bids and zero watchers.
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '4 days', now() + interval '9 days', 2, 'active'
    FROM users u
    WHERE u.email = 'demo_seller2@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Cast Iron Planter Set')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, quantity, cost_price, dutch_floor_price)
SELECT auction_id,
       '[DEMO] Cast Iron Planter Set',
       'Set of three. Price drops from $240 toward $90 until someone accepts.',
       'Home & Garden', 3, 240.00, 1, 150.00, 90.00
FROM new_auction;

-- L8 — second SAME_CATEGORY reserve, matching the viewer's Collectibles history via
-- its bid on L1. Also zero interactions, for the same reason as L7.
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '4 days', now() + interval '10 days', 1, 'active'
    FROM users u
    WHERE u.email = 'demo_seller2@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Sealed Trading Card Box')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, max_price, quantity, cost_price)
SELECT auction_id,
       '[DEMO] Sealed Trading Card Box',
       'Factory sealed booster box. Ascending bids from $150.',
       'Collectibles', 1, 150.00, 900.00, 1, 110.00
FROM new_auction;

-- L9 — ended, with bids from the viewer and from group A: the seller can declare a
-- winner and walk the order flow. status_id stays 1 so it is still declarable.
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '10 days', now() - interval '2 hours', 1, 'active'
    FROM users u
    WHERE u.email = 'demo_seller@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Ended Camera')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, max_price, quantity, cost_price)
SELECT auction_id,
       '[DEMO] Ended Camera',
       'This auction has ended. Seller can declare the winner and create an order.',
       'Electronics', 3, 200.00, 800.00, 1, 120.00
FROM new_auction;

-- L10 — ended, and the only Home & Garden listing the viewer has any history with.
-- That history is a browse row and nothing else, which is what makes L7 reachable
-- through the content-based stage: browse_history feeds my_signals there.
WITH new_auction AS (
    INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type, moderation_state)
    SELECT 1, u.id, now() - interval '12 days', now() - interval '1 day', 1, 'active'
    FROM users u
    WHERE u.email = 'demo_seller@auctionhub.test'
      AND NOT EXISTS (SELECT 1 FROM auction_details WHERE title = '[DEMO] Ended Garden Bench')
    RETURNING auction_id
)
INSERT INTO auction_details (id, title, description, category, item_condition_id,
                            starting_price, max_price, quantity, cost_price)
SELECT auction_id,
       '[DEMO] Ended Garden Bench',
       'Ended listing kept for browse history. Teak, two seats, weathered.',
       'Home & Garden', 3, 120.00, 600.00, 1, 70.00
FROM new_auction;

-- ── Bids ─────────────────────────────────────────────────────────────────────
-- One bid per buyer per auction, so the (auction, user) guard makes each insert
-- re-runnable. Within an auction a later bid_time always carries a strictly higher
-- amount, and every amount sits between starting_price and max_price. No seller
-- bids on their own listing: demo_seller lists L1–L4, L9, L10 and demo_seller2
-- lists L5–L8, and neither account bids at all.
--
-- bid_time is TIMESTAMP without time zone. It is written here exactly as BidDAO
-- writes it (a timestamptz assigned to the naive column), so the values line up
-- with production rows and with the trending window's AT TIME ZONE 'UTC' read on a
-- UTC database.

-- L1: viewer opens, group A raises. Both amounts under the $2,000 reserve.
INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, u.id, 550.00, now() - interval '6 days'
FROM auction_details d, users u
WHERE d.title = '[DEMO] Standard Ascending Watch'
  AND u.email = 'demo_buyer1@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = u.id);

INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, u.id, 620.00, now() - interval '5 days'
FROM auction_details d, users u
WHERE d.title = '[DEMO] Standard Ascending Watch'
  AND u.email = 'demo_buyer2@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = u.id);

-- L3: one sealed bid from group A. Blind auctions hide the amount until close.
INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, u.id, 145.00, now() - interval '4 days'
FROM auction_details d, users u
WHERE d.title = '[DEMO] Blind Sealed Headphones'
  AND u.email = 'demo_buyer2@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = u.id);

-- L4: the other half of group A's activity. Watched by demo_buyer2, bid by peer_a2.
INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, u.id, 95.00, now() - interval '4 days'
FROM auction_details d, users u
WHERE d.title = '[DEMO] Vintage Vinyl Crate'
  AND u.email = 'peer_a2@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = u.id);

-- L5 / L6: group B's own items. These bids are what give the two listings a weight
-- of 3 in the group B vectors, which is what ranks them for SIMILAR_TASTE.
INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, u.id, 75.00, now() - interval '3 days'
FROM auction_details d, users u
WHERE d.title = '[DEMO] Trail Running Shoes'
  AND u.email = 'peer_b1@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = u.id);

INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, u.id, 980.00, now() - interval '2 days'
FROM auction_details d, users u
WHERE d.title = '[DEMO] Carbon Road Bike'
  AND u.email = 'peer_b2@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = u.id);

-- L9: ended auction with a losing bid from the viewer and the leading bid from
-- group A, so declaring a winner produces a real order.
INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, u.id, 240.00, now() - interval '8 days'
FROM auction_details d, users u
WHERE d.title = '[DEMO] Ended Camera'
  AND u.email = 'demo_buyer1@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = u.id);

INSERT INTO bids (auction_id, user_id, bid_amount, bid_time)
SELECT d.id, u.id, 305.00, now() - interval '6 days'
FROM auction_details d, users u
WHERE d.title = '[DEMO] Ended Camera'
  AND u.email = 'demo_buyer2@auctionhub.test'
  AND NOT EXISTS (SELECT 1 FROM bids x WHERE x.auction_id = d.id AND x.user_id = u.id);

-- ── Watchlist ────────────────────────────────────────────────────────────────
-- The viewer's own entry (L2) is the row group A's peer_a2 overlaps on. Group B is
-- kept out of every auction the viewer bid on or watchlisted.
INSERT INTO watchlist (user_id, auction_id, added_at)
SELECT u.id, d.id, now() - interval '6 days'
FROM users u, auction_details d
WHERE u.email = 'demo_buyer1@auctionhub.test'
  AND d.title = '[DEMO] Dutch Descending Laptop'
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.user_id = u.id AND w.auction_id = d.id);

-- Group A, overlapping side: peer_a2 watches the laptop the viewer watches.
INSERT INTO watchlist (user_id, auction_id, added_at)
SELECT u.id, d.id, now() - interval '5 days'
FROM users u, auction_details d
WHERE u.email = 'peer_a2@auctionhub.test'
  AND d.title = '[DEMO] Dutch Descending Laptop'
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.user_id = u.id AND w.auction_id = d.id);

-- Group A, candidate side: the items the viewer should be shown as PEER_BIDS.
INSERT INTO watchlist (user_id, auction_id, added_at)
SELECT u.id, d.id, now() - interval '4 days'
FROM users u, auction_details d
WHERE u.email = 'demo_buyer2@auctionhub.test'
  AND d.title = '[DEMO] Vintage Vinyl Crate'
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.user_id = u.id AND w.auction_id = d.id);

INSERT INTO watchlist (user_id, auction_id, added_at)
SELECT u.id, d.id, now() - interval '4 days'
FROM users u, auction_details d
WHERE u.email = 'peer_a2@auctionhub.test'
  AND d.title = '[DEMO] Blind Sealed Headphones'
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.user_id = u.id AND w.auction_id = d.id);

-- Group B: watchlist rows on their own items only. Crossing into L1, L2 or L9 here
-- would put group B into the PEER_BIDS peer set and merge the two arms.
INSERT INTO watchlist (user_id, auction_id, added_at)
SELECT u.id, d.id, now() - interval '2 days'
FROM users u, auction_details d
WHERE u.email = 'peer_b1@auctionhub.test'
  AND d.title = '[DEMO] Carbon Road Bike'
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.user_id = u.id AND w.auction_id = d.id);

INSERT INTO watchlist (user_id, auction_id, added_at)
SELECT u.id, d.id, now() - interval '3 days'
FROM users u, auction_details d
WHERE u.email = 'peer_b2@auctionhub.test'
  AND d.title = '[DEMO] Trail Running Shoes'
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.user_id = u.id AND w.auction_id = d.id);

-- ── Browse history ───────────────────────────────────────────────────────────
-- browse_history has no unique constraint, so each insert carries its own
-- (user, auction) NOT EXISTS guard rather than relying on the table.
--
-- This is the only table group B shares with the viewer. collaborativeFiltering()
-- does not read it, so these rows cannot pull group B into the PEER_BIDS peer set;
-- loadInteractionVectors() does read it at weight 1, so they are enough to carry
-- group B over the 0.1 cosine threshold. The viewer's own row on L10 is also what
-- puts Home & Garden into its category history and so makes L7 a SAME_CATEGORY hit.

-- Viewer.
INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '6 days'
FROM users u, auction_details d
WHERE u.email = 'demo_buyer1@auctionhub.test'
  AND d.title = '[DEMO] Standard Ascending Watch'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);

INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '6 days'
FROM users u, auction_details d
WHERE u.email = 'demo_buyer1@auctionhub.test'
  AND d.title = '[DEMO] Dutch Descending Laptop'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);

INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '11 days'
FROM users u, auction_details d
WHERE u.email = 'demo_buyer1@auctionhub.test'
  AND d.title = '[DEMO] Ended Garden Bench'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);

-- peer_b1: viewed all three of the viewer's browsed listings (cosine 0.313).
INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '5 days'
FROM users u, auction_details d
WHERE u.email = 'peer_b1@auctionhub.test'
  AND d.title = '[DEMO] Standard Ascending Watch'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);

INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '5 days'
FROM users u, auction_details d
WHERE u.email = 'peer_b1@auctionhub.test'
  AND d.title = '[DEMO] Dutch Descending Laptop'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);

INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '10 days'
FROM users u, auction_details d
WHERE u.email = 'peer_b1@auctionhub.test'
  AND d.title = '[DEMO] Ended Garden Bench'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);

-- peer_b2: viewed two of the three (cosine 0.162). A weaker but still qualifying
-- peer, so the threshold is visibly doing something rather than passing everyone.
INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '4 days'
FROM users u, auction_details d
WHERE u.email = 'peer_b2@auctionhub.test'
  AND d.title = '[DEMO] Dutch Descending Laptop'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);

INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '9 days'
FROM users u, auction_details d
WHERE u.email = 'peer_b2@auctionhub.test'
  AND d.title = '[DEMO] Ended Garden Bench'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);

-- Group B also viewed the items they are bidding on. Harmless for the vectors
-- (interaction weights are merged with max, and a bid already scores 3) but it is
-- what real traffic looks like, and it keeps the seller's view-count panels honest.
INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '3 days'
FROM users u, auction_details d
WHERE u.email = 'peer_b1@auctionhub.test'
  AND d.title = '[DEMO] Trail Running Shoes'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);

INSERT INTO browse_history (user_id, auction_id, viewed_at)
SELECT u.id, d.id, now() - interval '2 days'
FROM users u, auction_details d
WHERE u.email = 'peer_b2@auctionhub.test'
  AND d.title = '[DEMO] Carbon Road Bike'
  AND NOT EXISTS (SELECT 1 FROM browse_history h WHERE h.user_id = u.id AND h.auction_id = d.id);
