-- Demo data for the System Administrator minimum requirements (Stakeholder #3).
--
-- Why this file exists rather than applying demo_seed.sql: demo_seed.sql has never been
-- applied to the hosted database, and it inserts nine users, ten listings, bids, browse
-- history, watchlist rows and orders. Landing all of that on a live dataset days before
-- submission would move the revenue totals, the top-seller tables and the recommendation
-- metrics that the rest of the demo is rehearsed against. This file seeds only what the
-- admin requirements cannot be shown without, and nothing it creates is referenced by any
-- pre-existing row.
--
-- Everything here is additive and idempotent. Nothing existing is updated or deleted.
--
-- Recognisable by:
--   users            username LIKE 'demoadmin\_%',  email LIKE '%@admin-demo.test'
--   auctions         auction_details.title LIKE '[DEMO-ADMIN]%'
--   reports/reviews  text containing '[DEMO-ADMIN]'
--
-- To remove everything this file created, see the cleanup block at the foot of the file.
--
-- Password for every account below is DemoPass1! (same salted hash demo_seed.sql uses).

-- ── Accounts ─────────────────────────────────────────────────────────────────
-- No admin account is created and no existing account is elevated: the pending row is a
-- buyer, which is exactly what the approve/reject queue moderates.
INSERT INTO users (username, email, password, role_id, status_id, can_sell)
SELECT v.username, v.email,
       '1$ptSLidr5VXyD1edF4w42Mg==$qnvCvKzdkRDi4DmXaCiTw8TQ3OK/bX/lCiO2PXfhn3g=',
       v.role_id, v.status_id, v.can_sell
FROM (VALUES
    -- Requirement (a): something in the approval queue to approve or reject.
    ('demoadmin_pending', 'demoadmin_pending@admin-demo.test', 2, 4, FALSE),
    -- Owns the demo service listings, so no real seller's data is touched.
    ('demoadmin_seller',  'demoadmin_seller@admin-demo.test',  3, 1, TRUE),
    -- Three buyers, because a star-percentage breakdown needs more than one review to
    -- show anything other than 100% of a single star.
    ('demoadmin_buyer1',  'demoadmin_buyer1@admin-demo.test',  2, 1, FALSE),
    ('demoadmin_buyer2',  'demoadmin_buyer2@admin-demo.test',  2, 1, FALSE),
    ('demoadmin_buyer3',  'demoadmin_buyer3@admin-demo.test',  2, 1, FALSE)
) AS v(username, email, role_id, status_id, can_sell)
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.email = v.email);

-- ── Listings ─────────────────────────────────────────────────────────────────
-- Two services and one product, so "manage database of products, services, ..." has real
-- rows of both kinds behind the admin listing filter, and the analytics report can label a
-- popular listing as a service.
DO $$
DECLARE
    seller_id BIGINT;
    new_auction_id BIGINT;
    spec RECORD;
BEGIN
    SELECT id INTO seller_id FROM users WHERE email = 'demoadmin_seller@admin-demo.test';
    IF seller_id IS NULL THEN RETURN; END IF;

    FOR spec IN
        SELECT * FROM (VALUES
            ('[DEMO-ADMIN] Wedding Photography — Full Day Coverage',
             'Ten hours of coverage, two photographers, 400+ edited images delivered in four weeks. '
             || 'Travel within the city included. A service listing, not a physical item.',
             'Other', 'SERVICE', 1200.00, 14, 'active'),
            ('[DEMO-ADMIN] Guitar Lessons — 10 Session Package',
             'Ten one-hour private lessons, in person or online, beginner to intermediate. '
             || 'Scheduled at the winner''s convenience within six months. A service listing.',
             'Other', 'SERVICE', 350.00, 21, 'active'),
            ('[DEMO-ADMIN] Studio Lighting Kit — 3 Head Softbox Set',
             'Three-head continuous lighting kit with softboxes, stands and carry bag. '
             || 'Flagged on purpose so listing moderation has an open case to work.',
             'Electronics', 'PRODUCT', 240.00, 10, 'flagged')
        ) AS t(title, description, category, kind, price, days, moderation)
    LOOP
        IF EXISTS (SELECT 1 FROM auction_details WHERE title = spec.title) THEN
            CONTINUE;
        END IF;

        INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type,
                             moderation_state)
        VALUES (1, seller_id, now() - interval '40 days',
                now() + (spec.days || ' days')::interval, 1, spec.moderation)
        RETURNING auction_id INTO new_auction_id;

        INSERT INTO auction_details (id, title, description, category, item_condition_id,
                                     starting_price, quantity, listing_kind)
        VALUES (new_auction_id, spec.title, spec.description, spec.category, 1,
                spec.price, 1, spec.kind);
    END LOOP;
