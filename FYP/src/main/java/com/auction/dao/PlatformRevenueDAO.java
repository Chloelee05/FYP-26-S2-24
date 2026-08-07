package com.auction.dao;

import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Records platform revenue from sale commissions and featured listing fees.
 *
 * <p>Reads {@code orders} to price a commission and writes to {@code platform_revenue}. Called by
 * the order API when an order reaches COMPLETED, and by the featured-listing flow. Every write is
 * idempotent through a unique constraint on (order_id, revenue_type), because order completion can
 * be retried and the platform must not bill the same sale twice.</p>
 */
public class PlatformRevenueDAO {

    /** 6% of the sale price, the platform's cut of a completed order. */
    public static final BigDecimal COMMISSION_RATE = new BigDecimal("0.06");
    /** Flat fee a seller pays to promote one listing. There is no billing UI; this is recorded only. */
    public static final BigDecimal FEATURED_LISTING_FEE = new BigDecimal("9.99");

    /**
     * Idempotent commission record for a completed order.
     *
     * <p>Re-reads the order rather than trusting a caller-supplied amount, and refuses anything not
     * in COMPLETED status, so commission is only ever taken on a sale that actually settled.</p>
     *
     * @return true if a new row was inserted
     */
    public boolean recordCommission(long orderId) {
        String fetch =
            "SELECT o.amount, o.seller_id, o.auction_id, o.status "
          + "FROM orders o WHERE o.id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(fetch)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                if (!"COMPLETED".equalsIgnoreCase(rs.getString("status"))) return false;

                BigDecimal amount = rs.getBigDecimal("amount");
                int sellerId = rs.getInt("seller_id");
                long auctionId = rs.getLong("auction_id");
                if (amount == null || amount.signum() <= 0) return false;

                BigDecimal commission = amount.multiply(COMMISSION_RATE)
                        .setScale(2, RoundingMode.HALF_UP);
                // rate_pct is stored as a percentage (6.00), not a fraction, so the admin revenue
                // report can display it without converting. movePointRight(2) does that shift.
                return insert(conn, "COMMISSION", orderId, auctionId, sellerId, commission, COMMISSION_RATE.movePointRight(2));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Bills the flat promotion fee against a listing. Has no order, so order_id is left null. */
    public boolean recordFeaturedListing(long auctionId, int sellerId) {
        try (Connection conn = DBUtil.connectDB()) {
            return insert(conn, "FEATURED_LISTING", null, auctionId, sellerId,
                    FEATURED_LISTING_FEE, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Total revenue booked under one type, for the admin dashboard.
     *
     * @param revenueType either COMMISSION or FEATURED_LISTING
     */
    public BigDecimal sumByType(String revenueType) {
        // SUM over no rows is NULL, so COALESCE turns an empty ledger into 0 and the caller never
        // has to handle a null total.
        String sql = "SELECT COALESCE(SUM(amount), 0) FROM platform_revenue WHERE revenue_type = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, revenueType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Shared insert for both revenue types. ON CONFLICT DO NOTHING makes the write idempotent, so a
     * replayed order completion returns false instead of double-booking revenue.
     */
    private boolean insert(Connection conn, String type, Long orderId, long auctionId,
                           int sellerId, BigDecimal amount, BigDecimal ratePct) throws Exception {
        String sql =
            "INSERT INTO platform_revenue (revenue_type, order_id, auction_id, seller_id, amount, rate_pct) "
          + "VALUES (?, ?, ?, ?, ?, ?) "
          + "ON CONFLICT (order_id, revenue_type) DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            if (orderId != null) ps.setLong(2, orderId);
            else ps.setNull(2, java.sql.Types.BIGINT);
            ps.setLong(3, auctionId);
            ps.setInt(4, sellerId);
            ps.setBigDecimal(5, amount);
            if (ratePct != null) ps.setBigDecimal(6, ratePct);
            else ps.setNull(6, java.sql.Types.NUMERIC);
            return ps.executeUpdate() == 1;
        }
    }
}
