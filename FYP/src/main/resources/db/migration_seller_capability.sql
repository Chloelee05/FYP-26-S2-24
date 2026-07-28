-- Merged buyer/seller accounts.
--
-- Previously an account was either a Buyer or a Seller, fixed at registration
-- (users.role_id, one row in `roles`). Selling is now a capability a buyer can
-- switch on at any time, so every new account registers as a Buyer and opts in
-- later.
--
-- `role_id` is intentionally left in place: ADMIN still keys off it, and existing
-- Seller rows keep their role so seller profiles, analytics and historical data
-- continue to resolve. Authorisation for seller actions now reads `can_sell`.
--
-- Safe to re-run.

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS can_sell BOOLEAN NOT NULL DEFAULT FALSE;

-- Everyone who was registered as a Seller (role_id = 3) keeps selling.
UPDATE users
   SET can_sell = TRUE
 WHERE role_id = 3
   AND can_sell = FALSE;

-- Admins can list and moderate sellers without scanning every account.
CREATE INDEX IF NOT EXISTS idx_users_can_sell ON users (can_sell) WHERE can_sell;
