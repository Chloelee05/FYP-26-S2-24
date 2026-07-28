package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Data-access layer for seller ratings (SCRUM-78).
 *
 * <p><b>One rating per auction:</b> Enforced by the {@code UNIQUE (auction_id, reviewer_user_id)}
 * constraint on {@code user_reviews}. {@link #existsByBuyerAndAuction} is checked first so the
 * servlet receives a clean {@link RatingResult} rather than a raw constraint-violation exception.</p>
 *
 * <p><b>Winner verification:</b> {@link #insertRating} reads {@code auction_details.winner_id}
 * inside the same connection to confirm the rater is the auction winner before inserting.</p>
 *
 * <p><b>IDOR prevention:</b> {@code sellerId} is looked up from the DB — never taken from
 * the request — so a buyer cannot fabricate a rating against an arbitrary user.</p>
 */
public class RatingDAO {

    /** Outcome codes returned by {@link #insertRating}. */
    public enum RatingResult {
        SUCCESS,
        AUCTION_NOT_FOUND,
        /** Auction status is not FINISHED. */
        AUCTION_NOT_FINISHED,
        /** The rater is not the winning buyer of this auction. */
        NOT_WINNER,
        /** A rating from this buyer for this auction already exists. */
        ALREADY_RATED,
        /** The order for this auction is not yet marked complete. */
        ORDER_NOT_COMPLETED
    }

    // -------------------------------------------------------------------------
    // Insert rating
    // -------------------------------------------------------------------------

    /**
     * Inserts a 1–5 star rating for the seller of a finished auction.
     *
     * <p>All preconditions (status, winner, duplicate) are verified within a single
     * transaction so the {@code sellerId} read from the DB is always consistent with
     * the auction being rated.</p>
     *
     * @param auctionId auction the buyer won (parsed as {@code long} by the servlet)
     * @param raterId   buyer submitting the rating (read from session, never from request)
     * @param score     star score; must be 1–5 (validated by servlet before this call)
     */
    public RatingResult insertRating(long auctionId, int raterId, int score, String comment) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            // Promote a time-expired auction to FINISHED (+ resolve winner) so the
            // winner/status checks below see consistent state. No-op if already final.
            com.auction.util.AuctionFinalizer.FinalizeResult finalizeResult =
                    com.auction.util.AuctionFinalizer.finalizeIfEnded(conn, auctionId);

            String selectSql =
                    "SELECT a.status_id, a.seller_id, d.winner_id "
                    + "FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.auction_id = ?";

            int statusId;
            int sellerId;
            Integer winnerId;

            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return RatingResult.AUCTION_NOT_FOUND;
                    }
                    statusId = rs.getInt("status_id");
                    sellerId = rs.getInt("seller_id");
                    int w = rs.getInt("winner_id");
                    winnerId = rs.wasNull() ? null : w;
                }
            }

            if (statusId != AuctionStatus.FINISHED.getId()) {
                conn.rollback();
                return RatingResult.AUCTION_NOT_FINISHED;
            }
            if (winnerId == null || winnerId != raterId) {
                conn.rollback();
                return RatingResult.NOT_WINNER;
            }

            if (!isOrderCompleted(conn, auctionId)) {
                conn.rollback();
                return RatingResult.ORDER_NOT_COMPLETED;
            }

            // Friendly duplicate check before hitting the UNIQUE constraint
            String existsSql =
                    "SELECT 1 FROM user_reviews "
                    + "WHERE reviewer_user_id = ? AND auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(existsSql)) {
                ps.setInt(1, raterId);
                ps.setLong(2, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return RatingResult.ALREADY_RATED;
                    }
                }
            }

            String insertSql =
                    "INSERT INTO user_reviews "
                    + "(reviewer_user_id, reviewee_user_id, auction_id, rating, comment) "
                    + "VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setInt(1, raterId);
                ps.setInt(2, sellerId);
                ps.setLong(3, auctionId);
                ps.setInt(4, score);
                if (comment != null && !comment.isBlank()) {
                    ps.setString(5, comment);
                } else {
                    ps.setNull(5, java.sql.Types.VARCHAR);
                }
                ps.executeUpdate();
            }

            conn.commit();
            if (finalizeResult.finalized && finalizeResult.winnerId > 0) {
                com.auction.notification.NotificationService.notifyAuctionWonIfAbsent(auctionId, finalizeResult.winnerId);
            }
            return RatingResult.SUCCESS;

        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) { }
            }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException ignored) { }
            }
        }
    }

    private static boolean isOrderCompleted(Connection conn, long auctionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM orders WHERE auction_id = ? AND status = 'COMPLETED'")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    // -------------------------------------------------------------------------
    // Existence check
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given buyer has already submitted a rating for
     * this auction.
     */
    public boolean existsByBuyerAndAuction(int buyerId, long auctionId) {
        String sql =
                "SELECT 1 FROM user_reviews "
                + "WHERE reviewer_user_id = ? AND auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, buyerId);
            ps.setLong(2, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Average rating
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Edit / delete own review (SCRUM-79) + admin moderation (SCRUM-80)
    // -------------------------------------------------------------------------

    /** How long (hours) after posting a user may still edit or delete their review. */
    public static final int EDIT_WINDOW_HOURS = 24;

    /** Outcome codes for {@link #updateOwnReview} / {@link #deleteOwnReview}. */
    public enum ModifyResult { SUCCESS, NOT_FOUND, WINDOW_EXPIRED }

    /** Reviews written by the given user, newest first, with an {@code editable} flag. */
    public java.util.List<java.util.Map<String, Object>> listWrittenBy(int reviewerId) {
        String sql = "SELECT r.id, r.auction_id, r.rating, r.comment, r.created_at, "
                + "u.username AS reviewee_name, ad.title, "
                + "(r.created_at > NOW() - (" + EDIT_WINDOW_HOURS + " * INTERVAL '1 hour')) AS editable "
                + "FROM user_reviews r "
                + "JOIN users u ON u.id = r.reviewee_user_id "
                + "LEFT JOIN auction_details ad ON ad.id = r.auction_id "
                + "WHERE r.reviewer_user_id = ? "
                + "ORDER BY r.created_at DESC";
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, reviewerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("auctionId", rs.getObject("auction_id"));
                    m.put("auctionTitle", rs.getString("title"));
                    m.put("revieweeName", rs.getString("reviewee_name"));
                    m.put("rating", rs.getInt("rating"));
                    m.put("comment", rs.getString("comment"));
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    m.put("createdAt", ts != null ? ts.toInstant().toString() : null);
                    m.put("editable", rs.getBoolean("editable"));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Updates the caller's own review, only within {@link #EDIT_WINDOW_HOURS} of posting. */
    public ModifyResult updateOwnReview(long reviewId, int reviewerId, int score, String comment) {
        String update = "UPDATE user_reviews SET rating = ?, comment = ? "
                + "WHERE id = ? AND reviewer_user_id = ? "
                + "AND created_at > NOW() - (" + EDIT_WINDOW_HOURS + " * INTERVAL '1 hour')";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setInt(1, score);
            if (comment != null) ps.setString(2, comment); else ps.setNull(2, java.sql.Types.VARCHAR);
            ps.setLong(3, reviewId);
            ps.setInt(4, reviewerId);
            if (ps.executeUpdate() == 1) return ModifyResult.SUCCESS;
            return ownReviewExists(reviewId, reviewerId) ? ModifyResult.WINDOW_EXPIRED : ModifyResult.NOT_FOUND;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Deletes the caller's own review, only within {@link #EDIT_WINDOW_HOURS} of posting. */
    public ModifyResult deleteOwnReview(long reviewId, int reviewerId) {
        String delete = "DELETE FROM user_reviews WHERE id = ? AND reviewer_user_id = ? "
                + "AND created_at > NOW() - (" + EDIT_WINDOW_HOURS + " * INTERVAL '1 hour')";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(delete)) {
            ps.setLong(1, reviewId);
            ps.setInt(2, reviewerId);
            if (ps.executeUpdate() == 1) return ModifyResult.SUCCESS;
            return ownReviewExists(reviewId, reviewerId) ? ModifyResult.WINDOW_EXPIRED : ModifyResult.NOT_FOUND;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean ownReviewExists(long reviewId, int reviewerId) {
        String sql = "SELECT 1 FROM user_reviews WHERE id = ? AND reviewer_user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, reviewId);
            ps.setInt(2, reviewerId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            return false;
        }
    }

    /** All reviews for the admin moderation view, newest first. */
    public java.util.List<java.util.Map<String, Object>> listAllForAdmin(int limit) {
        String sql = "SELECT r.id, r.auction_id, r.rating, r.comment, r.created_at, "
                + "ru.username AS reviewer_name, tu.username AS reviewee_name, ad.title "
                + "FROM user_reviews r "
                + "JOIN users ru ON ru.id = r.reviewer_user_id "
                + "JOIN users tu ON tu.id = r.reviewee_user_id "
                + "LEFT JOIN auction_details ad ON ad.id = r.auction_id "
                + "ORDER BY r.created_at DESC LIMIT ?";
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("auctionId", rs.getObject("auction_id"));
                    m.put("auctionTitle", rs.getString("title"));
                    m.put("reviewerName", rs.getString("reviewer_name"));
                    m.put("revieweeName", rs.getString("reviewee_name"));
                    m.put("rating", rs.getInt("rating"));
                    m.put("comment", rs.getString("comment"));
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    m.put("createdAt", ts != null ? ts.toInstant().toString() : null);
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /**
     * Highly-rated reviews with real comments, for the public landing-page
     * testimonials section. Data comes straight from {@code user_reviews} so the
     * marketing content is never hand-written.
     */
    public java.util.List<java.util.Map<String, Object>> listTestimonials(int limit) {
        String sql = "SELECT r.rating, r.comment, r.created_at, "
                + "ru.username AS reviewer_name, ad.title "
                + "FROM user_reviews r "
                + "JOIN users ru ON ru.id = r.reviewer_user_id "
                + "LEFT JOIN auction_details ad ON ad.id = r.auction_id "
                + "WHERE r.rating >= 4 AND r.comment IS NOT NULL AND LENGTH(TRIM(r.comment)) >= 10 "
                + "ORDER BY r.rating DESC, r.created_at DESC LIMIT ?";
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("reviewerName", rs.getString("reviewer_name"));
                    m.put("auctionTitle", rs.getString("title"));
                    m.put("rating", rs.getInt("rating"));
                    m.put("comment", rs.getString("comment"));
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    m.put("createdAt", ts != null ? ts.toInstant().toString() : null);
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Admin removal of an inappropriate review (no time window). */
    public boolean adminDeleteReview(long reviewId) {
        String sql = "DELETE FROM user_reviews WHERE id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, reviewId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the average star rating for the given seller, or {@code 0.0} if
     * the seller has no ratings yet.
     */
    public double getAvgRating(int sellerId) {
        String sql = "SELECT AVG(rating) FROM user_reviews WHERE reviewee_user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double avg = rs.getDouble(1);
                    return rs.wasNull() ? 0.0 : avg;
                }
                return 0.0;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
