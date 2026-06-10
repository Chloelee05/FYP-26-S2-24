import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.RatingDAO;
import com.auction.dao.RatingDAO.RatingResult;
import com.auction.servlet.RateSellerServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

/**
 * Unit tests for {@link RateSellerServlet}.
 *
 * <h2>RBAC</h2>
 * Only authenticated BUYERS may submit ratings. No session, SELLER, and ADMIN
 * must all receive 403.
 *
 * <h2>Input validation</h2>
 * Missing/non-numeric {@code auctionId} → 400. Score outside [1, 5] → 400.
 *
 * <h2>Business rules (verified in DAO)</h2>
 * <ul>
 *   <li>Auction must be FINISHED ({@link RatingResult#AUCTION_NOT_FINISHED}).</li>
 *   <li>Rater must be the auction winner ({@link RatingResult#NOT_WINNER}).</li>
 *   <li>One rating per auction per buyer ({@link RatingResult#ALREADY_RATED}).</li>
 * </ul>
 */
@DisplayName("RateSellerServlet")
public class TestRateSellerServlet {

    /** Exposes the protected {@code doPost} for out-of-package testing. */
    private static class Wrapper extends RateSellerServlet {
        Wrapper(RatingDAO dao) { super(dao); }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private RatingDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDAO = mock(RatingDAO.class);
        servlet = new Wrapper(mockDAO);
        req     = mock(HttpServletRequest.class);
        resp    = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        when(req.getContextPath()).thenReturn("/app");
    }

    // --------------------------------------------------------------------- helpers

    private void stubBuyerSession(int userId) {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userRole")).thenReturn("BUYER");
        when(session.getAttribute("userId")).thenReturn(userId);
    }

    // ==========================================================================
    // RBAC
    // ==========================================================================

    @Nested
    @DisplayName("RBAC")
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
        @DisplayName("score below 1 (score=0) → 400")
        void scoreTooLow() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("0");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("score above 5 (score=6) → 400")
        void scoreTooHigh() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("6");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("missing score → 400")
        void missingScore() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("non-numeric score → 400")
        void nonNumericScore() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("five");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("buyerId always comes from session, never from request (no IDOR)")
        void buyerIdFromSession() throws Exception {
            stubBuyerSession(99);
            when(req.getParameter("auctionId")).thenReturn("7");
            when(req.getParameter("score")).thenReturn("4");
            when(mockDAO.insertRating(7L, 99, 4, null)).thenReturn(RatingResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertRating(7L, 99, 4, null);
            verify(mockDAO, never()).insertRating(eq(7L), eq(1), anyInt(), any());
        }
    }

    // ==========================================================================
    // DAO outcomes
    // ==========================================================================

    @Nested
    @DisplayName("DAO outcomes")
    class DaoOutcomes {

        @Test
        @DisplayName("duplicate rating → ALREADY_RATED → error flash + redirect")
        void duplicateRating() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("4");
            when(mockDAO.insertRating(10L, 5, 4, null)).thenReturn(RatingResult.ALREADY_RATED);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("ratingFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("auction not finished → AUCTION_NOT_FINISHED → error flash + redirect")
        void auctionNotFinished() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("3");
            when(mockDAO.insertRating(10L, 5, 3, null)).thenReturn(RatingResult.AUCTION_NOT_FINISHED);

            servlet.doPost(req, resp);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(session).setAttribute(eq("ratingFlashError"), captor.capture());
            assertTrue(
                    captor.getValue().toLowerCase().contains("ended")
                    || captor.getValue().toLowerCase().contains("after"),
                    "Expected 'ended' or 'after' in message but got: " + captor.getValue());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("buyer not winner → NOT_WINNER → error flash + redirect")
        void buyerNotWinner() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("5");
            when(mockDAO.insertRating(10L, 5, 5, null)).thenReturn(RatingResult.NOT_WINNER);

            servlet.doPost(req, resp);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(session).setAttribute(eq("ratingFlashError"), captor.capture());
            assertTrue(
                    captor.getValue().toLowerCase().contains("winner"),
                    "Expected 'winner' in message but got: " + captor.getValue());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("auction not found → AUCTION_NOT_FOUND → error flash + redirect")
        void auctionNotFound() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("9999");
            when(req.getParameter("score")).thenReturn("3");
            when(mockDAO.insertRating(9999L, 5, 3, null)).thenReturn(RatingResult.AUCTION_NOT_FOUND);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("ratingFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/9999");
        }

        @Test
        @DisplayName("success → success flash + redirect to auction page")
        void success() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("5");
            when(mockDAO.insertRating(10L, 5, 5, null)).thenReturn(RatingResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertRating(10L, 5, 5, null);
            verify(session).setAttribute(eq("ratingFlash"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("boundary score 1 → accepted")
        void scoreBoundaryMin() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("1");
            when(mockDAO.insertRating(10L, 5, 1, null)).thenReturn(RatingResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertRating(10L, 5, 1, null);
        }

        @Test
        @DisplayName("boundary score 5 → accepted")
        void scoreBoundaryMax() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("5");
            when(mockDAO.insertRating(10L, 5, 5, null)).thenReturn(RatingResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertRating(10L, 5, 5, null);
        }
    }

    // ==========================================================================
    // toMessage helper
    // ==========================================================================

    @Nested
    @DisplayName("RateSellerServlet.toMessage coverage")
    class ToMessageHelper {

        @Test
        @DisplayName("all RatingResult values produce non-blank messages")
        void allResultsHaveMessages() {
            for (RatingResult r : RatingResult.values()) {
                String msg = RateSellerServlet.toMessage(r);
                assertFalse(msg == null || msg.isBlank(),
                        "Expected non-blank message for " + r);
            }
        }
    }
}
