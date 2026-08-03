-- Generic admin-tunable key/value settings for anti-abuse / lifecycle knobs that are not
-- part of the recommendation pipeline (see recommendation_settings for that). Same shape,
-- same convention: safe to re-run (CREATE TABLE IF NOT EXISTS / ON CONFLICT DO NOTHING).
--
-- Read by com.auction.dao.PlatformSettingsDAO, with a hardcoded Java fallback per key so an
-- unmigrated database still behaves sanely.

CREATE TABLE IF NOT EXISTS platform_settings (
  key        VARCHAR(50)  PRIMARY KEY,
  value      VARCHAR(255) NOT NULL,
  updated_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Feature: bid rate limiting (anti-spam, not anti-sniping). Minimum gap, in seconds,
-- between two bids from the same buyer on the same auction. BidDAO#placeBid rejects a
-- second bid inside this window with BID_TOO_FAST; it never touches the auction clock.
INSERT INTO platform_settings (key, value) VALUES ('bid_rate_limit_seconds', '3')
  ON CONFLICT (key) DO NOTHING;

-- Feature: auto-cancellation of unpaid winning bids. Hours a PENDING_PAYMENT order may sit
-- unpaid before the scheduled sweep (AuctionExpiryListener) cancels it automatically.
INSERT INTO platform_settings (key, value) VALUES ('order_payment_deadline_hours', '48')
  ON CONFLICT (key) DO NOTHING;

-- Feature: login brute-force protection. Consecutive wrong passwords before an account is
-- locked out, and the cooldown (minutes) before it clears itself automatically.
INSERT INTO platform_settings (key, value) VALUES ('login_lockout_threshold', '5')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO platform_settings (key, value) VALUES ('login_lockout_cooldown_minutes', '15')
  ON CONFLICT (key) DO NOTHING;
