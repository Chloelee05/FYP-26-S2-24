package com.auction.servlet;

import com.auction.dao.CategoryDAO;
import com.auction.dao.SearchDAO;
import com.auction.model.ItemCondition;
import com.auction.model.SearchFilter;
import com.auction.model.SearchResultItem;
import com.auction.model.admin.Category;
import com.auction.util.InputValidator;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Logger;

/**
 * Public keyword + multi-filter search endpoint for buyers (SCRUM-48 / SCRUM-59).
 *
 * <p><b>Auth policy (SCRUM-260):</b> This servlet is mapped to {@code /search}, which is
 * <em>outside</em> {@code /protected/*} and {@code /admin/*}. No session is required;
 * buyers may search without being logged in.</p>
 *
 * <p><b>Request parameters:</b>
 * <ul>
 *   <li>{@code q}         — keyword; blank → redirect to {@code /}; too long → error banner</li>
 *   <li>{@code category}  — category slug (optional); validated against DB</li>
 *   <li>{@code minPrice}  — minimum current-price filter (non-negative BigDecimal; SCRUM-345)</li>
 *   <li>{@code maxPrice}  — maximum current-price filter (non-negative BigDecimal; SCRUM-345)</li>
 *   <li>{@code condition} — {@link ItemCondition} name whitelist (SCRUM-345)</li>
 *   <li>{@code location}  — free-text location hint; max {@value #LOCATION_MAX_LENGTH} chars</li>
 *   <li>{@code endWithin} — positive integer hours; auction must end within this window</li>
 *   <li>{@code page}      — 1-based page number, default 1</li>
 *   <li>{@code size}      — rows per page, default 12, max {@value SearchDAO#MAX_PAGE_SIZE}</li>
 * </ul>
 * </p>
 *
 * <p><b>Security (SCRUM-294 / SCRUM-345):</b>
 * <ul>
 *   <li>Keyword length is capped at {@link InputValidator#SEARCH_QUERY_MAX_LENGTH} chars.</li>
 *   <li>Keyword/location/query echo is sanitized via {@link SecurityUtil#sanitize(String)}.</li>
 *   <li>minPrice/maxPrice must be non-negative {@link BigDecimal}; any negative or non-numeric
 *       value is <em>silently dropped</em> (not a 400 response).</li>
 *   <li>{@code condition} is validated against the {@link ItemCondition} enum whitelist;
 *       unknown strings are silently dropped — arbitrary strings never reach SQL.</li>
 *   <li>All values reaching the DAO are bound as {@code PreparedStatement} parameters.</li>
 * </ul>
 * </p>
 */
