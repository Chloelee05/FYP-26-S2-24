-- SCRUM-270+: Add per-buyer bid_increment to auto_bids.
-- bid_increment is not sensitive (unlike max_amount) so it is stored as plain NUMERIC.
-- Default 0.01 keeps backward compatibility with existing rows.
ALTER TABLE auto_bids
    ADD COLUMN IF NOT EXISTS bid_increment NUMERIC(10,2) NOT NULL DEFAULT 0.01;
