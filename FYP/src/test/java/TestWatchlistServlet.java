import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.WatchlistDAO;
import com.auction.dao.WatchlistDAO.WatchlistResult;
import com.auction.model.profile.WatchlistRow;
import com.auction.servlet.WatchlistServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link WatchlistServlet}.
 *
 * <h2>RBAC</h2>
 * Only authenticated BUYERS may access GET or POST. No session, SELLER, and
 * ADMIN must all receive 403.
 *
 * <h2>Input validation</h2>
 * Missing/non-numeric {@code auctionId} → 400. Missing/invalid {@code action} → 400.
 *
 * <h2>Security</h2>
 * {@code userId} comes exclusively from the session (no IDOR via request param).
 * Seller identity is never accepted from the request.
 *
 * <h2>Business rules</h2>
 * <ul>
 *   <li>Duplicate add → {@link WatchlistResult#ALREADY_WATCHING} → error flash.</li>
 *   <li>Own-auction guard → {@link WatchlistResult#OWN_AUCTION} → error flash.</li>
 *   <li>Remove non-existent entry → graceful (no error, redirect).</li>
 * </ul>
 */
@DisplayName("WatchlistServlet")
public class TestWatchlistServlet {

    /** Exposes protected doGet and doPost for out-of-package testing. */
    private static class Wrapper extends WatchlistServlet {
        Wrapper(WatchlistDAO dao) { super(dao); }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doPost(req, resp); }
    }

    private WatchlistDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDAO = mock(WatchlistDAO.class);
        servlet = new Wrapper(mockDAO);
        req     = mock(HttpServletRequest.class);
        resp    = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        when(req.getContextPath()).thenReturn("/app");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubBuyerSession(int userId) {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("userId")).thenReturn(userId);
    }

    private void stubAddParams(String auctionId) {
        when(req.getParameter("auctionId")).thenReturn(auctionId);
        when(req.getParameter("action")).thenReturn("add");
    }

    private void stubRemoveParams(String auctionId) {
        when(req.getParameter("auctionId")).thenReturn(auctionId);
        when(req.getParameter("action")).thenReturn("remove");
    }

    // ==========================================================================
    // RBAC — POST
    // ==========================================================================

    @Nested
    @DisplayName("RBAC — POST")
    class RbacPost {

        @Test
        @DisplayName("no session → 403")
        void noSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("SELLER role → 403")
        void sellerForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("SELLER");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("ADMIN role → 403")
        void adminForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("ADMIN");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockDAO);
        }
    }

    // ==========================================================================
    // RBAC — GET
    // ==========================================================================

    @Nested
    @DisplayName("RBAC — GET")
    class RbacGet {

        @Test
        @DisplayName("no session → 403")
        void noSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doGet(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("SELLER role → 403")
        void sellerForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("SELLER");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doGet(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("ADMIN role → 403")
        void adminForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("ADMIN");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doGet(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockDAO);
        }
    }

    // ==========================================================================
    // Input validation
    // ==========================================================================

    @Nested
    @DisplayName("Input validation")
    class InputValidation {

        @Test
        @DisplayName("missing auctionId → 400")
        void missingAuctionId() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn(null);
            when(req.getParameter("action")).thenReturn("add");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("blank auctionId → 400")
        void blankAuctionId() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("   ");
            when(req.getParameter("action")).thenReturn("add");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("non-numeric auctionId → 400 (IDOR guard)")
        void nonNumericAuctionId() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("' OR 1=1 --");
            when(req.getParameter("action")).thenReturn("add");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("missing action → 400")
        void missingAction() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("invalid action → 400")
        void invalidAction() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("action")).thenReturn("watch");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("userId always from session, never from request (no IDOR)")
        void userIdFromSession() throws Exception {
            stubBuyerSession(99);
            stubAddParams("7");
            when(mockDAO.add(7L, 99)).thenReturn(WatchlistResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).add(7L, 99);
            verify(mockDAO, never()).add(eq(7L), eq(1));
        }
    }

    // ==========================================================================
    // Add
    // ==========================================================================

    @Nested
    @DisplayName("Add")
    class Add {

        @Test
        @DisplayName("success → success flash + redirect to auction page")
        void addSuccess() throws Exception {
            stubBuyerSession(5);
            stubAddParams("10");
            when(mockDAO.add(10L, 5)).thenReturn(WatchlistResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).add(10L, 5);
            verify(session).setAttribute(eq("watchlistFlash"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("duplicate add → ALREADY_WATCHING → error flash + redirect")
        void duplicateAdd() throws Exception {
            stubBuyerSession(5);
            stubAddParams("10");
            when(mockDAO.add(10L, 5)).thenReturn(WatchlistResult.ALREADY_WATCHING);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("watchlistFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
            verify(resp, never()).sendError(anyInt());
        }

        @Test
        @DisplayName("own-auction guard → OWN_AUCTION → error flash containing 'own' + redirect")
        void ownAuction() throws Exception {
            stubBuyerSession(5);
            stubAddParams("10");
            when(mockDAO.add(10L, 5)).thenReturn(WatchlistResult.OWN_AUCTION);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("watchlistFlashError"),
                    argThat(msg -> msg.toString().toLowerCase().contains("own")));
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("auction not found → AUCTION_NOT_FOUND → error flash + redirect")
        void auctionNotFound() throws Exception {
            stubBuyerSession(5);
            stubAddParams("9999");
            when(mockDAO.add(9999L, 5)).thenReturn(WatchlistResult.AUCTION_NOT_FOUND);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("watchlistFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/9999");
        }

        @Test
        @DisplayName("DAO throws RuntimeException → 500")
        void daoThrows() throws Exception {
            stubBuyerSession(5);
            stubAddParams("10");
            when(mockDAO.add(10L, 5)).thenThrow(new RuntimeException("DB down"));

            servlet.doPost(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
        }
    }

    // ==========================================================================
    // Remove
    // ==========================================================================

    @Nested
    @DisplayName("Remove")
    class Remove {

        @Test
        @DisplayName("success → success flash + redirect to auction page")
        void removeSuccess() throws Exception {
            stubBuyerSession(5);
            stubRemoveParams("10");
            when(mockDAO.remove(10L, 5)).thenReturn(true);

            servlet.doPost(req, resp);

            verify(mockDAO).remove(10L, 5);
            verify(session).setAttribute(eq("watchlistFlash"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("remove non-existent → graceful (no error, redirect)")
        void removeNonExistent() throws Exception {
            stubBuyerSession(5);
            stubRemoveParams("10");
            when(mockDAO.remove(10L, 5)).thenReturn(false);

            servlet.doPost(req, resp);

            verify(mockDAO).remove(10L, 5);
            verify(resp).sendRedirect("/app/auction/10");
            verify(resp, never()).sendError(anyInt());
            // No error flash set for a non-existent entry
            verify(session, never()).setAttribute(eq("watchlistFlashError"), any());
        }

        @Test
        @DisplayName("DAO throws RuntimeException → 500")
        void daoThrows() throws Exception {
            stubBuyerSession(5);
            stubRemoveParams("10");
            when(mockDAO.remove(10L, 5)).thenThrow(new RuntimeException("DB down"));

            servlet.doPost(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
        }
    }

    // ==========================================================================
    // GET — list watchlist
    // ==========================================================================

    @Nested
    @DisplayName("GET — list watchlist")
    class GetWatchlist {

        @Test
        @DisplayName("buyer with empty watchlist → DAO called, empty list set, JSP forwarded")
        void emptyWatchlist() throws Exception {
            stubBuyerSession(5);
            when(mockDAO.listByUser(5)).thenReturn(Collections.emptyList());
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/watchlist.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(mockDAO).listByUser(5);
            verify(req).setAttribute(eq("watchlist"), eq(Collections.emptyList()));
            verify(rd).forward(req, resp);
        }

        @Test
        @DisplayName("buyer with items → rows set as request attribute + JSP forwarded")
        void watchlistWithRows() throws Exception {
            WatchlistRow row = new WatchlistRow(42L, "Vintage Camera", 1, Instant.now(), BigDecimal.ZERO, Instant.now(), 0);
            stubBuyerSession(5);
            when(mockDAO.listByUser(5)).thenReturn(List.of(row));
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/watchlist.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(req).setAttribute(eq("watchlist"), eq(List.of(row)));
            verify(rd).forward(req, resp);
        }

        @Test
        @DisplayName("userId always from session, never from request param (no IDOR)")
        void userIdFromSession() throws Exception {
            stubBuyerSession(99);
            when(req.getParameter("userId")).thenReturn("1"); // attacker-controlled
            when(mockDAO.listByUser(99)).thenReturn(Collections.emptyList());
            RequestDispatcher rd = mock(RequestDispatcher.class);
            when(req.getRequestDispatcher("/WEB-INF/views/watchlist.jsp")).thenReturn(rd);

            servlet.doGet(req, resp);

            verify(mockDAO).listByUser(99);
            verify(mockDAO, never()).listByUser(1);
        }

        @Test
        @DisplayName("DAO throws RuntimeException → 500")
        void daoThrows() throws Exception {
            stubBuyerSession(5);
            when(mockDAO.listByUser(5)).thenThrow(new RuntimeException("DB down"));

            servlet.doGet(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
        }
    }

    // ==========================================================================
    // toMessage helper
    // ==========================================================================

    @Nested
    @DisplayName("WatchlistServlet.toMessage coverage")
    class ToMessageHelper {

        @Test
        @DisplayName("all WatchlistResult values produce non-blank messages")
        void allResultsHaveMessages() {
            for (WatchlistResult r : WatchlistResult.values()) {
                String msg = WatchlistServlet.toMessage(r);
                assertFalse(msg == null || msg.isBlank(),
                        "Expected non-blank message for " + r);
            }
        }
    }
}
