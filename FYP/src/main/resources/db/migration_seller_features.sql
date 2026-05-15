-- SCRUM-33/36: starting_price was carried in the model but never persisted; add it now.
-- Existing rows default to 0 (acceptable for historical data).
ALTER TABLE auction_details
    ADD COLUMN IF NOT EXISTS starting_price NUMERIC(10,2) NOT NULL DEFAULT 0
        CHECK (starting_price >= 0);

-- SCRUM-33: max selling price – hard ceiling semantics.
-- NULL = no cap.  When set, bids may not exceed this value.
ALTER TABLE auction_details
    ADD COLUMN IF NOT EXISTS max_price NUMERIC(10,2) DEFAULT NULL
        CHECK (max_price IS NULL OR max_price > 0);

-- SCRUM-34: optional free-text reason stored when a seller cancels.
ALTER TABLE auction
    ADD COLUMN IF NOT EXISTS cancel_reason TEXT DEFAULT NULL;