END $$;

-- ── Bids spread across calendar periods ──────────────────────────────────────
-- The product-by-period cross-tab in the seller analytics report groups by calendar day,
-- week, month and quarter. Bids are dated so all four granularities have more than one
-- bucket and the "most popular" answer actually differs between them.
DO $$
DECLARE
    photography BIGINT;
    lessons     BIGINT;
    buyer1      BIGINT;
    buyer2      BIGINT;
    buyer3      BIGINT;
BEGIN
    SELECT id INTO photography FROM auction_details
        WHERE title = '[DEMO-ADMIN] Wedding Photography — Full Day Coverage';
    SELECT id INTO lessons FROM auction_details
        WHERE title = '[DEMO-ADMIN] Guitar Lessons — 10 Session Package';
    SELECT id INTO buyer1 FROM users WHERE email = 'demoadmin_buyer1@admin-demo.test';
    SELECT id INTO buyer2 FROM users WHERE email = 'demoadmin_buyer2@admin-demo.test';
    SELECT id INTO buyer3 FROM users WHERE email = 'demoadmin_buyer3@admin-demo.test';
    IF photography IS NULL OR lessons IS NULL OR buyer1 IS NULL THEN RETURN; END IF;

    -- Guard on the pair (auction, count) so a second run adds nothing.
    IF (SELECT COUNT(*) FROM bids WHERE auction_id IN (photography, lessons)) > 0 THEN
        RETURN;
    END IF;

    INSERT INTO bids (auction_id, user_id, bid_amount, bid_time) VALUES
        -- Photography leads yesterday and this month.
        (photography, buyer1, 1250.00, now() - interval '1 day'),
        (photography, buyer2, 1300.00, now() - interval '1 day' + interval '2 hours'),
        (photography, buyer3, 1400.00, now() - interval '1 day' + interval '5 hours'),
        (photography, buyer1, 1500.00, now() - interval '9 days'),
        (photography, buyer2, 1550.00, now() - interval '40 days'),
        -- Lessons leads two days ago and last month, so the winner changes per period.
        (lessons, buyer1, 360.00, now() - interval '2 days'),
        (lessons, buyer2, 380.00, now() - interval '2 days' + interval '1 hour'),
        (lessons, buyer3, 400.00, now() - interval '2 days' + interval '3 hours'),
        (lessons, buyer1, 420.00, now() - interval '2 days' + interval '6 hours'),
        (lessons, buyer2, 440.00, now() - interval '9 days' + interval '2 hours'),
        (lessons, buyer3, 460.00, now() - interval '9 days' + interval '4 hours'),
        (lessons, buyer1, 480.00, now() - interval '40 days' + interval '1 hour'),
        (lessons, buyer2, 500.00, now() - interval '40 days' + interval '3 hours'),
        (lessons, buyer3, 520.00, now() - interval '100 days');
END $$;

-- ── Reviews with a mixed star spread ─────────────────────────────────────────
-- "%-tage of star reviews for each pdt/service" is meaningless when every listing has one
-- review: it reads 100% of a single star. These give the breakdown a real distribution.
DO $$
DECLARE
    photography BIGINT;
    lessons     BIGINT;
    seller      BIGINT;
    buyer1      BIGINT;
    buyer2      BIGINT;
    buyer3      BIGINT;
