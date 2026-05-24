package com.auction.servlet;

import com.auction.dao.CategoryDAO;
import com.auction.dao.SearchDAO;
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
import java.util.List;
import java.util.logging.Logger;

/**
 * Public keyword search endpoint for buyers (SCRUM-48).
 *
 * <p><b>Auth policy (SCRUM-260):</b> This servlet is mapped to {@code /search}, which is
 * <em>outside</em> {@code /protected/*} and {@code /admin/*}. No session is required;
 * buyers may search without being logged in.</p>
 *
 * <p><b>Request parameters:</b>
 * <ul>
 *   <li>{@code q} — keyword; blank → redirect to {@code /}; too long → error banner</li>
 *   <li>{@code category} — category slug (optional); validated against DB — unknown slug is
 *       silently ignored (not a 400), so buyers are never blocked by a stale or mistyped value</li>
 *   <li>{@code page} — 1-based page number, default 1</li>
 *   <li>{@code size} — rows per page, default 12, max {@value SearchDAO#MAX_PAGE_SIZE}</li>
 * </ul>
 * </p>
 *
 * <p><b>Security (SCRUM-294):</b>
 * <ul>
 *   <li>Keyword length is capped at {@link InputValidator#SEARCH_QUERY_MAX_LENGTH} chars.</li>
 *   <li>Keyword is bound as a {@code PreparedStatement} parameter — never concatenated
 *       into SQL (prevents injection, SCRUM-261).</li>
 *   <li>Keyword is sanitized via {@link SecurityUtil#sanitize(String)} before being set
 *       as a request attribute and echoed in the JSP.</li>
 * </ul>
 * </p>
 */
@WebServlet("/search")
public class SearchServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(SearchServlet.class.getName());

    public static final int DEFAULT_PAGE_SIZE = 12;

    private SearchDAO searchDAO;
    private CategoryDAO categoryDAO;

    public SearchServlet() {
        this.searchDAO = new SearchDAO();
        this.categoryDAO = new CategoryDAO();
    }

    public SearchServlet(SearchDAO searchDAO) {
        this.searchDAO = searchDAO;
        this.categoryDAO = new CategoryDAO();
    }

    public SearchServlet(SearchDAO searchDAO, CategoryDAO categoryDAO) {
        this.searchDAO = searchDAO;
        this.categoryDAO = categoryDAO;
    }

    public void setSearchDAO(SearchDAO searchDAO) {
        this.searchDAO = searchDAO;
    }

    public void setCategoryDAO(CategoryDAO categoryDAO) {
        this.categoryDAO = categoryDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String raw = req.getParameter("q");

        // Blank query → redirect home (SCRUM-259: don't show empty-results page for no input)
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

        // SCRUM-294: sanitize for echo (HTML-escape for XSS; JSP also uses <c:out>)
        String keyword = raw.trim();
        String safeQuery = SecurityUtil.sanitize(keyword);

        // Pagination
        int page = parsePage(req);
        int size = parseSize(req);

        // Category filter — validated against DB; unknown slug is silently ignored (not a 400)
        String categorySlug = req.getParameter("category");
        String categoryName = null;
        String safeCategorySlug = null;
        if (categorySlug != null && !categorySlug.isBlank()) {
            categorySlug = categorySlug.trim();
            Category cat = categoryDAO.findBySlug(categorySlug);
            if (cat != null) {
                categoryName = cat.getName();
            }
            safeCategorySlug = SecurityUtil.sanitize(categorySlug);
        }

        List<SearchResultItem> results = searchDAO.search(keyword, categoryName, page, size);
        int total = searchDAO.count(keyword, categoryName);
        int totalPages = (total == 0) ? 1 : (int) Math.ceil((double) total / size);

        // Clamp page within valid range after we know totalPages
        if (page > totalPages) {
            page = totalPages;
            results = searchDAO.search(keyword, categoryName, page, size);
        }

        LOGGER.fine(String.format("Search [q=%s, category=%s] returned %d results (page %d/%d).",
                safeQuery, safeCategorySlug, total, page, totalPages));

        req.setAttribute("results", results);
        req.setAttribute("query", safeQuery);
        req.setAttribute("categorySlug", safeCategorySlug);
        req.setAttribute("categoryName", categoryName);
        req.setAttribute("total", total);
        req.setAttribute("currentPage", page);
        req.setAttribute("totalPages", totalPages);
        req.setAttribute("pageSize", size);
        // SCRUM-259: flag for empty-results UX
        req.setAttribute("searchEmpty", results.isEmpty());

        req.getRequestDispatcher("/WEB-INF/views/search.jsp").forward(req, resp);
    }

    // -------------------------------------------------------------------------
    // Pagination helpers
    // -------------------------------------------------------------------------

    private static int parsePage(HttpServletRequest req) {
        try {
            String p = req.getParameter("page");
            if (p != null && !p.isBlank()) {
                return Math.max(1, Integer.parseInt(p.trim()));
            }
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
