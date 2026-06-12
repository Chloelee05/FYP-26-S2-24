package com.auction.dao;

import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-seller performance analytics, computed on demand from the operational tables.
 */
public class SellerAnalyticsDAO {

    /** Builds an analytics snapshot (totals, revenue, top listings) for one seller. */
    public Map<String, Object> generate(int sellerId) {
        Map<String, Object> out = new LinkedHashMap<>();

        String countsSql =
            "SELECT COUNT(*) AS total, "
          + "  COUNT(*) FILTER (WHERE a.status_id = 1 AND a.moderation_state = 'active' AND a.date_end > now()) AS active, "
          + "  COUNT(*) FILTER (WHERE d.winner_id IS NOT NULL) AS sold, "
          + "  COALESCE(SUM(d.winning_bid) FILTER (WHERE d.winner_id IS NOT NULL), 0) AS revenue "
          + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
          + "WHERE a.seller_id = ?";

        try (Connection conn = DBUtil.connectDB()) {
            int total = 0, active = 0, sold = 0;
            long revenue = 0;
            try (PreparedStatement ps = conn.prepareStatement(countsSql)) {
                ps.setInt(1, sellerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getInt("total");
                        active = rs.getInt("active");
                        sold = rs.getInt("sold");
                        revenue = rs.getLong("revenue");
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

            List<Map<String, Object>> topListings = new ArrayList<>();
            String topSql =
                "SELECT d.title, "
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
                        row.put("bidCount", rs.getInt("bid_count"));
                        row.put("topBid", rs.getBigDecimal("top_bid"));
                        topListings.add(row);
                    }
                }
            }

            double avgSalePrice = sold > 0 ? (double) revenue / sold : 0;
            double sellThrough = total > 0 ? (double) sold / total * 100.0 : 0;

            out.put("totalListings", total);
            out.put("activeListings", active);
            out.put("soldCount", sold);
            out.put("totalRevenue", revenue);
            out.put("avgSalePrice", Math.round(avgSalePrice * 100.0) / 100.0);
            out.put("sellThroughRate", Math.round(sellThrough * 10.0) / 10.0);
            out.put("bidsReceived", bidsReceived);
            out.put("topListings", topListings);
            out.put("periodStats", loadPeriodStats(conn, sellerId));
            out.put("productRatings", loadProductRatings(conn, sellerId));
            out.put("earningsSummary", loadEarningsSummary(conn, sellerId));
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Map<String, Object>> loadPeriodStats(Connection conn, int sellerId) throws Exception {
        String[] labels = { "daily", "weekly", "monthly", "quarterly" };
        String[] intervals = { "1 day", "7 days", "30 days", "90 days" };
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", labels[i]);
            row.put("sold", countSince(conn,
                    "SELECT COUNT(*) FROM orders WHERE seller_id = ? AND created_at >= now() - interval '"
                  + intervals[i] + "'", sellerId));
            row.put("revenue", countSince(conn,
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
        BigDecimal net = gross.subtract(platformFee).subtract(featuredFees);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("grossSales", gross);
        row.put("platformFee", platformFee);
        row.put("featuredFees", featuredFees);
        row.put("netEarnings", net);
        row.put("completedOrders", countSince(conn,
                "SELECT COUNT(*) FROM orders WHERE seller_id = ? AND status = 'COMPLETED'", sellerId));
        row.put("commissionRatePct", 6);
        row.put("simulated", true);
        return row;
    }

    private BigDecimal sumDecimal(Connection conn, String sql, int sellerId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    private List<Map<String, Object>> loadProductRatings(Connection conn, int sellerId) throws Exception {
        String sql =
            "SELECT d.title, ur.auction_id, COUNT(*) AS review_count, "
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
          + "GROUP BY d.title, ur.auction_id "
          + "ORDER BY review_count DESC, avg_rating DESC LIMIT 10";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sellerId);
            ps.setInt(2, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", rs.getString("title"));
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

    /** Renders a plain-text email body from a generated analytics snapshot. */
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

        Object top = a.get("topListings");
        if (top instanceof List && !((List<?>) top).isEmpty()) {
            sb.append("\nTop listings by bids:\n");
            for (Map<String, Object> row : (List<Map<String, Object>>) top) {
                sb.append("  - ").append(row.get("title"))
                  .append(" (").append(row.get("bidCount")).append(" bids, top $")
                  .append(row.get("topBid")).append(")\n");
            }
        }
        Object periods = a.get("periodStats");
        if (periods instanceof List && !((List<?>) periods).isEmpty()) {
            sb.append("\nPeriod breakdown:\n");
            for (Map<String, Object> p : (List<Map<String, Object>>) periods) {
                sb.append("  ").append(p.get("period")).append(": ")
                  .append(p.get("sold")).append(" sold, $").append(p.get("revenue"))
                  .append(", ").append(p.get("bids")).append(" bids\n");
            }
        }
        Object ratings = a.get("productRatings");
        if (ratings instanceof List && !((List<?>) ratings).isEmpty()) {
            sb.append("\nProduct ratings:\n");
            for (Map<String, Object> pr : (List<Map<String, Object>>) ratings) {
                sb.append("  - ").append(pr.get("title"))
                  .append(" avg ").append(pr.get("avgRating")).append("/5 (")
                  .append(pr.get("reviewCount")).append(" reviews)\n");
            }
        }
        sb.append("\n— AuctionHub");
        return sb.toString();
    }
}
