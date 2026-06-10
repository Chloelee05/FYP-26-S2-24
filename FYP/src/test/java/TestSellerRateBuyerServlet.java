import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.ReviewDAO;
import com.auction.dao.ReviewDAO.SellerRatingResult;
import com.auction.servlet.SellerRateBuyerServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

/**
 * Unit tests for {@link SellerRateBuyerServlet}.
 *
 * <h2>RBAC</h2>
 * Only authenticated SELLERs may submit buyer ratings. No session, BUYER, and ADMIN
 * must all receive 403.
 *
 * <h2>Input validation</h2>
 * Missing/non-numeric {@code auctionId} → 400. Score outside [1, 5] → 400.
 *
 * <h2>Security</h2>
 * {@code sellerId} comes exclusively from the session (no IDOR via request param).
 * Wrong auction owner → 403. buyerId is resolved from winner_id in the DAO — never
 * accepted from the request.
 *
 * <h2>Business rules (verified in DAO)</h2>
 * <ul>
 *   <li>Seller must own the auction ({@link SellerRatingResult#NOT_AUCTION_OWNER} → 403).</li>
 *   <li>Auction must be FINISHED ({@link SellerRatingResult#AUCTION_NOT_FINISHED}).</li>
 *   <li>Auction must have a winner ({@link SellerRatingResult#NO_WINNER}).</li>
 *   <li>One rating per auction per seller ({@link SellerRatingResult#ALREADY_RATED}).</li>
 * </ul>
 */
@DisplayName("SellerRateBuyerServlet")
public class TestSellerRateBuyerServlet {

    /** Exposes the protected {@code doPost} for out-of-package testing. */
    private static class Wrapper extends SellerRateBuyerServlet {
        Wrapper(ReviewDAO dao) { super(dao); }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private ReviewDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDAO = mock(ReviewDAO.class);
        servlet = new Wrapper(mockDAO);
        req     = mock(HttpServletRequest.class);
        resp    = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        when(req.getContextPath()).thenReturn("/app");
    }

    // --------------------------------------------------------------------- helpers

    private void stubSellerSession(int userId) {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userRole")).thenReturn("SELLER");
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
        @DisplayName("BUYER role → 403")
        void buyerForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("BUYER");
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
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("blank auctionId → 400")
        void blankAuctionId() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("   ");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("non-numeric auctionId → 400 (IDOR guard)")
        void nonNumericAuctionId() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("' OR 1=1 --");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("missing score → 400")
        void missingScore() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("non-numeric score → 400")
        void nonNumericScore() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("five");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("score below 1 (score=0) → 400")
        void scoreTooLow() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("0");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("score above 5 (score=6) → 400")
        void scoreTooHigh() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("6");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("sellerId always comes from session, never from request (no IDOR)")
        void sellerIdFromSession() throws Exception {
            stubSellerSession(99);
            when(req.getParameter("auctionId")).thenReturn("7");
            when(req.getParameter("score")).thenReturn("4");
            when(mockDAO.insertSellerRating(7L, 99, 4, null)).thenReturn(SellerRatingResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertSellerRating(7L, 99, 4, null);
            verify(mockDAO, never()).insertSellerRating(eq(7L), eq(1), anyInt(), any());
        }
    }

    // ==========================================================================
    // DAO outcomes
    // ==========================================================================

    @Nested
    @DisplayName("DAO outcomes")
    class DaoOutcomes {

        @Test
        @DisplayName("wrong auction owner → NOT_AUCTION_OWNER → 403 (hard error, no flash)")
        void wrongAuctionOwner() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("4");
            when(mockDAO.insertSellerRating(10L, 5, 4, null))
                    .thenReturn(SellerRatingResult.NOT_AUCTION_OWNER);

            servlet.doPost(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
            verify(session, never()).setAttribute(eq("buyerRatingFlashError"), any());
            verify(resp, never()).sendRedirect(anyString());
        }

        @Test
        @DisplayName("no winner yet → NO_WINNER → error flash + redirect")
        void noWinner() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("3");
            when(mockDAO.insertSellerRating(10L, 5, 3, null)).thenReturn(SellerRatingResult.NO_WINNER);

            servlet.doPost(req, resp);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(session).setAttribute(eq("buyerRatingFlashError"), captor.capture());
            assertTrue(
                    captor.getValue().toLowerCase().contains("winner")
                    || captor.getValue().toLowerCase().contains("no winner"),
                    "Expected 'winner' in message but got: " + captor.getValue());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("duplicate rating → ALREADY_RATED → error flash + redirect")
        void duplicateRating() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("4");
            when(mockDAO.insertSellerRating(10L, 5, 4, null)).thenReturn(SellerRatingResult.ALREADY_RATED);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("buyerRatingFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("auction not finished → AUCTION_NOT_FINISHED → error flash containing 'ended' + redirect")
        void auctionNotFinished() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("3");
            when(mockDAO.insertSellerRating(10L, 5, 3, null))
                    .thenReturn(SellerRatingResult.AUCTION_NOT_FINISHED);

            servlet.doPost(req, resp);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(session).setAttribute(eq("buyerRatingFlashError"), captor.capture());
            assertTrue(
                    captor.getValue().toLowerCase().contains("ended")
                    || captor.getValue().toLowerCase().contains("after"),
                    "Expected 'ended' or 'after' in message but got: " + captor.getValue());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("auction not found → AUCTION_NOT_FOUND → error flash + redirect")
        void auctionNotFound() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("9999");
            when(req.getParameter("score")).thenReturn("3");
            when(mockDAO.insertSellerRating(9999L, 5, 3, null))
                    .thenReturn(SellerRatingResult.AUCTION_NOT_FOUND);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("buyerRatingFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/9999");
        }

        @Test
        @DisplayName("success → success flash + redirect to auction page")
        void success() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("5");
            when(mockDAO.insertSellerRating(10L, 5, 5, null)).thenReturn(SellerRatingResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertSellerRating(10L, 5, 5, null);
            verify(session).setAttribute(eq("buyerRatingFlash"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("boundary score 1 → accepted")
        void scoreBoundaryMin() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("1");
            when(mockDAO.insertSellerRating(10L, 5, 1, null)).thenReturn(SellerRatingResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertSellerRating(10L, 5, 1, null);
        }

        @Test
        @DisplayName("boundary score 5 → accepted")
        void scoreBoundaryMax() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("5");
            when(mockDAO.insertSellerRating(10L, 5, 5, null)).thenReturn(SellerRatingResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertSellerRating(10L, 5, 5, null);
        }

        @Test
        @DisplayName("DAO throws RuntimeException → 500")
        void daoThrows() throws Exception {
            stubSellerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("score")).thenReturn("4");
            when(mockDAO.insertSellerRating(10L, 5, 4, null))
                    .thenThrow(new RuntimeException("DB down"));

            servlet.doPost(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
        }
    }

    // ==========================================================================
    // toMessage helper
    // ==========================================================================

    @Nested
    @DisplayName("SellerRateBuyerServlet.toMessage coverage")
    class ToMessageHelper {

        @Test
        @DisplayName("all SellerRatingResult values produce non-blank messages")
        void allResultsHaveMessages() {
            for (SellerRatingResult r : SellerRatingResult.values()) {
                String msg = SellerRateBuyerServlet.toMessage(r);
                assertFalse(msg == null || msg.isBlank(),
                        "Expected non-blank message for " + r);
            }
        }
    }
}
