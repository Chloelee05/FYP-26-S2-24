-- Auction card CTA labels. Active listings always say "View Auction" (guest and
-- signed-in alike); bidding stays on the detail page. Ended listings keep
-- "View Result". Additive and safe to re-run.
--
-- Same column shape as migration_landing_content.sql, so AdminLandingContent
-- renders these fields automatically without any React change.

INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('card.cta.viewAuction', 'Auction cards', 'Active listing button', FALSE, 400,
        'View Auction'),
    ('card.cta.viewResult',  'Auction cards', 'Ended listing button',  FALSE, 410,
        'View Result')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
