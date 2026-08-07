package com.auction.servlet.api;

import com.auction.dao.SearchDAO;
import com.auction.model.ItemCondition;
import com.auction.model.SearchFilter;
import com.auction.model.SearchResultItem;
import com.auction.model.SearchSort;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/search
 * Params: q, category, minPrice, maxPrice, condition, location, endWithin,
 *         sortBy (newest|endingSoon|priceLow|priceHigh), page, size
 *
 * <p>Public browse and search endpoint, so guests can call it. The servlet only turns query
 * parameters into a typed {@link SearchFilter} and {@link SearchSort}; the SQL and its
 * parameter binding live in {@link SearchDAO}. Nothing from the request reaches the query as
 * text, which is what keeps the sort and filter options free of injection.</p>
 *
 * <p>Only listings that are live and pass moderation are returned, and the DAO applies the
 * BLIND rule so a sealed auction still in progress shows no price in the result list.</p>
 */
@WebServlet("/api/search")
public class SearchApiServlet extends ApiBase {

    private SearchDAO searchDAO;

    public SearchApiServlet() {
        this.searchDAO = new SearchDAO();
    }

    /** Test hook: lets a unit test assert the parsed filter without a database. */
    public void setSearchDAO(SearchDAO searchDAO) { this.searchDAO = searchDAO; }

    /**
     * Serves GET /api/search. An absent {@code q} means browse everything rather than an error,
     * which is how the category pages call it. {@code size} defaults to 12 and is capped at 50.
     * Returns the page of results plus {@code total}, {@code page}, {@code size} and
     * {@code totalPages}.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String keyword  = param(req, "q");
        if (keyword == null) keyword = "";
        String category = param(req, "category");

        SearchFilter filter = buildFilter(req);
        SearchSort   sort   = parseSort(param(req, "sortBy"));

        int page = parseInt(param(req, "page"), 1);
        int size = Math.min(parseInt(param(req, "size"), 12), 50);

        List<SearchResultItem> results = searchDAO.search(keyword, category, filter, sort, page, size);
        int total = searchDAO.count(keyword, category, filter);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results",     results);
        body.put("total",       total);
        body.put("page",        page);
        body.put("size",        size);
        body.put("totalPages",  (int) Math.ceil((double) total / size));
        ok(resp, body);
    }

    /**
     * Collects the optional filter parameters into a {@link SearchFilter}. A malformed value is
     * dropped rather than rejected, so one bad number in a bookmarked URL still returns results
     * with the remaining filters applied. {@code endWithin} and {@code endAfter} are in hours.
     */
    private SearchFilter buildFilter(HttpServletRequest req) {
        SearchFilter.Builder b = SearchFilter.builder();

        String minP = param(req, "minPrice");
        String maxP = param(req, "maxPrice");
        String cond = param(req, "condition");
        String loc  = param(req, "location");
        String endW = param(req, "endWithin");
        String endA = param(req, "endAfter");

        if (minP != null) { try { b.minPrice(new BigDecimal(minP)); } catch (NumberFormatException ignored) {} }
        if (maxP != null) { try { b.maxPrice(new BigDecimal(maxP)); } catch (NumberFormatException ignored) {} }
        if (loc  != null) b.location(loc.length() > 100 ? loc.substring(0, 100) : loc);
        if (endW != null) { try { int v = Integer.parseInt(endW); if (v > 0) b.endWithinHours(v); } catch (NumberFormatException ignored) {} }
        if (endA != null) { try { int v = Integer.parseInt(endA); if (v > 0) b.endAfterHours(v); } catch (NumberFormatException ignored) {} }
        // Condition arrives as a name and is matched against the enum, so only a known id ever
        // reaches the query. An unrecognised name simply leaves the filter unset.
        if (cond != null) {
            for (ItemCondition ic : ItemCondition.values()) {
                if (ic.name().equalsIgnoreCase(cond)) {
                    b.itemConditionId(ic.getId());
                    break;
                }
            }
        }
        return b.build();
    }

    /** Maps the {@code sortBy} parameter to an enum, which is what stops it being spliced into ORDER BY. */
    private SearchSort parseSort(String raw) {
        return SearchSort.fromParam(raw);
    }

    /** Parses a paging parameter, forced to at least 1 so a bad value cannot produce a negative offset. */
    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return def; }
    }
}
