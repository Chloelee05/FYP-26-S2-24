package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.model.AuctionType;
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
 * DB inside the transaction, never from the request, and rejects adds where
 * the watcher is the auction seller.</p>
 *
 * <p><b>IDOR prevention:</b> {@code auctionId} is parsed as {@code long} by the
 * servlet. The seller's identity is always resolved from the DB, never trusted
 * from request parameters.</p>
 *
 * <p>Reads and writes {@code watchlist}, and reads {@code auction},
 * {@code auction_details}, {@code bids} and {@code auction_images} to render the watchlist page.
 * Called by the watchlist API servlet and by the ending-soon notification job. The listing query
 * carries the blind-auction price guard.</p>
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

            // Resolve seller_id server-side (IDOR prevention). Reading it inside the same
            // transaction as the insert is what makes the own-auction check meaningful; taking a
            // seller id from the request would let a caller simply claim to be someone else.
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

            // Friendly duplicate check before hitting the UNIQUE constraint, so the servlet can
            // return ALREADY_WATCHING instead of surfacing a raw SQL constraint violation.
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
     *         not exist. No exception is thrown, so the caller can treat a repeated
     *         unwatch as a no-op.
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
     *
     * <p>Each row is one watchlist card: the auction's title and status, when it was watched, the
     * price to display, how many bids it has drawn and a thumbnail. The LEFT JOIN onto
     * {@code bids} plus GROUP BY is what lets a watched auction with no bids still appear, with
     * MAX and COUNT falling back to the starting price and zero.</p>
     */
    public List<WatchlistRow> listByUser(int userId) {
        String sql =
                "SELECT a.auction_id, ad.title, a.status_id, w.added_at, a.date_end, "
                // A blind auction that is still running resolves to its entry price, the same
                // guard SearchDAO, RecommendationDAO and FeaturedListingDAO apply. Watchlisting
                // a sealed listing is otherwise a way to read its leading bid: watch it, read
                // the top bid off this page, then outbid it by a dollar. Unlike those three
                // projections this query also returns concluded auctions, where the winning bid
                // is public, so the guard is conditional on the listing still being open.
                + "CASE WHEN a.auction_type = " + AuctionType.BLIND.getId()
                + "       AND a.status_id = " + AuctionStatus.ACTIVE.getId()
                + "       AND a.date_end > CURRENT_TIMESTAMP THEN ad.starting_price "
                + "     ELSE COALESCE(MAX(b.bid_amount), ad.starting_price) END AS current_bid, "
                + "COUNT(b.bid_id) AS bid_count, "
                + "(SELECT i.image_url FROM auction_images i WHERE i.auction_id = a.auction_id "
                + " ORDER BY i.id LIMIT 1) AS thumbnail_url "
                + "FROM watchlist w "
                + "JOIN auction a ON a.auction_id = w.auction_id "
                + "JOIN auction_details ad ON ad.id = a.auction_id "
                + "LEFT JOIN bids b ON b.auction_id = a.auction_id "
                + "WHERE w.user_id = ? "
                + "GROUP BY a.auction_id, a.auction_type, ad.title, a.status_id, w.added_at, "
                + "         a.date_end, ad.starting_price "
                + "ORDER BY w.added_at DESC";
        List<WatchlistRow> rows = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("date_end");
                    Instant endDate = ts != null ? ts.toInstant() : null;
                    // added_at is TIMESTAMPTZ, so it is read as a Timestamp and then converted,
                    // because the PG driver cannot map TIMESTAMPTZ directly to LocalDateTime.
                    Timestamp addedTs = rs.getTimestamp("added_at");
                    Instant addedAt = addedTs != null ? addedTs.toInstant() : null;
                    rows.add(new WatchlistRow(
                            rs.getLong("auction_id"),
                            rs.getString("title"),
                            rs.getInt("status_id"),
                            addedAt,
                            rs.getBigDecimal("current_bid"),
                            endDate,
                            rs.getInt("bid_count"),
                            rs.getString("thumbnail_url")));
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

    /**
     * Watchlist entries whose auction closes within the next two hours, used by the reminder job.
     *
     * <p>Returns one row per watcher per auction, so a popular listing yields several rows and each
     * watcher is notified individually. Suspended listings are excluded by the moderation filter.</p>
     */
    public List<WatchlistRow> getEndingSoonWatchlistItems() throws Exception {
        // The two-hour window is evaluated by the database with NOW(), so all application nodes
        // agree on which auctions count as ending soon.
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

    /**
     * Fans the ending-soon rows out into one in-app notification per watcher, each deep-linking to
     * the auction. Delegates the actual insert to {@link NotificationDAO}.
     */
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
