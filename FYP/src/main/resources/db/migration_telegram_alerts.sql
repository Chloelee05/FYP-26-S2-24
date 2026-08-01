-- Telegram alert bodies: the wording of the messages the bot actually pushes.
-- Additive and safe to re-run.
--
-- Same shape as migration_telegram_notifications.sql, so AdminLandingContent renders
-- these in the existing "Telegram" group with no React change.
--
-- Placeholders are substituted by com.auction.telegram.TelegramAlerts:
--   {title} — the listing title, HTML-escaped and emboldened
--   {price} — the money amount, already formatted
-- They are the only two substitutions; any other brace text is sent verbatim.
--
-- Privacy (PDPA): none of these messages may name the other party. Who outbid you and
-- who won are both personal data and strategically sensitive in a live marketplace, so
-- the copy is deliberately written to carry the listing and the price only.

INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('telegram.alert.outbid', 'Telegram', 'Alert — you were outbid ({title}, {price})', TRUE, 600,
        'You have been outbid on {title}.' || chr(10) || chr(10) ||
        'The bid to beat is now {price}. Open the auction to raise your bid before it closes.'),
    ('telegram.alert.won', 'Telegram', 'Alert — you won ({title}, {price})', TRUE, 610,
        'You won {title}.' || chr(10) || chr(10) ||
        'Winning price: {price}. Complete payment on AuctionHub to finish the transaction.'),
    ('telegram.alert.lost', 'Telegram', 'Alert — auction closed without you ({title}, {price})', TRUE, 620,
        '{title} has closed, and your bid was not the winning one.' || chr(10) || chr(10) ||
        'It sold for {price}. Browse AuctionHub to find something similar.')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
