package com.auction.dao;

import com.auction.model.SearchResultItem;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Featured / promoted listings (business model — no billing UI).
 */
public class FeaturedListingDAO {

    public boolean featureAuction(long auctionId, int days) {
        int d = days <= 0 ? 7 : days;
        String sql =
            "UPDATE auction SET is_featured = TRUE, "
          + "featured_until = now() + (? || ' days')::interval "
          + "WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, d);
            ps.setLong(2, auctionId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean unfeatureAuction(long auctionId) {
        String sql = "UPDATE auction SET is_featured = FALSE, featured_until = NULL WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<SearchResultItem> listActiveFeatured(int limit) {
        String sql =
            "SELECT a.auction_id, d.title, d.category, "
          + "  COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) AS current_price, "
          + "  a.date_end, u.username, "
          + "  (SELECT image_url FROM auction_images i WHERE i.auction_id = a.auction_id ORDER BY id LIMIT 1) AS thumb "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "JOIN users u ON u.id = a.seller_id "
          + "WHERE a.is_featured = TRUE "
          + "  AND (a.featured_until IS NULL OR a.featured_until > now()) "
          + "  AND a.status_id = 1 AND a.moderation_state = 'active' AND a.date_end > now() "
          + "ORDER BY a.featured_until DESC NULLS LAST, a.date_end ASC "
          + "LIMIT ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<SearchResultItem> out = new ArrayList<>();
                while (rs.next()) {
                    BigDecimal price = rs.getBigDecimal("current_price");
                    Timestamp end = rs.getTimestamp("date_end");
                    Instant endInstant = end != null ? end.toInstant() : null;
                    out.add(new SearchResultItem(
                            rs.getLong("auction_id"),
                            rs.getString("title"),
                            rs.getString("category"),
                            price,
                            endInstant,
                            rs.getString("username"),
                            rs.getString("thumb")));
                }
                return out;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int sellerIdForAuction(long auctionId) {
        String sql = "SELECT seller_id FROM auction WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("seller_id") : -1;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
