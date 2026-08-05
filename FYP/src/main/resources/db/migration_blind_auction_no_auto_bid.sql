-- Auto-bid does not apply to blind (sealed-bid) auctions. Clears the rows that were
-- accepted before the server started saying so.
-- Idempotent: the DELETE matches nothing on a second run. Safe to re-run.

-- ── Remove auto-bids stored against a sealed auction ─────────────────────────
--
-- Proxy bidding works by counter-bidding one increment above whoever is currently
-- leading. A blind auction has no visible leader and no moving price, and takes exactly
-- one hidden bid per buyer, so AutoBidDAO.processAutoBids is never reached on one:
-- BidApiServlet routes BLIND to placeSealedBid, which does not invoke it, and
-- BidDAO.placeBid — the only other path that does — now refuses BLIND outright.
--
-- AutoBidApiServlet nevertheless accepted a maximum for a blind auction and stored it,
-- and AuctionApiServlet echoed it back to the detail page as myAutoBid. The effect was a
-- buyer being shown an "Auto-Bid Active" panel, with a ceiling they had committed to, on
-- an auction where nothing was ever going to bid for them. Both are now refused, which
-- leaves these rows unreachable: nothing reads them and nothing can fire them.
--
-- They are deleted rather than left in place because an auto_bids row is live
-- configuration, not a transaction record — what was actually bid lives in the bids
-- table and is untouched here. A dormant row would only be a standing instruction
-- nobody can act on and its owner can no longer see, and it carries an encrypted
-- max-bid amount, so keeping it means holding a buyer's private ceiling for no purpose.
DELETE FROM auto_bids
WHERE auction_id IN (SELECT auction_id FROM auction WHERE auction_type = 3);

-- No database constraint accompanies this. The rule is "this row's auction must not be
-- of type 3", which is a statement about another table: a PostgreSQL CHECK constraint
-- cannot contain a subquery, so it cannot be expressed as one. A trigger could, but it
-- would be a third copy of a guard that already sits in front of both writers
-- (AutoBidApiServlet for the SPA, and BidDAO.placeBid for the path that would fire it),
-- and it is the writers, not the storage, that this defect was about.
