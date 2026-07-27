-- SCRUM-66/67: platform-wide auction rules, set by admins and enforced platform-wide.
--
-- Single-row configuration table (the primary key is pinned to 1 by a CHECK) that holds
-- the two rules every auction and bid is validated against:
--   min_bid_increment         — smallest amount a new bid must beat the current price by
--   max_auction_duration_days — longest allowed span between an auction's start and end
--
-- The row is created here with the platform defaults, so the table is never empty.
-- Safe to re-run.

CREATE TABLE IF NOT EXISTS platform_rules (
  id                        SMALLINT      PRIMARY KEY DEFAULT 1,
  min_bid_increment         NUMERIC(10,2) NOT NULL DEFAULT 1.00,
  max_auction_duration_days INT           NOT NULL DEFAULT 30,
  updated_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- Admin who last saved the rules; NULL for the seeded defaults.
  updated_by                BIGINT,
  CONSTRAINT platform_rules_singleton CHECK (id = 1),
  CONSTRAINT platform_rules_increment_check
      CHECK (min_bid_increment >= 0.01 AND min_bid_increment <= 10000.00),
  CONSTRAINT platform_rules_duration_check
      CHECK (max_auction_duration_days BETWEEN 1 AND 365),
  CONSTRAINT platform_rules_updated_by_fk FOREIGN KEY (updated_by) REFERENCES users (id)
);

-- Seed the single configuration row with the column defaults ($1.00 / 30 days).
INSERT INTO platform_rules (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
