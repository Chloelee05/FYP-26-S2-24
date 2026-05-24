import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.ReportDAO;
import com.auction.dao.ReportDAO.ReportResult;
import com.auction.servlet.BuyerReportServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Unit tests for {@link BuyerReportServlet}.
 *
 * <h2>RBAC</h2>
 * Only authenticated BUYERS may submit reports. No session, SELLER, and ADMIN
 * must all receive 403.
 *
 * <h2>Input validation</h2>
 * Missing/non-numeric {@code auctionId} → 400.
 * Description exceeding {@code REPORT_DESCRIPTION_MAX_LENGTH} → 400.
 *
 * <h2>Security</h2>
 * {@code buyerId} comes exclusively from the session (no IDOR via request param).
 * {@code reportedUserId} is never accepted from the request.
 *
 * <h2>Business rules (verified in DAO)</h2>
 * <ul>
 *   <li>Auction must exist ({@link ReportResult#AUCTION_NOT_FOUND}).</li>
 *   <li>Buyer must not be the seller ({@link ReportResult#SELF_REPORT}).</li>
 *   <li>One report per auction per buyer ({@link ReportResult#ALREADY_REPORTED}).</li>
 * </ul>
 */
@DisplayName("BuyerReportServlet")
public class TestBuyerReportServlet {

    /** Exposes the protected {@code doPost} for out-of-package testing. */
    private static class Wrapper extends BuyerReportServlet {
        Wrapper(ReportDAO dao) { super(dao); }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private ReportDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDAO = mock(ReportDAO.class);
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
        @DisplayName("blank auctionId → 400")
        void blankAuctionId() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("   ");
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
        @DisplayName("description exceeds max length → 400")
        void descriptionTooLong() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            // 1001 chars — one over the 1000-char limit
            String tooLong = "a".repeat(1001);
            when(req.getParameter("description")).thenReturn(tooLong);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDAO);
        }

        @Test
        @DisplayName("description at exact max length → accepted")
        void descriptionAtMaxLength() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            String exactMax = "a".repeat(1000);
            when(req.getParameter("description")).thenReturn(exactMax);
            when(mockDAO.insertReport(eq(10L), eq(5), anyString())).thenReturn(ReportResult.SUCCESS);
            servlet.doPost(req, resp);
            verify(resp, never()).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
        }

        @Test
        @DisplayName("null description → DAO called with null (optional field)")
        void nullDescriptionAccepted() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("description")).thenReturn(null);
            when(mockDAO.insertReport(10L, 5, null)).thenReturn(ReportResult.SUCCESS);
            servlet.doPost(req, resp);
            verify(mockDAO).insertReport(10L, 5, null);
        }

        @Test
        @DisplayName("buyerId always comes from session, never from request (no IDOR)")
        void buyerIdFromSession() throws Exception {
            stubBuyerSession(99);
            when(req.getParameter("auctionId")).thenReturn("7");
            when(req.getParameter("description")).thenReturn(null);
            when(mockDAO.insertReport(7L, 99, null)).thenReturn(ReportResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertReport(7L, 99, null);
            // Confirm it was never called with a forged buyerId
            verify(mockDAO, never()).insertReport(eq(7L), eq(1), any());
        }
    }

    // ==========================================================================
    // DAO outcomes
    // ==========================================================================

    @Nested
    @DisplayName("DAO outcomes")
    class DaoOutcomes {

        @Test
        @DisplayName("auction not found → AUCTION_NOT_FOUND → error flash + redirect")
        void auctionNotFound() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("9999");
            when(req.getParameter("description")).thenReturn(null);
            when(mockDAO.insertReport(9999L, 5, null)).thenReturn(ReportResult.AUCTION_NOT_FOUND);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("reportFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/9999");
        }

        @Test
        @DisplayName("self-report → SELF_REPORT → error flash containing 'own' + redirect")
        void selfReport() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("description")).thenReturn(null);
            when(mockDAO.insertReport(10L, 5, null)).thenReturn(ReportResult.SELF_REPORT);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("reportFlashError"),
                    argThat(msg -> msg.toString().toLowerCase().contains("own")
                            || msg.toString().toLowerCase().contains("yourself")));
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("duplicate report → ALREADY_REPORTED → error flash + redirect")
        void duplicateReport() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("description")).thenReturn(null);
            when(mockDAO.insertReport(10L, 5, null)).thenReturn(ReportResult.ALREADY_REPORTED);

            servlet.doPost(req, resp);

            verify(session).setAttribute(eq("reportFlashError"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("success → success flash + redirect to auction page")
        void success() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("description")).thenReturn("Suspicious listing.");
            when(mockDAO.insertReport(eq(10L), eq(5), anyString())).thenReturn(ReportResult.SUCCESS);

            servlet.doPost(req, resp);

            verify(mockDAO).insertReport(eq(10L), eq(5), anyString());
            verify(session).setAttribute(eq("reportFlash"), anyString());
            verify(resp).sendRedirect("/app/auction/10");
        }

        @Test
        @DisplayName("DAO throws RuntimeException → 500")
        void daoThrows() throws Exception {
            stubBuyerSession(5);
            when(req.getParameter("auctionId")).thenReturn("10");
            when(req.getParameter("description")).thenReturn(null);
            when(mockDAO.insertReport(10L, 5, null)).thenThrow(new RuntimeException("DB down"));

            servlet.doPost(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
        }
    }

    // ==========================================================================
    // toMessage helper
    // ==========================================================================

    @Nested
    @DisplayName("BuyerReportServlet.toMessage coverage")
    class ToMessageHelper {

        @Test
        @DisplayName("all ReportResult values produce non-blank messages")
        void allResultsHaveMessages() {
            for (ReportResult r : ReportResult.values()) {
                String msg = BuyerReportServlet.toMessage(r);
                assertFalse(msg == null || msg.isBlank(),
                        "Expected non-blank message for " + r);
            }
        }
    }
}
