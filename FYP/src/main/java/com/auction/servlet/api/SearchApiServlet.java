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
 */
@WebServlet("/api/search")
public class SearchApiServlet extends ApiBase {

    private SearchDAO searchDAO;

    public SearchApiServlet() {
        this.searchDAO = new SearchDAO();
    }

    /** Test hook */
    public void setSearchDAO(SearchDAO searchDAO) { this.searchDAO = searchDAO; }

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

    private SearchFilter buildFilter(HttpServletRequest req) {
        SearchFilter.Builder b = SearchFilter.builder();

        String minP = param(req, "minPrice");
        String maxP = param(req, "maxPrice");
        String cond = param(req, "condition");
        String loc  = param(req, "location");
        String endW = param(req, "endWithin");

        if (minP != null) { try { b.minPrice(new BigDecimal(minP)); } catch (NumberFormatException ignored) {} }
        if (maxP != null) { try { b.maxPrice(new BigDecimal(maxP)); } catch (NumberFormatException ignored) {} }
        if (loc  != null) b.location(loc.length() > 100 ? loc.substring(0, 100) : loc);
        if (endW != null) { try { int v = Integer.parseInt(endW); if (v > 0) b.endWithinHours(v); } catch (NumberFormatException ignored) {} }
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

    private SearchSort parseSort(String raw) {
        return SearchSort.fromParam(raw);
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return def; }
    }
}
