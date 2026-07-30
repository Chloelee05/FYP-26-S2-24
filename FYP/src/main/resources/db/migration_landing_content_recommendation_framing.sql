-- One extra landing page copy row for the recommendations strip. The list is only
-- labelled "Recommended for You" when the response actually came from a personalised
-- stage, so a signed-in buyer with no bids, watchlist or browse history now falls back
-- to the popular framing. The guest subtitle ends with "Sign in for personalised picks",
-- which is untrue for someone already signed in, hence a separate subtitle for them.
--
-- Same column shape as migration_landing_content.sql, so AdminLandingContent renders
-- this field automatically without any React change. Additive and safe to re-run.

INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('section.popular.subtitle.member', 'Section headings',
        'Recommendations (signed in, no history yet) — subtitle', TRUE, 330,
        'Trending auctions across the marketplace. Bid on or watch a few listings and this strip becomes your personalised picks.')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
