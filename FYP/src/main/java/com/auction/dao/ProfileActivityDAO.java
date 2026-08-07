package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.model.profile.BidHistoryRow;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Profile transaction history and reviews (SCRUM-84).
 *
 * <p>Read-only. Reads {@code auction}, {@code auction_details}, {@code auction_status},
 * {@code bids}, {@code user_reviews} and {@code auction_images}. Called by the profile API servlet
 * for the signed-in member's own activity tabs. Reviewer usernames are masked before they leave
 * the class, the same treatment {@link SellerProfileDAO} applies.</p>
 *
 * <p>The transaction history is assembled from four separate queries rather than one UNION,
 * because a purchase, a completed sale, a sale still running and a cancelled sale each derive
 * their date, amount and status from different columns.</p>
 */
public class ProfileActivityDAO {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Which side of the ledger to show. Parsed from a query parameter, defaulting to ALL. */
    public enum TxFilter {
        ALL, PURCHASE, SALE;

        /** Whitelist parse: anything unrecognised falls back to ALL rather than erroring. */
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

    /**
     * The member's combined purchase and sale history, newest first, with display ids applied.
     *
     * @param filter narrows the merged list to purchases or sales; filtering happens in Java
     *               because the four loaders have already been merged by then
     */
    public List<ProfileTransactionRow> listTransactions(int userId, TxFilter filter) {
        List<ProfileTransactionRow> raw = new ArrayList<>();
        // Four loaders share one connection: completed purchases, completed sales, sales still
        // running, and cancelled sales.
        try (Connection conn = DBUtil.connectDB()) {
            loadPurchaseCompleted(conn, userId, raw);
            loadSaleCompleted(conn, userId, raw);
            loadSalePending(conn, userId, raw);
            loadSaleCancelled(conn, userId, raw);
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

        // Display ids (T001, T002, ...) are assigned after sorting and filtering, so they number
        // the list the member is actually looking at. They are not stored anywhere.
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

    /**
     * Headline counters for the profile: how many auctions the member won, how many they sold, and
     * the combined value. Both halves key off {@code winning_bid IS NOT NULL}, which is what marks
     * an auction as actually sold, so unsold listings are excluded from every figure.
     */
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

    /**
     * Average score, review count and a 5-bucket histogram for the ratings bar chart.
     * Two queries: one aggregate, one GROUP BY rating for the distribution.
     */
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
                        // The histogram is stored highest first, so index 0 is 5 stars and index 4
                        // is 1 star. The bounds check discards any out-of-range score rather than
                        // throwing an array index error.
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

    /**
     * The member's bidding history, one row per auction rather than one per bid, newest activity
     * first.
     *
     * <p>The query joins two derived tables. {@code ub} collapses this member's bids on each
     * auction down to their highest amount and latest bid time; {@code top} does the same across
     * all bidders to get the leading bid on that auction. Comparing the two is how the "won" flag
     * is worked out. A correlated EXISTS also reports whether the member has already left a rating,
     * so the UI can hide the rate button.</p>
     *
     * @param page 1-based page number
     * @param size rows per page
     */
    public List<BidHistoryRow> getBidHistory(int userId, int page, int size) {
        List<BidHistoryRow> list = new ArrayList<>();
        // One row per auction; retrieve both the user's max bid and the overall max bid,
        // then compare in Java to avoid any SQL type/precision equality edge cases.
        String sql =
            "SELECT ub.auction_id, d.title, ub.my_max AS bid_amount, ub.last_bid AS bid_time, "
          + "       a.date_end, a.status_id, top.max_bid AS auction_max_bid, "
          + "       (SELECT i.image_url FROM auction_images i WHERE i.auction_id = ub.auction_id "
          + "        ORDER BY i.id LIMIT 1) AS thumbnail_url, "
          + "       EXISTS (SELECT 1 FROM user_reviews ur "
          + "               WHERE ur.auction_id = ub.auction_id AND ur.reviewer_user_id = ?) AS rated "
          + "FROM (SELECT auction_id, MAX(bid_amount) AS my_max, MAX(bid_time) AS last_bid "
          + "      FROM bids WHERE user_id = ? GROUP BY auction_id) ub "
          + "JOIN auction a          ON a.auction_id = ub.auction_id "
          + "JOIN auction_details d  ON d.id         = ub.auction_id "
          + "JOIN (SELECT auction_id, MAX(bid_amount) AS max_bid FROM bids GROUP BY auction_id) top "
          + "     ON top.auction_id = ub.auction_id "
          + "ORDER BY ub.last_bid DESC "
          + "LIMIT ? OFFSET ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.setInt(3, size);
            ps.setInt(4, (page - 1) * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long auctionId = rs.getLong("auction_id");
                    String title = rs.getString("title");
                    BigDecimal amount = rs.getBigDecimal("bid_amount");
                    BigDecimal auctionMax = rs.getBigDecimal("auction_max_bid");
                    Timestamp bidTs = rs.getTimestamp("bid_time");
                    LocalDateTime bidTime = bidTs == null
                            ? LocalDateTime.now()
                            : bidTs.toInstant().atZone(ZONE).toLocalDateTime();
                    Timestamp endTs = rs.getTimestamp("date_end");
                    int statusId = rs.getInt("status_id");
                    // An auction counts as ended if it is cancelled, marked finished, or simply
                    // past its end time. The last case matters because auctions are finalized
                    // lazily, so a closed auction can still be sitting at status ACTIVE.
                    boolean cancelled = statusId == AuctionStatus.CANCELLED.getId();
                    boolean ended = cancelled
                            || statusId == AuctionStatus.FINISHED.getId()
                            || (endTs != null && endTs.toInstant().isBefore(Instant.now()));
                    String status = ended ? "Ended" : "Live";
                    // Won means the auction is over, was not cancelled, and this member's best bid
                    // is the leading bid. Compared with compareTo rather than equals so scale
                    // differences such as 10.0 against 10.00 do not read as a loss.
                    boolean won = ended && !cancelled
                            && amount != null && auctionMax != null
                            && amount.compareTo(auctionMax) >= 0;
                    list.add(new BidHistoryRow(auctionId, title, amount, bidTime, status, won,
                            rs.getString("thumbnail_url"), rs.getBoolean("rated")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /**
     * Pagination total for {@link #getBidHistory}. Counts DISTINCT auction ids so it matches that
     * query's one-row-per-auction shape rather than counting individual bids.
     */
    public int countBidHistory(int userId) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(DISTINCT auction_id) FROM bids WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Reviews other members have left about this user, newest first, with reviewer names masked. */
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

    /** Auctions this member won: they are the {@code winner_id} and a winning bid was recorded. */
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

    /** Listings this member sold: they are the seller and a winning bid was recorded. */
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

    /**
     * Listings still running: no winning bid yet, end time in the future, and not already marked
     * cancelled or finished. The amount shown is the current leading bid, coalesced to 0 when
     * nobody has bid, since there is no winning_bid to report yet.
     */
    private static void loadSalePending(Connection conn, int userId, List<ProfileTransactionRow> out)
            throws Exception {
        String sql = "SELECT a.date_end, d.title, "
                + "COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), 0) AS cur_bid "
                + "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "JOIN auction_status s ON s.id = a.status_id "
                + "WHERE a.seller_id = ? AND d.winning_bid IS NULL AND a.date_end > CURRENT_TIMESTAMP "
                + "AND s.status NOT IN ('Cancelled', 'Finished')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("date_end");
                    LocalDate dt = ts == null ? LocalDate.now() : ts.toInstant().atZone(ZONE).toLocalDate();
                    String title = rs.getString("title");
                    BigDecimal amt = rs.getBigDecimal("cur_bid");
                    out.add(new ProfileTransactionRow("", dt, title, "sale", amt, "Pending"));
                }
            }
        }
    }

    /** Listings the seller pulled. Amount is zero because no money changed hands. */
    private static void loadSaleCancelled(Connection conn, int userId, List<ProfileTransactionRow> out)
            throws Exception {
        String sql = "SELECT a.date_end, d.title "
                + "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "JOIN auction_status s ON s.id = a.status_id "
                + "WHERE a.seller_id = ? AND s.status = 'Cancelled'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("date_end");
                    LocalDate dt = ts == null ? LocalDate.now() : ts.toInstant().atZone(ZONE).toLocalDate();
                    String title = rs.getString("title");
                    out.add(new ProfileTransactionRow("", dt, title, "sale", BigDecimal.ZERO, "Cancelled"));
                }
            }
        }
    }

    /**
     * Shared row builder for the two completed loaders. The display id is left blank here and
     * filled in by {@link #listTransactions} after the merged list has been sorted.
     */
    private static ProfileTransactionRow row(ResultSet rs, String type, String status) throws Exception {
        Timestamp ts = rs.getTimestamp("date_end");
        LocalDate dt = ts == null ? LocalDate.now() : ts.toInstant().atZone(ZONE).toLocalDate();
        String title = rs.getString("title");
        BigDecimal amt = rs.getBigDecimal("winning_bid");
        return new ProfileTransactionRow("", dt, title, type, amt, status);
    }

    /** Return type of {@link #computeTransactionStats}: counts plus combined value. */
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