@WebServlet("/search")
public class SearchServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(SearchServlet.class.getName());

    public static final int DEFAULT_PAGE_SIZE = 12;

    /** Maximum accepted length for the location free-text filter (SCRUM-345). */
    public static final int LOCATION_MAX_LENGTH = 100;

    private SearchDAO searchDAO;
    private CategoryDAO categoryDAO;

    public SearchServlet() {
        this.searchDAO   = new SearchDAO();
        this.categoryDAO = new CategoryDAO();
    }

    public SearchServlet(SearchDAO searchDAO) {
        this.searchDAO   = searchDAO;
        this.categoryDAO = new CategoryDAO();
    }

    public SearchServlet(SearchDAO searchDAO, CategoryDAO categoryDAO) {
        this.searchDAO   = searchDAO;
        this.categoryDAO = categoryDAO;
    }

    public void setSearchDAO(SearchDAO searchDAO)       { this.searchDAO   = searchDAO; }
    public void setCategoryDAO(CategoryDAO categoryDAO) { this.categoryDAO = categoryDAO; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String raw = req.getParameter("q");

        // Blank query → redirect home (SCRUM-259)
        if (raw == null || raw.isBlank()) {
            resp.sendRedirect(req.getContextPath() + "/");
            return;
        }

        // SCRUM-294: length cap
        String violation = InputValidator.getSearchQueryViolation(raw);
        if (violation != null) {
            req.setAttribute("searchError", violation);
            req.setAttribute("query", "");
            req.getRequestDispatcher("/WEB-INF/views/search.jsp").forward(req, resp);
            return;
        }

        String keyword  = raw.trim();
        String safeQuery = SecurityUtil.sanitize(keyword);

        // Pagination
        int page = parsePage(req);
        int size = parseSize(req);

        // Category filter (existing SCRUM-47 logic)
        String categorySlug  = req.getParameter("category");
        String categoryName  = null;
        String safeCategorySlug = null;
        if (categorySlug != null && !categorySlug.isBlank()) {
            categorySlug = categorySlug.trim();
            Category cat = categoryDAO.findBySlug(categorySlug);
            if (cat != null) categoryName = cat.getName();
            safeCategorySlug = SecurityUtil.sanitize(categorySlug);
        }

        // SCRUM-59 / SCRUM-345: parse and validate multi-dimensional filters
        SearchFilter filter = parseFilter(req);

        List<SearchResultItem> results = searchDAO.search(keyword, categoryName, filter, page, size);
        int total      = searchDAO.count(keyword, categoryName, filter);
        int totalPages = (total == 0) ? 1 : (int) Math.ceil((double) total / size);

        // Clamp page after knowing totalPages
        if (page > totalPages) {
            page    = totalPages;
            results = searchDAO.search(keyword, categoryName, filter, page, size);
        }

        LOGGER.fine(String.format(
                "Search [q=%s, category=%s, filter=%s] → %d results (page %d/%d).",
                safeQuery, safeCategorySlug,
                filter == null || filter.isEmpty() ? "none" : "active",
                total, page, totalPages));

        // Core attrs
        req.setAttribute("results",     results);
        req.setAttribute("query",       safeQuery);
        req.setAttribute("categorySlug", safeCategorySlug);
        req.setAttribute("categoryName", categoryName);
        req.setAttribute("total",        total);
        req.setAttribute("currentPage",  page);
        req.setAttribute("totalPages",   totalPages);
        req.setAttribute("pageSize",     size);
        req.setAttribute("searchEmpty",  results.isEmpty());

        // SCRUM-59: pass sanitized filter values back to JSP for display / pagination links
        if (filter != null) {
            if (filter.getMinPrice()       != null) req.setAttribute("filterMinPrice",    filter.getMinPrice().toPlainString());
            if (filter.getMaxPrice()       != null) req.setAttribute("filterMaxPrice",    filter.getMaxPrice().toPlainString());
            if (filter.getItemConditionId()!= null) req.setAttribute("filterConditionId", filter.getItemConditionId());
            if (filter.getLocation()       != null) req.setAttribute("filterLocation",    SecurityUtil.sanitize(filter.getLocation()));
            if (filter.getEndWithinHours() != null) req.setAttribute("filterEndWithin",   filter.getEndWithinHours());
        }

        req.getRequestDispatcher("/WEB-INF/views/search.jsp").forward(req, resp);
    }

    // =========================================================================
    // Filter parsing (SCRUM-59 / SCRUM-345)
    // =========================================================================

    /**
     * Parses optional filter parameters from the request.
     *
     * <p>Per SCRUM-345, invalid values are <em>silently dropped</em> — the search still
     * proceeds with the remaining valid filters, rather than returning a 400 error.</p>
     *
     * @return a {@link SearchFilter} (possibly empty), or {@code null} if no filter
     *         parameters were present
     */
    static SearchFilter parseFilter(HttpServletRequest req) {
        SearchFilter.Builder b = SearchFilter.builder();
        boolean hasAny = false;

        // --- minPrice: non-negative BigDecimal (SCRUM-345) ---
        String minStr = req.getParameter("minPrice");
        if (minStr != null && !minStr.isBlank()) {
            try {
                BigDecimal v = new BigDecimal(minStr.trim());
                if (v.compareTo(BigDecimal.ZERO) >= 0) {
                    b.minPrice(v);
                    hasAny = true;
                }
                // negative → silently drop
            } catch (NumberFormatException ignored) {
                // non-numeric (e.g. SQL injection attempt) → silently drop (SCRUM-345)
            }
        }

        // --- maxPrice: non-negative BigDecimal (SCRUM-345) ---
        String maxStr = req.getParameter("maxPrice");
        if (maxStr != null && !maxStr.isBlank()) {
            try {
                BigDecimal v = new BigDecimal(maxStr.trim());
                if (v.compareTo(BigDecimal.ZERO) >= 0) {
                    b.maxPrice(v);
                    hasAny = true;
                }
            } catch (NumberFormatException ignored) { }
        }

        // --- condition: ItemCondition enum whitelist (SCRUM-345) ---
        String condStr = req.getParameter("condition");
        if (condStr != null && !condStr.isBlank()) {
            try {
                ItemCondition cond = ItemCondition.valueOf(condStr.trim().toUpperCase());
                b.itemConditionId(cond.getId());
                hasAny = true;
            } catch (IllegalArgumentException ignored) {
                // unknown string → silently drop; arbitrary strings never reach SQL (SCRUM-345)
            }
        }

        // --- location: free text, max LOCATION_MAX_LENGTH chars ---
        String locStr = req.getParameter("location");
        if (locStr != null && !locStr.isBlank()) {
            String loc = locStr.trim();
            if (loc.length() <= LOCATION_MAX_LENGTH) {
                b.location(loc);  // raw value for DAO (PreparedStatement param)
                hasAny = true;
            }
            // too long → silently drop
        }

        // --- endWithin: positive integer hours ---
        String endStr = req.getParameter("endWithin");
        if (endStr != null && !endStr.isBlank()) {
            try {
                int hours = Integer.parseInt(endStr.trim());
                if (hours > 0) {
                    b.endWithinHours(hours);
                    hasAny = true;
                }
            } catch (NumberFormatException ignored) { }
        }

        return hasAny ? b.build() : null;
    }

    // =========================================================================
    // Pagination helpers
    // =========================================================================

    private static int parsePage(HttpServletRequest req) {
        try {
            String p = req.getParameter("page");
            if (p != null && !p.isBlank()) return Math.max(1, Integer.parseInt(p.trim()));
        } catch (NumberFormatException ignored) { }
        return 1;
    }

    private static int parseSize(HttpServletRequest req) {
        try {
            String s = req.getParameter("size");
            if (s != null && !s.isBlank()) {
                return Math.min(SearchDAO.MAX_PAGE_SIZE, Math.max(1, Integer.parseInt(s.trim())));
            }
        } catch (NumberFormatException ignored) { }
        return DEFAULT_PAGE_SIZE;
    }
}
