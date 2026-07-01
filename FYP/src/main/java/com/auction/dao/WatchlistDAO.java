package com.auction.dao;

import com.auction.model.Notification;
import com.auction.model.profile.WatchlistRow;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for the buyer watchlist.
 *
 * <p><b>One entry per buyer per auction:</b> Enforced by the
 * {@code UNIQUE (user_id, auction_id)} constraint on {@code watchlist}.
 * {@link #add} performs a pre-check so the servlet receives a clean
 * {@link WatchlistResult} rather than a raw constraint-violation exception.</p>
 *
 * <p><b>Own-auction guard:</b> {@link #add} resolves {@code seller_id} from the
 * DB inside the transaction — never from the request — and rejects adds where
 * the watcher is the auction seller.</p>
 *
 * <p><b>IDOR prevention:</b> {@code auctionId} is parsed as {@code long} by the
 * servlet. The seller's identity is always resolved from the DB, never trusted
 * from request parameters.</p>
 */
public class WatchlistDAO {

    /** Outcome codes returned by {@link #add}. */
    public enum WatchlistResult {
        SUCCESS,
        AUCTION_NOT_FOUND,
        /** The user is the seller of this auction. */
        OWN_AUCTION,
        /** This auction is already in the user's watchlist. */
        ALREADY_WATCHING
    }

    // -------------------------------------------------------------------------
    // Add
    // -------------------------------------------------------------------------

    /**
     * Adds an auction to the buyer's watchlist.
     *
     * <p>All preconditions (auction existence, own-auction, duplicate) are
     * verified within a single transaction so the {@code seller_id} read from
     * the DB is always consistent with the insert.</p>
     *
     * @param auctionId auction to watch (parsed as {@code long} by the servlet)
     * @param userId    buyer adding the item (read from session, never from request)
     */
    public WatchlistResult add(long auctionId, int userId) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            // Resolve seller_id server-side (IDOR prevention)
            String selectSql = "SELECT seller_id FROM auction WHERE auction_id = ?";
            int sellerId;
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return WatchlistResult.AUCTION_NOT_FOUND;
                    }
                    sellerId = rs.getInt("seller_id");
                }
            }

            if (sellerId == userId) {
                conn.rollback();
                return WatchlistResult.OWN_AUCTION;
            }

            // Friendly duplicate check before hitting the UNIQUE constraint
            String existsSql =
                    "SELECT 1 FROM watchlist WHERE user_id = ? AND auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(existsSql)) {
                ps.setInt(1, userId);
                ps.setLong(2, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return WatchlistResult.ALREADY_WATCHING;
                    }
                }
            }

            String insertSql =
                    "INSERT INTO watchlist (user_id, auction_id) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setInt(1, userId);
                ps.setLong(2, auctionId);
                ps.executeUpdate();
            }

            conn.commit();
            return WatchlistResult.SUCCESS;

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

    // -------------------------------------------------------------------------
    // Remove
    // -------------------------------------------------------------------------

    /**
     * Removes an auction from the buyer's watchlist.
     *
     * @return {@code true} if a row was deleted; {@code false} if the entry did
     *         not exist (caller should handle gracefully — no exception is thrown)
     */
    public boolean remove(long auctionId, int userId) {
        String sql = "DELETE FROM watchlist WHERE user_id = ? AND auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setLong(2, auctionId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // List
    // -------------------------------------------------------------------------

    /**
     * Returns all watchlist entries for the given user, ordered by most-recently
     * added first.
     */
    public List<WatchlistRow> listByUser(int userId) {
        String sql =
                "SELECT a.auction_id, ad.title, a.status_id, w.added_at, a.date_end, "
                + "COALESCE(MAX(b.bid_amount), ad.starting_price) AS current_bid, "
                + "COUNT(b.bid_id) AS bid_count "
                + "FROM watchlist w "
                + "JOIN auction a ON a.auction_id = w.auction_id "
                + "JOIN auction_details ad ON ad.id = a.auction_id "
                + "LEFT JOIN bids b ON b.auction_id = a.auction_id "
                + "WHERE w.user_id = ? "
                + "GROUP BY a.auction_id, ad.title, a.status_id, w.added_at, a.date_end, ad.starting_price "
                + "ORDER BY w.added_at DESC";
        List<WatchlistRow> rows = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("date_end");
                    Instant endDate = ts != null ? ts.toInstant() : null;
                    // added_at is TIMESTAMPTZ — read as Timestamp then convert, since the
                    // PG driver cannot map TIMESTAMPTZ directly to LocalDateTime.
                    Timestamp addedTs = rs.getTimestamp("added_at");
                    Instant addedAt = addedTs != null ? addedTs.toInstant() : null;
                    rows.add(new WatchlistRow(
                            rs.getLong("auction_id"),
                            rs.getString("title"),
                            rs.getInt("status_id"),
                            addedAt,
                            rs.getBigDecimal("current_bid"),
                            endDate,
                            rs.getInt("bid_count")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // Existence check
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given user has the given auction in their
     * watchlist.
     */
    public boolean existsByUserAndAuction(int userId, long auctionId) {
        String sql =
                "SELECT 1 FROM watchlist WHERE user_id = ? AND auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setLong(2, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<WatchlistRow> getEndingSoonWatchlistItems() throws Exception {
        String sql = "SELECT w.user_id, w.auction_id, ad.title, a.date_end " +
                "FROM watchlist w " +
                "JOIN auction a ON w.auction_id = a.auction_id " +
                "JOIN auction_details ad ON a.auction_id = ad.id " +
                "WHERE a.date_end BETWEEN NOW() AND NOW() + INTERVAL '2 hours' " +
                "AND a.moderation_state = 'active'";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<WatchlistRow> result = new ArrayList<>();
            while (rs.next()) {
                WatchlistRow alert = new WatchlistRow();
                alert.setUserId(rs.getInt("user_id"));
                alert.setAuctionId(rs.getLong("auction_id"));
                alert.setTitle(rs.getString("title"));
                alert.setEndDate(rs.getTimestamp("date_end").toInstant());
                result.add(alert);
            }
            return result;
        } catch (Exception e) {
            throw new Exception("Failed to retrieve ending soon watchlist items", e);
        }
    }

    private void sendNotification(List<WatchlistRow> endingSoon) throws Exception
    {
        try {
            NotificationDAO notificationDAO = new NotificationDAO();
            for (WatchlistRow each : endingSoon) {
                notificationDAO.create(each.getUserId(), each.getTitle(), String.valueOf(each.getAuctionId()),
                        "/auction/" + each.getAuctionId());
            }
        } catch (Exception e) {
            throw new Exception("Error creating notifications", e);
        }
    }
}
