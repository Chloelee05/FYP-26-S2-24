package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.model.Order;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orders represent the simulated post-auction transaction (payment + fulfilment).
 * Created when a seller declares the winner of an ended auction.
 */
public class OrderDAO {

    public enum DeclareStatus { SUCCESS, AUCTION_NOT_FOUND, NOT_SELLER, NOT_ENDED, ALREADY_FINALIZED, NO_BIDS }

    /** Result of declaring a winner: outcome plus (on success) the new order + winner. */
    public static final class DeclareResult {
        public final DeclareStatus status;
        public final long orderId;
        public final int winnerId;
        public final BigDecimal amount;
        DeclareResult(DeclareStatus status, long orderId, int winnerId, BigDecimal amount) {
            this.status = status; this.orderId = orderId; this.winnerId = winnerId; this.amount = amount;
        }
        static DeclareResult fail(DeclareStatus s) { return new DeclareResult(s, -1, -1, null); }
    }

    /**
     * Finalises an ended auction owned by {@code sellerId}: records the highest bidder
     * as winner, marks the auction FINISHED, and creates a PENDING_PAYMENT order.
     */
    public DeclareResult declareWinner(long auctionId, int sellerId) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            int statusId, ownerId;
            Instant dateEnd;
            String moderationState;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status_id, seller_id, date_end, moderation_state "
                  + "FROM auction WHERE auction_id = ? FOR UPDATE")) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return DeclareResult.fail(DeclareStatus.AUCTION_NOT_FOUND); }
                    statusId = rs.getInt("status_id");
                    ownerId = rs.getInt("seller_id");
                    Timestamp ts = rs.getTimestamp("date_end");
                    dateEnd = ts != null ? ts.toInstant() : null;
                    moderationState = rs.getString("moderation_state");
                }
            }

            if (ownerId != sellerId) { conn.rollback(); return DeclareResult.fail(DeclareStatus.NOT_SELLER); }
            if (dateEnd == null || Instant.now().isBefore(dateEnd)) {
                conn.rollback(); return DeclareResult.fail(DeclareStatus.NOT_ENDED);
            }

            // Already has an order? (unique per auction)
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM orders WHERE auction_id = ?")) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) { conn.rollback(); return DeclareResult.fail(DeclareStatus.ALREADY_FINALIZED); }
                }
            }

            int winnerId;
            BigDecimal amount;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, bid_amount FROM bids WHERE auction_id = ? "
                  + "ORDER BY bid_amount DESC, bid_time ASC LIMIT 1")) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return DeclareResult.fail(DeclareStatus.NO_BIDS); }
                    winnerId = rs.getInt("user_id");
                    amount = rs.getBigDecimal("bid_amount");
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auction SET status_id = ? WHERE auction_id = ?")) {
                ps.setInt(1, AuctionStatus.FINISHED.getId());
                ps.setLong(2, auctionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auction_details SET winner_id = ?, winning_bid = ? WHERE id = ?")) {
                ps.setInt(1, winnerId);
                ps.setInt(2, amount.setScale(0, RoundingMode.HALF_UP).intValue());
                ps.setLong(3, auctionId);
                ps.executeUpdate();
            }

            long orderId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO orders (auction_id, buyer_id, seller_id, amount, status) "
                  + "VALUES (?, ?, ?, ?, 'PENDING_PAYMENT')", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, auctionId);
                ps.setInt(2, winnerId);
                ps.setInt(3, sellerId);
                ps.setBigDecimal(4, amount);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    orderId = rs.next() ? rs.getLong(1) : -1;
                }
            }

            conn.commit();
            return new DeclareResult(DeclareStatus.SUCCESS, orderId, winnerId, amount);
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
        }
    }

    /** Marks a PENDING_PAYMENT order PAID (simulated payment) for the owning buyer. */
    public boolean pay(long orderId, int buyerId, Long paymentMethodId) {
        String sql = "UPDATE orders SET status = 'PAID', paid_at = CURRENT_TIMESTAMP, payment_method_id = ? "
                + "WHERE id = ? AND buyer_id = ? AND status = 'PENDING_PAYMENT'";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (paymentMethodId != null) ps.setLong(1, paymentMethodId); else ps.setNull(1, java.sql.Types.BIGINT);
            ps.setLong(2, orderId);
            ps.setInt(3, buyerId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Marks a PAID order COMPLETED (seller confirms fulfilment). */
    public boolean complete(long orderId, int sellerId) {
        String sql = "UPDATE orders SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND seller_id = ? AND status = 'PAID'";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setInt(2, sellerId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns the (buyer, seller, auctionId) for an order, or null. Used for notifications. */
    public int[] partiesAndAuction(long orderId) {
        String sql = "SELECT buyer_id, seller_id, auction_id FROM orders WHERE id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new int[]{ rs.getInt("buyer_id"), rs.getInt("seller_id"), rs.getInt("auction_id") };
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /** All orders where the user is buyer or seller, newest first, framed for that user. */
    public List<Order> listForUser(int userId) {
        String sql =
            "SELECT o.id, o.auction_id, d.title, o.buyer_id, o.seller_id, o.amount, o.status, "
          + "  o.created_at, o.paid_at, o.completed_at, bu.username AS buyer_name, su.username AS seller_name "
          + "FROM orders o "
          + "JOIN auction_details d ON d.id = o.auction_id "
          + "JOIN users bu ON bu.id = o.buyer_id "
          + "JOIN users su ON su.id = o.seller_id "
          + "WHERE o.buyer_id = ? OR o.seller_id = ? "
          + "ORDER BY o.created_at DESC";
        List<Order> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long buyerId = rs.getLong("buyer_id");
                    boolean isBuyer = buyerId == userId;
                    String role = isBuyer ? "buyer" : "seller";
                    String counterparty = isBuyer ? rs.getString("seller_name") : rs.getString("buyer_name");
                    out.add(new Order(
                            rs.getLong("id"),
                            rs.getLong("auction_id"),
                            rs.getString("title"),
                            buyerId,
                            rs.getLong("seller_id"),
                            rs.getBigDecimal("amount"),
                            rs.getString("status"),
                            instant(rs.getTimestamp("created_at")),
                            instant(rs.getTimestamp("paid_at")),
                            instant(rs.getTimestamp("completed_at")),
                            role,
                            counterparty));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private static Instant instant(Timestamp ts) { return ts != null ? ts.toInstant() : null; }
}
