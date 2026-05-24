import com.auction.dao.ProfileActivityDAO;
import com.auction.model.profile.BidHistoryRow;
import com.auction.servlet.BiddingHistoryServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BiddingHistoryServlet}.
 *
 * <p><b>Auth policy:</b> Mapped to {@code /protected/bidding-history}; no session → redirect
 * to login. Any authenticated role (BUYER or SELLER) may view their own bid history.</p>
 *
 * <p><b>No IDOR:</b> {@code userId} is always sourced from the session; an attacker-supplied
 * request parameter is silently ignored.</p>
 */
public class TestBiddingHistoryServlet {

    private static class Wrapper extends BiddingHistoryServlet {
        Wrapper(ProfileActivityDAO dao) { super(dao); }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }
    }

    private ProfileActivityDAO mockDao;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDao  = mock(ProfileActivityDAO.class);
        servlet  = new Wrapper(mockDao);
        req      = mock(HttpServletRequest.class);
        resp     = mock(HttpServletResponse.class);
        session  = mock(HttpSession.class);
        when(req.getContextPath()).thenReturn("");
    }

    // =========================================================================
    // Auth policy — unauthenticated access
    // =========================================================================

    @Test
    @DisplayName("No session → redirect to /login")
    void testNoSessionRedirectsToLogin() throws Exception {
        when(req.getSession(false)).thenReturn(null);

        servlet.doGet(req, resp);

        verify(resp).sendRedirect("/login");
        verifyNoInteractions(mockDao);
    }

    @Test
    @DisplayName("Session without userId attribute → redirect to /login")
    void testSessionMissingUserIdRedirectsToLogin() throws Exception {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(null);

        servlet.doGet(req, resp);

        verify(resp).sendRedirect("/login");
        verifyNoInteractions(mockDao);
    }

    // =========================================================================
    // Role access — SELLER can view own bids
    // =========================================================================

    @Test
    @DisplayName("SELLER with valid session can view their own bid history")
    void testSellerCanViewOwnBidHistory() throws Exception {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(7);
        when(session.getAttribute("userRole")).thenReturn("SELLER");
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDao.getBidHistory(7, 1, BiddingHistoryServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDao.countBidHistory(7)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/bidding-history.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        // Must NOT be forbidden or redirected to login
        verify(resp, never()).sendRedirect(anyString());
        verify(resp, never()).sendError(anyInt());
        verify(rd).forward(req, resp);
    }

    // =========================================================================
    // No IDOR — userId from session only
    // =========================================================================

    @Test
    @DisplayName("userId is taken from session, not from request parameter (no IDOR)")
    void testUserIdFromSessionNotRequestParam() throws Exception {
        // Session user is 5; request carries userId=9 (attacker-controlled)
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(5);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(req.getParameter("userId")).thenReturn("9");
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDao.getBidHistory(5, 1, BiddingHistoryServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDao.countBidHistory(5)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/bidding-history.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(mockDao).getBidHistory(eq(5), anyInt(), anyInt());
        verify(mockDao, never()).getBidHistory(eq(9), anyInt(), anyInt());
        verify(rd).forward(req, resp);
    }

    // =========================================================================
    // Empty history
    // =========================================================================

    @Test
    @DisplayName("User with no bids receives an empty list and total=0")
    void testEmptyBidHistory() throws Exception {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(42);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDao.getBidHistory(42, 1, BiddingHistoryServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDao.countBidHistory(42)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/bidding-history.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(req).setAttribute(eq("bids"), eq(Collections.emptyList()));
        verify(req).setAttribute(eq("total"), eq(0));
        verify(req).setAttribute(eq("totalPages"), eq(1));
        verify(rd).forward(req, resp);
    }

    // =========================================================================
    // Results and request attributes
    // =========================================================================

    @Test
    @DisplayName("Bid history rows are set as request attribute with correct totals")
    void testBidHistoryRowsSetAsAttribute() throws Exception {
        BidHistoryRow row = new BidHistoryRow(
                101L, "Vintage Camera", BigDecimal.valueOf(250),
                LocalDateTime.now(), "Ended", true);
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(3);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(req.getParameter("page")).thenReturn(null);
        when(req.getParameter("size")).thenReturn(null);
        when(mockDao.getBidHistory(3, 1, BiddingHistoryServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(List.of(row));
        when(mockDao.countBidHistory(3)).thenReturn(1);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/bidding-history.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(req).setAttribute(eq("bids"), eq(List.of(row)));
        verify(req).setAttribute(eq("total"), eq(1));
        verify(req).setAttribute(eq("currentPage"), eq(1));
        verify(req).setAttribute(eq("totalPages"), eq(1));
        verify(rd).forward(req, resp);
    }

    // =========================================================================
    // Pagination
    // =========================================================================

    @Test
    @DisplayName("page and size params are passed to DAO; size is clamped to MAX_PAGE_SIZE")
    void testPaginationParamsRespected() throws Exception {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(10);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(req.getParameter("page")).thenReturn("3");
        when(req.getParameter("size")).thenReturn("999"); // above max → clamped to 50
        when(mockDao.getBidHistory(10, 3, BiddingHistoryServlet.MAX_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDao.countBidHistory(10)).thenReturn(100);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/bidding-history.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(mockDao).getBidHistory(10, 3, BiddingHistoryServlet.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("Invalid page/size strings default to page=1 and DEFAULT_PAGE_SIZE")
    void testInvalidPaginationDefaultsToFirst() throws Exception {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(10);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(req.getParameter("page")).thenReturn("notanumber");
        when(req.getParameter("size")).thenReturn("xyz");
        when(mockDao.getBidHistory(10, 1, BiddingHistoryServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDao.countBidHistory(10)).thenReturn(0);
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/bidding-history.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(mockDao).getBidHistory(10, 1, BiddingHistoryServlet.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("page beyond totalPages is clamped to totalPages and DAO is re-queried")
    void testPageBeyondTotalIsClamped() throws Exception {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userId")).thenReturn(10);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(req.getParameter("page")).thenReturn("99");
        when(req.getParameter("size")).thenReturn(null);
        // total=5 with default size=10 → totalPages=1; page 99 should clamp to 1
        when(mockDao.countBidHistory(10)).thenReturn(5);
        when(mockDao.getBidHistory(10, 99, BiddingHistoryServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        when(mockDao.getBidHistory(10, 1, BiddingHistoryServlet.DEFAULT_PAGE_SIZE))
                .thenReturn(Collections.emptyList());
        RequestDispatcher rd = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/bidding-history.jsp")).thenReturn(rd);

        servlet.doGet(req, resp);

        verify(req).setAttribute(eq("currentPage"), eq(1));
        verify(rd).forward(req, resp);
    }
}
