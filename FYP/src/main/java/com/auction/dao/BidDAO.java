package com.auction.dao;

import com.auction.model.AuctionBidHistoryEntry;
import com.auction.model.AuctionDetail;
import com.auction.model.AuctionStatus;
import com.auction.model.Bid;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for bid placement (SCRUM-51).
 *
 * <p><b>Transactional safety (SCRUM-263):</b> {@link #placeBid} opens a single
 * JDBC transaction, acquires a row-level lock on the {@code auction} row via
 * {@code SELECT … FOR UPDATE}, re-validates all preconditions inside the lock,
 * then inserts the bid or rolls back. This prevents TOCTOU races on concurrent
 * bids (SCRUM-265).</p>
 *
 * <p><b>Minimum increment (SCRUM-263):</b> A new bid must exceed the greater of the
 * current highest bid and the starting price. Because {@code bids.bid_amount} is
 * {@code NUMERIC(10,2)}, the effective minimum meaningful step is {@code 0.01}.
 * Equal bids are always rejected ({@code >}, not {@code >=}).</p>
 *
 * <p><b>Auto-bid integration (SCRUM-52):</b> After each successful manual bid insert,
 * {@link AutoBidDAO#processAutoBids(Connection, long)} is called within the same
 * transaction to fire any proxy counter-bids before the lock is released.</p>
 *
 * <p><b>Max-price cap (SCRUM-263):</b> The seller-set ceiling from
 * {@code auction_details.max_price} is re-checked inside the transaction.</p>
 *
 * <p><b>IDOR prevention (SCRUM-295):</b> {@code buyerId} is <em>always</em> taken
 * from the session (never from a request parameter); {@code auctionId} is parsed
 * as {@code long} (rejects non-numeric input) and then looked up in the DB.</p>
 */
public class BidDAO {

    /** Maximum page size for public bid history (SCRUM-58). */
    public static final int MAX_BID_HISTORY_PAGE_SIZE = 50;

    private final AutoBidDAO autoBidDAO;

    public BidDAO() {
        this.autoBidDAO = new AutoBidDAO();
    }

    /** Injection constructor for testing (allows mocking {@link AutoBidDAO}). */
    public BidDAO(AutoBidDAO autoBidDAO) {
        this.autoBidDAO = autoBidDAO;
    }

    /** Outcome codes returned by {@link #placeBid}. */
    public enum BidResult {
        SUCCESS,
        AUCTION_NOT_FOUND,
        /** Auction status is not ACTIVE, or end date has passed. */
        AUCTION_CLOSED,
        /** Auction moderation state is not {@code 'active'}. */
        AUCTION_REMOVED,
        /** The bidder is the seller of this auction (self-bid disallowed). */
        SELF_BID,
        /** Bid amount ≤ current floor (current highest bid or starting price). */
        BID_TOO_LOW,
        /** Bid amount exceeds the seller-set max-price cap. */
        EXCEEDS_MAX_PRICE
    }

    // -------------------------------------------------------------------------
    // Place bid (SCRUM-263 + SCRUM-265)
    // -------------------------------------------------------------------------

    /**
     * Atomically places a bid on the auction identified by {@code auctionId}.
     *
     * <p>All validations run inside a single serializable transaction protected by
     * {@code SELECT … FOR UPDATE} on the auction row — concurrent callers block
     * until the lock is released, preventing duplicate-amount bids (SCRUM-265).</p>
     *
     * @param auctionId  ID of the target auction (parsed server-side, not trusted from client)
     * @param buyerId    ID of the authenticated buyer (read from session, not from request)
     * @param bidAmount  proposed bid amount; must be positive
     * @return a {@link BidResult} indicating success or the specific rejection reason
     */
    public BidResult placeBid(long auctionId, int buyerId, BigDecimal bidAmount) {
        if (bidAmount == null || bidAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BidResult.BID_TOO_LOW;
        }

        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            // SCRUM-265: lock the auction row to serialize concurrent bids
            String lockSql =
                    "SELECT a.auction_id, a.status_id, a.date_end, "
                    + "a.moderation_state, a.seller_id, "
                    + "d.starting_price, d.max_price "
                    + "FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.auction_id = ? "
                    + "FOR UPDATE";

            int statusId;
            Instant dateEnd;
            String moderationState;
            int sellerId;
            BigDecimal startingPrice;
            BigDecimal maxPrice;

            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return BidResult.AUCTION_NOT_FOUND;
                    }
                    statusId = rs.getInt("status_id");
                    dateEnd = rs.getTimestamp("date_end").toInstant();
                    moderationState = rs.getString("moderation_state");
                    sellerId = rs.getInt("seller_id");
                    startingPrice = rs.getBigDecimal("starting_price");
                    if (startingPrice == null) startingPrice = BigDecimal.ZERO;
                    maxPrice = rs.getBigDecimal("max_price"); // null = no cap
                }
            }

            // SCRUM-263: auction must be ACTIVE and not expired
            if (statusId != AuctionStatus.ACTIVE.getId() || Instant.now().isAfter(dateEnd)) {
                conn.rollback();
                return BidResult.AUCTION_CLOSED;
            }
            // Moderation check
            if (!"active".equals(moderationState)) {
                conn.rollback();
                return BidResult.AUCTION_REMOVED;
            }
            // SCRUM-266: self-bid guard
            if (sellerId == buyerId) {
                conn.rollback();
                return BidResult.SELF_BID;
            }

            // Fetch current highest bid (within same transaction)
            BigDecimal currentMax;
            String maxBidSql = "SELECT MAX(bid_amount) FROM bids WHERE auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(maxBidSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    currentMax = rs.next() ? rs.getBigDecimal(1) : null;
                }
            }

            // Floor = max(startingPrice, currentMax)
            BigDecimal floor = (currentMax == null) ? startingPrice
                    : currentMax.max(startingPrice);

            // SCRUM-263/SCRUM-267: bid must be strictly greater than floor
            if (bidAmount.compareTo(floor) <= 0) {
                conn.rollback();
                return BidResult.BID_TOO_LOW;
            }

            // Max-price cap check (SCRUM-263)
            if (maxPrice != null && bidAmount.compareTo(maxPrice) > 0) {
                conn.rollback();
                return BidResult.EXCEEDS_MAX_PRICE;
            }

            // All checks passed — insert manual bid
            String insertSql =
                    "INSERT INTO bids (auction_id, user_id, bid_amount, bid_time) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, auctionId);
                ps.setInt(2, buyerId);
                ps.setBigDecimal(3, bidAmount);
                ps.executeUpdate();
            }

            // SCRUM-52: trigger proxy auto-bids within the same transaction
            autoBidDAO.processAutoBids(conn, auctionId);

            conn.commit();
            return BidResult.SUCCESS;

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
    // Fetch auction detail for the public detail page (SCRUM-264)
    // -------------------------------------------------------------------------

    /**
     * Loads the full public detail of an auction by its ID.
     *
     * @param auctionId the auction to load
     * @return fully-populated {@link AuctionDetail}, or {@code null} if not found
     */
    public AuctionDetail findByIdForDisplay(long auctionId) {
        String sql =
                "SELECT a.auction_id, a.status_id, a.date_end, a.moderation_state, a.seller_id, "
                + "u.username AS seller_username, "
                + "d.title, d.description, d.category, d.starting_price, d.max_price, "
                + "COALESCE(MAX(b.bid_amount), d.starting_price) AS current_bid, "
                + "COUNT(b.bid_id)::int AS bid_count "
                + "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "JOIN users u ON u.id = a.seller_id "
                + "LEFT JOIN bids b ON b.auction_id = a.auction_id "
                + "WHERE a.auction_id = ? "
                + "GROUP BY a.auction_id, a.status_id, a.date_end, a.moderation_state, "
                + "         a.seller_id, u.username, d.title, d.description, d.category, "
                + "         d.starting_price, d.max_price";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                Instant dateEnd = rs.getTimestamp("date_end").toInstant();
                int statusId = rs.getInt("status_id");
                String modState = rs.getString("moderation_state");
                boolean open = statusId == AuctionStatus.ACTIVE.getId()
                        && "active".equals(modState)
                        && Instant.now().isBefore(dateEnd);

                BigDecimal startingPrice = rs.getBigDecimal("starting_price");
                if (startingPrice == null) startingPrice = BigDecimal.ZERO;
                BigDecimal currentBid = rs.getBigDecimal("current_bid");
                if (currentBid == null) currentBid = startingPrice;

                List<String> images = fetchImages(conn, auctionId);

                return new AuctionDetail(
                        rs.getLong("auction_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("category"),
                        startingPrice,
                        currentBid,
                        rs.getInt("bid_count"),
                        rs.getBigDecimal("max_price"),
                        dateEnd,
                        rs.getInt("seller_id"),
                        rs.getString("seller_username"),
                        images,
                        open);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> fetchImages(Connection conn, long auctionId) throws SQLException {
        List<String> urls = new ArrayList<>();
        String sql = "SELECT image_url FROM auction_images WHERE auction_id = ? ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) urls.add(rs.getString("image_url"));
            }
        }
        return urls;
    }

    // -------------------------------------------------------------------------
    // Public bid history (SCRUM-58)
    // -------------------------------------------------------------------------

    /**
     * Returns a paginated page of bids for an auction, newest first.
     *
     * <p><b>Masking (SCRUM-361):</b> The current highest bidder's username is partially
     * masked via {@link SecurityUtil#maskUsername(String)}; all other bidders are fully
     * masked via {@link SecurityUtil#maskUsernameFully(String)}. Raw usernames never
     * leave the DAO.</p>
     *
     * @param auctionId auction primary key
     * @param page      1-based page number
     * @param pageSize  rows per page (caller should clamp to [1, {@link #MAX_BID_HISTORY_PAGE_SIZE}])
     * @return ordered list; empty when the auction has no bids
     */
    public List<AuctionBidHistoryEntry> getBidHistory(long auctionId, int page, int pageSize) {
        Integer leaderUserId = findCurrentLeaderUserId(auctionId);
        int offset = pageSize * (page - 1);

        String sql =
                "SELECT b.bid_amount, b.bid_time, b.user_id, u.username "
                + "FROM bids b "
                + "JOIN users u ON u.id = b.user_id "
                + "WHERE b.auction_id = ? "
                + "ORDER BY b.bid_time DESC "
                + "LIMIT ? OFFSET ?";

        List<AuctionBidHistoryEntry> list = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, pageSize);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int bidderId = rs.getInt("user_id");
                    String rawUsername = rs.getString("username");
                    boolean isLeader = leaderUserId != null && leaderUserId == bidderId;
                    String masked = isLeader
                            ? SecurityUtil.maskUsername(rawUsername)
                            : SecurityUtil.maskUsernameFully(rawUsername);

                    Timestamp bidTs = rs.getTimestamp("bid_time");
                    BigDecimal amount = rs.getBigDecimal("bid_amount");
                    if (amount == null) amount = BigDecimal.ZERO;

                    list.add(new AuctionBidHistoryEntry(
                            amount,
                            bidTs != null ? bidTs.toInstant() : null,
                            masked,
                            isLeader));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /** Total bid count for an auction (used for pagination). */
    public int countBidHistory(long auctionId) {
        String sql = "SELECT COUNT(*)::int FROM bids WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    /** Returns {@code true} when an auction row exists (any moderation state). */
    public boolean auctionExists(long auctionId) {
        String sql = "SELECT 1 FROM auction WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the {@code user_id} of the current highest bidder, or {@code null} when
     * no bids exist. Ties on amount are broken by latest {@code bid_time}.
     */
    Integer findCurrentLeaderUserId(long auctionId) {
        String sql =
                "SELECT user_id FROM bids WHERE auction_id = ? "
                + "ORDER BY bid_amount DESC, bid_time DESC LIMIT 1";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("user_id");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public List<Bid> auctionBidHistory(Long auction_id)throws Exception
    {
        String sqlString = "SELECT user_id, bid_amount, bid_time FROM bids WHERE auction_id =?";
        try(Connection conn = DBUtil.connectDB())
        {
        try(PreparedStatement stmt = conn.prepareStatement(sqlString))
        {
            List<Bid> result = new ArrayList<>();
            stmt.setLong(1, auction_id);
            try(ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Bid temp = new Bid();
                    temp.setUser_id(rs.getLong("user_id"));
                    temp.setBid_amount(rs.getFloat("bid_amount"));
                    temp.setBid_time(rs.getTimestamp("bid_time").toInstant());
                    result.add(temp);
                }
            }
            return result;
        }
    }catch(Exception e)
        {
            throw new Exception("retrieve failed. try again", e);
        }
    }
}
