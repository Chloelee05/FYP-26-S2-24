package com.auction.dao;

import com.auction.model.SellerPublicProfile;
import com.auction.model.profile.ProfileReviewRow;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for the public seller profile page (SCRUM-63).
 *
 * <p>Only users with the Seller role and Active status are returned by
 * {@link #getPublicProfile(long)} — buyers/admins/suspended accounts yield {@code null}
 * so the servlet can respond with 404 without leaking role information.</p>
 *
 * <p>Reviewer names in {@link #getReviews(long, int, int)} are masked; raw emails and
 * encrypted PII columns are never selected.</p>
 */
public class SellerProfileDAO {

    /** Seller role_id in {@code roles} table (matches {@link com.auction.model.Role#SELLER}). */
    static final int SELLER_ROLE_ID = 3;

    /** Active status_id in {@code user_status} table. */
    static final int ACTIVE_STATUS_ID = 1;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Average rating + review count for a seller. */
    public static final class AvgRating {
        private final double average;
        private final int count;

        public AvgRating(double average, int count) {
            this.average = average;
            this.count = count;
        }

        public double getAverage() { return average; }
        public int getCount() { return count; }
    }

    /**
     * Loads the public seller profile, or {@code null} when the user is not an active seller.
     *
     * @param sellerId parsed seller id from URL path
     */
    public SellerPublicProfile getPublicProfile(long sellerId) {
        String sql =
                "SELECT u.id, u.username, u.email, u.date_created, u.profile_image_url, "
                + "       (SELECT COUNT(*)::int FROM auction a "
                + "        WHERE a.seller_id = u.id "
                + "          AND a.moderation_state = 'active' "
                + "          AND a.date_end > CURRENT_TIMESTAMP) AS active_listings "
                + "FROM users u "
                + "WHERE u.id = ? "
                + "  AND u.role_id = ? "
                + "  AND u.status_id = ?";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            ps.setInt(2, SELLER_ROLE_ID);
            ps.setInt(3, ACTIVE_STATUS_ID);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                Timestamp created = rs.getTimestamp("date_created");
                String rawEmail = rs.getString("email");
                return new SellerPublicProfile(
                        rs.getLong("id"),
                        rs.getString("username"),
                        SecurityUtil.maskEmail(rawEmail),
                        created != null ? created.toInstant() : null,
                        rs.getString("profile_image_url"),
                        rs.getInt("active_listings"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the average star rating and total review count for a seller.
     *
     * @param sellerId seller user id
     */
    public AvgRating getAvgRating(long sellerId) {
        String sql =
                "SELECT COALESCE(AVG(rating), 0) AS avg_rating, COUNT(*)::int AS review_count "
                + "FROM user_reviews WHERE reviewee_user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double avg = rs.getDouble("avg_rating");
                    int count = rs.getInt("review_count");
                    if (count > 0) {
                        avg = BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP).doubleValue();
                    }
                    return new AvgRating(avg, count);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new AvgRating(0, 0);
    }

    /**
     * Returns a paginated page of reviews received by the seller, newest first.
     * Reviewer usernames are masked; comments are stored sanitized at insert time.
     *
     * @param sellerId seller user id
     * @param page     1-based page number
     * @param pageSize rows per page
     */
    public List<ProfileReviewRow> getReviews(long sellerId, int page, int pageSize) {
        String sql =
                "SELECT r.rating, r.comment, r.created_at, u.username, d.title AS item_title "
                + "FROM user_reviews r "
                + "JOIN users u ON u.id = r.reviewer_user_id "
                + "LEFT JOIN auction_details d ON d.id = r.auction_id "
                + "WHERE r.reviewee_user_id = ? "
                + "ORDER BY r.created_at DESC "
                + "LIMIT ? OFFSET ?";

        List<ProfileReviewRow> list = new ArrayList<>();
        int offset = pageSize * (page - 1);
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            ps.setInt(2, pageSize);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapReviewRow(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /** Total review count for pagination (same filter as {@link #getReviews}). */
    public int countReviews(long sellerId) {
        String sql = "SELECT COUNT(*)::int FROM user_reviews WHERE reviewee_user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    private static ProfileReviewRow mapReviewRow(ResultSet rs) throws Exception {
        Timestamp ts = rs.getTimestamp("created_at");
        LocalDate d = ts == null ? LocalDate.now() : ts.toInstant().atZone(ZONE).toLocalDate();
        String comment = rs.getString("comment");
        if (comment == null) comment = "";
        return new ProfileReviewRow(
                SecurityUtil.maskUsername(rs.getString("username")),
                rs.getInt("rating"),
                comment,
                d,
                rs.getString("item_title"));
    }
}
