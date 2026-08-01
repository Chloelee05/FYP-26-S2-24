-- Telegram alert bodies for the post-sale order lifecycle, plus the one preference that
-- gates them. Additive and safe to re-run.
--
-- Same shape as migration_telegram_seller_alerts.sql, so AdminLandingContent renders these
-- in the existing "Telegram" group with no React change.
--
-- Stage model, as implemented by OrderDAO (not invented here):
--   orders.status          PENDING_PAYMENT -> PAID -> COMPLETED, or CANCELLED
--   orders.shipping_status PREPARING -> SHIPPED -> IN_TRANSIT -> DELIVERED
--   orders.refund_status   REQUESTED -> APPROVED (order CANCELLED) | REJECTED (order stays PAID)
-- PREPARING has no alert of its own: it is set by the payment itself, so announcing it would
-- mean two messages for one event, and the payment confirmation already says as much.
--
-- Placeholders are substituted by com.auction.telegram.TelegramAlerts, in a single pass so
-- that a listing title containing brace text cannot have a value spliced into it:
--   {title}  — the listing title, HTML-escaped and emboldened
--   {price}  — the order amount, already formatted
--   {winner} — the masked buyer handle, e.g. "c***e" (seller-facing messages only)
-- Any other brace text is sent verbatim.
--
-- Privacy (PDPA): nothing here carries a delivery address, a phone number, an email address
-- or a payment instrument. The buyer's own emailed receipt holds the card hint, and the
-- shipping and contact details live on the order page behind a login — a push message is
-- read off a lock screen by whoever is holding the phone. Seller-facing messages name the
-- buyer only as a masked handle, applied inside TelegramAlerts rather than at a call site so
-- it cannot be skipped; buyer-facing messages name nobody, since a buyer already knows whose
-- listing they bought. The buyer's free-text refund reason is deliberately left out of the
-- seller's alert: it is dispute correspondence, and the seller has to open the order to
-- answer it anyway.
--
-- Volume: one switch (telegram_order_updates) covers all nine, defaulting TRUE. They are the
-- bounded, non-repeating consequences of a transaction the member is already party to — at
-- most four per order per side, none of which can fire while the member does nothing — so
-- unlike telegram_seller_price there is nothing to opt in to, and nobody sensibly wants to
-- hear that their parcel shipped but not that it arrived.

ALTER TABLE notification_preference
    ADD COLUMN IF NOT EXISTS telegram_order_updates BOOLEAN NOT NULL DEFAULT TRUE;

INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('telegram.alert.orderPayment', 'Telegram', 'Order — payment confirmed, to buyer ({title}, {price})', TRUE, 660,
        'Payment confirmed for {title}.' || chr(10) || chr(10) ||
        'We received {price}. The seller has been told and will get your item ready to send.'),
    ('telegram.alert.orderPaid', 'Telegram', 'Order — buyer has paid, to seller ({title}, {price}, {winner})', TRUE, 670,
        '{winner} has paid {price} for {title}.' || chr(10) || chr(10) ||
        'Get the item ready and mark it shipped from My sales.'),
    ('telegram.alert.orderShipped', 'Telegram', 'Order — shipped, to buyer ({title})', TRUE, 680,
        '{title} is on its way.' || chr(10) || chr(10) ||
        'The seller has handed your order over for delivery. You will hear from us again ' ||
        'when it is out for delivery.'),
    ('telegram.alert.orderInTransit', 'Telegram', 'Order — out for delivery, to buyer ({title})', TRUE, 690,
        '{title} is out for delivery.' || chr(10) || chr(10) ||
        'Your order is on the last leg of its journey and should reach you shortly.'),
    ('telegram.alert.orderDelivered', 'Telegram', 'Order — delivered, to buyer ({title})', TRUE, 700,
        '{title} has been marked delivered.' || chr(10) || chr(10) ||
        'Confirm receipt from My purchases once you have checked the item over. If it has ' ||
        'not arrived, request a refund from the same page instead.'),
    ('telegram.alert.orderCompleted', 'Telegram', 'Order — receipt confirmed, to seller ({title}, {price}, {winner})', TRUE, 710,
        '{winner} has confirmed receipt of {title}.' || chr(10) || chr(10) ||
        'The sale is complete and {price} is reflected in your earnings summary.'),
    ('telegram.alert.refundRequested', 'Telegram', 'Order — refund requested, to seller ({title}, {winner})', TRUE, 720,
        '{winner} has requested a refund on {title}.' || chr(10) || chr(10) ||
        'Their reason is on the order in My sales, where you can approve or decline it.'),
    ('telegram.alert.refundApproved', 'Telegram', 'Order — refund approved, to buyer ({title}, {price})', TRUE, 730,
        'Your refund request for {title} was approved.' || chr(10) || chr(10) ||
        'The order has been cancelled and {price} goes back to the payment method you used.'),
    ('telegram.alert.refundRejected', 'Telegram', 'Order — refund declined, to buyer ({title})', TRUE, 740,
        'Your refund request for {title} was declined.' || chr(10) || chr(10) ||
        'The order is still open in My purchases. Message the seller from there if you ' ||
        'want to take it further.')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
