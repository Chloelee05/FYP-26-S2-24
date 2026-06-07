package com.auction.dao;

import com.auction.model.SearchFilter;
import com.auction.model.SearchResultItem;
import com.auction.model.SearchSort;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for the public buyer keyword search (SCRUM-48 / SCRUM-59 / SCRUM-60).
 *
 * <p><b>Case sensitivity (SCRUM-259):</b> PostgreSQL {@code ILIKE} is used for
 * case-insensitive matching — "Electronics" matches "electronics" and "ELECTRONICS".</p>
 *
 * <p><b>Injection safety (SCRUM-261 / SCRUM-294 / SCRUM-345):</b> Every filter value
 * — keyword, category, price bounds, condition id, location, end-time timestamp — is
 * <em>always</em> bound via a {@link PreparedStatement} parameter; nothing is ever
 * concatenated into the SQL string. Price bounds are accepted as {@link BigDecimal};
 * the condition id is derived from the {@link com.auction.model.ItemCondition} enum
 * (whitelist), so arbitrary strings never reach the database.</p>
 *
 * <p><b>Scope:</b> Only auctions whose {@code moderation_state = 'active'} and whose
 * {@code date_end} is in the future are returned, so removed/flagged or expired auctions
 * are never surfaced to buyers.</p>
 *
 * <p><b>Price filter (SCRUM-59):</b> When {@code minPrice} or {@code maxPrice} is set the
 * query is wrapped in a derived-table (sub-query) so that the computed column
 * {@code current_price = COALESCE(MAX(bid_amount), starting_price)} can be used in the
 * outer {@code WHERE} clause without repeating the correlated sub-select.</p>
 *
 * <p><b>Sort (SCRUM-60 / SCRUM-349):</b> The {@code sort} parameter selects a fixed
 * {@code ORDER BY} fragment from the {@link SearchSort} enum whitelist. User input is
 * never concatenated into the ORDER BY clause — ORDER BY injection is prevented.</p>
 */
public class SearchDAO {

    /** Maximum allowed page size — mirrors the cap in {@code SearchServlet}. */
    public static final int MAX_PAGE_SIZE = 50;

    /** Max length for the location hint string accepted from the client. */
    static final int LOCATION_MAX_LENGTH = 100;

    // =========================================================================
    // Public API — full-filter versions (SCRUM-59)
    // =========================================================================

