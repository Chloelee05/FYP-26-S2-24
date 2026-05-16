package com.auction.dao;

import com.auction.model.SearchResultItem;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for the public buyer keyword search (SCRUM-48).
 *
 * <p><b>Case sensitivity (SCRUM-259):</b> PostgreSQL {@code ILIKE} is used for
 * case-insensitive matching — "Electronics" matches "electronics" and "ELECTRONICS".</p>
 *
 * <p><b>Injection safety (SCRUM-261 / SCRUM-294):</b> The keyword is <em>always</em> bound
 * via a {@link PreparedStatement} parameter; it is never concatenated into the SQL string.
 * The wildcard pattern {@code %keyword%} is built in Java before binding, so all SQL-special
 * characters ({@code '}, {@code ;}, {@code --}, etc.) in the keyword are treated as literal
 * data by the JDBC driver and the PostgreSQL server.</p>
 *
 * <p><b>Scope:</b> Only auctions whose {@code moderation_state = 'active'} and whose
 * {@code date_end} is in the future are returned, so removed/flagged or expired auctions
 * are never surfaced to buyers.</p>
 */
public class SearchDAO {

    /** Maximum allowed page size — mirrors the cap in {@code SearchServlet}. */
    public static final int MAX_PAGE_SIZE = 50;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Searches active, non-expired auctions whose title or description contains the keyword
     * (case-insensitive). Results are ordered by most-recently created first.
     *
     * @param keyword  raw keyword from the user; must not be {@code null} or blank
     * @param page     1-based page number
     * @param pageSize rows per page; caller should clamp to [1, {@link #MAX_PAGE_SIZE}]
     * @return ordered page of matching {@link SearchResultItem}s; empty list if none found
     */
    public List<SearchResultItem> search(String keyword, int page, int pageSize) {
        String pattern = likePattern(keyword);
        int offset = pageSize * (page - 1);

        String sql = "SELECT a.auction_id, d.title, d.category, "
                + "COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), "
                + "         d.starting_price) AS current_price, "
                + "a.date_end, u.username AS seller_username, "
                + "(SELECT ai.image_url FROM auction_images ai "
                + " WHERE ai.auction_id = a.auction_id ORDER BY ai.id LIMIT 1) AS thumbnail_url "
                + "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "JOIN users u ON u.id = a.seller_id "
                + "WHERE a.moderation_state = 'active' "
                + "  AND a.date_end > CURRENT_TIMESTAMP "
                + "  AND (d.title ILIKE ? OR d.description ILIKE ?) "
                + "ORDER BY a.date_created DESC "
                + "LIMIT ? OFFSET ?";

        List<SearchResultItem> results = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setInt(3, pageSize);
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return results;
    }

    /**
     * Returns the total count of active, non-expired auctions matching the keyword.
     * Used to compute {@code totalPages} for pagination.
     *
     * @param keyword  raw keyword; must not be {@code null} or blank
     * @return number of matching rows
     */
    public int count(String keyword) {
        String pattern = likePattern(keyword);
        String sql = "SELECT COUNT(*)::int FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "WHERE a.moderation_state = 'active' "
                + "  AND a.date_end > CURRENT_TIMESTAMP "
                + "  AND (d.title ILIKE ? OR d.description ILIKE ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the ILIKE pattern {@code %keyword%}.
     * The keyword is used verbatim as a {@code PreparedStatement} parameter, so all
     * SQL-special characters are safely neutralised by the JDBC driver. The {@code %}
     * wildcards are added here in Java and are intentional; user-typed {@code %} or
     * {@code _} behave as LIKE wildcards, which is acceptable search behaviour.
     */
    public static String likePattern(String keyword) {
        return "%" + keyword + "%";
    }

    private static SearchResultItem mapRow(ResultSet rs) throws java.sql.SQLException {
        Timestamp endTs = rs.getTimestamp("date_end");
        BigDecimal price = rs.getBigDecimal("current_price");
        if (price == null) price = BigDecimal.ZERO;
        return new SearchResultItem(
                rs.getLong("auction_id"),
                rs.getString("title"),
                rs.getString("category"),
                price,
                endTs != null ? endTs.toInstant() : null,
                rs.getString("seller_username"),
                rs.getString("thumbnail_url"));
    }
}
