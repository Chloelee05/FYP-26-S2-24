import com.auction.dao.CategoryDAO;
import com.auction.dao.SearchDAO;
import com.auction.model.SearchResultItem;
import com.auction.model.admin.Category;
import com.auction.servlet.SearchServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for the category-filter feature added to {@link SearchServlet} (SCRUM-47).
 *
 * <p>Covers three cases required by the story:
 * <ol>
 *   <li>Valid category slug → slug resolved to category name via DB, DAO called with that name.</li>
 *   <li>Unknown category slug → {@link CategoryDAO#findBySlug} returns {@code null}, search runs
 *       without a category filter (not a 400).</li>
 *   <li>SQL injection string in the category param → no DB row matches, search runs unfiltered;
 *       the injection string is handled as a parameterized value and never concatenated into SQL.</li>
 * </ol>
 * </p>
 */
public class TestSearchServletCategory extends Mockito {

    private static class Wrapper extends SearchServlet {
        Wrapper(SearchDAO searchDAO, CategoryDAO categoryDAO) {
            super(searchDAO, categoryDAO);
        }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doGet(req, resp);
        }
    }

    private SearchDAO mockSearchDAO;
    private CategoryDAO mockCategoryDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private RequestDispatcher rd;

    @BeforeEach
    void setUp() {
        mockSearchDAO = mock(SearchDAO.class);
        mockCategoryDAO = mock(CategoryDAO.class);
        servlet = new Wrapper(mockSearchDAO, mockCategoryDAO);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        rd = mock(RequestDispatcher.class);

        when(req.getContextPath()).thenReturn("");
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);
    }

    // =========================================================================
    // Helper: build a minimal Category stub
    // =========================================================================

    private static Category stubCategory(int id, String name, String slug) {
        return new Category(id, name, "desc", 1, slug, false, LocalDateTime.now(), 0);
    }

    // =========================================================================
    // Valid category slug — results filtered by category
    // =========================================================================

    @Test
    @DisplayName("Valid category slug: DAO called with resolved category name")
    void testValidCategorySlugFiltersResults() throws Exception {
        Category electronics = stubCategory(1, "Electronics", "electronics");
        SearchResultItem item = new SearchResultItem(
                10L, "Laptop", "Electronics",
                BigDecimal.valueOf(500), Instant.now().plusSeconds(3600), "seller1", null);

        when(req.getParameter("q")).thenReturn("laptop");
        when(req.getParameter("category")).thenReturn("electronics");
        when(mockCategoryDAO.findBySlug("electronics")).thenReturn(electronics);
        when(mockSearchDAO.search("laptop", "Electronics", 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(List.of(item));
        when(mockSearchDAO.count("laptop", "Electronics")).thenReturn(1);

        servlet.doGet(req, resp);

        verify(mockCategoryDAO).findBySlug("electronics");
        verify(mockSearchDAO).search("laptop", "Electronics", 1, SearchServlet.DEFAULT_PAGE_SIZE);
        verify(mockSearchDAO).count("laptop", "Electronics");
        verify(req).setAttribute(eq("categorySlug"), eq("electronics"));
        verify(req).setAttribute(eq("categoryName"), eq("Electronics"));
        verify(rd).forward(req, resp);
    }

    @Test
    @DisplayName("Valid category slug: results and searchEmpty flag set correctly")
    void testValidCategorySlugSetsAttributes() throws Exception {
        Category collectibles = stubCategory(2, "Collectibles", "collectibles");
        SearchResultItem item = new SearchResultItem(
                20L, "Vintage Watch", "Collectibles",
                BigDecimal.valueOf(200), Instant.now().plusSeconds(7200), "seller2", null);

        when(req.getParameter("q")).thenReturn("watch");
        when(req.getParameter("category")).thenReturn("collectibles");
        when(mockCategoryDAO.findBySlug("collectibles")).thenReturn(collectibles);
        when(mockSearchDAO.search("watch", "Collectibles", 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(List.of(item));
        when(mockSearchDAO.count("watch", "Collectibles")).thenReturn(1);

        servlet.doGet(req, resp);

        verify(req).setAttribute(eq("results"), eq(List.of(item)));
        verify(req).setAttribute(eq("searchEmpty"), eq(false));
        verify(req).setAttribute(eq("total"), eq(1));
    }

    // =========================================================================
    // Unknown category slug — silently ignored, search runs without filter
    // =========================================================================

    @Test
    @DisplayName("Unknown category slug: findBySlug returns null, search runs without category filter (not 400)")
    void testUnknownCategorySlugIsIgnored() throws Exception {
        when(req.getParameter("q")).thenReturn("camera");
        when(req.getParameter("category")).thenReturn("nonexistent-category");
        when(mockCategoryDAO.findBySlug("nonexistent-category")).thenReturn(null);
        when(mockSearchDAO.search("camera", null, 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockSearchDAO.count("camera", null)).thenReturn(0);

        servlet.doGet(req, resp);

        // Must NOT reject with an error status
        verify(resp, never()).sendError(anyInt());
        verify(resp, never()).sendError(anyInt(), anyString());
        // Must call DAO with null categoryName (no filter)
        verify(mockSearchDAO).search("camera", null, 1, SearchServlet.DEFAULT_PAGE_SIZE);
        verify(mockSearchDAO).count("camera", null);
        // categoryName attribute is null since slug was invalid
        verify(req).setAttribute(eq("categoryName"), isNull());
        verify(rd).forward(req, resp);
    }

    @Test
    @DisplayName("No category param: search runs without category filter")
    void testNoCategoryParamRunsUnfiltered() throws Exception {
        when(req.getParameter("q")).thenReturn("phone");
        when(req.getParameter("category")).thenReturn(null);
        when(mockSearchDAO.search("phone", null, 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockSearchDAO.count("phone", null)).thenReturn(0);

        servlet.doGet(req, resp);

        verify(mockCategoryDAO, never()).findBySlug(any());
        verify(mockSearchDAO).search("phone", null, 1, SearchServlet.DEFAULT_PAGE_SIZE);
        verify(req).setAttribute(eq("categorySlug"), isNull());
        verify(req).setAttribute(eq("categoryName"), isNull());
        verify(rd).forward(req, resp);
    }

    // =========================================================================
    // SQL injection in category param — safe passthrough
    // =========================================================================

    @Test
    @DisplayName("SQL injection in category param: no DB match, search runs unfiltered, no error")
    void testSqlInjectionInCategoryIsSafePassthrough() throws Exception {
        String injection = "' OR '1'='1";
        when(req.getParameter("q")).thenReturn("item");
        when(req.getParameter("category")).thenReturn(injection);
        // Injection string passed to findBySlug as a bound PreparedStatement param — returns no match
        when(mockCategoryDAO.findBySlug(injection)).thenReturn(null);
        when(mockSearchDAO.search("item", null, 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockSearchDAO.count("item", null)).thenReturn(0);

        servlet.doGet(req, resp);

        // Injection slug reaches findBySlug safely (parameterized) and returns no category
        verify(mockCategoryDAO).findBySlug(injection);
        // Search proceeds without category filter — injection string never enters the search SQL
        verify(mockSearchDAO).search("item", null, 1, SearchServlet.DEFAULT_PAGE_SIZE);
        verify(mockSearchDAO).count("item", null);
        verify(resp, never()).sendError(anyInt());
        verify(rd).forward(req, resp);
    }

    @Test
    @DisplayName("DROP TABLE injection in category param: no DB match, search runs unfiltered")
    void testDropTableInjectionInCategoryIsSafe() throws Exception {
        String injection = "'; DROP TABLE categories; --";
        when(req.getParameter("q")).thenReturn("item");
        when(req.getParameter("category")).thenReturn(injection);
        when(mockCategoryDAO.findBySlug(injection)).thenReturn(null);
        when(mockSearchDAO.search("item", null, 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockSearchDAO.count("item", null)).thenReturn(0);

        servlet.doGet(req, resp);

        verify(mockCategoryDAO).findBySlug(injection);
        verify(mockSearchDAO).search("item", null, 1, SearchServlet.DEFAULT_PAGE_SIZE);
        verify(resp, never()).sendError(anyInt());
        verify(rd).forward(req, resp);
    }
}
