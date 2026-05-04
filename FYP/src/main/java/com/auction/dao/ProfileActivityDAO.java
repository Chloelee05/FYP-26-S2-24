package com.auction.dao;

import com.auction.model.profile.ProfileReviewRow;
import com.auction.model.profile.ProfileTransactionRow;
import com.auction.model.profile.RatingSummary;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Profile transaction history and reviews (SCRUM-84).
 */
public class ProfileActivityDAO {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    public enum TxFilter {
        ALL, PURCHASE, SALE;

        public static TxFilter fromParam(String raw) {
            if (raw == null || raw.isBlank()) {
                return ALL;
            }
            switch (raw.trim().toLowerCase()) {
                case "purchase":
                    return PURCHASE;
                case "sale":
                    return SALE;
                default:
                    return ALL;
            }
        }
    }

    public List<ProfileTransactionRow> listTransactions(int userId, TxFilter filter) {
        List<ProfileTransactionRow> raw = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB()) {
            loadPurchaseCompleted(conn, userId, raw);
            loadSaleCompleted(conn, userId, raw);
            loadSalePending(conn, userId, raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        raw.sort(Comparator.comparing(ProfileTransactionRow::getTransactionDate).reversed());

        List<ProfileTransactionRow> filtered = new ArrayList<>();
        for (ProfileTransactionRow r : raw) {
            if (filter == TxFilter.ALL) {
                filtered.add(r);
            } else if (filter == TxFilter.PURCHASE && "purchase".equals(r.getTransactionType())) {
                filtered.add(r);
            } else if (filter == TxFilter.SALE && "sale".equals(r.getTransactionType())) {
                filtered.add(r);
            }
        }

        int seq = 1;
        List<ProfileTransactionRow> withIds = new ArrayList<>();
        for (ProfileTransactionRow r : filtered) {
            String id = String.format("T%03d", seq++);
            withIds.add(new ProfileTransactionRow(
                    id,
                    r.getTransactionDate(),
                    r.getItemTitle(),
                    r.getTransactionType(),
                    r.getAmount(),
                    r.getStatus()));
        }
        return withIds;
    }

    public TransactionStats computeTransactionStats(int userId) {
        int purchases = 0;
        int sales = 0;
        BigDecimal volume = BigDecimal.ZERO;
        try (Connection conn = DBUtil.connectDB()) {
            String sqlP = "SELECT COUNT(*), COALESCE(SUM(d.winning_bid), 0) FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE d.winner_id = ? AND d.winning_bid IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(sqlP)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        purchases = rs.getInt(1);
                        volume = volume.add(rs.getBigDecimal(2));
                    }
                }
            }
            String sqlS = "SELECT COUNT(*), COALESCE(SUM(d.winning_bid), 0) FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.seller_id = ? AND d.winning_bid IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(sqlS)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        sales = rs.getInt(1);
                        volume = volume.add(rs.getBigDecimal(2));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new TransactionStats(purchases, sales, volume);
    }

    public RatingSummary getRatingSummary(int userId) {
        double avg = 0;
        int count = 0;
        int[] hist = new int[]{0, 0, 0, 0, 0};
        try (Connection conn = DBUtil.connectDB()) {
            String sqlAvg = "SELECT COALESCE(AVG(rating), 0), COUNT(*) FROM user_reviews WHERE reviewee_user_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlAvg)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        avg = rs.getDouble(1);
                        count = rs.getInt(2);
                    }
                }
            }
            String sqlH = "SELECT rating, COUNT(*) AS c FROM user_reviews WHERE reviewee_user_id = ? GROUP BY rating";
            try (PreparedStatement ps = conn.prepareStatement(sqlH)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int rating = rs.getInt("rating");
                        int c = rs.getInt("c");
                        if (rating >= 1 && rating <= 5) {
                            hist[5 - rating] += c;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new RatingSummary(avg, count, hist);
    }

    public List<ProfileReviewRow> listReviewsAboutUser(int userId) {
        List<ProfileReviewRow> list = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT r.rating, r.comment, r.created_at, u.username, d.title AS item_title "
                    + "FROM user_reviews r "
                    + "JOIN users u ON u.id = r.reviewer_user_id "
                    + "LEFT JOIN auction_details d ON d.id = r.auction_id "
                    + "WHERE r.reviewee_user_id = ? "
                    + "ORDER BY r.created_at DESC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp ts = rs.getTimestamp("created_at");
                        LocalDate d = ts == null ? LocalDate.now() : ts.toInstant().atZone(ZONE).toLocalDate();
                        String uname = rs.getString("username");
                        String c = rs.getString("comment");
                        if (c == null) {
                            c = "";
                        }
                        list.add(new ProfileReviewRow(
                                SecurityUtil.maskUsername(uname),
                                rs.getInt("rating"),
                                c,
                                d,
                                rs.getString("item_title")));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    private static void loadPurchaseCompleted(Connection conn, int userId, List<ProfileTransactionRow> out)
            throws Exception {
        String sql = "SELECT a.date_end, d.title, d.winning_bid FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "WHERE d.winner_id = ? AND d.winning_bid IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(row(rs, "purchase", "Completed"));
                }
            }
        }
    }

    private static void loadSaleCompleted(Connection conn, int userId, List<ProfileTransactionRow> out)
            throws Exception {
        String sql = "SELECT a.date_end, d.title, d.winning_bid FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "WHERE a.seller_id = ? AND d.winning_bid IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(row(rs, "sale", "Completed"));
                }
            }
        }
    }

    private static void loadSalePending(Connection conn, int userId, List<ProfileTransactionRow> out)
            throws Exception {
        String sql = "SELECT a.date_end, d.title, "
                + "COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), 0) AS cur_bid "
                + "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "WHERE a.seller_id = ? AND d.winning_bid IS NULL AND a.date_end > CURRENT_TIMESTAMP";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("date_end");
                    LocalDate dt = ts == null ? LocalDate.now() : ts.toInstant().atZone(ZONE).toLocalDate();
                    String title = rs.getString("title");
                    BigDecimal amt = rs.getBigDecimal("cur_bid");
                    out.add(new ProfileTransactionRow(
                            "",
                            dt,
                            title,
                            "sale",
                            amt,
                            "Pending"));
                }
            }
        }
    }

    private static ProfileTransactionRow row(ResultSet rs, String type, String status) throws Exception {
        Timestamp ts = rs.getTimestamp("date_end");
        LocalDate dt = ts == null ? LocalDate.now() : ts.toInstant().atZone(ZONE).toLocalDate();
        String title = rs.getString("title");
        BigDecimal amt = rs.getBigDecimal("winning_bid");
        return new ProfileTransactionRow("", dt, title, type, amt, status);
    }

    public static final class TransactionStats {
        private final int purchaseCount;
        private final int saleCount;
        private final BigDecimal totalVolume;

        public TransactionStats(int purchaseCount, int saleCount, BigDecimal totalVolume) {
            this.purchaseCount = purchaseCount;
            this.saleCount = saleCount;
            this.totalVolume = totalVolume;
        }

        public int getPurchaseCount() {
            return purchaseCount;
        }

        public int getSaleCount() {
            return saleCount;
        }

        public BigDecimal getTotalVolume() {
            return totalVolume;
        }
    }
}
