package com.auction.dao;

import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-seller performance analytics, computed on demand from the operational tables.
 *
 * <p>Read only. Aggregates {@code auction} and {@code auction_details} for listing counts and
 * revenue, {@code bids} for buyer interest, {@code orders} for settled sales,
 * {@code platform_revenue} for fees deducted, and {@code user_reviews} for the star breakdown.
 * Called by the seller analytics API and by the scheduled performance email, which renders the
 * same snapshot through {@link #toEmailBody}. Money columns are NUMERIC and are always read as
 * {@link BigDecimal}, see the notes below.</p>
 */
public class SellerAnalyticsDAO {

    /** Builds an analytics snapshot (totals, revenue, top listings) for one seller. */
    public Map<String, Object> generate(int sellerId) {
        Map<String, Object> out = new LinkedHashMap<>();

        // One pass over the seller's listings producing four figures at once. The FILTER clauses
        // are conditional aggregates: "active" counts only listings that are approved, not removed
        // by a moderator, and not yet past their end time, while "sold" and "revenue" count only
        // listings that ended with a winner. Doing it as one grouped query avoids four round trips.
        String countsSql =
            "SELECT COUNT(*) AS total, "
          + "  COUNT(*) FILTER (WHERE a.status_id = 1 AND a.moderation_state = 'active' AND a.date_end > now()) AS active, "
          + "  COUNT(*) FILTER (WHERE d.winner_id IS NOT NULL) AS sold, "
          + "  COALESCE(SUM(d.winning_bid) FILTER (WHERE d.winner_id IS NOT NULL), 0) AS revenue "
          + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
          + "WHERE a.seller_id = ?";

        try (Connection conn = DBUtil.connectDB()) {
            int total = 0, active = 0, sold = 0;
            // winning_bid is NUMERIC(12,2). Reading the sum as a long silently drops the
            // cents of every sale, so the seller's revenue is short by up to 99c per lot.
            BigDecimal revenue = BigDecimal.ZERO;
            try (PreparedStatement ps = conn.prepareStatement(countsSql)) {
                ps.setInt(1, sellerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getInt("total");
                        active = rs.getInt("active");
                        sold = rs.getInt("sold");
                        revenue = money(rs.getBigDecimal("revenue"));
                    }
                }
            }

            int bidsReceived = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM bids b JOIN auction a ON a.auction_id = b.auction_id WHERE a.seller_id = ?")) {
                ps.setInt(1, sellerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) bidsReceived = rs.getInt(1);
                }
            }

            // Five busiest listings. Both columns come from correlated subqueries against bids:
            // one counts them, the other takes the highest. COALESCE falls back to the starting
            // price so a listing with no bids yet still shows a sensible figure instead of null.
            List<Map<String, Object>> topListings = new ArrayList<>();
            String topSql =
                "SELECT d.title, d.listing_kind, "
              + "  (SELECT COUNT(*) FROM bids b WHERE b.auction_id = a.auction_id) AS bid_count, "
              + "  COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) AS top_bid "
              + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
              + "WHERE a.seller_id = ? ORDER BY bid_count DESC, top_bid DESC LIMIT 5";
            try (PreparedStatement ps = conn.prepareStatement(topSql)) {
                ps.setInt(1, sellerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("title", rs.getString("title"));
                        row.put("listingKind", rs.getString("listing_kind"));
                        row.put("bidCount", rs.getInt("bid_count"));
                        row.put("topBid", rs.getBigDecimal("top_bid"));
                        topListings.add(row);
                    }
                }
            }

            // Sell-through is the share of listings that found a buyer, rounded to one decimal.
            BigDecimal avgSalePrice = sold > 0
                    ? revenue.divide(BigDecimal.valueOf(sold), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2);
            double sellThrough = total > 0 ? (double) sold / total * 100.0 : 0;

            out.put("totalListings", total);
            out.put("activeListings", active);
            out.put("soldCount", sold);
            out.put("totalRevenue", revenue);
            out.put("avgSalePrice", avgSalePrice);
            out.put("sellThroughRate", Math.round(sellThrough * 10.0) / 10.0);
            out.put("bidsReceived", bidsReceived);
            out.put("topListings", topListings);
            out.put("periodStats", loadPeriodStats(conn, sellerId));
            out.put("popularityByPeriod", loadPopularityByPeriod(conn, sellerId));
            out.put("popularityMetricNote", POPULARITY_NOTE);
            out.put("productRatings", loadProductRatings(conn, sellerId));
            out.put("earningsSummary", loadEarningsSummary(conn, sellerId));
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Explains the ranking, because "most popular" has two defensible readings on an
     * auction marketplace and the report must not pretend otherwise.
     */
    static final String POPULARITY_NOTE =
            "Popularity is reported two ways per calendar period: by bids received "
          + "(buyer interest) and by sale value (commercial outcome). Units sold cannot "
          + "rank listings here because an auction sells a single lot, so every sold "
          + "listing would tie at one.";

    /**
     * Calendar granularities, how many buckets to show, and their display label.
     *
     * <p>The labels say "with activity" because a bucket with no bid and no sale is
     * dropped rather than padded in. Listings here go quiet for weeks at a time, so
     * "the last 7 days" would usually be six empty rows and one real one.</p>
     */
    private static final String[][] POPULARITY_PERIODS = {
        { "day",     "7",  "Daily — 7 most recent days with activity" },
        { "week",    "4",  "Weekly — 4 most recent weeks with activity" },
        { "month",   "6",  "Monthly — 6 most recent months with activity" },
        { "quarter", "4",  "Quarterly — 4 most recent quarters with activity" },
    };

    /**
     * Names the most popular listing in each calendar day, week, month and quarter.
     *
     * <p>This is the product-by-period cross-tab the minimum requirements ask for.
     * {@link #loadPeriodStats} answers a different question, how much moved in a rolling
     * window, and names no listing at all.</p>
     *
     * <p>Buckets are calendar-aligned with {@code date_trunc} rather than rolling
     * {@code now() - interval} windows, because the requirement enumerates "each day /
     * week / month / quarter", which reads as named calendar periods: a reader expects
     * "week of 27 July", not "the last seven days". Calendar buckets are also the only
     * form that lets two reports of the same period agree.</p>
     */
    private List<Map<String, Object>> loadPopularityByPeriod(Connection conn, int sellerId)
            throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] period : POPULARITY_PERIODS) {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("granularity", period[0]);
            section.put("label", period[2]);
            section.put("buckets",
                    loadPopularityBuckets(conn, sellerId, period[0], Integer.parseInt(period[1])));
            out.add(section);
        }
        return out;
    }

    /**
     * One row per calendar bucket, each naming the listing that drew the most bids in that bucket
     * and the listing that earned the most money in it. Returns at most {@code limit} buckets,
     * newest first, skipping any bucket with neither a bid nor a sale.
     */
    private List<Map<String, Object>> loadPopularityBuckets(Connection conn, int sellerId,
                                                            String granularity, int limit)
            throws Exception {
        // granularity is one of the hard-coded literals in POPULARITY_PERIODS, never user
        // input, but date_trunc's unit cannot be bound as a parameter so it is passed as a
        // bind value to date_trunc rather than concatenated into the statement.
        //
        // Four CTEs feed a single result set:
        //   bid_activity  bids per (bucket, listing) across all of this seller's auctions
        //   bid_top       ranks those within each bucket, ROW_NUMBER() = 1 is the busiest listing
        //   sale_activity settled money per (bucket, listing), only PAID and COMPLETED orders,
        //                 since a pending order is not revenue yet
        //   sale_top      the same ranking by revenue
        // The two winners are then FULL JOINed on bucket, so a bucket that had bids but no sale,
        // or a sale but no bids, still produces a row. That is also why the output bucket is
        // COALESCEd: only one side may be present. Each winner is LEFT JOINed to auction_details
        // for its title, under separate aliases bd and sd. Ties inside a bucket break on
        // auction_id, which keeps repeated runs of the report consistent.
        String sql =
            "WITH bid_activity AS ("
          + "  SELECT date_trunc(?, b.bid_time)::date AS bucket, a.auction_id, COUNT(*) AS bids"
          + "  FROM bids b JOIN auction a ON a.auction_id = b.auction_id"
          + "  WHERE a.seller_id = ? GROUP BY 1, 2"
          + "), bid_top AS ("
          + "  SELECT bucket, auction_id, bids,"
          + "         ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY bids DESC, auction_id) AS rn"
          + "  FROM bid_activity"
          + "), sale_activity AS ("
          + "  SELECT date_trunc(?, o.created_at)::date AS bucket, o.auction_id,"
          + "         COALESCE(SUM(o.amount), 0) AS revenue"
          + "  FROM orders o"
          + "  WHERE o.seller_id = ? AND o.status IN ('PAID', 'COMPLETED') GROUP BY 1, 2"
          + "), sale_top AS ("
          + "  SELECT bucket, auction_id, revenue,"
          + "         ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY revenue DESC, auction_id) AS rn"
          + "  FROM sale_activity"
          + ") "
          + "SELECT COALESCE(bt.bucket, st.bucket) AS bucket, "
          + "  bd.title AS bid_title, bd.listing_kind AS bid_kind, bt.bids, "
          + "  sd.title AS sale_title, sd.listing_kind AS sale_kind, st.revenue "
          + "FROM      (SELECT * FROM bid_top  WHERE rn = 1) bt "
          + "FULL JOIN (SELECT * FROM sale_top WHERE rn = 1) st ON st.bucket = bt.bucket "
          + "LEFT JOIN auction_details bd ON bd.id = bt.auction_id "
          + "LEFT JOIN auction_details sd ON sd.id = st.auction_id "
          + "ORDER BY 1 DESC LIMIT ?";

        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, granularity);
            ps.setInt(2, sellerId);
            ps.setString(3, granularity);
            ps.setInt(4, sellerId);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("periodStart", String.valueOf(rs.getDate("bucket")));
                    row.put("topByBidsTitle", rs.getString("bid_title"));
                    row.put("topByBidsKind", rs.getString("bid_kind"));
                    row.put("topByBidsCount", rs.getInt("bids"));
                    row.put("topBySalesTitle", rs.getString("sale_title"));
                    row.put("topBySalesKind", rs.getString("sale_kind"));
                    BigDecimal revenue = rs.getBigDecimal("revenue");
                    row.put("topBySalesRevenue", revenue == null ? BigDecimal.ZERO : revenue);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * Sold count, revenue and bids received over four rolling windows ending now.
     * The interval literals are concatenated into the SQL, which is safe here because they come
     * from the fixed array below and never from a request.
     */
    private List<Map<String, Object>> loadPeriodStats(Connection conn, int sellerId) throws Exception {
        // Deliberately rolling windows, and labelled as such. The calendar-aligned
        // product-by-period answer lives in loadPopularityByPeriod; these labels used to
        // read "daily"/"weekly"/"monthly"/"quarterly" over 1/7/30/90-day windows, which
        // named calendar periods the numbers were not measuring.
        String[] labels = { "last 24 hours", "last 7 days", "last 30 days", "last 90 days" };
        String[] intervals = { "1 day", "7 days", "30 days", "90 days" };
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", labels[i]);
            row.put("sold", countSince(conn,
                    "SELECT COUNT(*) FROM orders WHERE seller_id = ? AND created_at >= now() - interval '"
                  + intervals[i] + "'", sellerId));
            // orders.amount is NUMERIC(10,2), so this has to be read as a decimal. Read as an
            // int it truncated to whole dollars, which made every period's revenue in the
            // email quietly wrong, and it is one of the few figures the report exists to show.
            row.put("revenue", sumDecimal(conn,
                    "SELECT COALESCE(SUM(amount), 0) FROM orders WHERE seller_id = ? AND created_at >= now() - interval '"
                  + intervals[i] + "'", sellerId));
            row.put("bids", countSince(conn,
                    "SELECT COUNT(*) FROM bids b JOIN auction a ON a.auction_id = b.auction_id "
                  + "WHERE a.seller_id = ? AND b.bid_time >= now() - interval '"
                  + intervals[i] + "'", sellerId));
            rows.add(row);
        }
        return rows;
    }

    /**
     * Runs a single-column {@code COUNT(*)} for one seller.
     *
     * <p>Only for counts. Money columns are {@code NUMERIC} and must go through
     * {@link #sumDecimal}; this method used to serve both, which is how the period revenue
     * figures ended up truncated to whole dollars.</p>
     */
    private int countSince(Connection conn, String sql, int sellerId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Read-only seller earnings from completed orders and {@code platform_revenue}
     * (no wallet balance or payouts).
     */
    private Map<String, Object> loadEarningsSummary(Connection conn, int sellerId) throws Exception {
        BigDecimal gross = sumDecimal(conn,
                "SELECT COALESCE(SUM(amount), 0) FROM orders WHERE seller_id = ? AND status = 'COMPLETED'",
                sellerId);
        BigDecimal platformFee = sumDecimal(conn,
                "SELECT COALESCE(SUM(amount), 0) FROM platform_revenue "
              + "WHERE seller_id = ? AND revenue_type = 'COMMISSION'",
                sellerId);
        BigDecimal featuredFees = sumDecimal(conn,
                "SELECT COALESCE(SUM(amount), 0) FROM platform_revenue "
              + "WHERE seller_id = ? AND revenue_type = 'FEATURED_LISTING'",
                sellerId);
        // What the seller actually keeps: settled sales less the commission the platform took and
        // any fees paid to feature listings.
        BigDecimal net = gross.subtract(platformFee).subtract(featuredFees);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("grossSales", gross);
        row.put("platformFee", platformFee);
        row.put("featuredFees", featuredFees);
        row.put("netEarnings", net);
        row.put("completedOrders", countSince(conn,
                "SELECT COUNT(*) FROM orders WHERE seller_id = ? AND status = 'COMPLETED'", sellerId));
        row.put("commissionRatePct", 6);
        // Flags to the UI that these are book figures derived from order rows, not a real
        // payout ledger. There is no wallet or settlement system behind them.
        row.put("simulated", true);
        return row;
    }

    /** Runs a single-column money SUM for one seller, normalised to two decimal places. */
    private BigDecimal sumDecimal(Connection conn, String sql, int sellerId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                return money(rs.next() ? rs.getBigDecimal(1) : null);
            }
        }
    }

    /** Normalises a money column to two decimal places, treating a null sum as zero. */
    static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2)
                             : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Star distribution for the ten most reviewed listings this seller sold.
     *
     * <p>Grouped per auction, so each row is one listing rather than one review. The five
     * percentage columns each divide a FILTERed count of that star value by the group's total;
     * NULLIF(COUNT(*), 0) guards the division, though a group only exists when it has reviews.
     * The WHERE clause pins both sides: the auction must belong to this seller and the review
     * must be about this seller, which excludes reviews the seller wrote about buyers.</p>
     */
    private List<Map<String, Object>> loadProductRatings(Connection conn, int sellerId) throws Exception {
        String sql =
            "SELECT d.title, d.listing_kind, ur.auction_id, COUNT(*) AS review_count, "
          + "  ROUND(AVG(ur.rating)::numeric, 1) AS avg_rating, "
          + "  ROUND(100.0 * COUNT(*) FILTER (WHERE ur.rating = 5) / NULLIF(COUNT(*), 0), 1) AS pct5, "
          + "  ROUND(100.0 * COUNT(*) FILTER (WHERE ur.rating = 4) / NULLIF(COUNT(*), 0), 1) AS pct4, "
          + "  ROUND(100.0 * COUNT(*) FILTER (WHERE ur.rating = 3) / NULLIF(COUNT(*), 0), 1) AS pct3, "
          + "  ROUND(100.0 * COUNT(*) FILTER (WHERE ur.rating = 2) / NULLIF(COUNT(*), 0), 1) AS pct2, "
          + "  ROUND(100.0 * COUNT(*) FILTER (WHERE ur.rating = 1) / NULLIF(COUNT(*), 0), 1) AS pct1 "
          + "FROM user_reviews ur "
          + "JOIN auction a ON a.auction_id = ur.auction_id "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "WHERE a.seller_id = ? AND ur.reviewee_user_id = ? "
          + "GROUP BY d.title, d.listing_kind, ur.auction_id "
          + "ORDER BY review_count DESC, avg_rating DESC LIMIT 10";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sellerId);
            ps.setInt(2, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", rs.getString("title"));
                    row.put("listingKind", rs.getString("listing_kind"));
                    row.put("auctionId", rs.getLong("auction_id"));
                    row.put("reviewCount", rs.getInt("review_count"));
                    row.put("avgRating", rs.getDouble("avg_rating"));
                    Map<String, Object> starPct = new LinkedHashMap<>();
                    starPct.put("5", rs.getDouble("pct5"));
                    starPct.put("4", rs.getDouble("pct4"));
                    starPct.put("3", rs.getDouble("pct3"));
                    starPct.put("2", rs.getDouble("pct2"));
                    starPct.put("1", rs.getDouble("pct1"));
                    row.put("starPercentages", starPct);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * Renders a plain-text email body from a snapshot produced by {@link #generate}.
     * Static and map-driven so the scheduled mailer can format a snapshot without a database
     * connection. Each section is skipped when its data is absent.
     */
    @SuppressWarnings("unchecked")
    public static String toEmailBody(String sellerName, Map<String, Object> a) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(sellerName).append(",\n\n");
        sb.append("Here is your AuctionHub seller performance summary:\n\n");
        sb.append("• Total listings: ").append(a.get("totalListings")).append('\n');
        sb.append("• Active listings: ").append(a.get("activeListings")).append('\n');
        sb.append("• Items sold: ").append(a.get("soldCount")).append('\n');
        sb.append("• Total revenue: $").append(a.get("totalRevenue")).append('\n');
        sb.append("• Average sale price: $").append(a.get("avgSalePrice")).append('\n');
        sb.append("• Sell-through rate: ").append(a.get("sellThroughRate")).append("%\n");
        sb.append("• Total bids received: ").append(a.get("bidsReceived")).append('\n');

        Object earnings = a.get("earningsSummary");
        if (earnings instanceof Map) {
            Map<String, Object> e = (Map<String, Object>) earnings;
            sb.append("\nEarnings (completed orders):\n");
            sb.append("  Gross sales:   $").append(e.get("grossSales")).append('\n');
            sb.append("  Platform fee (").append(e.get("commissionRatePct")).append("%): $")
              .append(e.get("platformFee")).append('\n');
            sb.append("  Featured fees: $").append(e.get("featuredFees")).append('\n');
            sb.append("  Net earnings:  $").append(e.get("netEarnings"))
              .append(" over ").append(e.get("completedOrders")).append(" completed order(s)\n");
        }

        Object top = a.get("topListings");
        if (top instanceof List && !((List<?>) top).isEmpty()) {
            sb.append("\nTop listings by bids:\n");
            for (Map<String, Object> row : (List<Map<String, Object>>) top) {
                sb.append("  - ").append(row.get("title")).append(kindSuffix(row.get("listingKind")))
                  .append(" (").append(row.get("bidCount")).append(" bids, top $")
                  .append(row.get("topBid")).append(")\n");
            }
        }

        appendPopularity(sb, a);

        Object periods = a.get("periodStats");
        if (periods instanceof List && !((List<?>) periods).isEmpty()) {
            sb.append("\nRolling-window totals:\n");
            for (Map<String, Object> p : (List<Map<String, Object>>) periods) {
                sb.append("  ").append(p.get("period")).append(": ")
                  .append(p.get("sold")).append(" sold, $").append(p.get("revenue"))
                  .append(", ").append(p.get("bids")).append(" bids\n");
            }
        }

        appendStarRatings(sb, a);

        sb.append("\n— AuctionHub");
        return sb.toString();
    }

    /**
     * Renders the calendar day / week / month / quarter popularity cross-tab, which the
     * minimum requirements name explicitly ("which pdt/service is most popular for each
     * day / week / month / quarter").
     */
    @SuppressWarnings("unchecked")
    private static void appendPopularity(StringBuilder sb, Map<String, Object> a) {
        Object sections = a.get("popularityByPeriod");
        if (!(sections instanceof List) || ((List<?>) sections).isEmpty()) return;

        sb.append("\nMost popular listing by calendar period\n");
        Object note = a.get("popularityMetricNote");
        if (note != null) sb.append("  (").append(note).append(")\n");

        for (Map<String, Object> section : (List<Map<String, Object>>) sections) {
            sb.append("\n  ").append(section.get("label")).append(":\n");
            Object buckets = section.get("buckets");
            if (!(buckets instanceof List) || ((List<?>) buckets).isEmpty()) {
                sb.append("    no activity in this period\n");
                continue;
            }
            for (Map<String, Object> b : (List<Map<String, Object>>) buckets) {
                sb.append("    ").append(b.get("periodStart")).append('\n');
                Object bidTitle = b.get("topByBidsTitle");
                if (bidTitle != null) {
                    sb.append("      most bids:  ").append(bidTitle)
                      .append(kindSuffix(b.get("topByBidsKind")))
                      .append(" — ").append(b.get("topByBidsCount")).append(" bid(s)\n");
                }
                Object saleTitle = b.get("topBySalesTitle");
                if (saleTitle != null) {
                    sb.append("      top sale:   ").append(saleTitle)
                      .append(kindSuffix(b.get("topBySalesKind")))
                      .append(" — $").append(b.get("topBySalesRevenue")).append('\n');
                } else if (bidTitle != null) {
                    sb.append("      top sale:   no sale in this period\n");
                }
            }
        }
    }

    /**
     * Renders the per-listing star distribution, the second metric the requirements name
     * ("%-tage of star reviews for each pdt/service from buyers"). The percentages were
     * computed but dropped on the floor here, so the email carried only the average.
     */
    @SuppressWarnings("unchecked")
    private static void appendStarRatings(StringBuilder sb, Map<String, Object> a) {
        Object ratings = a.get("productRatings");
        if (!(ratings instanceof List) || ((List<?>) ratings).isEmpty()) return;

        sb.append("\nStar review breakdown per product/service (from buyers):\n");
        for (Map<String, Object> pr : (List<Map<String, Object>>) ratings) {
            sb.append("  - ").append(pr.get("title")).append(kindSuffix(pr.get("listingKind")))
              .append(": average ").append(pr.get("avgRating")).append("/5 from ")
              .append(pr.get("reviewCount")).append(" review(s)\n");
            Object pct = pr.get("starPercentages");
            if (pct instanceof Map) {
                Map<String, Object> stars = (Map<String, Object>) pct;
                for (String star : new String[] { "5", "4", "3", "2", "1" }) {
                    Object value = stars.get(star);
                    sb.append("      ").append(star).append(" star: ")
                      .append(value == null ? 0.0 : value).append("%\n");
                }
            }
        }
    }

    /** Tags a title as a service in the email; products get no suffix, which is the common case. */
    private static String kindSuffix(Object listingKind) {
        return "SERVICE".equalsIgnoreCase(String.valueOf(listingKind)) ? " [service]" : "";
    }
}
