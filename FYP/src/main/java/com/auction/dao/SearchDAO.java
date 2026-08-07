package com.auction.dao;

import com.auction.model.AuctionType;
import com.auction.model.SearchFilter;
import com.auction.model.SearchResultItem;
import com.auction.model.SearchSort;
import com.auction.util.DBUtil;
import com.auction.util.DutchClock;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for the public buyer keyword search (SCRUM-48 / SCRUM-59 / SCRUM-60).
 *
 * <p>Reads {@code auction}, {@code auction_details}, {@code users}, {@code auction_status},
 * {@code bids} and {@code auction_images}. Called by the search API servlet for the buyer browse
 * and results pages, which are open to guests. Writes nothing.</p>
 *
 * <p><b>Case sensitivity (SCRUM-259):</b> PostgreSQL {@code ILIKE} is used for
 * case-insensitive matching, so "Electronics" matches "electronics" and "ELECTRONICS".</p>
 *
 * <p><b>Injection safety (SCRUM-261 / SCRUM-294 / SCRUM-345):</b> Every filter value,
 * meaning keyword, category, price bounds, condition id, location and end-time timestamp, is
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
 * never concatenated into the ORDER BY clause, which prevents ORDER BY injection.</p>
 */
public class SearchDAO {

    /** Maximum allowed page size, mirroring the cap in {@code SearchServlet}. */
    public static final int MAX_PAGE_SIZE = 50;

    /** Max length for the location hint string accepted from the client. */
    static final int LOCATION_MAX_LENGTH = 100;

    // =========================================================================
    // Public API, full-filter versions (SCRUM-59)
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
        // params is filled in the same order the builders append their placeholders, then bound
        // positionally by bindAll. The SQL is assembled conditionally, so the parameter list has to
        // be built alongside it rather than declared up front.
        List<Object> params = new ArrayList<>();
        int offset = pageSize * (page - 1);

        // Two query shapes, chosen by whether a price bound was supplied. See the builders below.
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
    // Kept in step with search() on purpose: it applies exactly the same filters, so the
    // pagination total the UI shows cannot disagree with the pages it is counting.
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

    /** Category filter only, no extra filters; default sort (newest). */
    public List<SearchResultItem> search(String keyword, String categoryName, int page, int pageSize) {
        return search(keyword, categoryName, null, SearchSort.DEFAULT, page, pageSize);
    }

    /** Filters only, with the default sort (newest). Backward-compatible with SCRUM-59 callers. */
    public List<SearchResultItem> search(String keyword, String categoryName,
                                         SearchFilter filter, int page, int pageSize) {
        return search(keyword, categoryName, filter, SearchSort.DEFAULT, page, pageSize);
    }

    /** No category filter, no extra filters. */
    public int count(String keyword) {
        return count(keyword, null, null);
    }

    /** Category filter only, no extra filters. */
    public int count(String keyword, String categoryName) {
        return count(keyword, categoryName, null);
    }

    // =========================================================================
    // SQL builders
    // =========================================================================

    /**
     * The {@code current_price} column. Blind auctions resolve to their starting
     * price instead of the leading bid: every row these queries return is still
     * open, so the top sealed bid must not leave the server. That covers both the
     * payload and what could be inferred through the price filter, which runs on
     * this same column.
     *
     * <p>For the other two auction types the value is the highest bid so far, or the starting
     * price when there are no bids yet, which is what the COALESCE around the MAX subquery
     * provides. Held as a constant so the search query and the count query cannot drift apart.</p>
     */
    private static final String SEALED_SAFE_PRICE =
            "CASE WHEN a.auction_type = " + AuctionType.BLIND.getId() + " THEN d.starting_price "
          + "     ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), "
          + "                   d.starting_price) END AS current_price, ";

    /**
     * The inputs {@link DutchClock#listedPrice} needs to re-price a descending listing in
     * {@link #mapRow}. {@code current_price} above is the figure the bids table knows about,
     * which for a Dutch auction is the price its clock started at rather than the price on
     * offer now.
     */
    private static final String DUTCH_CLOCK_COLUMNS =
            "d.starting_price, d.dutch_floor_price, a.date_created, ";

