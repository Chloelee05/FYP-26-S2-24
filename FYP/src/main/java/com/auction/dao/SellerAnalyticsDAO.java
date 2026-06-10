package com.auction.dao;

import com.auction.util.DBUtil;

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
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        sb.append("\n— AuctionHub");
        return sb.toString();
    }
}