BEGIN
    SELECT id INTO photography FROM auction_details
        WHERE title = '[DEMO-ADMIN] Wedding Photography — Full Day Coverage';
    SELECT id INTO lessons FROM auction_details
        WHERE title = '[DEMO-ADMIN] Guitar Lessons — 10 Session Package';
    SELECT id INTO seller FROM users WHERE email = 'demoadmin_seller@admin-demo.test';
    SELECT id INTO buyer1 FROM users WHERE email = 'demoadmin_buyer1@admin-demo.test';
    SELECT id INTO buyer2 FROM users WHERE email = 'demoadmin_buyer2@admin-demo.test';
    SELECT id INTO buyer3 FROM users WHERE email = 'demoadmin_buyer3@admin-demo.test';
    IF photography IS NULL OR lessons IS NULL OR seller IS NULL THEN RETURN; END IF;

    -- Photography: 2 x 5-star, 1 x 4-star  →  66.7% / 33.3% / 0 / 0 / 0
    INSERT INTO user_reviews (reviewer_user_id, reviewee_user_id, auction_id, rating, comment)
    SELECT v.reviewer, seller, photography, v.rating, v.comment
    FROM (VALUES
        (buyer1, 5, '[DEMO-ADMIN] Flawless on the day, delivered early.'),
        (buyer2, 5, '[DEMO-ADMIN] Worth every cent, would book again.'),
        (buyer3, 4, '[DEMO-ADMIN] Great photos, edits took a little longer than quoted.')
    ) AS v(reviewer, rating, comment)
    WHERE NOT EXISTS (
        SELECT 1 FROM user_reviews r
        WHERE r.auction_id = photography AND r.reviewer_user_id = v.reviewer);

    -- Lessons: 1 x 5, 1 x 3, 1 x 2  →  33.3% / 0 / 33.3% / 33.3% / 0
    INSERT INTO user_reviews (reviewer_user_id, reviewee_user_id, auction_id, rating, comment)
    SELECT v.reviewer, seller, lessons, v.rating, v.comment
    FROM (VALUES
        (buyer1, 5, '[DEMO-ADMIN] Patient teacher, real progress in ten weeks.'),
        (buyer2, 3, '[DEMO-ADMIN] Fine lessons, but rescheduling was hard to arrange.'),
        (buyer3, 2, '[DEMO-ADMIN] Two sessions were cancelled at short notice.')
    ) AS v(reviewer, rating, comment)
    WHERE NOT EXISTS (
        SELECT 1 FROM user_reviews r
        WHERE r.auction_id = lessons AND r.reviewer_user_id = v.reviewer);
END $$;

-- ── Sale on a service, dated for the calendar cross-tab ──────────────────────
-- Gives the report a service with realised revenue, and puts a row in the "top sale" half
-- of the popularity cross-tab. Only the photography listing sells; orders_auction_unique
-- allows one order per auction.
DO $$
DECLARE
    photography BIGINT;
    seller      BIGINT;
    buyer3      BIGINT;
BEGIN
    SELECT id INTO photography FROM auction_details
        WHERE title = '[DEMO-ADMIN] Wedding Photography — Full Day Coverage';
    SELECT id INTO seller FROM users WHERE email = 'demoadmin_seller@admin-demo.test';
    SELECT id INTO buyer3 FROM users WHERE email = 'demoadmin_buyer3@admin-demo.test';
    IF photography IS NULL OR seller IS NULL OR buyer3 IS NULL THEN RETURN; END IF;

    INSERT INTO orders (auction_id, buyer_id, seller_id, amount, status, created_at, paid_at,
                        completed_at)
    SELECT photography, buyer3, seller, 1400.00, 'COMPLETED',
           now() - interval '1 day', now() - interval '1 day' + interval '1 hour',
           now() - interval '20 hours'
    WHERE NOT EXISTS (SELECT 1 FROM orders WHERE auction_id = photography);

    -- Close the listing out to match the order. Without this the report shows "Items sold: 0"
    -- next to a completed order, because the sold count reads auction_details.winner_id while
    -- earnings read the orders table.
    UPDATE auction_details
       SET winner_id = buyer3, winning_bid = 1400
     WHERE id = photography AND winner_id IS NULL;

    -- Matching commission row, so the 6% platform fee in the earnings block is not $0.
    INSERT INTO platform_revenue (revenue_type, order_id, auction_id, seller_id, amount, rate_pct)
    SELECT 'COMMISSION', o.id, photography, seller, 84.00, 6.00
    FROM orders o
    WHERE o.auction_id = photography
      AND NOT EXISTS (SELECT 1 FROM platform_revenue pr
                      WHERE pr.order_id = o.id AND pr.revenue_type = 'COMMISSION');
