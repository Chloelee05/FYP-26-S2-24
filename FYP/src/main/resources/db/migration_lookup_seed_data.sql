-- Seed lookup tables that were created without data.
-- Safe to run multiple times: ON CONFLICT DO NOTHING skips existing rows.
-- IDs are forced via OVERRIDING SYSTEM VALUE to match Java enum values.

INSERT INTO auction_status (id, status) OVERRIDING SYSTEM VALUE VALUES
  (1, 'Active'),
  (2, 'Finished'),
  (3, 'Cancelled'),
  (4, 'Pending')
ON CONFLICT (id) DO NOTHING;

INSERT INTO auction_type (id, type) OVERRIDING SYSTEM VALUE VALUES
  (1, 'Price Up'),
  (2, 'Dutch Auction'),
  (3, 'Blind')
ON CONFLICT (id) DO NOTHING;

INSERT INTO item_status (id, item_condition) OVERRIDING SYSTEM VALUE VALUES
  (1, 'Brand New'),
  (2, 'Slightly Used'),
  (3, 'Used'),
  (4, 'Damaged')
ON CONFLICT (id) DO NOTHING;

-- Also apply seller feature columns if not already present
ALTER TABLE auction_details
    ADD COLUMN IF NOT EXISTS starting_price NUMERIC(10,2) NOT NULL DEFAULT 0
        CHECK (starting_price >= 0);

ALTER TABLE auction_details
    ADD COLUMN IF NOT EXISTS max_price NUMERIC(10,2) DEFAULT NULL
        CHECK (max_price IS NULL OR max_price > 0);

ALTER TABLE auction
    ADD COLUMN IF NOT EXISTS cancel_reason TEXT DEFAULT NULL;
