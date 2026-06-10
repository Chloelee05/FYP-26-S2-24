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

    public enum DeclareStatus { SUCCESS, AUCTION_NOT_FOUND, NOT_SELLER, NOT_ENDED, NOT_ACTIVE, ALREADY_FINALIZED, NO_BIDS }

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

    /** Finalises an ended auction (standard declare after close). */
    public DeclareResult declareWinner(long auctionId, int sellerId) {
        return declareWinner(auctionId, sellerId, false);
    }

    /**
     * Finalises an auction owned by {@code sellerId}: records the highest bidder as winner,
     * marks the auction FINISHED, and creates a PENDING_PAYMENT order.
     * When {@code early} is true the seller may close before the scheduled end time.
     */
    public DeclareResult declareWinner(long auctionId, int sellerId, boolean early) {
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
            if (statusId != AuctionStatus.ACTIVE.getId()) {
                conn.rollback(); return DeclareResult.fail(DeclareStatus.NOT_ACTIVE);
            }
            if (!early && (dateEnd == null || Instant.now().isBefore(dateEnd))) {
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
                    "UPDATE auction SET status_id = ?, date_end = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE date_end END "
                  + "WHERE auction_id = ?")) {
                ps.setInt(1, AuctionStatus.FINISHED.getId());
                ps.setBoolean(2, early);
                ps.setLong(3, auctionId);
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

    public enum ShippingAdvanceResult { SUCCESS, NOT_FOUND, NOT_SELLER, NOT_PAID, INVALID_TRANSITION, ALREADY_DELIVERED }

    private static final String[] SHIPPING_SEQUENCE = { "PREPARING", "SHIPPED", "IN_TRANSIT", "DELIVERED" };

    /** Marks a PENDING_PAYMENT order PAID (simulated payment) for the owning buyer. */
    public boolean pay(long orderId, int buyerId, Long paymentMethodId) {
        String sql = "UPDATE orders SET status = 'PAID', paid_at = CURRENT_TIMESTAMP, "
                + "shipping_status = 'PREPARING', shipping_updated_at = CURRENT_TIMESTAMP, payment_method_id = ? "
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

    /** Advances shipping one step (seller only, PAID orders). */
    public ShippingAdvanceResult advanceShipping(long orderId, int sellerId) {
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            String status, shipping;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, shipping_status FROM orders WHERE id = ? AND seller_id = ? FOR UPDATE")) {
                ps.setLong(1, orderId);
                ps.setInt(2, sellerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return ShippingAdvanceResult.NOT_FOUND; }
                    status = rs.getString("status");
                    shipping = rs.getString("shipping_status");
                }
            }
            if (!"PAID".equals(status)) { conn.rollback(); return ShippingAdvanceResult.NOT_PAID; }
            String next = nextShipping(shipping);
            if (next == null) { conn.rollback(); return ShippingAdvanceResult.ALREADY_DELIVERED; }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET shipping_status = ?, shipping_updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                ps.setString(1, next);
                ps.setLong(2, orderId);
                ps.executeUpdate();
            }
            conn.commit();
            return ShippingAdvanceResult.SUCCESS;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String nextShipping(String current) {
        if (current == null || current.isBlank()) return SHIPPING_SEQUENCE[0];
        for (int i = 0; i < SHIPPING_SEQUENCE.length - 1; i++) {
            if (SHIPPING_SEQUENCE[i].equalsIgnoreCase(current)) return SHIPPING_SEQUENCE[i + 1];
        }
        return null;
    }

    public static String labelForShipping(String s) {
        if (s == null) return "Pending";
        switch (s.toUpperCase()) {
            case "PREPARING":  return "Seller preparing your order";
            case "SHIPPED":    return "Package shipped";
            case "IN_TRANSIT": return "Out for delivery";
            case "DELIVERED":  return "Delivered";
            default:           return s;
        }
    }

    /** Marks a PAID+DELIVERED order COMPLETED (buyer confirms receipt). Blocked while a refund is pending. */
    public boolean confirmReceipt(long orderId, int buyerId) {
        String sql = "UPDATE orders SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND buyer_id = ? AND status = 'PAID' "
                + "AND UPPER(COALESCE(shipping_status, '')) = 'DELIVERED' "
                + "AND COALESCE(refund_status, '') IN ('', 'REJECTED')";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setInt(2, buyerId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public enum RefundDecision { SUCCESS, NOT_FOUND, NOT_REQUESTED }

    /**
     * Seller approves or declines a pending refund request.
     * Approve → order CANCELLED + refund_status APPROVED; Decline → refund_status REJECTED
     * (order stays PAID so the normal flow can resume).
     */
    public RefundDecision resolveRefund(long orderId, int sellerId, boolean approve) {
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            String refundStatus;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT refund_status FROM orders WHERE id = ? AND seller_id = ? FOR UPDATE")) {
                ps.setLong(1, orderId);
                ps.setInt(2, sellerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return RefundDecision.NOT_FOUND; }
                    refundStatus = rs.getString("refund_status");
                }
            }
            if (!"REQUESTED".equals(refundStatus)) { conn.rollback(); return RefundDecision.NOT_REQUESTED; }

            String update = approve
                    ? "UPDATE orders SET refund_status = 'APPROVED', status = 'CANCELLED', refund_resolved_at = CURRENT_TIMESTAMP WHERE id = ?"
                    : "UPDATE orders SET refund_status = 'REJECTED', refund_resolved_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setLong(1, orderId);
                ps.executeUpdate();
            }
            conn.commit();
            return RefundDecision.SUCCESS;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public enum RefundResult { SUCCESS, NOT_FOUND, NOT_BUYER, NOT_ELIGIBLE, ALREADY_REQUESTED }

    /** Buyer requests a refund on a paid order that is not yet completed. */
    public RefundResult requestRefund(long orderId, int buyerId, String reason) {
        if (reason == null || reason.isBlank()) return RefundResult.NOT_ELIGIBLE;
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            String status, refundStatus;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, refund_status FROM orders WHERE id = ? AND buyer_id = ? FOR UPDATE")) {
                ps.setLong(1, orderId);
                ps.setInt(2, buyerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return RefundResult.NOT_FOUND; }
                    status = rs.getString("status");
                    refundStatus = rs.getString("refund_status");
                }
            }
            if (!"PAID".equals(status)) { conn.rollback(); return RefundResult.NOT_ELIGIBLE; }
            if (refundStatus != null && !refundStatus.isBlank()) {
                conn.rollback();
                return RefundResult.ALREADY_REQUESTED;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE orders SET refund_status = 'REQUESTED', refund_reason = ?, "
                    + "refund_requested_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                ps.setString(1, reason.trim());
                ps.setLong(2, orderId);
                ps.executeUpdate();
            }
            conn.commit();
            return RefundResult.SUCCESS;
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

    /**
     * Creates a {@code PENDING_PAYMENT} order when an auction already has a winner but no order row.
     * Idempotent — no-op if an order exists or the auction has no winner yet.
     */
    public void ensureOrderForAuction(Connection conn, long auctionId) throws SQLException {
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT 1 FROM orders WHERE auction_id = ?")) {
            check.setLong(1, auctionId);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) return;
            }
        }

        int winnerId, sellerId;
        BigDecimal amount;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT a.seller_id, d.winner_id, d.winning_bid "
              + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
              + "WHERE a.auction_id = ?")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                sellerId = rs.getInt("seller_id");
                winnerId = rs.getInt("winner_id");
                if (rs.wasNull() || winnerId <= 0) return;
                amount = rs.getBigDecimal("winning_bid");
                if (amount == null) return;
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (auction_id, buyer_id, seller_id, amount, status) "
              + "VALUES (?, ?, ?, ?, 'PENDING_PAYMENT')")) {
            ps.setLong(1, auctionId);
            ps.setInt(2, winnerId);
            ps.setInt(3, sellerId);
            ps.setBigDecimal(4, amount);
            ps.executeUpdate();
        }
    }

    /** Backfill order rows for finalized auctions that have a winner but were never declared via {@link #declareWinner}. */
    private void syncMissingOrdersForUser(int userId) {
        String sql =
            "INSERT INTO orders (auction_id, buyer_id, seller_id, amount, status) "
          + "SELECT a.auction_id, d.winner_id, a.seller_id, d.winning_bid, 'PENDING_PAYMENT' "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "WHERE d.winner_id IS NOT NULL AND d.winning_bid IS NOT NULL "
          + "AND (a.seller_id = ? OR d.winner_id = ?) "
          + "AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.auction_id = a.auction_id)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** All orders where the user is buyer or seller, newest first, framed for that user. */
    public List<Order> listForUser(int userId) {
        syncMissingOrdersForUser(userId);
        String sql =
            "SELECT o.id, o.auction_id, d.title, o.buyer_id, o.seller_id, o.amount, o.status, "
          + "  o.created_at, o.paid_at, o.completed_at, o.shipping_status, o.shipping_updated_at, "
          + "  o.refund_status, o.refund_reason, o.refund_requested_at, "
          + "  bu.username AS buyer_name, su.username AS seller_name, "
          + "  EXISTS (SELECT 1 FROM user_reviews ur WHERE ur.auction_id = o.auction_id AND ur.reviewer_user_id = ?) AS has_rated "
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
            ps.setInt(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long buyerId = rs.getLong("buyer_id");
                    boolean isBuyer = buyerId == userId;
                    String role = isBuyer ? "buyer" : "seller";
                    String counterparty = isBuyer ? rs.getString("seller_name") : rs.getString("buyer_name");
                    out.add(mapOrderRow(rs, role, counterparty));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** All platform orders for the admin console (newest first). */
    public List<Order> listAllForAdmin() {
        String sql =
            "SELECT o.id, o.auction_id, d.title, o.buyer_id, o.seller_id, o.amount, o.status, "
          + "  o.created_at, o.paid_at, o.completed_at, o.shipping_status, o.shipping_updated_at, "
          + "  o.refund_status, o.refund_reason, o.refund_requested_at, "
          + "  bu.username AS buyer_name, su.username AS seller_name, false AS has_rated "
          + "FROM orders o "
          + "JOIN auction_details d ON d.id = o.auction_id "
          + "JOIN users bu ON bu.id = o.buyer_id "
          + "JOIN users su ON su.id = o.seller_id "
          + "ORDER BY o.created_at DESC";
        List<Order> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapOrderRow(rs, "admin",
                        rs.getString("buyer_name") + " → " + rs.getString("seller_name"), false));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public boolean isDelivered(long orderId) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM orders WHERE id = ? AND UPPER(COALESCE(shipping_status, '')) = 'DELIVERED'")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isOrderCompleted(long auctionId) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM orders WHERE auction_id = ? AND status = 'COMPLETED'")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasUserRatedAuction(long auctionId, int userId) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM user_reviews WHERE auction_id = ? AND reviewer_user_id = ?")) {
            ps.setLong(1, auctionId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Order mapOrderRow(ResultSet rs, String role, String counterparty) throws SQLException {
        return mapOrderRow(rs, role, counterparty, rs.getBoolean("has_rated"));
    }

    private static Order mapOrderRow(ResultSet rs, String role, String counterparty, boolean hasRated) throws SQLException {
        return new Order(
                rs.getLong("id"),
                rs.getLong("auction_id"),
                rs.getString("title"),
                rs.getLong("buyer_id"),
                rs.getLong("seller_id"),
                rs.getBigDecimal("amount"),
                rs.getString("status"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("paid_at")),
                instant(rs.getTimestamp("completed_at")),
                role,
                counterparty,
                rs.getString("shipping_status"),
                instant(rs.getTimestamp("shipping_updated_at")),
                hasRated,
                rs.getString("refund_status"),
                rs.getString("refund_reason"),
                instant(rs.getTimestamp("refund_requested_at")));
    }

    private static Instant instant(Timestamp ts) { return ts != null ? ts.toInstant() : null; }
}