END $$;

-- ── Open moderation cases ────────────────────────────────────────────────────
-- Requirement (c) was graded MET in code with nothing to demonstrate against: zero rows in
-- account_reports, and the one seller_report already resolved with the reply 'Test'.
DO $$
DECLARE
    lessons BIGINT;
    seller  BIGINT;
    buyer1  BIGINT;
    buyer2  BIGINT;
BEGIN
    SELECT id INTO lessons FROM auction_details
        WHERE title = '[DEMO-ADMIN] Guitar Lessons — 10 Session Package';
    SELECT id INTO seller FROM users WHERE email = 'demoadmin_seller@admin-demo.test';
    SELECT id INTO buyer1 FROM users WHERE email = 'demoadmin_buyer1@admin-demo.test';
    SELECT id INTO buyer2 FROM users WHERE email = 'demoadmin_buyer2@admin-demo.test';
    IF lessons IS NULL OR seller IS NULL OR buyer1 IS NULL THEN RETURN; END IF;

    -- An unresolved account report. Reported party is a demo account, never a real user.
    INSERT INTO account_reports (reporter_id, target_id, reason, comment, resolved)
    SELECT buyer1, seller,
           '[DEMO-ADMIN] Seller unresponsive after payment',
           '[DEMO-ADMIN] Paid on the 3rd, no reply to three messages about scheduling.',
           FALSE
    WHERE NOT EXISTS (
        SELECT 1 FROM account_reports WHERE reporter_id = buyer1 AND target_id = seller);

    -- An unresolved listing report, so the admin reply box has a live case to answer.
    INSERT INTO seller_reports (reporter_user_id, reported_user_id, auction_id, description,
                                resolved)
    SELECT buyer2, seller, lessons,
           '[DEMO-ADMIN] The listing says lessons are in person, the seller now says '
           || 'online only. Description does not match what is being offered.',
           FALSE
    WHERE NOT EXISTS (
        SELECT 1 FROM seller_reports
        WHERE reporter_user_id = buyer2 AND auction_id = lessons);

    -- An unanswered buyer question, so the seller Q&A queue is not empty either.
    INSERT INTO auction_questions (auction_id, asker_user_id, question_text)
    SELECT lessons, buyer1,
           '[DEMO-ADMIN] Can the ten sessions be split between two students?'
    WHERE NOT EXISTS (
        SELECT 1 FROM auction_questions
        WHERE auction_id = lessons AND asker_user_id = buyer1);
END $$;

-- ── Cleanup (kept commented; run by hand when the demo data is no longer wanted) ──
-- Order matters: children before parents.
--
-- DELETE FROM auction_questions WHERE question_text LIKE '[DEMO-ADMIN]%';
-- DELETE FROM seller_reports    WHERE description   LIKE '[DEMO-ADMIN]%';
-- DELETE FROM account_reports   WHERE reason        LIKE '[DEMO-ADMIN]%';
-- DELETE FROM user_reviews      WHERE comment       LIKE '[DEMO-ADMIN]%';
-- DELETE FROM platform_revenue WHERE auction_id IN
--     (SELECT id FROM auction_details WHERE title LIKE '[DEMO-ADMIN]%');
-- DELETE FROM orders WHERE auction_id IN
--     (SELECT id FROM auction_details WHERE title LIKE '[DEMO-ADMIN]%');
-- DELETE FROM bids   WHERE auction_id IN
--     (SELECT id FROM auction_details WHERE title LIKE '[DEMO-ADMIN]%');
-- DELETE FROM admin_audit_log WHERE entity_type = 'LISTING' AND entity_id IN
--     (SELECT id FROM auction_details WHERE title LIKE '[DEMO-ADMIN]%');
-- CREATE TEMP TABLE demo_admin_ids AS
--     SELECT id FROM auction_details WHERE title LIKE '[DEMO-ADMIN]%';
-- DELETE FROM auction_details WHERE id IN (SELECT id FROM demo_admin_ids);
-- DELETE FROM auction        WHERE auction_id IN (SELECT id FROM demo_admin_ids);
-- DELETE FROM users WHERE email LIKE '%@admin-demo.test';
