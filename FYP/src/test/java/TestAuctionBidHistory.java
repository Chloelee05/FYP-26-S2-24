import com.auction.dao.BidDAO;
import com.auction.model.AuctionBidHistoryEntry;
import com.auction.servlet.AuctionBidHistoryServlet;
import com.auction.util.SecurityUtil;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for public auction bid history (SCRUM-58 / SCRUM-362).
 */
@DisplayName("AuctionBidHistory (SCRUM-58)")
public class TestAuctionBidHistory {

    private static class Wrapper extends AuctionBidHistoryServlet {
        Wrapper(BidDAO dao) { super(dao); }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }
    }

    private BidDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    private static AuctionBidHistoryEntry entry(BigDecimal amount, String masked, boolean leader) {
        return new AuctionBidHistoryEntry(amount, Instant.parse("2024-06-01T12:00:00Z"), masked, leader);
    }

    @BeforeEach
    void setUp() {
        mockDAO = mock(BidDAO.class);
        servlet = new Wrapper(mockDAO);
        req     = mock(HttpServletRequest.class);
        resp    = mock(HttpServletResponse.class);
        when(req.getContextPath()).thenReturn("/app");
    }

    // =========================================================================
    // Public access (SCRUM-362)
    // =========================================================================

    @Nested
    @DisplayName("Public access")
    class PublicAccessTests {

        @Test
        @DisplayName("no session required — bid history loads successfully")
        void testPublicAccessNoSession() throws Exception {
            when(req.getParameter("auctionId")).thenReturn("42");
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("size")).thenReturn(null);
            when(req.getSession(false)).thenReturn(null);
            when(mockDAO.auctionExists(42L)).thenReturn(true);
            when(mockDAO.countBidHistory(42L)).thenReturn(1);
            when(mockDAO.getBidHistory(42L, 1, AuctionBidHistoryServlet.DEFAULT_PAGE_SIZE))
                    .thenReturn(List.of(entry(new BigDecimal("55.00"), "l***r", true)));
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/auction-bid-history.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(resp, never()).sendRedirect(contains("login"));
            verify(rd).forward(req, resp);
        }
    }

    // =========================================================================
    // Username masking (SCRUM-361 / SCRUM-362)
    // =========================================================================

    @Nested
    @DisplayName("Username masking")
    class MaskingTests {

        @Test
        @DisplayName("SecurityUtil partial mask for leader, full mask for others")
        void testSecurityUtilMaskingRules() {
            assertEquals("a***e", SecurityUtil.maskUsername("alice"));
            assertEquals("****", SecurityUtil.maskUsernameFully("alice"));
            assertEquals("****", SecurityUtil.maskUsernameFully(null));
        }

        @Test
        @DisplayName("servlet forwards pre-masked names from DAO — leader vs non-leader")
        void testMaskedNamesForwardedFromDao() throws Exception {
            when(req.getParameter("auctionId")).thenReturn("10");
            when(mockDAO.auctionExists(10L)).thenReturn(true);
            when(mockDAO.countBidHistory(10L)).thenReturn(2);
            List<AuctionBidHistoryEntry> rows = List.of(
                    entry(new BigDecimal("100.00"), "l***r", true),
                    entry(new BigDecimal("80.00"), "****", false));
            when(mockDAO.getBidHistory(10L, 1, AuctionBidHistoryServlet.DEFAULT_PAGE_SIZE))
                    .thenReturn(rows);
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/auction-bid-history.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(req).setAttribute("bidHistory", rows);
            verify(req).setAttribute(eq("bidHistoryEmpty"), eq(false));
        }
    }

    // =========================================================================
    // Non-existent auction → 404 (SCRUM-362)
    // =========================================================================

    @Nested
    @DisplayName("Non-existent auction")
    class NotFoundTests {

        @Test
        @DisplayName("unknown auctionId → 404")
        void testUnknownAuction404() throws Exception {
            when(req.getParameter("auctionId")).thenReturn("99999");
            when(mockDAO.auctionExists(99999L)).thenReturn(false);

            servlet.doGet(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_NOT_FOUND), contains("not found"));
            verify(mockDAO, never()).getBidHistory(anyLong(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("invalid auctionId param → 400")
        void testInvalidAuctionId400() throws Exception {
            when(req.getParameter("auctionId")).thenReturn("abc");

            servlet.doGet(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), contains("Invalid"));
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("missing auctionId → 400")
        void testMissingAuctionId400() throws Exception {
            when(req.getParameter("auctionId")).thenReturn(null);

            servlet.doGet(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }
    }

    // =========================================================================
    // Empty bid list (SCRUM-362)
    // =========================================================================

    @Nested
    @DisplayName("Empty bids")
    class EmptyBidsTests {

        @Test
        @DisplayName("auction with no bids sets bidHistoryEmpty=true")
        void testNoBidsEmptyFlag() throws Exception {
            when(req.getParameter("auctionId")).thenReturn("5");
            when(mockDAO.auctionExists(5L)).thenReturn(true);
            when(mockDAO.countBidHistory(5L)).thenReturn(0);
            when(mockDAO.getBidHistory(5L, 1, AuctionBidHistoryServlet.DEFAULT_PAGE_SIZE))
                    .thenReturn(Collections.emptyList());
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/auction-bid-history.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(req).setAttribute(eq("bidHistoryEmpty"), eq(true));
            verify(req).setAttribute("bidHistory", Collections.emptyList());
            verify(rd).forward(req, resp);
        }
    }

    // =========================================================================
    // Pagination (SCRUM-361 / SCRUM-362)
    // =========================================================================

    @Nested
    @DisplayName("Pagination")
    class PaginationTests {

        @Test
        @DisplayName("parsePage clamps invalid values to 1")
        void testParsePageClamping() {
            when(req.getParameter("page")).thenReturn("-3");
            assertEquals(1, AuctionBidHistoryServlet.parsePage(req));

            when(req.getParameter("page")).thenReturn("abc");
            assertEquals(1, AuctionBidHistoryServlet.parsePage(req));

            when(req.getParameter("page")).thenReturn("3");
            assertEquals(3, AuctionBidHistoryServlet.parsePage(req));
        }

        @Test
        @DisplayName("parsePageSize clamps to [1, MAX]")
        void testParsePageSizeClamping() {
            when(req.getParameter("size")).thenReturn("0");
            assertEquals(1, AuctionBidHistoryServlet.parsePageSize(req));

            when(req.getParameter("size")).thenReturn("999");
            assertEquals(BidDAO.MAX_BID_HISTORY_PAGE_SIZE,
                    AuctionBidHistoryServlet.parsePageSize(req));

            when(req.getParameter("size")).thenReturn("25");
            assertEquals(25, AuctionBidHistoryServlet.parsePageSize(req));
        }

        @Test
        @DisplayName("bidPage/bidSize aliases work on detail page params")
        void testBidPageAliasParsing() {
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("bidPage")).thenReturn("2");
            assertEquals(2, AuctionBidHistoryServlet.parsePage(req));

            when(req.getParameter("size")).thenReturn(null);
            when(req.getParameter("bidSize")).thenReturn("5");
            assertEquals(5, AuctionBidHistoryServlet.parsePageSize(req));
        }

        @Test
        @DisplayName("page beyond total is clamped and DAO re-queried")
        void testPageBeyondTotalClamped() throws Exception {
            when(req.getParameter("auctionId")).thenReturn("42");
            when(req.getParameter("page")).thenReturn("99");
            when(req.getParameter("size")).thenReturn("10");
            when(mockDAO.auctionExists(42L)).thenReturn(true);
            when(mockDAO.countBidHistory(42L)).thenReturn(15);
            when(mockDAO.getBidHistory(42L, 99, 10))
                    .thenReturn(Collections.emptyList());
            when(mockDAO.getBidHistory(42L, 2, 10))
                    .thenReturn(List.of(entry(new BigDecimal("10.00"), "****", false)));
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/auction-bid-history.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(mockDAO).getBidHistory(42L, 2, 10);
            verify(req).setAttribute("bidPage", 2);
            verify(req).setAttribute("bidTotalPages", 2);
        }

        @Test
        @DisplayName("auctionId parsed as long")
        void testAuctionIdParsedAsLong() throws Exception {
            when(req.getParameter("auctionId")).thenReturn("  42  ");
            long id = AuctionBidHistoryServlet.parseAuctionId(req, resp);
            assertEquals(42L, id);
        }
    }
}
