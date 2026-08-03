-- Auto-cancellation of unpaid winning bids (order payment deadline). Additive and
-- idempotent: ADD COLUMN IF NOT EXISTS / ON CONFLICT DO NOTHING throughout, safe to re-run.
--
-- Depends on migration_platform_settings.sql having already created platform_settings
-- (migrate_all.sql runs that one first).
--
-- Design decision (documented here and in OrderDAO#cancelOverduePendingOrders / the
-- project report): on timeout the order is CANCELLED with cancel_reason = 'PAYMENT_TIMEOUT'
-- and the auction is left FINISHED with its existing winner_id/winning_bid — i.e. the
-- listing closes as unsold rather than being re-awarded to the next-highest bidder or
-- relisted automatically. A re-award flow would have to answer "what if the next bidder
-- also doesn't pay?" and behave sensibly for Dutch/blind auctions too, which is a lot of new
-- surface area for a viva twelve days out. The seller is notified and can relist manually,
-- exactly as an auction that closes with zero bids already tells them to.
--
-- Grandfathering: three PENDING_PAYMENT orders already existed in the live database before
-- this feature shipped, stuck indefinitely. Rather than special-casing their ids (which
-- would not generalise to any other pre-existing stuck order), cancel_overdue reads
-- order_payment_timeout_effective_since_epoch_ms and only ever considers orders created at
-- or after that instant — i.e. every order that predates this migration's first apply is
-- permanently grandfathered and left exactly as it is, untouched by SQL and untouched by
-- the new scheduled logic. Seeded once, from ON CONFLICT DO NOTHING, so re-running this
-- migration cannot move the cutoff forward and start grandfathering newer orders too.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(30);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE table_name = 'orders' AND constraint_name = 'orders_cancel_reason_check'
  ) THEN
    ALTER TABLE orders ADD CONSTRAINT orders_cancel_reason_check
      CHECK (cancel_reason IS NULL OR cancel_reason IN ('PAYMENT_TIMEOUT', 'REFUND_APPROVED', 'ADMIN_CANCELLED'));
  END IF;
END $$;

INSERT INTO platform_settings (key, value)
VALUES ('order_payment_timeout_effective_since_epoch_ms',
        (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint::text)
ON CONFLICT (key) DO NOTHING;

-- Telegram copy for the two new alerts, same "Telegram" landing_content group as every
-- other order-lifecycle alert, so AdminLandingContent renders them with no React change.
INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('telegram.alert.orderPaymentTimeoutBuyer', 'Telegram', 'Order — payment deadline missed, to buyer ({title})', TRUE, 750,
        'Your order for {title} was cancelled.' || chr(10) || chr(10) ||
        'Payment was not received within the required window, so the order was automatically cancelled.'),
    ('telegram.alert.orderPaymentTimeoutSeller', 'Telegram', 'Order — payment deadline missed, to seller ({title})', TRUE, 760,
        '{title} closed unsold.' || chr(10) || chr(10) ||
        'The winning bidder did not pay in time, so the order was cancelled. You can relist the item from your seller dashboard.')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
