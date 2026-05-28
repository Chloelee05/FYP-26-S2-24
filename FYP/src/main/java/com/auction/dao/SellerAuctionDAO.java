package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.model.Bid;
import com.auction.model.seller.SellerAuctionRow;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Seller-scoped auction operations.
 *
 * State transitions: PENDING(4) → ACTIVE(1) → FINISHED(2)
 *                                    ↓               ↓
 *                               CANCELLED(3)    CANCELLED(3)*
 * (* cancelling a finished auction is rejected – only PENDING and ACTIVE may be cancelled)
 */
public class SellerAuctionDAO {

    // ------------------------------------------------------------------ cancel

    /**
     * Cancels an auction owned by {@code sellerId} if it is ACTIVE or PENDING.
     * Existing bids are preserved for audit; the auction status is set to CANCELLED.
     *
     * @return true if the row was updated; false means not-found, wrong owner, or wrong state
     */
    public boolean cancelAuction(long auctionId, int sellerId, String cancelReason) throws Exception {
        String sql = "UPDATE auction SET status_id = ?, cancel_reason = ? "
                   + "WHERE auction_id = ? AND seller_id = ? AND status_id IN (?, ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, AuctionStatus.CANCELLED.getId());
            ps.setString(2, cancelReason);
            ps.setLong(3, auctionId);
            ps.setInt(4, sellerId);
            ps.setInt(5, AuctionStatus.ACTIVE.getId());
            ps.setInt(6, AuctionStatus.PENDING.getId());
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ bid-cap (SCRUM-33)

    /**
     * Returns true when {@code bidAmount} does not exceed the max_price cap.
     * Always returns true when the auction has no cap (max_price IS NULL).
     * Hard-ceiling semantics: bid == cap is allowed; bid > cap is rejected.
     * <p>
     * Intended for use by BidDAO (SCRUM-51) before persisting any bid.
     */
    public boolean withinBidCap(long auctionId, BigDecimal bidAmount) throws Exception {
        String sql = "SELECT max_price FROM auction_details WHERE id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false; // auction not found — fail safe
                BigDecimal cap = rs.getBigDecimal("max_price");
                return cap == null || bidAmount.compareTo(cap) <= 0;
            }
        }
    }

    // ------------------------------------------------------------------ edit (SCRUM-37)

    /**
     * Returns the auction data needed to populate the edit form.
     * Returns null when the auction does not exist or is not owned by {@code sellerId}.
     */
    public AuctionEditData getAuctionForEdit(long auctionId, int sellerId) throws Exception {
        String sql =
            "SELECT a.auction_id, a.seller_id, a.status_id, "
          + "       d.title, d.description, d.max_price, "
          + "       a.date_created AS start_date, a.date_end "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "WHERE a.auction_id = ? AND a.seller_id = ?";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                List<AuctionEditData.ImageEntry> images = fetchImages(conn, auctionId);
                return new AuctionEditData(
                        rs.getLong("auction_id"),
                        rs.getLong("seller_id"),
                        rs.getInt("status_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getBigDecimal("max_price"),
                        rs.getTimestamp("start_date").toInstant(),
                        rs.getTimestamp("date_end").toInstant(),
                        images);
            }
        }
    }

    /** Returns the number of bids placed on the given auction. */
    public int countBids(long auctionId) throws Exception {
        String sql = "SELECT COUNT(*) FROM bids WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Updates title, description, and images atomically.
     * Precondition enforcement (bid count == 0, ownership, editable status) is the
     * caller's responsibility and must be re-checked on every submit to prevent TOCTOU.
     *
     * @param deleteImageIds IDs of existing auction_images rows to remove (may be empty)
     * @param newImageFilenames new filenames already written to the upload directory
     */
    public void editAuction(long auctionId, int sellerId,
                            String title, String description,
                            List<Long> deleteImageIds,
                            List<String> newImageFilenames) throws Exception {
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            try {
                // Re-verify ownership and editable state inside the transaction
                if (!isEditableBy(conn, auctionId, sellerId)) {
                    throw new IllegalStateException("Auction is not editable");
                }
                // Re-verify zero bids inside the transaction (TOCTOU guard)
                if (countBidsConn(conn, auctionId) > 0) {
                    throw new IllegalStateException("Bids already placed; auction cannot be edited");
                }

                updateDetails(conn, auctionId, title, description);
                deleteImages(conn, auctionId, deleteImageIds);
                insertNewImages(conn, auctionId, newImageFilenames);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ dashboard (SCRUM-38)

    /**
     * Returns a seller-scoped page of auction rows ordered by end date descending.
     *
     * @param statusId null to include all statuses; otherwise filter to that AuctionStatus id
     * @param page     1-based page number
     * @param pageSize rows per page (caller must clamp to a reasonable max)
     */
    public List<SellerAuctionRow> listSellerAuctions(int sellerId, Integer statusId,
                                                     int page, int pageSize) throws Exception {
        StringBuilder sb = new StringBuilder(
            "SELECT a.auction_id, d.title, d.starting_price, d.max_price, "
          + "COALESCE(MAX(b.bid_amount), 0) AS current_bid, "
          + "COUNT(b.bid_id) AS bid_count, "
          + "a.date_created AS start_date, a.date_end, s.status AS status_name "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "JOIN auction_status  s ON s.id = a.status_id "
          + "LEFT JOIN bids       b ON b.auction_id = a.auction_id "
          + "WHERE a.seller_id = ?");
        if (statusId != null) sb.append(" AND a.status_id = ?");
        sb.append(" GROUP BY a.auction_id, d.title, d.starting_price, d.max_price, a.date_created, a.date_end, s.status"
                + " ORDER BY a.date_end DESC"
                + " LIMIT ? OFFSET ?");

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            int i = 1;
            ps.setInt(i++, sellerId);
            if (statusId != null) ps.setInt(i++, statusId);
            ps.setInt(i++, pageSize);
            ps.setInt(i, pageSize * (page - 1));

            List<SellerAuctionRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SellerAuctionRow(
                            rs.getLong("auction_id"),
                            rs.getString("title"),
                            rs.getBigDecimal("starting_price"),
                            rs.getBigDecimal("max_price"),
                            rs.getBigDecimal("current_bid"),
                            rs.getInt("bid_count"),
                            rs.getTimestamp("start_date").toInstant(),
                            rs.getTimestamp("date_end").toInstant(),
                            rs.getString("status_name")));
                }
            }
            return rows;
        }
    }

    /** Total count for pagination — same WHERE clause as listSellerAuctions. */
    public int countSellerAuctions(int sellerId, Integer statusId) throws Exception {
        String sql = "SELECT COUNT(*) FROM auction WHERE seller_id = ?"
                   + (statusId != null ? " AND status_id = ?" : "");
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sellerId);
            if (statusId != null) ps.setInt(2, statusId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ------------------------------------------------------------------ private helpers

    private boolean isEditableBy(Connection conn, long auctionId, int sellerId) throws Exception {
        String sql = "SELECT 1 FROM auction WHERE auction_id = ? AND seller_id = ? AND status_id IN (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, sellerId);
            ps.setInt(3, AuctionStatus.ACTIVE.getId());
            ps.setInt(4, AuctionStatus.PENDING.getId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int countBidsConn(Connection conn, long auctionId) throws Exception {
        String sql = "SELECT COUNT(*) FROM bids WHERE auction_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void updateDetails(Connection conn, long auctionId,
                               String title, String description) throws Exception {
        String sql = "UPDATE auction_details SET title = ?, description = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setString(2, description);
            ps.setLong(3, auctionId);
            if (ps.executeUpdate() == 0) throw new Exception("auction_details row not found");
        }
    }

    /** Deletes image rows that belong to this auction only (auction_id guard prevents IDOR). */
    private void deleteImages(Connection conn, long auctionId,
                              List<Long> deleteImageIds) throws Exception {
        if (deleteImageIds == null || deleteImageIds.isEmpty()) return;
        String sql = "DELETE FROM auction_images WHERE id = ? AND auction_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Long imgId : deleteImageIds) {
                ps.setLong(1, imgId);
                ps.setLong(2, auctionId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertNewImages(Connection conn, long auctionId,
                                 List<String> filenames) throws Exception {
        if (filenames == null || filenames.isEmpty()) return;
        String sql = "INSERT INTO auction_images (auction_id, image_url, upload_date) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            for (String fn : filenames) {
                ps.setLong(1, auctionId);
                ps.setString(2, fn);
                ps.setTimestamp(3, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private List<AuctionEditData.ImageEntry> fetchImages(Connection conn,
                                                         long auctionId) throws Exception {
        String sql = "SELECT id, image_url FROM auction_images WHERE auction_id = ? ORDER BY upload_date";
        List<AuctionEditData.ImageEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AuctionEditData.ImageEntry(rs.getLong("id"), rs.getString("image_url")));
                }
            }
        }
        return list;
    }

    public List<Bid> getBidHistory(Long auctionId, Long sellerId) throws Exception
    {
        String sqlString = "SELECT b.user_id, b.bid_amount, b.bid_time " +
                "FROM bids b " +
                "JOIN auction a ON b.auction_id = a.auction_id " +
                "WHERE b.auction_id = ? AND a.seller_id = ?";
        try(Connection conn = DBUtil.connectDB())
        {
            try(PreparedStatement stmt = conn.prepareStatement(sqlString))
            {
                List<Bid> result = new ArrayList<>();
                stmt.setLong(1, auctionId);
                stmt.setLong(2, sellerId);
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
    // ------------------------------------------------------------------ value types

    public static final class AuctionEditData {
        public final long auctionId;
        public final long sellerId;
        public final int statusId;
        public final String title;
        public final String description;
        public final BigDecimal maxPrice;   // null when no cap
        public final Instant startDate;
        public final Instant endDate;
        public final List<ImageEntry> images;

        public AuctionEditData(long auctionId, long sellerId, int statusId,
                               String title, String description, BigDecimal maxPrice,
                               Instant startDate, Instant endDate,
                               List<ImageEntry> images) {
            this.auctionId = auctionId;
            this.sellerId = sellerId;
            this.statusId = statusId;
            this.title = title;
            this.description = description;
            this.maxPrice = maxPrice;
            this.startDate = startDate;
            this.endDate = endDate;
            this.images = images;
        }

        public static final class ImageEntry {
            public final long imageId;
            public final String imageUrl;

            public ImageEntry(long imageId, String imageUrl) {
                this.imageId = imageId;
                this.imageUrl = imageUrl;
            }
        }
    }
}
