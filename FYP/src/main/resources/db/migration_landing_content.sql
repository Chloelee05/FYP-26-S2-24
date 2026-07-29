-- Admin-editable landing page copy. Apply once against auction_db. Safe to re-run
-- (IF NOT EXISTS / ON CONFLICT DO NOTHING).
--
-- Only marketing copy lives here. The landing page *data* (popular categories,
-- platform metrics, featured listings, testimonials) is already computed from the
-- database by /api/stats and must never be duplicated as content rows.
--
-- Each row carries its own presentation metadata (group, label, multiline, order) so
-- the admin form renders whatever the database says instead of a field list hardcoded
-- in React, and its seeded default so "reset to default" needs no code change either.

CREATE TABLE IF NOT EXISTS landing_content (
    content_key   VARCHAR(80)  PRIMARY KEY,
    content_group VARCHAR(40)  NOT NULL,
    label         VARCHAR(120) NOT NULL,
    content_value TEXT         NOT NULL,
    default_value TEXT         NOT NULL,
    multiline     BOOLEAN      NOT NULL DEFAULT FALSE,
    display_order INT          NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    INT,
    CONSTRAINT landing_content_updated_by_fk FOREIGN KEY (updated_by)
        REFERENCES users (id) ON DELETE SET NULL
);

-- Seeded with the copy currently hardcoded in Home.jsx, so nothing on the landing page
-- changes appearance until an admin edits it. content_value starts equal to default_value.
INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('hero.eyebrow',        'Hero', 'Badge text',                FALSE, 10,
        'Live auctions running right now'),
    ('hero.headline',       'Hero', 'Headline',                  FALSE, 20,
        'Bid smart, buy'),
    ('hero.headlineAccent', 'Hero', 'Headline highlighted words', FALSE, 30,
        'right.'),
    ('hero.subheading',     'Hero', 'Sub-heading paragraph',     TRUE,  40,
        'List your items, bid on your favourites, and find the perfect deal — with live pricing and no surprises.'),
    ('hero.trust1.title',   'Hero', 'Trust point 1 — title',     FALSE, 50,
        'Verified sellers'),
    ('hero.trust1.text',    'Hero', 'Trust point 1 — text',      FALSE, 55,
        'Ratings and reviews on every listing.'),
    ('hero.trust2.title',   'Hero', 'Trust point 2 — title',     FALSE, 60,
        'Three auction types'),
    ('hero.trust2.text',    'Hero', 'Trust point 2 — text',      FALSE, 65,
        'Ascending, Dutch and sealed-bid listings.'),
    ('hero.trust3.title',   'Hero', 'Trust point 3 — title',     FALSE, 70,
        'Smart picks'),
    ('hero.trust3.text',    'Hero', 'Trust point 3 — text',      FALSE, 75,
        'Recommendations tuned to what you bid on.'),

    ('why.eyebrow',         'Why AuctionHub', 'Eyebrow',          FALSE, 100,
        'Why bid here'),
    ('why.heading',         'Why AuctionHub', 'Heading',          FALSE, 110,
        'Not another marketplace.'),
    ('why.headingAccent',   'Why AuctionHub', 'Heading highlighted words', FALSE, 120,
        'A real auction floor.'),
    ('why.intro',           'Why AuctionHub', 'Intro paragraph',  TRUE,  130,
        'Carousell, Facebook Marketplace and big listing sites are great for fixed prices. AuctionHub is for when you want competition, fair discovery and a clock that actually closes the deal.'),
    ('why.card1.title',     'Why AuctionHub', 'Card 1 — title',   FALSE, 140,
        'True price discovery'),
    ('why.card1.body',      'Why AuctionHub', 'Card 1 — body',    TRUE,  145,
        'Bids compete in the open — you don’t guess a “Buy Now” number or settle for the first offer.'),
    ('why.card1.contrast',  'Why AuctionHub', 'Card 1 — Elsewhere', TRUE, 150,
        'Fixed-price apps lock you into one sticker price.'),
    ('why.card2.title',     'Why AuctionHub', 'Card 2 — title',   FALSE, 155,
        'Timed urgency that works'),
    ('why.card2.body',      'Why AuctionHub', 'Card 2 — body',    TRUE,  160,
        'Live countdowns, ending-soon sorts and auto-bid mean serious buyers show up before the clock hits zero.'),
    ('why.card2.contrast',  'Why AuctionHub', 'Card 2 — Elsewhere', TRUE, 165,
        'Listings on classifieds can sit for weeks with no momentum.'),
    ('why.card3.title',     'Why AuctionHub', 'Card 3 — title',   FALSE, 170,
        'Built for trust'),
    ('why.card3.body',      'Why AuctionHub', 'Card 3 — body',    TRUE,  175,
        'Masked bidder names, encrypted personal data, seller ratings and report tools — PDPA-aware by design.'),
    ('why.card3.contrast',  'Why AuctionHub', 'Card 3 — Elsewhere', TRUE, 180,
        'Many peer-to-peer chats leave you negotiating in DMs with little protection.'),
    ('why.card4.title',     'Why AuctionHub', 'Card 4 — title',   FALSE, 185,
        'Formats for every item'),
    ('why.card4.body',      'Why AuctionHub', 'Card 4 — body',    TRUE,  190,
        'Ascending, Dutch and sealed-bid auctions — plus Buy It Now when you want an instant sale.'),
    ('why.card4.contrast',  'Why AuctionHub', 'Card 4 — Elsewhere', TRUE, 195,
        'One listing style fits every category elsewhere.'),
    ('why.ctaHeadline',     'Why AuctionHub', 'CTA band headline', TRUE, 200,
        'Free to browse. Free to bid. Sellers only pay when something sells.'),

    ('section.categories.title',    'Section headings', 'Popular Categories — title',    FALSE, 300,
        'Popular Categories'),
    ('section.categories.subtitle', 'Section headings', 'Popular Categories — subtitle', TRUE,  305,
        'Ranked by live listing count across the marketplace.'),
    ('section.featured.title',      'Section headings', 'Featured Listings — title',     FALSE, 310,
        'Featured Listings'),
    ('section.featured.subtitle',   'Section headings', 'Featured Listings — subtitle',  TRUE,  315,
        'Promoted auctions from our sellers.'),
    ('section.trending.title',      'Section headings', 'Trending Auctions — title',     FALSE, 320,
        'Trending Auctions'),
    ('section.trending.subtitle',   'Section headings', 'Trending Auctions — subtitle',  TRUE,  325,
        'The listings collecting the most bids today.'),
    ('section.fees.title',          'Section headings', 'Costs — title',                 FALSE, 330,
        'Simple, Transparent Costs'),
    ('section.fees.subtitle',       'Section headings', 'Costs — subtitle',              TRUE,  335,
        'No surprises — this is everything AuctionHub charges.'),
    ('section.testimonials.title',    'Section headings', 'Testimonials — title',        FALSE, 340,
        'What Buyers Say'),
    ('section.testimonials.subtitle', 'Section headings', 'Testimonials — subtitle',     TRUE,  345,
        'Real reviews left by buyers after completed orders.'),

    ('guest.heading',       'Guest CTA', 'Heading',               FALSE, 400,
        'Ready to place your first bid?'),
    -- {users} is replaced with the live registered-user count from /api/stats.
    ('guest.subtext',       'Guest CTA', 'Paragraph ({users} = live user count)', TRUE, 410,
        'Join {users} registered users — create a free account to bid, watch and sell.')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
