package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Data-access layer for seller-rates-buyer reviews.
 *
 * <p><b>One rating per auction:</b> The {@code UNIQUE (auction_id, reviewer_user_id)}
 * constraint on {@code user_reviews} prevents a seller from submitting a second rating
 * for the same auction. A pre-check is done first so the servlet receives a clean
 * {@link SellerRatingResult} rather than a raw constraint-violation exception.</p>
 *
 * <p><b>Auction ownership:</b> {@link #insertSellerRating} reads {@code auction.seller_id}
 * from the DB and compares it with the caller's {@code sellerId}. A mismatch returns
 * {@link SellerRatingResult#NOT_AUCTION_OWNER}, which the servlet maps to 403.</p>
 *
 * <p><b>IDOR prevention:</b> The buyer being rated ({@code reviewee_user_id}) is resolved
 * from {@code auction_details.winner_id} inside the transaction — never taken from the
 * request.</p>
 */
public class ReviewDAO {

    /** Outcome codes returned by {@link #insertSellerRating}. */
    public enum SellerRatingResult {
        SUCCESS,
        AUCTION_NOT_FOUND,
        /** Auction status is not FINISHED. */
        AUCTION_NOT_FINISHED,
        /** The session seller does not own this auction. */
        NOT_AUCTION_OWNER,
        /** The auction has no winner yet (winner_id is NULL). */
        NO_WINNER,
        /** A rating from this seller for this auction already exists. */
        ALREADY_RATED,
        /** The order for this auction is not yet marked complete. */
        ORDER_NOT_COMPLETED
    }

    /**
     * Inserts a 1–5 star rating for the winning buyer of a finished auction.
     *
     * <p>All preconditions (status, ownership, winner, duplicate) are verified within
     * a single transaction so every DB read is consistent with the eventual insert.</p>
     *
     * @param auctionId auction the seller owns (parsed as {@code long} by the servlet)
     * @param sellerId  seller submitting the rating (read from session, never from request)
     * @param score     star score; must be 1–5 (validated by servlet before this call)
     */
    public SellerRatingResult insertSellerRating(long auctionId, int sellerId, int score, String comment) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            // Promote a time-expired auction to FINISHED (+ resolve winner) so the
            // status/winner checks below see consistent state. No-op if already final.
            com.auction.util.AuctionFinalizer.FinalizeResult finalizeResult =
                    com.auction.util.AuctionFinalizer.finalizeIfEnded(conn, auctionId);

            String selectSql =
                    "SELECT a.status_id, a.seller_id, d.winner_id "
                    + "FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.auction_id = ?";

            int statusId;
            int dbSellerId;
            Integer winnerId;

            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return SellerRatingResult.AUCTION_NOT_FOUND;
                    }
                    statusId  = rs.getInt("status_id");
                    dbSellerId = rs.getInt("seller_id");
                    int w = rs.getInt("winner_id");
                    winnerId = rs.wasNull() ? null : w;
                }
            }

            if (statusId != AuctionStatus.FINISHED.getId()) {
                conn.rollback();
                return SellerRatingResult.AUCTION_NOT_FINISHED;
            }
            if (dbSellerId != sellerId) {
                conn.rollback();
                return SellerRatingResult.NOT_AUCTION_OWNER;
            }
            if (winnerId == null) {
                conn.rollback();
                return SellerRatingResult.NO_WINNER;
            }

            if (!isOrderCompleted(conn, auctionId)) {
                conn.rollback();
                return SellerRatingResult.ORDER_NOT_COMPLETED;
            }

            // Friendly duplicate check before hitting the UNIQUE constraint
            String existsSql =
                    "SELECT 1 FROM user_reviews "
                    + "WHERE reviewer_user_id = ? AND auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(existsSql)) {
                ps.setInt(1, sellerId);
                ps.setLong(2, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return SellerRatingResult.ALREADY_RATED;
                    }
                }
            }

            // buyerId resolved from winner_id — never from the request (IDOR prevention)
            String insertSql =
                    "INSERT INTO user_reviews "
                    + "(reviewer_user_id, reviewee_user_id, auction_id, rating, comment) "
                    + "VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setInt(1, sellerId);
                ps.setInt(2, winnerId);
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
            return SellerRatingResult.SUCCESS;

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

    public enum BuyerRatingResult {
        SUCCESS,
        AUCTION_NOT_FOUND,
        /** Auction status is not FINISHED. */
        AUCTION_NOT_FINISHED,
        /** The session seller does not own this auction. */
        NOT_AUCTION_OWNER,
        /** The auction has no winner yet (winner_id is NULL). */
        NO_WINNER,
        /** A rating from this seller for this auction already exists. */
        ALREADY_RATED
    }

    public BuyerRatingResult insertBuyerRating(long auctionId, int buyerId, int score) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            String selectSql =
                    "SELECT a.status_id, a.buyer_id, d.seller_id "
                            + "FROM auction a "
                            + "JOIN auction_details d ON d.id = a.auction_id "
                            + "WHERE a.auction_id = ?";

            int statusId;
            int dbBuyerId;
            Integer winnerId;

            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return BuyerRatingResult.AUCTION_NOT_FOUND;
                    }
                    statusId  = rs.getInt("status_id");
                    dbBuyerId = rs.getInt("buyer_id");
                    int w = rs.getInt("winner_id");
                    winnerId = rs.wasNull() ? null : w;
                }
            }

            if (statusId != AuctionStatus.FINISHED.getId()) {
                conn.rollback();
                return BuyerRatingResult.AUCTION_NOT_FINISHED;
            }
            if (dbBuyerId != buyerId) {
                conn.rollback();
                return BuyerRatingResult.NOT_AUCTION_OWNER;
            }
            if (winnerId == null) {
                conn.rollback();
                return BuyerRatingResult.NO_WINNER;
            }

            // Friendly duplicate check before hitting the UNIQUE constraint
            String existsSql =
                    "SELECT 1 FROM user_reviews "
                            + "WHERE reviewer_user_id = ? AND auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(existsSql)) {
                ps.setInt(1, buyerId);
                ps.setLong(2, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return BuyerRatingResult.ALREADY_RATED;
                    }
                }
            }

            // buyerId resolved from winner_id — never from the request (IDOR prevention)
            String insertSql =
                    "INSERT INTO user_reviews "
                            + "(reviewer_user_id, reviewee_user_id, auction_id, rating) "
                            + "VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setInt(1, buyerId);
                ps.setInt(2, winnerId);
                ps.setLong(3, auctionId);
                ps.setInt(4, score);
                ps.executeUpdate();
            }

            conn.commit();
            return BuyerRatingResult.SUCCESS;

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

}
