package com.auction.util;

import com.auction.dao.OrderDAO;
import com.auction.dao.SellerAuctionDAO;
import com.auction.model.AuctionStatus;
import com.auction.notification.NotificationService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Lazily finalizes an auction whose end date has passed.
 *
 * <p>This handles one of the four ways an auction can conclude: the clock running out on
 * an ascending or blind auction. A winner is chosen by taking the highest bid, with the
 * earlier bid winning a tie on amount. The auction is then marked FINISHED, the winner and
 * winning amount are recorded, stock is decremented and an order is opened so the buyer
 * has something to pay against.</p>
 *
 * <p>Buy It Now and accepting a Dutch clock price are alternative conclusion paths and do
 * not come through here. Both end the auction immediately at a known price with a known
 * buyer, so there is no highest bid to resolve; they are handled where the purchase is
 * made and reach the same order creation from the other side.</p>
 *
 * <p>"Lazily" means there is no single authoritative job doing this. A scheduled sweep runs
 * every sixty seconds, and any page that loads an ended auction also triggers it, so an
 * auction concludes on whichever happens first. That makes the method re-entrant by
 * design: it takes a row lock, re-checks that the auction is still ACTIVE, and returns
 * having done nothing if another caller got there first.</p>
 */
public final class AuctionFinalizer {

    private AuctionFinalizer() { }

    /** Whether this call was the one that concluded the auction, and who won if so. */
    public static final class FinalizeResult {
        public final boolean finalized;
        /** The winning bidder, or -1 when the auction ended with no bids at all. */
        public final int winnerId;

        private FinalizeResult(boolean finalized, int winnerId) {
            this.finalized = finalized;
            this.winnerId = winnerId;
        }

        static FinalizeResult none() { return new FinalizeResult(false, -1); }
        static FinalizeResult ok(int winnerId) { return new FinalizeResult(true, winnerId); }
    }

    /**
     * If {@code auctionId} is ACTIVE and its end date has passed, set it to FINISHED
     * and populate {@code winner_id} / {@code winning_bid} from the highest bid.
     *
     * <p>Runs inside the caller's transaction so the status change, the winner and the
     * resulting order either all land or none of them do.</p>
     */
    public static FinalizeResult finalizeIfEnded(Connection conn, long auctionId) throws SQLException {
        int statusId;
        Timestamp dateEnd;
        // FOR UPDATE holds the row until the transaction ends. Two callers can reach this
        // at once (the sweep and a page load), and the second must find the auction already
        // FINISHED rather than concluding it a second time.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status_id, date_end FROM auction WHERE auction_id = ? FOR UPDATE")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return FinalizeResult.none();
                statusId = rs.getInt("status_id");
                dateEnd  = rs.getTimestamp("date_end");
            }
        }

        if (statusId != AuctionStatus.ACTIVE.getId()) return FinalizeResult.none();
        if (dateEnd == null || dateEnd.toInstant().isAfter(Instant.now())) return FinalizeResult.none();

        Integer winnerId = null;
        BigDecimal winningBid = null;
        // Highest amount wins; the earlier of two equal bids wins the tie, which is why
        // bid_time is the second sort key.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id, bid_amount FROM bids WHERE auction_id = ? "
              + "ORDER BY bid_amount DESC, bid_time ASC LIMIT 1")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    winnerId   = rs.getInt("user_id");
                    winningBid = rs.getBigDecimal("bid_amount");
                }
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE auction SET status_id = ? WHERE auction_id = ?")) {
            ps.setInt(1, AuctionStatus.FINISHED.getId());
            ps.setLong(2, auctionId);
            ps.executeUpdate();
        }

        if (winnerId != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auction_details SET winner_id = ?, winning_bid = ? WHERE id = ?")) {
                ps.setInt(1, winnerId);
                // The bid, to the cent. This used to be setInt(winningBid.intValue()), which
                // truncated: a $33.77 winning bid became a $33.00 order, because
                // ensureOrderForAuction reads the order amount straight back out of this column.
                // winning_bid is NUMERIC(12,2) as of migration_seller_maintain_listing.sql.
                ps.setBigDecimal(2, winningBid.setScale(2, java.math.RoundingMode.HALF_UP));
                ps.setLong(3, auctionId);
                ps.executeUpdate();
            }
            // One unit leaves on a conclusion: this model awards one auction to one winner.
            SellerAuctionDAO.decrementStockForSale(conn, auctionId);
            new OrderDAO().ensureOrderForAuction(conn, auctionId);
            return FinalizeResult.ok(winnerId);
        }
        return FinalizeResult.ok(-1);
    }

    /**
     * Standalone finalize + notify (used when viewing an ended auction).
     *
     * <p>Opens its own transaction and then tells the people affected. Notification is kept
     * outside the transaction and swallows its own failures, because a mail or push problem
     * must not roll back a concluded auction.</p>
     */
    public static void finalizeIfExpiredAndNotify(long auctionId) {
        try {
            FinalizeResult r = DBUtil.runInTransaction(conn -> finalizeIfEnded(conn, auctionId));
            if (r.finalized && r.winnerId > 0) {
                NotificationService.notifyAuctionWonIfAbsent(auctionId, r.winnerId);
                // Everyone else who bid. Deduplicated per recipient inside the service, which
                // matters here because this method is re-entered by the 60-second sweep and by
                // any page that lazily finalises the same auction.
                NotificationService.notifyAuctionLost(auctionId, r.winnerId);
            } else if (r.finalized && r.winnerId <= 0) {
                NotificationService.notifyAuctionEndedUnsold(auctionId);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