    /**
     * Searches active, non-expired auctions whose title or description contains the keyword,
     * with optional multi-dimensional filters.
     *
     * @param keyword      raw keyword; must not be {@code null} or blank
     * @param categoryName exact category name from the DB (already validated); {@code null} = no filter
     * @param filter       optional filters (price range, condition, location, end-time); {@code null} = no filter
     * @param sort         sort order from {@link SearchSort} whitelist; {@code null} → {@link SearchSort#DEFAULT}
     * @param page         1-based page number
     * @param pageSize     rows per page; caller should clamp to [1, {@link #MAX_PAGE_SIZE}]
     * @return ordered page of matching {@link SearchResultItem}s; empty list if none found
     */
    public List<SearchResultItem> search(String keyword, String categoryName,
                                         SearchFilter filter, SearchSort sort,
                                         int page, int pageSize) {
        if (sort == null) sort = SearchSort.DEFAULT;
        List<Object> params = new ArrayList<>();
        int offset = pageSize * (page - 1);

        boolean needsPriceWrap = hasPriceFilter(filter);
        String sql = needsPriceWrap
                ? buildPriceWrappedSearchSql(keyword, categoryName, filter, sort, params, pageSize, offset)
                : buildSimpleSearchSql(keyword, categoryName, filter, sort, params, pageSize, offset);

        List<SearchResultItem> results = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindAll(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return results;
    }

    /**
     * Returns the total count of matching active, non-expired auctions.
     *
     * @param keyword      raw keyword
     * @param categoryName exact category name; {@code null} = no filter
     * @param filter       optional filters; {@code null} = no filter
     * @return number of matching rows
     */
    public int count(String keyword, String categoryName, SearchFilter filter) {
        List<Object> params = new ArrayList<>();
        String sql;
        if (hasPriceFilter(filter)) {
            sql = buildPriceWrappedCountSql(keyword, categoryName, filter, params);
        } else {
            sql = buildSimpleCountSql(keyword, categoryName, filter, params);
        }

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindAll(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    // =========================================================================
    // Backward-compatible overloads (SCRUM-48)
    // =========================================================================

    /** No category and no extra filters; default sort (newest). */
    public List<SearchResultItem> search(String keyword, int page, int pageSize) {
        return search(keyword, null, null, SearchSort.DEFAULT, page, pageSize);
    }

    /** Category filter only — no extra filters; default sort (newest). */
    public List<SearchResultItem> search(String keyword, String categoryName, int page, int pageSize) {
        return search(keyword, categoryName, null, SearchSort.DEFAULT, page, pageSize);
    }

    /** Filters only — default sort (newest). Backward-compatible with SCRUM-59 callers. */
    public List<SearchResultItem> search(String keyword, String categoryName,
                                         SearchFilter filter, int page, int pageSize) {
        return search(keyword, categoryName, filter, SearchSort.DEFAULT, page, pageSize);
    }

    /** No category filter, no extra filters. */
    public int count(String keyword) {
        return count(keyword, null, null);
    }

    /** Category filter only — no extra filters. */
    public int count(String keyword, String categoryName) {
        return count(keyword, categoryName, null);
    }

    // =========================================================================
    // SQL builders
    // =========================================================================

    /**
     * Builds the inner part of the FROM + WHERE clause (shared by search and count).
     * Appends all inner-query parameter values to {@code params} in the correct order.
     */
    private static String buildInnerFromWhere(String keyword, String categoryName,
                                               SearchFilter filter, List<Object> params) {
        String pattern = likePattern(keyword);
        StringBuilder sb = new StringBuilder(
                "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "JOIN users u ON u.id = a.seller_id "
                + "WHERE a.moderation_state = 'active' "
                + "  AND a.date_end > CURRENT_TIMESTAMP "
                + "  AND (d.title ILIKE ? OR d.description ILIKE ?) ");
        params.add(pattern);
        params.add(pattern);

        if (categoryName != null) {
            sb.append("  AND LOWER(d.category) = LOWER(?) ");
            params.add(categoryName);
        }
        if (filter != null && filter.getItemConditionId() != null) {
            sb.append("  AND d.item_condition_id = ? ");
            params.add(filter.getItemConditionId());
        }
        if (filter != null && filter.getEndWithinHours() != null) {
            sb.append("  AND a.date_end <= ? ");
            params.add(Timestamp.from(Instant.now().plusSeconds(
                    (long) filter.getEndWithinHours() * 3600)));
        }
        if (filter != null && filter.getLocation() != null && !filter.getLocation().isBlank()) {
            String locPat = "%" + filter.getLocation() + "%";
            sb.append("  AND (d.title ILIKE ? OR d.description ILIKE ?) ");
            params.add(locPat);
            params.add(locPat);
        }
        return sb.toString();
    }

    /**
     * Simple search SQL (no price filter) — the computed alias {@code current_price} is
     * only needed in the SELECT list; WHERE conditions are applied directly.
     */
    private static String buildSimpleSearchSql(String keyword, String categoryName,
                                                SearchFilter filter, SearchSort sort,
                                                List<Object> params,
                                                int pageSize, int offset) {
        String fromWhere = buildInnerFromWhere(keyword, categoryName, filter, params);
        params.add(pageSize);
        params.add(offset);
        return "SELECT a.auction_id, d.title, d.category, "
                + "COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), "
                + "         d.starting_price) AS current_price, "
                + "a.date_end, u.username AS seller_username, "
                + "(SELECT ai.image_url FROM auction_images ai "
                + " WHERE ai.auction_id = a.auction_id ORDER BY ai.id LIMIT 1) AS thumbnail_url "
                + fromWhere
                + sort.orderBySimple()
                + "LIMIT ? OFFSET ?";
    }

    /**
     * Price-wrapped search SQL — wraps the inner query so that the computed
     * {@code current_price} column can be used in an outer {@code WHERE} clause.
     */
    private static String buildPriceWrappedSearchSql(String keyword, String categoryName,
                                                      SearchFilter filter, SearchSort sort,
                                                      List<Object> params,
                                                      int pageSize, int offset) {
        String fromWhere = buildInnerFromWhere(keyword, categoryName, filter, params);
        String inner = "SELECT a.auction_id, d.title, d.category, a.date_created, "
                + "COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), "
                + "         d.starting_price) AS current_price, "
                + "a.date_end, u.username AS seller_username, "
                + "(SELECT ai.image_url FROM auction_images ai "
                + " WHERE ai.auction_id = a.auction_id ORDER BY ai.id LIMIT 1) AS thumbnail_url "
                + fromWhere;

        StringBuilder outer = new StringBuilder("SELECT * FROM (")
                .append(inner).append(") AS base WHERE 1=1 ");
        appendPriceConditions(filter, outer, params);
        outer.append(sort.orderByWrapped()).append("LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add(offset);
        return outer.toString();
    }

    private static String buildSimpleCountSql(String keyword, String categoryName,
                                               SearchFilter filter, List<Object> params) {
        String fromWhere = buildInnerFromWhere(keyword, categoryName, filter, params);
        return "SELECT COUNT(*)::int " + fromWhere;
    }

    private static String buildPriceWrappedCountSql(String keyword, String categoryName,
                                                     SearchFilter filter, List<Object> params) {
        String fromWhere = buildInnerFromWhere(keyword, categoryName, filter, params);
        String inner = "SELECT "
                + "COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), "
                + "         d.starting_price) AS current_price "
                + fromWhere;
        StringBuilder outer = new StringBuilder("SELECT COUNT(*)::int FROM (")
                .append(inner).append(") AS base WHERE 1=1 ");
        appendPriceConditions(filter, outer, params);
        return outer.toString();
    }

    private static void appendPriceConditions(SearchFilter filter, StringBuilder sql, List<Object> params) {
        if (filter.getMinPrice() != null) {
            sql.append("AND current_price >= ? ");
            params.add(filter.getMinPrice());
        }
        if (filter.getMaxPrice() != null) {
            sql.append("AND current_price <= ? ");
            params.add(filter.getMaxPrice());
        }
    }

    private static boolean hasPriceFilter(SearchFilter filter) {
        return filter != null && (filter.getMinPrice() != null || filter.getMaxPrice() != null);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    private static void bindAll(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v instanceof String)    ps.setString(i + 1, (String) v);
            else if (v instanceof Integer)   ps.setInt(i + 1, (Integer) v);
            else if (v instanceof BigDecimal) ps.setBigDecimal(i + 1, (BigDecimal) v);
            else if (v instanceof Timestamp)  ps.setTimestamp(i + 1, (Timestamp) v);
            else ps.setObject(i + 1, v);
        }
    }

    private static SearchResultItem mapRow(ResultSet rs) throws SQLException {
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
