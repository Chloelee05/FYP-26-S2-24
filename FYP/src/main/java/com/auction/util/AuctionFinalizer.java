package com.auction.util;

import com.auction.dao.OrderDAO;
import com.auction.model.AuctionStatus;

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
 * <p>The application has no background scheduler, so an auction can sit at
 * {@code status_id = ACTIVE} with {@code winner_id = NULL} long after its
 * {@code date_end}. That breaks anything that requires a FINISHED auction with a
 * winner — most visibly the rating flows. This helper, called inside an existing
 * transaction, promotes such an auction to {@code FINISHED} and records the winner
 * (highest bid) so the rest of the logic can proceed against consistent state.</p>
 */
public final class AuctionFinalizer {

    private AuctionFinalizer() { }

    /**
     * If {@code auctionId} is ACTIVE and its end date has passed, set it to FINISHED
     * and populate {@code winner_id} / {@code winning_bid} from the highest bid.
     * No-op for auctions that are not active, have not ended, or do not exist.
     * Runs on the supplied connection so it participates in the caller's transaction.
     */
    public static void finalizeIfEnded(Connection conn, long auctionId) throws SQLException {
        int statusId;
        Timestamp dateEnd;
        String selectSql = "SELECT status_id, date_end FROM auction WHERE auction_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                statusId = rs.getInt("status_id");
                dateEnd  = rs.getTimestamp("date_end");
            }
        }

        if (statusId != AuctionStatus.ACTIVE.getId()) return;
        if (dateEnd == null || dateEnd.toInstant().isAfter(Instant.now())) return;

        // Highest bid wins; earliest bid breaks ties.
        Integer winnerId = null;
        BigDecimal winningBid = null;
        String bidSql = "SELECT user_id, bid_amount FROM bids WHERE auction_id = ? "
                + "ORDER BY bid_amount DESC, bid_time ASC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(bidSql)) {
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
        }
    }
}
