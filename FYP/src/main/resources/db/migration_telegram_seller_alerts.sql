-- Telegram seller alert bodies: the price feed and the auction result.
-- Additive and safe to re-run.
--
-- Same shape as migration_telegram_alerts.sql, so AdminLandingContent renders these in the
-- existing "Telegram" group with no React change.
--
-- Placeholders are substituted by com.auction.telegram.TelegramAlerts, in a single pass so
-- that a listing title containing brace text cannot have a value spliced into it:
--   {title}  — the listing title, HTML-escaped and emboldened
--   {price}  — the money amount, already formatted
--   {bids}   — the bid count as a phrase, e.g. "12 bids" (seller price alert only)
--   {winner} — the masked buyer handle, e.g. "c***e" (seller sold alert only)
-- Any other brace text is sent verbatim.
--
-- Privacy (PDPA): the price feed must not name the bidder — a seller has no more claim on
-- their bidders' identities mid-auction than a bidder has on their rivals', and knowing who
-- is bidding invites off-platform approaches. The sold alert is the one bounded exception:
-- the seller is about to ship to this person, so they get a masked handle to recognise them
-- by. The unmasked identity lives on the order behind authentication, and the masking is
-- applied in TelegramAlerts.sellerSold rather than at a call site, so it cannot be skipped.
--
-- Volume: telegram_seller_price defaults FALSE (see migration_telegram_notifications.sql).
-- It is the only alert that fires repeatedly while the member does nothing, so it is opt-in,
-- and it coalesces to one message per auction per cooldown — TELEGRAM_PRICE_COOLDOWN_SECONDS,
-- tightening to TELEGRAM_PRICE_COOLDOWN_ENDGAME_SECONDS inside the last
-- TELEGRAM_PRICE_ENDGAME_WINDOW_MINUTES before the close.

INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('telegram.alert.sellerPrice', 'Telegram', 'Alert — bid on your listing ({title}, {price}, {bids})', TRUE, 630,
        '{title} is now at {price}.' || chr(10) || chr(10) ||
        '{bids} so far. Bidding is live — no action needed from you.'),
    ('telegram.alert.sellerSold', 'Telegram', 'Alert — your listing sold ({title}, {price}, {winner})', TRUE, 640,
        '{title} sold for {price}.' || chr(10) || chr(10) ||
        'The winning bidder is {winner}. Their full details are on the order in your ' ||
        'seller dashboard once payment clears, where you can arrange delivery.'),
    ('telegram.alert.sellerUnsold', 'Telegram', 'Alert — your listing ended unsold ({title})', TRUE, 650,
        '{title} has ended without a sale.' || chr(10) || chr(10) ||
        'Nothing was bid on it. You can relist it from your seller dashboard.')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
