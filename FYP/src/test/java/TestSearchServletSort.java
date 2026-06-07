import com.auction.dao.CategoryDAO;
import com.auction.dao.SearchDAO;
import com.auction.model.SearchFilter;
import com.auction.model.SearchResultItem;
import com.auction.model.SearchSort;
import com.auction.servlet.SearchServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for buyer search sort feature (SCRUM-60 / SCRUM-350).
 *
 * <p>Coverage:
 * <ul>
 *   <li>Each valid sortBy option is passed to SearchDAO as the correct SearchSort enum</li>
 *   <li>Unknown sortBy → SearchSort.DEFAULT (newest)</li>
 *   <li>SQL injection in sortBy → DEFAULT applied, no error response</li>
 *   <li>SearchSort.fromParam / parseSortBy whitelist validation (SCRUM-349)</li>
 *   <li>SearchSort orderBy fragments are fixed strings (ORDER BY injection prevention)</li>
 * </ul>
 * </p>
 */
public class TestSearchServletSort extends Mockito {

    private static class Wrapper extends SearchServlet {
        Wrapper(SearchDAO dao, CategoryDAO catDao) { super(dao, catDao); }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }
    }

    private SearchDAO mockDAO;
    private CategoryDAO mockCatDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO    = mock(SearchDAO.class);
        mockCatDAO = mock(CategoryDAO.class);
        servlet    = new Wrapper(mockDAO, mockCatDAO);
        req        = mock(HttpServletRequest.class);
        resp       = mock(HttpServletResponse.class);
        when(req.getContextPath()).thenReturn("");
        when(req.getParameter("category")).thenReturn(null);
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(req.getParameter("minPrice")).thenReturn(null);
        when(req.getParameter("maxPrice")).thenReturn(null);
        when(req.getParameter("condition")).thenReturn(null);
        when(req.getParameter("location")).thenReturn(null);
        when(req.getParameter("endWithin")).thenReturn(null);
    }

    private SearchResultItem dummyItem() {
        return new SearchResultItem(1L, "Item", "Electronics",
                BigDecimal.TEN, Instant.now().plusSeconds(3600), "seller1", null);
    }

    private void stubDao() {
        when(mockDAO.search(any(), any(), any(), any(SearchSort.class), anyInt(), anyInt()))
                .thenReturn(List.of(dummyItem()));
        when(mockDAO.count(any(), any(), any())).thenReturn(1);
    }

    private void setupDispatcher() {
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);
    }

    // =========================================================================
    // SearchSort.fromParam — whitelist (SCRUM-349)
    // =========================================================================

    @Nested
    @DisplayName("SearchSort.fromParam — enum whitelist (SCRUM-349)")
    class FromParamTests {

        @Test
        @DisplayName("null sortBy → DEFAULT (newest)")
        void testNullReturnsDefault() {
            assertEquals(SearchSort.DEFAULT, SearchSort.fromParam(null));
        }

        @Test
        @DisplayName("blank sortBy → DEFAULT (newest)")
        void testBlankReturnsDefault() {
            assertEquals(SearchSort.DEFAULT, SearchSort.fromParam("   "));
        }

        @Test
        @DisplayName("newest → SearchSort.NEWEST")
        void testNewest() {
            assertEquals(SearchSort.NEWEST, SearchSort.fromParam("newest"));
        }

        @Test
        @DisplayName("endingSoon → SearchSort.ENDING_SOON")
        void testEndingSoon() {
            assertEquals(SearchSort.ENDING_SOON, SearchSort.fromParam("endingSoon"));
        }

        @Test
        @DisplayName("priceLow → SearchSort.PRICE_LOW")
        void testPriceLow() {
            assertEquals(SearchSort.PRICE_LOW, SearchSort.fromParam("priceLow"));
        }

        @Test
        @DisplayName("priceHigh → SearchSort.PRICE_HIGH")
        void testPriceHigh() {
            assertEquals(SearchSort.PRICE_HIGH, SearchSort.fromParam("priceHigh"));
        }

        @Test
        @DisplayName("unknown sortBy → DEFAULT (newest) (SCRUM-349)")
        void testUnknownReturnsDefault() {
            assertEquals(SearchSort.DEFAULT, SearchSort.fromParam("randomSort"));
            assertEquals(SearchSort.DEFAULT, SearchSort.fromParam("NEWEST")); // case-sensitive param
        }

        @Test
        @DisplayName("SQL injection in sortBy → DEFAULT (SCRUM-349)")
        void testSqlInjectionReturnsDefault() {
            assertEquals(SearchSort.DEFAULT,
                    SearchSort.fromParam("newest; DROP TABLE bids;--"));
            assertEquals(SearchSort.DEFAULT,
                    SearchSort.fromParam("1 OR 1=1"));
        }
    }

    @Nested
    @DisplayName("SearchSort orderBy fragments — fixed SQL (SCRUM-349)")
    class OrderByFragmentTests {

        @Test
        @DisplayName("NEWEST → ORDER BY a.date_created DESC")
        void testNewestOrderBy() {
            assertTrue(SearchSort.NEWEST.orderBySimple().contains("date_created DESC"));
            assertTrue(SearchSort.NEWEST.orderByWrapped().contains("date_created DESC"));
        }

        @Test
        @DisplayName("ENDING_SOON → ORDER BY date_end ASC")
        void testEndingSoonOrderBy() {
            assertTrue(SearchSort.ENDING_SOON.orderBySimple().contains("date_end ASC"));
            assertTrue(SearchSort.ENDING_SOON.orderByWrapped().contains("date_end ASC"));
        }

        @Test
        @DisplayName("PRICE_LOW → ORDER BY current_price ASC")
        void testPriceLowOrderBy() {
            assertTrue(SearchSort.PRICE_LOW.orderBySimple().contains("current_price ASC"));
            assertTrue(SearchSort.PRICE_LOW.orderByWrapped().contains("current_price ASC"));
        }

        @Test
        @DisplayName("PRICE_HIGH → ORDER BY current_price DESC")
        void testPriceHighOrderBy() {
            assertTrue(SearchSort.PRICE_HIGH.orderBySimple().contains("current_price DESC"));
            assertTrue(SearchSort.PRICE_HIGH.orderByWrapped().contains("current_price DESC"));
        }

        @Test
        @DisplayName("orderBy fragments contain no user-input placeholders")
        void testOrderByContainsNoQuestionMarks() {
            for (SearchSort sort : SearchSort.values()) {
                assertFalse(sort.orderBySimple().contains("?"),
                        sort + " simple orderBy must not contain bind placeholders");
                assertFalse(sort.orderByWrapped().contains("?"),
                        sort + " wrapped orderBy must not contain bind placeholders");
            }
        }
    }

    @Nested
    @DisplayName("parseSortBy — servlet helper (SCRUM-349)")
    class ParseSortByTests {

        @Test
        @DisplayName("parseSortBy delegates to SearchSort.fromParam")
        void testParseSortByEndingSoon() {
            when(req.getParameter("sortBy")).thenReturn("endingSoon");
            assertEquals(SearchSort.ENDING_SOON, SearchServlet.parseSortBy(req));
        }

        @Test
        @DisplayName("parseSortBy with injection → DEFAULT")
        void testParseSortByInjection() {
            when(req.getParameter("sortBy")).thenReturn("'; DROP TABLE auction; --");
            assertEquals(SearchSort.DEFAULT, SearchServlet.parseSortBy(req));
        }
    }

    // =========================================================================
    // Servlet integration — sort passed to DAO (SCRUM-348)
    // =========================================================================

    @Nested
    @DisplayName("Servlet integration — sortBy reaches DAO (SCRUM-348)")
    class ServletIntegrationTests {

        @BeforeEach
        void commonSetup() {
            when(req.getParameter("q")).thenReturn("watch");
            stubDao();
            setupDispatcher();
        }

        @Test
        @DisplayName("sortBy=newest → DAO receives SearchSort.NEWEST")
        void testNewestSortPassedToDAO() throws Exception {
            when(req.getParameter("sortBy")).thenReturn("newest");
            servlet.doGet(req, resp);
            verify(mockDAO).search(eq("watch"), isNull(), isNull(),
                    eq(SearchSort.NEWEST), anyInt(), anyInt());
        }

        @Test
        @DisplayName("sortBy=endingSoon → DAO receives SearchSort.ENDING_SOON")
        void testEndingSoonSortPassedToDAO() throws Exception {
            when(req.getParameter("sortBy")).thenReturn("endingSoon");
            servlet.doGet(req, resp);
            verify(mockDAO).search(eq("watch"), isNull(), isNull(),
                    eq(SearchSort.ENDING_SOON), anyInt(), anyInt());
        }

        @Test
        @DisplayName("sortBy=priceLow → DAO receives SearchSort.PRICE_LOW")
        void testPriceLowSortPassedToDAO() throws Exception {
            when(req.getParameter("sortBy")).thenReturn("priceLow");
            servlet.doGet(req, resp);
            verify(mockDAO).search(eq("watch"), isNull(), isNull(),
                    eq(SearchSort.PRICE_LOW), anyInt(), anyInt());
        }

        @Test
        @DisplayName("sortBy=priceHigh → DAO receives SearchSort.PRICE_HIGH")
        void testPriceHighSortPassedToDAO() throws Exception {
            when(req.getParameter("sortBy")).thenReturn("priceHigh");
            servlet.doGet(req, resp);
            verify(mockDAO).search(eq("watch"), isNull(), isNull(),
                    eq(SearchSort.PRICE_HIGH), anyInt(), anyInt());
        }

        @Test
        @DisplayName("missing sortBy → DAO receives SearchSort.DEFAULT (newest)")
        void testMissingSortDefaultsToNewest() throws Exception {
            when(req.getParameter("sortBy")).thenReturn(null);
            servlet.doGet(req, resp);
            verify(mockDAO).search(eq("watch"), isNull(), isNull(),
                    eq(SearchSort.DEFAULT), anyInt(), anyInt());
        }

        @Test
        @DisplayName("unknown sortBy → DAO receives SearchSort.DEFAULT (SCRUM-349)")
        void testUnknownSortDefaultsToNewest() throws Exception {
            when(req.getParameter("sortBy")).thenReturn("invalidOption");
            servlet.doGet(req, resp);
            verify(mockDAO).search(eq("watch"), isNull(), isNull(),
                    eq(SearchSort.DEFAULT), anyInt(), anyInt());
            verify(resp, never()).sendError(anyInt());
        }

        @Test
        @DisplayName("SQL injection in sortBy → DEFAULT applied, no error (SCRUM-349)")
        void testSqlInjectionSortDefaultsSafely() throws Exception {
            when(req.getParameter("sortBy")).thenReturn("priceLow; DELETE FROM bids");
            servlet.doGet(req, resp);

            ArgumentCaptor<SearchSort> captor = ArgumentCaptor.forClass(SearchSort.class);
            verify(mockDAO).search(eq("watch"), isNull(), isNull(), captor.capture(), anyInt(), anyInt());
            assertEquals(SearchSort.DEFAULT, captor.getValue());
            verify(resp, never()).sendError(anyInt());
        }

        @Test
        @DisplayName("sortBy attribute set on request for JSP")
        void testSortByAttrSetOnRequest() throws Exception {
            when(req.getParameter("sortBy")).thenReturn("endingSoon");
            servlet.doGet(req, resp);
            verify(req).setAttribute(eq("sortBy"), eq("endingSoon"));
        }

        @Test
        @DisplayName("invalid sortBy → sortBy attr reflects DEFAULT param value")
        void testInvalidSortAttrReflectsDefault() throws Exception {
            when(req.getParameter("sortBy")).thenReturn("bogus");
            servlet.doGet(req, resp);
            verify(req).setAttribute(eq("sortBy"), eq(SearchSort.DEFAULT.getParamValue()));
        }

        @Test
        @DisplayName("sort combined with filters → both passed to DAO")
        void testSortWithFilters() throws Exception {
            when(req.getParameter("sortBy")).thenReturn("priceHigh");
            when(req.getParameter("minPrice")).thenReturn("50");
            when(mockDAO.search(any(), any(), any(SearchFilter.class), any(SearchSort.class), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDAO.count(any(), any(), any(SearchFilter.class))).thenReturn(0);

            servlet.doGet(req, resp);

            ArgumentCaptor<SearchFilter> filterCaptor = ArgumentCaptor.forClass(SearchFilter.class);
            ArgumentCaptor<SearchSort> sortCaptor = ArgumentCaptor.forClass(SearchSort.class);
            verify(mockDAO).search(eq("watch"), isNull(), filterCaptor.capture(),
                    sortCaptor.capture(), anyInt(), anyInt());
            assertNotNull(filterCaptor.getValue());
            assertEquals(new BigDecimal("50"), filterCaptor.getValue().getMinPrice());
            assertEquals(SearchSort.PRICE_HIGH, sortCaptor.getValue());
        }
    }
}
