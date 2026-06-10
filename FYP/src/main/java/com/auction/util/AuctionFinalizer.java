package com.auction.util;

import com.auction.dao.OrderDAO;
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
 */
public final class AuctionFinalizer {

    private AuctionFinalizer() { }

    public static final class FinalizeResult {
        public final boolean finalized;
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
     */
    public static FinalizeResult finalizeIfEnded(Connection conn, long auctionId) throws SQLException {
        int statusId;
        Timestamp dateEnd;
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
                ps.setInt(2, winningBid.intValue());
                ps.setLong(3, auctionId);
                ps.executeUpdate();
            }
            new OrderDAO().ensureOrderForAuction(conn, auctionId);
            return FinalizeResult.ok(winnerId);
        }
        return FinalizeResult.ok(-1);
    }

    /** Standalone finalize + notify (used when viewing an ended auction). */
    public static void finalizeIfExpiredAndNotify(long auctionId) {
        try {
            FinalizeResult r = DBUtil.runInTransaction(conn -> finalizeIfEnded(conn, auctionId));
            if (r.finalized && r.winnerId > 0) {
                NotificationService.notifyAuctionWonIfAbsent(auctionId, r.winnerId);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
