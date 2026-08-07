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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orders represent the simulated post-auction transaction (payment + fulfilment).
 * Created when a seller declares the winner of an ended auction.
 *
 * <p>Owns the {@code orders} table and, while finalising, also writes {@code auction.status_id}
 * and {@code auction_details.winner_id}/{@code winning_bid}. Reads {@code bids} to find the
 * winner, plus {@code users}, {@code auction_images} and {@code user_reviews} for display.
 * Called by the order API, the seller dashboard and the scheduled payment-timeout job.</p>
 *
 * <p>Every state change that depends on the row's current state takes {@code SELECT ... FOR
 * UPDATE} first and re-checks the state in the UPDATE's WHERE clause, so two clicks or two
 * workers cannot both advance the same order.</p>
 */
public class OrderDAO {

    /** Why a declare attempt was refused, so the API can explain it instead of returning a 500. */
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
     *
     * <p>All of it happens in one transaction opened here, because a winner recorded without its
     * order, or an order without stock taken off the listing, leaves the sale half done. The
     * auction row is locked FOR UPDATE at the start so the background finalizer cannot conclude
     * the same auction at the same moment.</p>
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

            // Already has an order? One order per auction, so the presence of a row is the marker
            // that this auction was already concluded, whether by the seller or by the finalizer.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM orders WHERE auction_id = ?")) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) { conn.rollback(); return DeclareResult.fail(DeclareStatus.ALREADY_FINALIZED); }
                }
            }

            // Highest bid wins; an equal-valued tie goes to whoever bid first.
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

            // The CASE WHEN only rewrites date_end on an early close, so the listing's history shows
            // when it actually stopped taking bids. A normal close leaves the scheduled time alone.
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
                // Was setInt(amount.setScale(0, HALF_UP)), which rounded the cents away, and it
                // rounded where AuctionFinalizer truncated, so the same auction concluded at a
                // different figure depending on which path got there first. Both now write the
                // bid itself into the NUMERIC(12,2) column.
                ps.setBigDecimal(2, amount.setScale(2, RoundingMode.HALF_UP));
                ps.setLong(3, auctionId);
                ps.executeUpdate();
            }
            // One unit leaves stock on a conclusion. See SellerAuctionDAO.decrementStockForSale.
            // Runs on this connection so it commits or rolls back with the rest of the declare.
            SellerAuctionDAO.decrementStockForSale(conn, auctionId);

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

    /** The fulfilment steps in order. A seller can only move forward one step at a time. */
    private static final String[] SHIPPING_SEQUENCE = { "PREPARING", "SHIPPED", "IN_TRANSIT", "DELIVERED" };

    /**
     * Marks a PENDING_PAYMENT order PAID (simulated payment) for the owning buyer, and starts
     * fulfilment at PREPARING in the same statement. The buyer id and current status are both in
     * the WHERE clause, so someone else's order cannot be paid and a double submit updates
     * nothing the second time. Returns false in either of those cases.
     */
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

    /**
     * Cancels every {@code PENDING_PAYMENT} order whose {@code created_at} is older than
     * {@code deadline}. This is the auto-cancellation of an unpaid winning bid, an anti-abuse and
     * lifecycle feature.
     *
     * <p>Design decision, see also migration_order_payment_timeout.sql: a cancelled
     * order is <em>not</em> re-awarded to the next-highest bidder, nor is the auction
     * automatically relisted. The auction stays {@code FINISHED} with its existing
     * {@code winner_id}/{@code winning_bid} untouched, because who won the auction is a
     * historical fact, separate from whether the resulting order was ever paid. The listing
     * simply closes as unsold from a sale-completion point of view (the order row is the
     * source of truth for that: {@code status = 'CANCELLED'}), and the seller is told they
     * can relist, exactly as an auction that closes with zero bids already tells them. A
     * re-award flow was deliberately not built: it raises questions (what if the next
     * bidder also does not pay? how does it interact with Dutch/blind auctions, which have
     * no "next bidder" concept at all?) that are not worth the additional surface area this
     * close to a viva.</p>
     *
     * <p>Grandfathering: {@code effectiveSince} excludes every order created before
     * the feature's first migration apply. See the migration for why: a handful of orders
     * were already stuck in {@code PENDING_PAYMENT} indefinitely in the live database before
     * this shipped, and silently cancelling them the instant this deploys would be a surprise
     * ahead of a demo. They stay exactly as they are, forever, without needing their ids
     * hardcoded anywhere.</p>
     *
     * <p>Row-locked ({@code FOR UPDATE}) and the status re-checked in the {@code UPDATE}'s
     * {@code WHERE} clause, so an order that was paid in the instant between the SELECT and
     * the UPDATE can never be cancelled after all. There is no such instant today, since both
     * run in one transaction, but it is cheap insurance against a future caller reusing the
     * connection differently. A {@code PAID} order is never touched here, whatever its age.</p>
     *
     * @return the ids of the orders that were cancelled, for the caller to notify about
     */
    public List<Long> cancelOverduePendingOrders(Duration deadline, Instant effectiveSince) {
        Instant cutoff = Instant.now().minus(deadline);
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            // Two bounds: older than the payment deadline, but not older than the day the feature
            // went live. The second one is the grandfathering rule described above.
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' "
                  + "AND created_at < ? AND created_at >= ? FOR UPDATE")) {
                ps.setTimestamp(1, Timestamp.from(cutoff));
                ps.setTimestamp(2, Timestamp.from(effectiveSince));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(rs.getLong("id"));
                }
            }

            if (!ids.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE orders SET status = 'CANCELLED', cancel_reason = 'PAYMENT_TIMEOUT', "
                      + "cancelled_at = CURRENT_TIMESTAMP "
                      + "WHERE id = ? AND status = 'PENDING_PAYMENT'")) {
                    for (Long id : ids) {
                        ps.setLong(1, id);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            conn.commit();
            return ids;
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
            }
        }
    }

    /**
     * Advances shipping one step for the seller who owns the order, which must be PAID.
     * The seller id is part of the locking SELECT, so another seller's order reads as NOT_FOUND
     * rather than revealing that it exists.
     */
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

    /**
     * The step after {@code current}, or null once DELIVERED is reached, which is what tells
     * {@link #advanceShipping} there is nowhere left to go. A blank value starts at PREPARING.
     */
    private static String nextShipping(String current) {
        if (current == null || current.isBlank()) return SHIPPING_SEQUENCE[0];
        for (int i = 0; i < SHIPPING_SEQUENCE.length - 1; i++) {
            if (SHIPPING_SEQUENCE[i].equalsIgnoreCase(current)) return SHIPPING_SEQUENCE[i + 1];
        }
        return null;
    }

    /** Turns a stored shipping code into the wording shown to the buyer. */
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

    /**
     * Marks a PAID and DELIVERED order COMPLETED when the buyer confirms receipt.
     * Refused while a refund is outstanding, since completing the order would settle a sale the
     * buyer is currently disputing.
     */
    public boolean confirmReceipt(long orderId, int buyerId) {
        // Every precondition lives in the WHERE clause, so the whole check and the write are one
        // atomic statement. COALESCE on refund_status treats "never requested" as an empty string,
        // which lets a single IN test cover both that case and a refund the seller already refused.
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
     * Seller approves or declines a pending refund request on their own order.
     * Approving sets refund_status APPROVED and cancels the order. Declining sets REJECTED and
     * leaves the order PAID, so the buyer can still confirm receipt and the normal flow resumes.
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

    /**
     * Admin override for dispute resolution (SCRUM-70): approves or declines a pending
     * refund request on any order, regardless of seller. Same state transitions as
     * {@link #resolveRefund(long, int, boolean)}.
     */
    public RefundDecision adminResolveRefund(long orderId, boolean approve) {
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            String refundStatus;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT refund_status FROM orders WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, orderId);
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

    /**
     * Buyer requests a refund on a paid order that is not yet completed. A reason is required.
     * Any non-blank refund_status means a request was already made, including one the seller
     * rejected, so the buyer cannot reopen the same dispute by asking again.
     */
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
     * Idempotent: it does nothing if an order exists or the auction has no winner yet.
     *
     * <p>Runs on the caller's connection because the finalizer calls it inside the same transaction
     * that stamped the winner onto {@code auction_details}.</p>
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
                // getInt returns 0 for SQL NULL, so wasNull is what distinguishes "no winner
                // declared" from a real id. Either way there is nothing to create an order from.
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

    /**
     * Backfills order rows for finalized auctions that have a winner but were never declared via
     * {@link #declareWinner}. Called before listing a user's orders so a lazily finalized auction
     * shows up straight away rather than after the next background sweep.
     */
    private void syncMissingOrdersForUser(int userId) {
        // INSERT ... SELECT, so the rows are found and written in one statement. Scoped to
        // auctions this user is on either side of, and NOT EXISTS keeps it from duplicating an
        // order that is already there, which makes repeated page loads harmless.
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

    /**
     * All orders where the user is buyer or seller, newest first, framed for that user: each row
     * carries the role they played and the name of the other party, so one list serves both sides.
     */
    public List<Order> listForUser(int userId) {
        syncMissingOrdersForUser(userId);
        // users is joined twice to name both sides. The thumbnail comes from a correlated subquery
        // taking the listing's first image by id, and has_rated is an EXISTS test against
        // user_reviews so the UI knows whether to offer a rating link on that order.
        String sql =
            "SELECT o.id, o.auction_id, d.title, o.buyer_id, o.seller_id, o.amount, o.status, "
          + "  o.created_at, o.paid_at, o.completed_at, o.shipping_status, o.shipping_updated_at, "
          + "  o.refund_status, o.refund_reason, o.refund_requested_at, o.cancel_reason, "
          + "  bu.username AS buyer_name, su.username AS seller_name, "
          + "  (SELECT i.image_url FROM auction_images i WHERE i.auction_id = o.auction_id "
          + "   ORDER BY i.id LIMIT 1) AS thumbnail_url, "
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

    /**
     * Every order on the platform for the admin console, newest first. Same shape as
     * {@link #listForUser}, but has_rated is a constant false because an admin is not a party to
     * the sale and never sees a rating prompt.
     */
    public List<Order> listAllForAdmin() {
        String sql =
            "SELECT o.id, o.auction_id, d.title, o.buyer_id, o.seller_id, o.amount, o.status, "
          + "  o.created_at, o.paid_at, o.completed_at, o.shipping_status, o.shipping_updated_at, "
          + "  o.refund_status, o.refund_reason, o.refund_requested_at, o.cancel_reason, "
          + "  bu.username AS buyer_name, su.username AS seller_name, "
          + "  (SELECT i.image_url FROM auction_images i WHERE i.auction_id = o.auction_id "
          + "   ORDER BY i.id LIMIT 1) AS thumbnail_url, "
          + "  false AS has_rated "
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

    /** Count of fully settled orders, shown in the public platform statistics. */
    public int countCompletedOrders() {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The order's current shipping step, or null if it has none yet.
     *
     * <p>Read after {@link #advanceShipping} so the caller can say which step was reached,
     * rather than only whether it was the last one. Every step tells the buyer something.</p>
     */
    public String shippingStatusOf(long orderId) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT shipping_status FROM orders WHERE id = ?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@code true} once a winner has been declared for this auction, i.e. an order
     * exists. Declaring is a one-shot action, so the UI uses this to retire the
     * control rather than offer a button that can only answer "already finalised".
     */
    public boolean existsForAuction(long auctionId) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM orders WHERE auction_id = ?")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Whether this auction's order has settled. Rating a counterparty is gated on this, so a
     * buyer cannot review a seller before the transaction actually finished.
     */
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

    /** Whether this user already left a review on this auction. One review per person per sale. */
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

    /** Maps a row, taking the has_rated flag from the result set. */
    private static Order mapOrderRow(ResultSet rs, String role, String counterparty) throws SQLException {
        return mapOrderRow(rs, role, counterparty, rs.getBoolean("has_rated"));
    }

    /** Maps a row with hasRated supplied by the caller, which the admin listing sets to false. */
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
                instant(rs.getTimestamp("refund_requested_at")),
                rs.getString("thumbnail_url"),
                rs.getString("cancel_reason"));
    }

    /** Null-safe timestamp conversion; most of the lifecycle timestamps are null early on. */
    private static Instant instant(Timestamp ts) { return ts != null ? ts.toInstant() : null; }
}
