import com.auction.dao.SearchDAO;
import com.auction.model.SearchFilter;
import com.auction.model.SearchResultItem;
import com.auction.servlet.SearchServlet;
import com.auction.util.InputValidator;
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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SearchServlet} (SCRUM-48).
 *
 * <p><b>Auth policy coverage (SCRUM-260):</b> {@code /search} is mapped outside
 * {@code /protected/*} and {@code /admin/*}, so no filter blocks unauthenticated
 * callers. These tests invoke the servlet directly without any session and verify
 * that results are returned — confirming the public-access policy at the servlet layer.
 * No login-required code path exists in {@link SearchServlet}.</p>
 *
 * <p><b>Injection safety (SCRUM-261):</b> The tests verify that SQL-injectable strings
 * (e.g. {@code ' OR '1'='1}, {@code '; DROP TABLE users; --}) are passed to the DAO
 * unchanged — correctly bound as {@code PreparedStatement} parameters — and do not
 * cause 500 errors or special branching in the servlet.</p>
 */
public class TestSearchServlet extends Mockito {

    private static class Wrapper extends SearchServlet {
        Wrapper(SearchDAO dao) { super(dao); }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }
    }

    private SearchDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(SearchDAO.class);
        servlet = new Wrapper(mockDAO);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        when(req.getContextPath()).thenReturn("");
    }

    // =========================================================================
    // Auth policy — public access (SCRUM-260)
    // =========================================================================

    @Test
    @DisplayName("Search is accessible without a session (public auth policy, SCRUM-260)")
    void testPublicAccessNoSession() throws Exception {
        // No session set — getSession(false) returns null by default via mockito
        when(req.getParameter("q")).thenReturn("electronics");
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDAO.search("electronics", null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDAO.count("electronics", null, null)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        // Must NOT throw and must NOT redirect to login
        servlet.doGet(req, resp);

        verify(resp, never()).sendRedirect(contains("login"));
        verify(rd).forward(req, resp);
    }

    // =========================================================================
    // Blank / missing query (SCRUM-259)
    // =========================================================================

    @Test
    @DisplayName("Null query redirects to home")
    void testNullQueryRedirectsHome() throws Exception {
        when(req.getParameter("q")).thenReturn(null);
        servlet.doGet(req, resp);
        verify(resp).sendRedirect("/");
        verify(mockDAO, never()).search(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Blank query redirects to home")
    void testBlankQueryRedirectsHome() throws Exception {
        when(req.getParameter("q")).thenReturn("   ");
        servlet.doGet(req, resp);
        verify(resp).sendRedirect("/");
        verify(mockDAO, never()).search(any(), any(), any(), anyInt(), anyInt());
    }

    // =========================================================================
    // Length cap (SCRUM-294)
    // =========================================================================

    @Test
    @DisplayName("Query exceeding " + InputValidator.SEARCH_QUERY_MAX_LENGTH + " chars returns error attr (SCRUM-294)")
    void testQueryTooLongReturnsError() throws Exception {
        String longQuery = "a".repeat(InputValidator.SEARCH_QUERY_MAX_LENGTH + 1);
        when(req.getParameter("q")).thenReturn(longQuery);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(req).setAttribute(eq("searchError"), contains("200 characters"));
        verify(mockDAO, never()).search(any(), any(), any(), anyInt(), anyInt());
        verify(rd).forward(req, resp);
    }

    @Test
    @DisplayName("Query at exact max length is accepted")
    void testQueryAtMaxLength() throws Exception {
        String maxQuery = "a".repeat(InputValidator.SEARCH_QUERY_MAX_LENGTH);
        when(req.getParameter("q")).thenReturn(maxQuery);
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDAO.search(maxQuery, null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE)).thenReturn(Collections.emptyList());
        when(mockDAO.count(maxQuery, null, null)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(req, never()).setAttribute(eq("searchError"), any());
        verify(mockDAO).search(maxQuery, null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE);
        verify(rd).forward(req, resp);
    }

    // =========================================================================
    // Successful search (SCRUM-258)
    // =========================================================================

    @Test
    @DisplayName("Valid keyword returns results and sets request attributes")
    void testValidKeywordReturnsResults() throws Exception {
        SearchResultItem item = new SearchResultItem(1L, "Vintage Watch", "Collectibles",
                BigDecimal.valueOf(150), Instant.now().plusSeconds(3600), "seller1", null);
        when(req.getParameter("q")).thenReturn("Watch");
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDAO.search("Watch", null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE)).thenReturn(List.of(item));
        when(mockDAO.count("Watch", null, null)).thenReturn(1);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(req).setAttribute(eq("results"), eq(List.of(item)));
        verify(req).setAttribute(eq("total"), eq(1));
        verify(req).setAttribute(eq("currentPage"), eq(1));
        verify(req).setAttribute(eq("totalPages"), eq(1));
        verify(req).setAttribute(eq("searchEmpty"), eq(false));
        verify(rd).forward(req, resp);
    }

    @Test
    @DisplayName("No matching results sets searchEmpty=true (SCRUM-259 empty UX)")
    void testNoResultsSetsEmptyFlag() throws Exception {
        when(req.getParameter("q")).thenReturn("XyzNotExisting");
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDAO.search("XyzNotExisting", null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDAO.count("XyzNotExisting", null, null)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(req).setAttribute(eq("searchEmpty"), eq(true));
        verify(rd).forward(req, resp);
    }

    // =========================================================================
    // Injection safety — special characters (SCRUM-261)
    // =========================================================================

    @Test
    @DisplayName("SQL injection attempt is passed unchanged to DAO as parameterized value (SCRUM-261)")
    void testSqlInjectionAttemptIsHandledSafely() throws Exception {
        String injection = "' OR '1'='1";
        when(req.getParameter("q")).thenReturn(injection);
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDAO.search(injection, null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDAO.count(injection, null, null)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        // Verify that the raw injection string reaches the DAO (PreparedStatement handles it safely)
        verify(mockDAO).search(eq(injection), isNull(), isNull(), anyInt(), anyInt());
        verify(resp, never()).sendError(anyInt());
        verify(rd).forward(req, resp);
    }

    @Test
    @DisplayName("DROP TABLE injection attempt is forwarded safely to DAO (SCRUM-261)")
    void testDropTableInjectionIsHandledSafely() throws Exception {
        String injection = "'; DROP TABLE users; --";
        when(req.getParameter("q")).thenReturn(injection);
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDAO.search(injection, null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDAO.count(injection, null, null)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(mockDAO).search(eq(injection), isNull(), isNull(), anyInt(), anyInt());
        verify(resp, never()).sendError(anyInt());
    }

    @Test
    @DisplayName("Special characters (<, >, &, \") are sanitized before echo (SCRUM-294)")
    void testSpecialCharsAreSanitizedForEcho() throws Exception {
        String xssAttempt = "<script>alert(1)</script>";
        when(req.getParameter("q")).thenReturn(xssAttempt);
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDAO.search(xssAttempt, null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDAO.count(xssAttempt, null, null)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        // The "query" attribute set on the request must not contain raw < or >
        verify(req).setAttribute(eq("query"), argThat(v -> {
            String s = (String) v;
            return !s.contains("<") && !s.contains(">");
        }));
    }

    // =========================================================================
    // Pagination (SCRUM-258)
    // =========================================================================

    @Test
    @DisplayName("page and size params are respected; size is clamped to MAX_PAGE_SIZE")
    void testPaginationParamsAreRespected() throws Exception {
        when(req.getParameter("q")).thenReturn("laptop");
        when(req.getParameter("page")).thenReturn("2");
        when(req.getParameter("size")).thenReturn("999"); // above max → clamped to 50
        when(mockDAO.search("laptop", null, null, 2, SearchDAO.MAX_PAGE_SIZE)).thenReturn(Collections.emptyList());
        when(mockDAO.count("laptop", null, null)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(mockDAO).search("laptop", null, null, 2, SearchDAO.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("Invalid page/size strings default to page=1 and DEFAULT_PAGE_SIZE")
    void testInvalidPaginationDefaultsToFirst() throws Exception {
        when(req.getParameter("q")).thenReturn("phone");
        when(req.getParameter("page")).thenReturn("abc");
        when(req.getParameter("size")).thenReturn("xyz");
        when(mockDAO.search("phone", null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE)).thenReturn(Collections.emptyList());
        when(mockDAO.count("phone", null, null)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(mockDAO).search("phone", null, null, 1, SearchServlet.DEFAULT_PAGE_SIZE);
    }

    // =========================================================================
    // SearchDAO.likePattern helper (SCRUM-258 — pattern construction)
    // =========================================================================

    @Test
    @DisplayName("likePattern wraps keyword with % wildcards")
    void testLikePattern() {
        assertEquals("%Electronics%", SearchDAO.likePattern("Electronics"));
        assertEquals("%100%", SearchDAO.likePattern("100"));
        assertEquals("%a b c%", SearchDAO.likePattern("a b c"));
    }

    // =========================================================================
    // InputValidator.getSearchQueryViolation (SCRUM-294)
    // =========================================================================

    @Test
    @DisplayName("getSearchQueryViolation returns null for blank (caller redirects)")
    void testValidatorBlankIsNull() {
        assertNull(InputValidator.getSearchQueryViolation(null));
        assertNull(InputValidator.getSearchQueryViolation(""));
        assertNull(InputValidator.getSearchQueryViolation("   "));
    }

    @Test
    @DisplayName("getSearchQueryViolation returns null for query within max length")
    void testValidatorWithinLimit() {
        assertNull(InputValidator.getSearchQueryViolation("electronics"));
        assertNull(InputValidator.getSearchQueryViolation("a".repeat(InputValidator.SEARCH_QUERY_MAX_LENGTH)));
    }

    @Test
    @DisplayName("getSearchQueryViolation returns message for query over max length")
    void testValidatorOverLimit() {
        String msg = InputValidator.getSearchQueryViolation("a".repeat(201));
        assertNotNull(msg);
        assertTrue(msg.contains("200"));
    }
}
