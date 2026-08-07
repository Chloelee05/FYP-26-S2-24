-- NEW for the "platform-wide auction rules" admin story. Seeds two more admin-tunable keys
-- into the existing platform_settings table (created by migration_platform_settings.sql), the
-- same generic key/value store the bid rate limit and login lockout settings already live in.
-- Purely additive and safe to re-run: no CREATE TABLE (platform_settings already exists by the
-- time this runs) and ON CONFLICT DO NOTHING on both inserts, exactly like every other seed row
-- in migration_platform_settings.sql.
--
-- Read by com.auction.dao.PlatformSettingsDAO (via the new getBigDecimal reader for the first
-- key), with a hardcoded Java fallback per key so an unmigrated database still behaves sanely.

-- Feature: platform-wide minimum bid increment. A manual ascending bid (BidDAO#placeBid) must
-- exceed the current floor by at least this much. Chosen to exactly reproduce today's
-- unwritten behaviour: bids.bid_amount is NUMERIC(10,2), so the smallest amount by which one
-- bid could already exceed another is one cent, and today's placeBid check (bidAmount > floor)
-- already enforces exactly that. Seeding '0.01' here means the new guard added alongside that
-- check changes nothing until an admin deliberately raises it.
INSERT INTO platform_settings (key, value) VALUES ('min_bid_increment', '0.01')
  ON CONFLICT (key) DO NOTHING;

-- Feature: platform-wide maximum auction duration, in days, checked on listing create (and on
-- edit, if the end date is changed) alongside the existing "end after start" check. Today there
-- is no such limit at all, so any positive number technically changes behaviour for a
-- hypothetical listing longer than it -- but 3650 days (10 years) is far beyond any demo or
-- real listing this platform will ever see (existing auctions run for hours to weeks), so in
-- practice no plausible listing is ever rejected by this default.
INSERT INTO platform_settings (key, value) VALUES ('max_auction_duration_days', '3650')
  ON CONFLICT (key) DO NOTHING;
