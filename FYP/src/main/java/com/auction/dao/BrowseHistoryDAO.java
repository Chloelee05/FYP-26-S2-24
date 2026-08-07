package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Persists buyer browsing signals (FR4.3) for the recommendation engine.
 *
 * <p>Writes only to {@code browse_history}. Called from the auction detail API when a signed-in
 * buyer opens a listing. {@link RecommendationDAO} later reads these rows as the implicit
 * interest signal behind the SIMILAR_TASTE and SAME_CATEGORY arms.</p>
 */
public class BrowseHistoryDAO {

    /** Records a detail-page view; ignores duplicate views within the last hour. */
    public void recordView(int userId, long auctionId) {
        // INSERT ... SELECT ... WHERE NOT EXISTS does the de-duplication inside the database in one
        // round trip, so a buyer refreshing the same listing repeatedly cannot inflate their own
        // interest signal and skew the recommendation weighting.
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
