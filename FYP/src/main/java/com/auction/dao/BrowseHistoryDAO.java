package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Persists buyer browsing signals (FR4.3) for the recommendation engine.
 */
public class BrowseHistoryDAO {

    /** Records a detail-page view; ignores duplicate views within the last hour. */
    public void recordView(int userId, long auctionId) {
        String sql =
            "INSERT INTO browse_history (user_id, auction_id) "
          + "SELECT ?, ? "
          + "WHERE NOT EXISTS ( "
          + "  SELECT 1 FROM browse_history "
          + "  WHERE user_id = ? AND auction_id = ? "
          + "    AND viewed_at > now() - interval '1 hour' "
          + ")";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setLong(2, auctionId);
            ps.setInt(3, userId);
            ps.setLong(4, auctionId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
