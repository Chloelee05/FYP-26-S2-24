-- Seller record maintenance: money precision on winning_bid, and a quantity floor of
-- zero so the last item can actually be removed from a listing.
-- Additive and idempotent throughout (guarded DO blocks / ON CONFLICT DO NOTHING),
-- safe to re-run.

-- ── 1. winning_bid: INTEGER → NUMERIC(12,2) ───────────────────────────────────
--
-- bids.bid_amount is NUMERIC(10,2) and orders.amount is NUMERIC(10,2), but the
-- denormalised winning_bid it is copied into was INTEGER, so every conclusion path
-- silently dropped the cents — and did it inconsistently: AuctionFinalizer truncated
-- (intValue) while OrderDAO/BidDAO rounded half-up. A $33.77 winning bid became a
-- $33.00 order. Widening to NUMERIC(12,2) (12 rather than 10 digits: this column is
-- summed for revenue reporting in several places, and a wider precision costs nothing
-- for a numeric) makes the column able to hold what the bid actually was, and the four
-- Java write paths now all setBigDecimal.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'auction_details'
      AND column_name = 'winning_bid'
      AND data_type <> 'numeric'
  ) THEN
    ALTER TABLE auction_details ALTER COLUMN winning_bid TYPE NUMERIC(12,2);
  END IF;
END $$;

-- ── 2. Historical rows are deliberately left as they are ─────────────────────
--
-- Three rows pre-date this fix and still hold a whole-dollar figure: auctions 3, 4 and 12,
-- whose winning_bid reads 205 / 350 / 550 against true top bids of 204.96 / 349.98 / 550.04.
-- They are NOT corrected here, by decision.
--
-- winning_bid is a derived denormalisation, but for these three auctions it is also the
-- figure the order was created from, and those orders are COMPLETED: orders.amount is the
-- settled record of what the buyer was actually charged and both parties confirmed, and a
-- receipt carrying it has already been emailed. Rewriting winning_bid alone would make the
-- auction disagree with its own settled order; rewriting the order too would falsify
-- transaction history. A documented few-cent artefact on three legacy rows is the smaller
-- problem, and the code change above means no new row can be created this way.
--
-- Consequence to keep in mind when reading reports: revenue aggregates that sum winning_bid
-- (AuctionDAO.sumWinningBidDollars, AdminReportDAO's revenue report, ProfileActivityDAO's
-- transaction volume, SellerAnalyticsDAO's seller revenue) are overstated by $0.06 in total
-- across those three legacy rows, and will stay that way.

-- ── 3. quantity floor: 1 → 0 ─────────────────────────────────────────────────
--
-- CHECK (quantity >= 1) made "remove the last item from this auction" impossible, which
-- is half of minimum requirement Seller (d). Removing the final unit is now allowed and
-- ends the listing: SellerAuctionDAO.removeUnit sets quantity = 0 and CANCELLED in one
-- transaction, so quantity = 0 can only ever be seen on a listing that is no longer
-- accepting bids (CANCELLED by that path, or FINISHED once a sale decremented the last
-- unit). Relisting restores at least one unit — see SellerAuctionDAO.relistAuction — so
-- a live listing with nothing to sell is not reachable.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'auction_details'::regclass
      AND conname = 'auction_details_quantity_check'
      AND pg_get_constraintdef(oid) LIKE '%>= 1%'
  ) THEN
    ALTER TABLE auction_details DROP CONSTRAINT auction_details_quantity_check;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'auction_details'::regclass
      AND conname = 'auction_details_quantity_check'
  ) THEN
    ALTER TABLE auction_details
      ADD CONSTRAINT auction_details_quantity_check CHECK (quantity >= 0);
  END IF;
END $$;

-- ── 4. Telegram copy for the auction-cancelled alert ─────────────────────────
--
-- Same "Telegram" landing_content group as every other alert body, so the existing
-- admin copy editor picks it up with no React change. TelegramAlerts falls back to the
-- same text in code, so the push works whether or not this row is present.
INSERT INTO landing_content
    (content_key, content_group, label, multiline, display_order, content_value, default_value)
SELECT k, g, l, m, o, v, v
FROM (VALUES
    ('telegram.alert.auctionCancelled', 'Telegram', 'Auction — cancelled by the seller, to bidders ({title})', TRUE, 770,
        'The seller has cancelled {title}.' || chr(10) || chr(10) ||
        'Your bid no longer stands and nothing is owed. Browse AuctionHub to find something similar.')
) AS seed(k, g, l, m, o, v)
ON CONFLICT (content_key) DO NOTHING;
