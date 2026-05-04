package com.auction.dao;

import com.auction.model.admin.AdminListingRow;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
}
