-- Account closure clean-up (Seller a-3). Additive and idempotent: the constraint is
-- dropped and recreated from a guard that checks the current definition, so re-running
-- this file is a no-op.
--
-- Why: closing an account anonymises the users row but used to leave the member's business
-- running. A departing seller's ACTIVE/PENDING listings stayed live and biddable, so members
-- could still win an auction from a seller who no longer exists, and their open orders were
-- left dangling. UserDAO#closeAccount now, in the same transaction as the anonymisation:
--
--   * cancels the seller's ACTIVE and PENDING listings (auction.cancel_reason names the
--     closure; bids are kept, exactly as they are for a seller-initiated cancel, because
--     they are the audit trail of a real auction);
--   * cancels every PENDING_PAYMENT order on either side of the departing member with
--     orders.cancel_reason = 'ACCOUNT_CLOSED' — no money has moved, so nobody is out of
--     pocket and neither party is left owing a counterparty who has gone;
--   * leaves PAID-but-undespatched sales PAID and flags them refund_status = 'REQUESTED',
--     so they enter the pending-refund queue an admin already services
--     (OrderDAO#adminResolveRefund). This is the case that must not be got wrong: the buyer
--     has paid. Cancelling the order outright would make their payment vanish along with the
--     seller, so the refund is raised on their behalf instead and the cancellation follows
--     from an admin approving it;
--   * leaves PAID orders whose goods are already in transit, and the departing member's own
--     paid purchases, completely untouched — whether those should be unwound depends on
--     facts only the two parties and support can establish. The counterparty is notified.
--
-- The only schema change needed is the new cancel_reason value. orders.cancel_reason is
-- constrained to a fixed set by migration_order_payment_timeout.sql, and an INSERT of
-- 'ACCOUNT_CLOSED' would be rejected by it.

DO $$
BEGIN
  -- Recreate the constraint only when it does not already permit ACCOUNT_CLOSED, so a
  -- second run neither errors nor churns the table.
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'orders_cancel_reason_check'
      AND pg_get_constraintdef(oid) LIKE '%ACCOUNT_CLOSED%'
  ) THEN
    ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_cancel_reason_check;
    ALTER TABLE orders ADD CONSTRAINT orders_cancel_reason_check
      CHECK (cancel_reason IS NULL OR cancel_reason IN (
        'PAYMENT_TIMEOUT', 'REFUND_APPROVED', 'ADMIN_CANCELLED', 'ACCOUNT_CLOSED'));
  END IF;
END $$;
