-- Extra landing page copy introduced by the "why choose us" refresh: the head-to-head
-- comparison strip, the CTA band bullet points, and the hero/CTA button labels that were
-- still hardcoded in Home.jsx. Additive and safe to re-run.
--
-- Same column shape as migration_landing_content.sql, so AdminLandingContent renders
-- these fields automatically without any React change.

INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('hero.ctaPrimary',   'Hero', 'Primary button label',   FALSE, 80,
        'Explore auctions'),
    ('hero.ctaSecondary', 'Hero', 'Secondary button label (guests only)', FALSE, 85,
        'Start selling'),

    ('why.contrastLabel', 'Why AuctionHub', 'Card contrast line prefix', FALSE, 199,
        'Elsewhere:'),
    ('why.ctaPoint1',     'Why AuctionHub', 'CTA band bullet 1', FALSE, 205,
        'Live ascending bids'),
    ('why.ctaPoint2',     'Why AuctionHub', 'CTA band bullet 2', FALSE, 207,
        'Dutch & sealed formats'),
    ('why.ctaPoint3',     'Why AuctionHub', 'CTA band bullet 3', FALSE, 209,
        'Auto-bid proxy'),
    ('why.ctaPoint4',     'Why AuctionHub', 'CTA band bullet 4', FALSE, 211,
        'Seller ratings'),
    ('why.ctaPrimary',    'Why AuctionHub', 'CTA band primary button',   FALSE, 215,
        'Explore live auctions'),
    ('why.ctaSecondary',  'Why AuctionHub', 'CTA band secondary button (guests only)', FALSE, 220,
        'Create free account'),

    ('why.compare.eyebrow', 'Head-to-head comparison', 'Eyebrow', FALSE, 250,
        'Head to head'),
    ('why.compare.heading', 'Head-to-head comparison', 'Heading', TRUE, 252,
        'The same item, two very different outcomes.'),
    ('why.compare.ours',    'Head-to-head comparison', 'Our column heading', FALSE, 254,
        'AuctionHub'),
    ('why.compare.theirs',  'Head-to-head comparison', 'Their column heading', FALSE, 256,
        'Fixed-price marketplaces'),

    ('why.compare.row1.label',  'Head-to-head comparison', 'Row 1 — criterion', FALSE, 260,
        'Who sets the price'),
    ('why.compare.row1.ours',   'Head-to-head comparison', 'Row 1 — AuctionHub', TRUE, 261,
        'Buyers compete in the open, so the market decides what it is worth.'),
    ('why.compare.row1.theirs', 'Head-to-head comparison', 'Row 1 — elsewhere', TRUE, 262,
        'One seller picks a number and waits to be haggled down.'),

    ('why.compare.row2.label',  'Head-to-head comparison', 'Row 2 — criterion', FALSE, 265,
        'Momentum'),
    ('why.compare.row2.ours',   'Head-to-head comparison', 'Row 2 — AuctionHub', TRUE, 266,
        'A live countdown turns idle interest into a decision.'),
    ('why.compare.row2.theirs', 'Head-to-head comparison', 'Row 2 — elsewhere', TRUE, 267,
        'Listings drift for weeks with no deadline to act on.'),

    ('why.compare.row3.label',  'Head-to-head comparison', 'Row 3 — criterion', FALSE, 270,
        'Your privacy'),
    ('why.compare.row3.ours',   'Head-to-head comparison', 'Row 3 — AuctionHub', TRUE, 271,
        'Bidder names are masked and personal data is encrypted.'),
    ('why.compare.row3.theirs', 'Head-to-head comparison', 'Row 3 — elsewhere', TRUE, 272,
        'Full-name direct messages with strangers in your inbox.'),

    ('why.compare.row4.label',  'Head-to-head comparison', 'Row 4 — criterion', FALSE, 275,
        'Selling formats'),
    ('why.compare.row4.ours',   'Head-to-head comparison', 'Row 4 — AuctionHub', TRUE, 276,
        'Ascending, Dutch, sealed-bid — plus Buy It Now for instant sales.'),
    ('why.compare.row4.theirs', 'Head-to-head comparison', 'Row 4 — elsewhere', TRUE, 277,
        'One listing style stretched across every category.'),

    ('why.compare.row5.label',  'Head-to-head comparison', 'Row 5 — criterion', FALSE, 280,
        'Cost to take part'),
    ('why.compare.row5.ours',   'Head-to-head comparison', 'Row 5 — AuctionHub', TRUE, 281,
        'Free to browse, watch and bid — sellers pay only on a sale.'),
    ('why.compare.row5.theirs', 'Head-to-head comparison', 'Row 5 — elsewhere', TRUE, 282,
        'Paid bumps and boosts just to stay visible in the feed.'),

    -- The recommendations strip swaps heading depending on whether the visitor is signed in.
    ('section.recommended.title',    'Section headings', 'Recommendations (signed in) — title',    FALSE, 326,
        'Recommended for You'),
    ('section.recommended.subtitle', 'Section headings', 'Recommendations (signed in) — subtitle', TRUE,  327,
        'Based on items you and similar buyers have bid on or watched. Open “why this?” on any card to see the reasoning.'),
    ('section.popular.title',        'Section headings', 'Recommendations (guest) — title',        FALSE, 328,
        'Popular Right Now'),
    ('section.popular.subtitle',     'Section headings', 'Recommendations (guest) — subtitle',     TRUE,  329,
        'Trending auctions across the marketplace. Sign in for personalised picks.')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
