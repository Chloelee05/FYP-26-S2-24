-- Order shipping tracking (seller-updated steps). Safe to re-run.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shipping_status VARCHAR(30);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shipping_updated_at TIMESTAMPTZ;
