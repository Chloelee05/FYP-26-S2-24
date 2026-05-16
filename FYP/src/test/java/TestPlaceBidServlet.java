import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.BidDAO;
import com.auction.dao.BidDAO.BidResult;
import com.auction.servlet.PlaceBidServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Unit tests for {@link PlaceBidServlet} covering SCRUM-266, SCRUM-267, and SCRUM-295.
 *
 * <h2>RBAC policy (SCRUM-266)</h2>
 * <ul>
 *   <li>Only authenticated users with role {@code BUYER} may reach {@code doPost}.</li>
 *   <li>No session, SELLER role, and ADMIN role must all receive 403.</li>
 *   <li>{@code /protected/bid} is guarded by {@code AuthFilter} at the container level,
 *       but the servlet adds an inline role check for defense-in-depth.</li>
 * </ul>
 *
 * <h2>Self-bid (SCRUM-266)</h2>
 * The DAO returns {@link BidResult#SELF_BID} if the bidder owns the auction.
 * The servlet redirects back with an error flash message.
 *
 * <h2>Edge cases (SCRUM-267)</h2>
 * <ul>
 *   <li>Equal bid: {@link BidResult#BID_TOO_LOW} — bid == current max is rejected.</li>
 *   <li>Last-second / closed auction: {@link BidResult#AUCTION_CLOSED}.</li>
 *   <li>Non-numeric/negative bid amount: 400 Bad Request or error redirect.</li>
 * </ul>
 *
 * <h2>No IDOR (SCRUM-295)</h2>
 * {@code buyerId} is always taken from the session; {@code auctionId} is parsed strictly
 * as {@code long}. Non-numeric auction IDs return 400.
 */
@DisplayName("PlaceBidServlet – SCRUM-51")
public class TestPlaceBidServlet {

    /** Exposes the protected {@code doPost} for out-of-package testing. */
    private static class Wrapper extends PlaceBidServlet {
        Wrapper(BidDAO dao) { super(dao); }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private BidDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDAO  = mock(BidDAO.class);
        servlet  = new Wrapper(mockDAO);
        req      = mock(HttpServletRequest.class);
        resp     = mock(HttpServletResponse.class);
        session  = mock(HttpSession.class);
        when(req.getContextPath()).thenReturn("/app");
    }

    // --------------------------------------------------------------------- helpers

    private void stubBuyerSession(int userId) {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("userId")).thenReturn(userId);
    }

    // ==========================================================================
    // RBAC (SCRUM-266)
    // ==========================================================================

    @Nested
    @DisplayName("RBAC – SCRUM-266")
    class Rbac {

        @Test
        @DisplayName("no session → 403")
        void noSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("SELLER role → 403 (sellers cannot bid)")
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
    // Input validation (SCRUM-295)
    // ==========================================================================

    @Nested
    @DisplayName("Input validation – SCRUM-295")
    class InputValidation {

        @Test
        @DisplayName("missing auctionId → 400")
        void missingAuctionId() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("non-numeric auctionId → 400 (IDOR guard)")
        void nonNumericAuctionId() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("' OR 1=1 --");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("missing bidAmount → error redirect (not 400)")
        void missingBidAmount() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn(null);
            servlet.doPost(req, resp);
            verify(session).setAttribute(eq("bidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("non-numeric bidAmount → error redirect")
        void nonNumericBidAmount() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("abc");
            servlet.doPost(req, resp);
            verify(session).setAttribute(eq("bidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("zero bid amount → error redirect")
        void zeroBidAmount() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("0.00");
            servlet.doPost(req, resp);
            verify(session).setAttribute(eq("bidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("negative bid amount → error redirect")
        void negativeBidAmount() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("-5.00");
            servlet.doPost(req, resp);
            verify(session).setAttribute(eq("bidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("buyerId always comes from session, never from request (no IDOR)")
        void buyerIdFromSession() throws Exception {
            stubBuyerSession(99);
            when(req.getParameter("auctionId")).thenReturn("7");
            when(req.getParameter("bidAmount")).thenReturn("50.00");
            when(mockDAO.placeBid(7L, 99, new BigDecimal("50.00"))).thenReturn(BidResult.SUCCESS);

            servlet.doPost(req, resp);

            // DAO receives session userId=99, not any other id from the request
            verify(mockDAO).placeBid(7L, 99, new BigDecimal("50.00"));
            verify(mockDAO, never()).placeBid(eq(7L), eq(1), any());
        }
    }

    // ==========================================================================
    // Successful bid (SCRUM-263)
    // ==========================================================================

    @Nested
    @DisplayName("Successful bid")
    class Success {

        @Test
        @DisplayName("valid buyer + valid auction + valid amount → success flash + redirect")
        void successfulBid() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("150.00");
            when(mockDAO.placeBid(10L, 5, new BigDecimal("150.00"))).thenReturn(BidResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).placeBid(10L, 5, new BigDecimal("150.00"));
            verify(session).setAttribute(eq("bidFlash"), argThat(msg ->
                    msg.toString().contains("150.00")));
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("bid amount with many decimals is accepted (BigDecimal parsing)")
        void bidAmountWithDecimals() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("99.99");
            when(mockDAO.placeBid(anyLong(), anyInt(), any())).thenReturn(BidResult.SUCCESS);

            servlet.doPost(req, resp);

            ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(mockDAO).placeBid(eq(10L), eq(5), captor.capture());
            assertEquals(0, captor.getValue().compareTo(new BigDecimal("99.99")));
        }
    }

    // ==========================================================================
    // Self-bid (SCRUM-266)
    // ==========================================================================

    @Nested
    @DisplayName("Self-bid – SCRUM-266")
    class SelfBid {

        @Test
        @DisplayName("seller bidding own auction → SELF_BID → error flash + redirect")
        void selfBidRejected() throws Exception {
            stubBuyerSession(3);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("200.00");
            when(mockDAO.placeBid(10L, 3, new BigDecimal("200.00"))).thenReturn(BidResult.SELF_BID);

            servlet.doPost(req, resp);

            ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
            verify(session).setAttribute(eq("bidFlashError"), msgCaptor.capture());
            assertTrue(msgCaptor.getValue().toLowerCase().contains("own"),
                    "Expected 'own' in self-bid message but got: " + msgCaptor.getValue());
            verify(resp).sendRedirect("/app/auction/10");
        }
    }

    // ==========================================================================
    // Edge cases (SCRUM-267)
    // ==========================================================================

    @Nested
    @DisplayName("Edge cases – SCRUM-267")
    class EdgeCases {

        @Test
        @DisplayName("equal bid (bid == current max) → BID_TOO_LOW → error flash")
        void equalBidRejected() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("100.00");
            when(mockDAO.placeBid(10L, 5, new BigDecimal("100.00"))).thenReturn(BidResult.BID_TOO_LOW);

            servlet.doPost(req, resp);

            ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
            verify(session).setAttribute(eq("bidFlashError"), msgCaptor.capture());
            assertFalse(msgCaptor.getValue().isBlank());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("bid on closed auction (last-second / expired) → AUCTION_CLOSED → error flash")
        void closedAuction() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("150.00");
            when(mockDAO.placeBid(10L, 5, new BigDecimal("150.00"))).thenReturn(BidResult.AUCTION_CLOSED);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("bidFlashError"),
                    argThat(msg -> msg.toString().toLowerCase().contains("ended")
                                   || msg.toString().toLowerCase().contains("closed")));
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("bid on removed auction → AUCTION_REMOVED → error flash")
        void removedAuction() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("150.00");
            when(mockDAO.placeBid(10L, 5, new BigDecimal("150.00"))).thenReturn(BidResult.AUCTION_REMOVED);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("bidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("bid exceeds max-price cap → EXCEEDS_MAX_PRICE → error flash")
        void exceedsMaxPrice() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("bidAmount")).thenReturn("9999.00");
            when(mockDAO.placeBid(10L, 5, new BigDecimal("9999.00"))).thenReturn(BidResult.EXCEEDS_MAX_PRICE);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("bidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("auction not found → AUCTION_NOT_FOUND → error flash")
        void auctionNotFound() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("9999");
            when(req.getParameter("bidAmount")).thenReturn("50.00");
            when(mockDAO.placeBid(9999L, 5, new BigDecimal("50.00"))).thenReturn(BidResult.AUCTION_NOT_FOUND);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("bidFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/9999");
        }
    }

    // ==========================================================================
    // toMessage helper
    // ==========================================================================

    @Nested
    @DisplayName("PlaceBidServlet.toMessage coverage")
    class ToMessageHelper {

        @Test
        @DisplayName("all BidResult values produce non-blank messages")
        void allResultsHaveMessages() {
            for (BidResult r : BidResult.values()) {
                String msg = PlaceBidServlet.toMessage(r);
                assertFalse(msg == null || msg.isBlank(),
                        "Expected non-blank message for " + r);
            }
        }
    }
}
