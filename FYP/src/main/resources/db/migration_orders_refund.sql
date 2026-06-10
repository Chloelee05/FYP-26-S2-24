-- Buyer refund requests on paid (in-progress) orders. Safe to re-run.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS refund_status VARCHAR(20);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS refund_reason TEXT;

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS refund_requested_at TIMESTAMPTZ;
