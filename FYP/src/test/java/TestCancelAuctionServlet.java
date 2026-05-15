import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.SellerAuctionDAO;
import com.auction.servlet.seller.CancelAuctionServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

/**
 * SCRUM-34 – CancelAuctionServlet tests.
 * Covers: RBAC, input validation, state enforcement, data integrity of bids.
 */
@DisplayName("CancelAuctionServlet – SCRUM-34")
public class TestCancelAuctionServlet {

    private static class Wrapper extends CancelAuctionServlet {
        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private Wrapper servlet;
    private SellerAuctionDAO mockDao;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDao = mock(SellerAuctionDAO.class);
        servlet = new Wrapper();
        servlet.setDao(mockDao);

        req     = mock(HttpServletRequest.class);
        resp    = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
    }

    private void stubSellerSession(int userId) {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userRole")).thenReturn("SELLER");
        when(session.getAttribute("userId")).thenReturn(userId);
    }

    // ------------------------------------------------------------------ RBAC

    @Nested
    @DisplayName("RBAC checks")
    class Rbac {

        @Test
        @DisplayName("no session → 403")
        void noSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
            verifyNoInteractions(mockDao);
        }

        @Test
        @DisplayName("BUYER role → 403")
        void buyerForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("BUYER");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doPost(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
            verifyNoInteractions(mockDao);
        }

        @Test
        @DisplayName("ADMIN role → 403 (only the owning seller may cancel)")
        void adminForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("ADMIN");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doPost(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
            verifyNoInteractions(mockDao);
        }
    }

    // ------------------------------------------------------------------ Input validation

    @Nested
    @DisplayName("Input validation")
    class InputValidation {

        @Test
        @DisplayName("missing auction_id → 400")
        void missingAuctionId() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("auction_id")).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDao);
        }

        @Test
        @DisplayName("blank auction_id → 400")
        void blankAuctionId() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("auction_id")).thenReturn("  ");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
        }

        @Test
        @DisplayName("non-numeric auction_id → 400")
        void nonNumericAuctionId() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("auction_id")).thenReturn("abc");
            servlet.doPost(req, resp);
            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
        }
    }

    // ------------------------------------------------------------------ Cancel success / state enforcement

    @Nested
    @DisplayName("Cancel logic")
    class CancelLogic {

        @Test
        @DisplayName("valid seller + valid auction → DAO called with correct args, redirect to dashboard")
        void successfulCancel() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("auction_id")).thenReturn("7");
            when(req.getParameter("cancel_reason")).thenReturn("No longer selling");
            when(req.getContextPath()).thenReturn("/app");
            when(mockDao.cancelAuction(eq(7L), eq(42), anyString())).thenReturn(true);

            servlet.doPost(req, resp);

            verify(mockDao).cancelAuction(eq(7L), eq(42), anyString());
            verify(resp).sendRedirect("/app/protected/seller/auctions");
        }

        @Test
        @DisplayName("DAO returns false (wrong owner or non-cancellable state) → 403")
        void daoReturnsFalse() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("auction_id")).thenReturn("7");
            when(req.getParameter("cancel_reason")).thenReturn(null);
            when(mockDao.cancelAuction(7L, 42, null)).thenReturn(false);

            servlet.doPost(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
        }

        @Test
        @DisplayName("cancel_reason > 1000 chars is truncated before storage")
        void longReasonTruncated() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("auction_id")).thenReturn("7");
            String longReason = "x".repeat(2000);
            when(req.getParameter("cancel_reason")).thenReturn(longReason);
            when(req.getContextPath()).thenReturn("/app");
            when(mockDao.cancelAuction(anyLong(), anyInt(), anyString())).thenReturn(true);

            servlet.doPost(req, resp);

            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockDao).cancelAuction(anyLong(), anyInt(), reasonCaptor.capture());
            assertTrue(reasonCaptor.getValue().length() <= 1000);
        }

        @Test
        @DisplayName("blank cancel_reason is stored as null")
        void blankReasonStoredAsNull() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("auction_id")).thenReturn("7");
            when(req.getParameter("cancel_reason")).thenReturn("   ");
            when(req.getContextPath()).thenReturn("/app");
            when(mockDao.cancelAuction(7L, 42, null)).thenReturn(true);

            servlet.doPost(req, resp);

            verify(mockDao).cancelAuction(7L, 42, null);
        }

        @Test
        @DisplayName("seller cannot cancel another seller's auction (DAO receives own sellerId from session)")
        void sellerIdComesFromSession() throws Exception {
            stubSellerSession(99); // session user is 99
            when(req.getParameter("auction_id")).thenReturn("7");
            when(req.getParameter("cancel_reason")).thenReturn(null);
            when(mockDao.cancelAuction(7L, 99, null)).thenReturn(false);

            servlet.doPost(req, resp);

            // DAO is called with session userId (99), not any other id
            verify(mockDao).cancelAuction(7L, 99, null);
            verify(mockDao, never()).cancelAuction(7L, 42, null);
        }
    }
}