    /**
     * Builds the inner part of the FROM + WHERE clause (shared by search and count).
     * Appends all inner-query parameter values to {@code params} in the correct order.
     */
    private static String buildInnerFromWhere(String keyword, String categoryName,
                                               SearchFilter filter, List<Object> params) {
        String pattern = likePattern(keyword);
        // Three visibility conditions come before any user filter: moderation-approved, status
        // Active, and not yet ended. A removed, suspended or expired listing is never searchable.
        // The keyword is matched against both title and description, hence two bindings of the
        // same pattern.
        StringBuilder sb = new StringBuilder(
                "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "JOIN users u ON u.id = a.seller_id "
                + "JOIN auction_status s ON s.id = a.status_id "
                + "WHERE a.moderation_state = 'active' "
                + "  AND s.status = 'Active' "
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
        if (filter != null && filter.getEndAfterHours() != null) {
            sb.append("  AND a.date_end > ? ");
            params.add(Timestamp.from(Instant.now().plusSeconds(
                    (long) filter.getEndAfterHours() * 3600)));
        }
        if (filter != null && filter.getLocation() != null && !filter.getLocation().isBlank()) {
            // There is no location column, so a location hint is matched as free text against the
            // title and description. It narrows results rather than filtering precisely.
            String locPat = "%" + filter.getLocation() + "%";
            sb.append("  AND (d.title ILIKE ? OR d.description ILIKE ?) ");
            params.add(locPat);
            params.add(locPat);
        }
        return sb.toString();
    }

    /**
     * Simple search SQL, used when no price filter is set. The computed alias
     * {@code current_price} is only needed in the SELECT list, so the WHERE conditions can be
     * applied directly against the base tables with no wrapping.
     */
    private static String buildSimpleSearchSql(String keyword, String categoryName,
                                                SearchFilter filter, SearchSort sort,
                                                List<Object> params,
                                                int pageSize, int offset) {
        String fromWhere = buildInnerFromWhere(keyword, categoryName, filter, params);
        params.add(pageSize);
        params.add(offset);
        return "SELECT a.auction_id, d.title, d.category, a.auction_type, "
                + SEALED_SAFE_PRICE
                + DUTCH_CLOCK_COLUMNS
                + "a.date_end, u.username AS seller_username, "
                + "(SELECT ai.image_url FROM auction_images ai "
                + " WHERE ai.auction_id = a.auction_id ORDER BY ai.id LIMIT 1) AS thumbnail_url "
                + fromWhere
                + sort.orderBySimple()
                + "LIMIT ? OFFSET ?";
    }

    /**
     * Price-wrapped search SQL. SQL does not allow a SELECT alias in the WHERE clause of the same
     * query, so the whole result set becomes a derived table named {@code base} and the price
     * bounds are applied outside it. That way {@code current_price} is computed once and the
     * price filter tests the same figure the buyer sees on the card, including the blind-auction
     * substitution.
     *
     * <p>Parameter order matters here: the inner filters bind first, then the price bounds, then
     * the limit and offset, which is why the page size is appended after
     * {@link #appendPriceConditions}.</p>
     */
    private static String buildPriceWrappedSearchSql(String keyword, String categoryName,
                                                      SearchFilter filter, SearchSort sort,
                                                      List<Object> params,
                                                      int pageSize, int offset) {
        String fromWhere = buildInnerFromWhere(keyword, categoryName, filter, params);
        String inner = "SELECT a.auction_id, d.title, d.category, a.auction_type, "
                + SEALED_SAFE_PRICE
                + DUTCH_CLOCK_COLUMNS
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

    /** Row count for the unfiltered-by-price case: the same FROM and WHERE, counted instead of selected. */
    private static String buildSimpleCountSql(String keyword, String categoryName,
                                               SearchFilter filter, List<Object> params) {
        String fromWhere = buildInnerFromWhere(keyword, categoryName, filter, params);
        return "SELECT COUNT(*)::int " + fromWhere;
    }

    /**
     * Row count for the price-filtered case, wrapped the same way as the search query so the
     * total and the pages agree.
     */
    private static String buildPriceWrappedCountSql(String keyword, String categoryName,
                                                     SearchFilter filter, List<Object> params) {
        String fromWhere = buildInnerFromWhere(keyword, categoryName, filter, params);
        // The same sealed-safe column the result page is built from. This query returns only
        // a count, so nothing leaves directly, but the price filter runs on this column, so
        // computing it from the true sealed bid made the result count answer "is the top bid
        // between X and Y?" for any range the caller tried. It also made the count disagree
        // with the page it counts, since that one already resolved blind rows to entry price.
        String inner = "SELECT " + SEALED_SAFE_PRICE + "a.auction_id " + fromWhere;
        StringBuilder outer = new StringBuilder("SELECT COUNT(*)::int FROM (")
                .append(inner).append(") AS base WHERE 1=1 ");
        appendPriceConditions(filter, outer, params);
        return outer.toString();
    }

    /**
     * Appends the price bounds to an outer WHERE. The caller has already written "WHERE 1=1", which
     * is what lets both bounds be optional and still start with AND.
     */
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

    /** Whether either price bound was supplied, which decides between the two query shapes. */
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

    /**
     * Binds the collected values positionally. The type switch exists because the parameter list is
     * heterogeneous (patterns, ids, money, timestamps) and JDBC needs the right setter for each,
     * particularly for BigDecimal and Timestamp.
     */
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

    /** Builds one result card, applying the Dutch clock on top of the price the query resolved. */
    private static SearchResultItem mapRow(ResultSet rs) throws SQLException {
        Timestamp endTs = rs.getTimestamp("date_end");
        Timestamp startTs = rs.getTimestamp("date_created");
        int typeId = rs.getInt("auction_type");
        BigDecimal price = rs.getBigDecimal("current_price");
        if (price == null) price = BigDecimal.ZERO;
        // Only a Dutch listing changes here. Routing every card through DutchClock keeps the
        // search grid, the featured carousel and the detail page quoting the same declining price.
        price = DutchClock.listedPrice(typeId, price,
                rs.getBigDecimal("starting_price"), rs.getBigDecimal("dutch_floor_price"),
                startTs != null ? startTs.toInstant() : null,
                endTs != null ? endTs.toInstant() : null,
                Instant.now());
        return new SearchResultItem(
                rs.getLong("auction_id"),
                rs.getString("title"),
                rs.getString("category"),
                price,
                endTs != null ? endTs.toInstant() : null,
                rs.getString("seller_username"),
                rs.getString("thumbnail_url"),
                typeId);
    }
}
