package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.model.AuctionTags;
import com.auction.model.admin.AdminListingRow;
import com.auction.util.DBUtil;
import com.auction.model.Auction;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Auction persistence for admin moderation and dashboard metrics.
 */
public class AuctionDAO {

    private static final ZoneId ADMIN_ZONE = ZoneId.systemDefault();

    public List<AdminListingRow> listListingsForModeration() {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT a.auction_id, d.title, a.date_created, u.username, "
                    + "d.category, "
                    + "COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), 0) AS current_bid, "
                    + "a.report_count, a.moderation_state "
                    + "FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "JOIN users u ON u.id = a.seller_id "
                    + "ORDER BY a.report_count DESC, a.auction_id DESC";
            List<AdminListingRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate listed = rs.getTimestamp("date_created").toInstant()
                            .atZone(ADMIN_ZONE).toLocalDate();
                    rows.add(new AdminListingRow(
                            rs.getLong("auction_id"),
                            rs.getString("title"),
                            listed,
                            rs.getString("username"),
                            rs.getString("category"),
                            rs.getBigDecimal("current_bid"),
                            rs.getInt("report_count"),
                            rs.getString("moderation_state")));
                }
            }
            return rows;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean updateModerationState(long auctionId, String state) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE auction SET moderation_state = ? WHERE auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, state);
                ps.setLong(2, auctionId);
                return ps.executeUpdate() == 1;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean incrementReports(long auctionId) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE auction SET report_count = report_count + 1 WHERE auction_id = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setLong(1, auctionId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int countListingsTotal() {
        return countQuery("SELECT COUNT(*) FROM auction");
    }

    public int countListingsModerationActive() {
        return countQuery("SELECT COUNT(*) FROM auction WHERE moderation_state = 'active'");
    }

    public int countListingsFlagged() {
        return countQuery("SELECT COUNT(*) FROM auction WHERE moderation_state = 'flagged'");
    }

    /**
     * Sum of winning_bid over completed listing rows (dollars; placeholder until payments tracked per auction).
     */
    public long sumWinningBidDollars() {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT COALESCE(SUM(winning_bid), 0) FROM auction_details WHERE winning_bid IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0L;
    }

    public List<FlaggedTitleEvent> recentFlaggedListings(int limit) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT d.title, a.date_created "
                    + "FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.moderation_state = 'flagged' "
                    + "ORDER BY a.date_created DESC "
                    + "LIMIT ?";
            List<FlaggedTitleEvent> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Instant at = rs.getTimestamp("date_created").toInstant();
                        out.add(new FlaggedTitleEvent(rs.getString("title"), at));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int countQuery(String sql) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    public static final class FlaggedTitleEvent {
        private final String title;
        private final Instant at;

        public FlaggedTitleEvent(String title, Instant at) {
            this.title = title;
            this.at = at;
        }

        public String getTitle() {
            return title;
        }

        public Instant getAt() {
            return at;
        }
    }

    public long createAuction(Auction auction, List<String> imageFilenames) throws Exception {
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            try {
                long auctionId = insertAuction(conn, auction);
                insertAuctionDetails(conn, auctionId, auction);
                insertAuctionImages(conn, auctionId, imageFilenames);
                insertAuctionTags(conn, auctionId, auction.getAuctionTagsList());
                conn.commit();
                return auctionId;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private long insertAuction(Connection conn, Auction auction) throws Exception {
        String sql = "INSERT INTO auction (status_id, seller_id, date_created, date_end, auction_type) VALUES(?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, auction.getStart_date().isAfter(Instant.now()) ? AuctionStatus.PENDING.getId() : AuctionStatus.ACTIVE.getId());
            stmt.setInt(2, auction.getSeller_id());
            stmt.setTimestamp(3, Timestamp.from(auction.getStart_date()));
            stmt.setTimestamp(4, Timestamp.from(auction.getEnd_date()));
            stmt.setInt(5, auction.getAuctionType().getId());
            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
                throw new Exception("Failed to retrieve generated auction ID");
            }
        }
    }

    private void insertAuctionDetails(Connection conn, long auctionId, Auction auction) throws Exception {
        String sql = "INSERT INTO auction_details "
                   + "(id, title, description, item_condition_id, starting_price, max_price) "
                   + "VALUES(?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, auctionId);
            stmt.setString(2, auction.getAuction_name());
            stmt.setString(3, auction.getAuction_details());
            stmt.setInt(4, auction.getItemCondition().getId());
            stmt.setBigDecimal(5, BigDecimal.valueOf(auction.getStarting_price()));
            if (auction.getMaxPrice() != null) {
                stmt.setBigDecimal(6, auction.getMaxPrice());
            } else {
                stmt.setNull(6, java.sql.Types.NUMERIC);
            }
            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) throw new Exception("Failed at auction_details");
        }
    }

    private void insertAuctionImages(Connection conn, long auctionId, List<String> imageFilenames) throws Exception {
        if (imageFilenames == null || imageFilenames.isEmpty()) return;
        String sql = "INSERT INTO auction_images (auction_id, image_url, upload_date) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            for (String filename : imageFilenames) {
                stmt.setLong(1, auctionId);
                stmt.setString(2, filename);
                stmt.setTimestamp(3, now);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (BatchUpdateException e) {
            throw new Exception("Failed to save images to database", e);
        }
    }

    private void insertAuctionTags(Connection conn, long auctionId, List<Long> tags) throws Exception {
        if (tags == null || tags.isEmpty()) return;
        String sql = "INSERT INTO auction_tag_info (auction_id, tag_id) VALUES (?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Long tag : tags) {
                stmt.setLong(1, auctionId);
                stmt.setLong(2, tag);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (BatchUpdateException e) {
            throw new Exception("Failed to save tags to database", e);
        }
    }

    public boolean removeAuction(long auction_id) throws Exception {
        String sqlString = "UPDATE auction SET moderation_state = ? WHERE auction_id = ?";
        try(Connection conn = DBUtil.connectDB())
        {
            try(PreparedStatement stmt = conn.prepareStatement(sqlString))
            {
                stmt.setString(1, "removed");
                stmt.setLong(2, auction_id);
                int rs = stmt.executeUpdate();
                if(rs > 0)
                {
                    return true;
                }
            }
        }catch(Exception e)
        {
            throw new Exception("remove auction failed", e);
        }
        return false;
    }
}
