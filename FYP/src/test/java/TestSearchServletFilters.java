import com.auction.dao.CategoryDAO;
import com.auction.dao.SearchDAO;
import com.auction.model.ItemCondition;
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
 * Unit tests for buyer search multi-filter feature (SCRUM-59 / SCRUM-346).
 *
 * <p>Coverage:
 * <ul>
 *   <li>Price range filter (minPrice, maxPrice) — passed to DAO as non-null filter</li>
 *   <li>Condition filter — valid ItemCondition name → conditionId in filter</li>
 *   <li>Both filters combined</li>
 *   <li>Negative price → silently ignored (filter remains null for that dimension)</li>
 *   <li>Invalid condition string → silently ignored (SCRUM-345 whitelist)</li>
 *   <li>SQL/special characters in price param → NumberFormatException → dropped safely</li>
 *   <li>No filter params → parseFilter returns null (no-op for DAO)</li>
 *   <li>Location filter → raw string passed to DAO via filter</li>
 *   <li>endWithin filter → positive int hours</li>
 *   <li>Location too long → silently dropped</li>
 * </ul>
 * </p>
 */
public class TestSearchServletFilters extends Mockito {

    /** Exposes protected doGet for testing. */
    private static class Wrapper extends SearchServlet {
        Wrapper(SearchDAO dao, CategoryDAO catDao) { super(dao, catDao); }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }
    }

    private SearchDAO     mockDAO;
    private CategoryDAO   mockCatDAO;
    private Wrapper       servlet;
    private HttpServletRequest  req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO    = mock(SearchDAO.class);
        mockCatDAO = mock(CategoryDAO.class);
        servlet    = new Wrapper(mockDAO, mockCatDAO);
        req        = mock(HttpServletRequest.class);
        resp       = mock(HttpServletResponse.class);
        when(req.getContextPath()).thenReturn("");
        // No category filter in these tests
        when(req.getParameter("category")).thenReturn(null);
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(req.getParameter("sortBy")).thenReturn(null);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SearchResultItem dummyItem() {
        return new SearchResultItem(1L, "Test Item", "Electronics",
                BigDecimal.valueOf(100), Instant.now().plusSeconds(3600), "seller1", null);
    }

    private void stubDaoAny(List<SearchResultItem> results, int count) {
        when(mockDAO.search(any(), any(), any(SearchFilter.class), any(SearchSort.class), anyInt(), anyInt()))
                .thenReturn(results);
        when(mockDAO.count(any(), any(), any(SearchFilter.class))).thenReturn(count);
        when(mockDAO.search(any(), any(), isNull(), any(SearchSort.class), anyInt(), anyInt()))
                .thenReturn(results);
        when(mockDAO.count(any(), any(), isNull())).thenReturn(count);
    }

    private void setupDispatcher() {
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/search.jsp")).thenReturn(rd);
    }

    // =========================================================================
    // parseFilter unit tests (SCRUM-345 — static method, pure logic)
    // =========================================================================

    @Nested
    @DisplayName("parseFilter — static validation logic (SCRUM-345)")
    class ParseFilterTests {

        @Test
        @DisplayName("No filter params → returns null (no active filter)")
        void testNoParamsReturnsNull() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f);
        }

        @Test
        @DisplayName("Valid minPrice and maxPrice → parsed as BigDecimal (SCRUM-345)")
        void testValidPriceRange() {
            when(req.getParameter("minPrice")).thenReturn("50.00");
            when(req.getParameter("maxPrice")).thenReturn("500");
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNotNull(f);
            assertEquals(new BigDecimal("50.00"), f.getMinPrice());
            assertEquals(new BigDecimal("500"), f.getMaxPrice());
        }

        @Test
        @DisplayName("Negative minPrice → silently dropped (SCRUM-345)")
        void testNegativeMinPriceDropped() {
            when(req.getParameter("minPrice")).thenReturn("-10");
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f);  // no valid filter → null returned
        }

        @Test
        @DisplayName("Negative maxPrice → silently dropped (SCRUM-345)")
        void testNegativeMaxPriceDropped() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn("-0.01");
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f);
        }

        @Test
        @DisplayName("Zero minPrice is valid (boundary) (SCRUM-345)")
        void testZeroMinPriceAccepted() {
            when(req.getParameter("minPrice")).thenReturn("0");
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNotNull(f);
            assertEquals(BigDecimal.ZERO, f.getMinPrice().stripTrailingZeros());
        }

        @Test
        @DisplayName("SQL injection in price param → NumberFormatException → dropped safely (SCRUM-345)")
        void testSqlInjectionInPriceDropped() {
            when(req.getParameter("minPrice")).thenReturn("1 OR 1=1");
            when(req.getParameter("maxPrice")).thenReturn("'; DROP TABLE bids; --");
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f, "SQL strings cannot be parsed as BigDecimal and must be silently dropped");
        }

        @Test
        @DisplayName("Non-numeric price string → silently dropped (SCRUM-345)")
        void testNonNumericPriceDropped() {
            when(req.getParameter("minPrice")).thenReturn("abc");
            when(req.getParameter("maxPrice")).thenReturn("xyz");
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f);
        }

        @Test
        @DisplayName("Valid condition BRAND_NEW → conditionId=1 in filter (SCRUM-345 whitelist)")
        void testValidConditionBrandNew() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn("BRAND_NEW");
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNotNull(f);
            assertEquals(ItemCondition.BRAND_NEW.getId(), f.getItemConditionId());
        }

        @Test
        @DisplayName("Valid condition USED (case-insensitive) → conditionId in filter")
        void testValidConditionCaseInsensitive() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn("used");
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNotNull(f);
            assertEquals(ItemCondition.USED.getId(), f.getItemConditionId());
        }

        @Test
        @DisplayName("Invalid condition string → silently dropped (SCRUM-345 whitelist)")
        void testInvalidConditionStringDropped() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn("UNKNOWN_COND");
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f, "Unknown condition string must be silently dropped, not reach SQL");
        }

        @Test
        @DisplayName("SQL injection in condition → IllegalArgumentException → dropped safely (SCRUM-345)")
        void testSqlInjectionInConditionDropped() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn("'; DROP TABLE auction; --");
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f, "SQL strings do not match ItemCondition enum and must be silently dropped");
        }

        @Test
        @DisplayName("Valid location within 100 chars → included in filter")
        void testValidLocationIncluded() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn("Singapore");
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNotNull(f);
            assertEquals("Singapore", f.getLocation());
        }

        @Test
        @DisplayName("Location exceeding 100 chars → silently dropped")
        void testLocationTooLongDropped() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn("A".repeat(101));
            when(req.getParameter("endWithin")).thenReturn(null);

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f);
        }

        @Test
        @DisplayName("Valid endWithin 24 → Integer 24 in filter")
        void testValidEndWithin() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn("24");

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNotNull(f);
            assertEquals(24, f.getEndWithinHours());
        }

        @Test
        @DisplayName("endWithin=0 → silently dropped (must be positive)")
        void testZeroEndWithinDropped() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn("0");

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f);
        }

        @Test
        @DisplayName("endWithin non-numeric → silently dropped")
        void testNonNumericEndWithinDropped() {
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn("soon");

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNull(f);
        }

        @Test
        @DisplayName("All valid filters combined → all dimensions set in filter")
        void testAllFiltersCombined() {
            when(req.getParameter("minPrice")).thenReturn("10");
            when(req.getParameter("maxPrice")).thenReturn("999.99");
            when(req.getParameter("condition")).thenReturn("SLIGHTLY_USED");
            when(req.getParameter("location")).thenReturn("Kuala Lumpur");
            when(req.getParameter("endWithin")).thenReturn("48");

            SearchFilter f = SearchServlet.parseFilter(req);
            assertNotNull(f);
            assertEquals(new BigDecimal("10"),      f.getMinPrice());
            assertEquals(new BigDecimal("999.99"),  f.getMaxPrice());
            assertEquals(ItemCondition.SLIGHTLY_USED.getId(), f.getItemConditionId());
            assertEquals("Kuala Lumpur",            f.getLocation());
            assertEquals(48,                        f.getEndWithinHours());
        }
    }

    // =========================================================================
    // Servlet integration — filter passed to DAO (SCRUM-344)
    // =========================================================================

    @Nested
    @DisplayName("Servlet integration — filter params reach DAO (SCRUM-344)")
    class ServletIntegrationTests {

        @Test
        @DisplayName("Price range filter → DAO called with non-null SearchFilter containing prices")
        void testPriceRangeFilterPassedToDAO() throws Exception {
            when(req.getParameter("q")).thenReturn("watch");
            when(req.getParameter("minPrice")).thenReturn("50");
            when(req.getParameter("maxPrice")).thenReturn("500");
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(List.of(dummyItem()), 1);
            setupDispatcher();

            servlet.doGet(req, resp);

            ArgumentCaptor<SearchFilter> captor = ArgumentCaptor.forClass(SearchFilter.class);
            verify(mockDAO).search(eq("watch"), isNull(), captor.capture(), eq(SearchSort.NEWEST), anyInt(), anyInt());
            SearchFilter captured = captor.getValue();
            assertNotNull(captured);
            assertEquals(new BigDecimal("50"),  captured.getMinPrice());
            assertEquals(new BigDecimal("500"), captured.getMaxPrice());
            assertNull(captured.getItemConditionId());
        }

        @Test
        @DisplayName("Condition filter → DAO called with conditionId from ItemCondition enum")
        void testConditionFilterPassedToDAO() throws Exception {
            when(req.getParameter("q")).thenReturn("laptop");
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn("BRAND_NEW");
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            ArgumentCaptor<SearchFilter> captor = ArgumentCaptor.forClass(SearchFilter.class);
            verify(mockDAO).search(eq("laptop"), isNull(), captor.capture(), eq(SearchSort.NEWEST), anyInt(), anyInt());
            SearchFilter captured = captor.getValue();
            assertNotNull(captured);
            assertEquals(ItemCondition.BRAND_NEW.getId(), captured.getItemConditionId());
            assertNull(captured.getMinPrice());
        }

        @Test
        @DisplayName("Price + condition combined → both present in captured filter")
        void testPriceAndConditionCombined() throws Exception {
            when(req.getParameter("q")).thenReturn("phone");
            when(req.getParameter("minPrice")).thenReturn("100");
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn("USED");
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            ArgumentCaptor<SearchFilter> captor = ArgumentCaptor.forClass(SearchFilter.class);
            verify(mockDAO).search(eq("phone"), isNull(), captor.capture(), eq(SearchSort.NEWEST), anyInt(), anyInt());
            SearchFilter f = captor.getValue();
            assertNotNull(f);
            assertEquals(new BigDecimal("100"), f.getMinPrice());
            assertNull(f.getMaxPrice());
            assertEquals(ItemCondition.USED.getId(), f.getItemConditionId());
        }

        @Test
        @DisplayName("Negative price → filter not sent to DAO (null filter)")
        void testNegativePriceDropped_filterIsNull() throws Exception {
            when(req.getParameter("q")).thenReturn("camera");
            when(req.getParameter("minPrice")).thenReturn("-99");
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            // filter should be null (no valid filter dimensions)
            verify(mockDAO).search(eq("camera"), isNull(), isNull(), eq(SearchSort.NEWEST), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Invalid condition string → filter not sent (null filter) (SCRUM-345)")
        void testInvalidConditionDropped_filterIsNull() throws Exception {
            when(req.getParameter("q")).thenReturn("bike");
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn("NOT_AN_ENUM");
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            verify(mockDAO).search(eq("bike"), isNull(), isNull(), eq(SearchSort.NEWEST), anyInt(), anyInt());
        }

        @Test
        @DisplayName("SQL injection in price → parse fails → filter null → DAO not given injection")
        void testSqlInjectionInPriceSafelyDropped() throws Exception {
            when(req.getParameter("q")).thenReturn("tv");
            when(req.getParameter("minPrice")).thenReturn("1 OR 1=1; DROP TABLE bids;--");
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            // Verify null filter (injection string failed BigDecimal parse → dropped)
            verify(mockDAO).search(eq("tv"), isNull(), isNull(), eq(SearchSort.NEWEST), anyInt(), anyInt());
            verify(resp, never()).sendError(anyInt());
        }

        @Test
        @DisplayName("No filter params → DAO receives null filter (backward compatible)")
        void testNoFilterParamsDAOReceivesNull() throws Exception {
            when(req.getParameter("q")).thenReturn("book");
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            verify(mockDAO).search(eq("book"), isNull(), isNull(), eq(SearchSort.NEWEST), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Location filter → passed raw to DAO via filter object")
        void testLocationFilterPassedToDAO() throws Exception {
            when(req.getParameter("q")).thenReturn("chair");
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn("Singapore");
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            ArgumentCaptor<SearchFilter> captor = ArgumentCaptor.forClass(SearchFilter.class);
            verify(mockDAO).search(eq("chair"), isNull(), captor.capture(), eq(SearchSort.NEWEST), anyInt(), anyInt());
            assertNotNull(captor.getValue());
            assertEquals("Singapore", captor.getValue().getLocation());
        }

        @Test
        @DisplayName("endWithin=24 → filter carries hours=24 to DAO")
        void testEndWithinFilterPassedToDAO() throws Exception {
            when(req.getParameter("q")).thenReturn("desk");
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn("24");
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            ArgumentCaptor<SearchFilter> captor = ArgumentCaptor.forClass(SearchFilter.class);
            verify(mockDAO).search(eq("desk"), isNull(), captor.capture(), eq(SearchSort.NEWEST), anyInt(), anyInt());
            assertNotNull(captor.getValue());
            assertEquals(24, captor.getValue().getEndWithinHours());
        }

        @Test
        @DisplayName("filterMinPrice and filterMaxPrice set as request attrs for JSP display")
        void testFilterAttrsSetOnRequest() throws Exception {
            when(req.getParameter("q")).thenReturn("lamp");
            when(req.getParameter("minPrice")).thenReturn("20");
            when(req.getParameter("maxPrice")).thenReturn("200");
            when(req.getParameter("condition")).thenReturn(null);
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            verify(req).setAttribute(eq("filterMinPrice"), eq("20"));
            verify(req).setAttribute(eq("filterMaxPrice"), eq("200"));
        }

        @Test
        @DisplayName("filterConditionId set as request attr when condition is valid")
        void testConditionIdAttrSetOnRequest() throws Exception {
            when(req.getParameter("q")).thenReturn("mug");
            when(req.getParameter("minPrice")).thenReturn(null);
            when(req.getParameter("maxPrice")).thenReturn(null);
            when(req.getParameter("condition")).thenReturn("DAMAGED");
            when(req.getParameter("location")).thenReturn(null);
            when(req.getParameter("endWithin")).thenReturn(null);
            stubDaoAny(Collections.emptyList(), 0);
            setupDispatcher();

            servlet.doGet(req, resp);

            verify(req).setAttribute(eq("filterConditionId"), eq(ItemCondition.DAMAGED.getId()));
        }
    }

    // =========================================================================
    // SearchFilter model — isEmpty helper
    // =========================================================================

    @Nested
    @DisplayName("SearchFilter.isEmpty()")
    class SearchFilterEmptyTests {

        @Test
        @DisplayName("Default builder → isEmpty() is true")
        void testEmptyFilter() {
            SearchFilter f = SearchFilter.builder().build();
            assertTrue(f.isEmpty());
        }

        @Test
        @DisplayName("Filter with minPrice → isEmpty() is false")
        void testFilterWithMinPriceNotEmpty() {
            SearchFilter f = SearchFilter.builder().minPrice(BigDecimal.TEN).build();
            assertFalse(f.isEmpty());
        }

        @Test
        @DisplayName("Filter with conditionId → isEmpty() is false")
        void testFilterWithConditionNotEmpty() {
            SearchFilter f = SearchFilter.builder().itemConditionId(1).build();
            assertFalse(f.isEmpty());
        }
    }
}
