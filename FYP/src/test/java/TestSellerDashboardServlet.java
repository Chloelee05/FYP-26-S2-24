import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.SellerAuctionDAO;
import com.auction.model.AuctionStatus;
import com.auction.model.seller.SellerAuctionRow;
import com.auction.servlet.seller.SellerDashboardServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * SCRUM-38 – SellerDashboardServlet tests.
 * Covers: RBAC, no leakage of other sellers' auctions, status filter, pagination, empty state.
 */
@DisplayName("SellerDashboardServlet – SCRUM-38")
public class TestSellerDashboardServlet {

    private static class Wrapper extends SellerDashboardServlet {
        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }
    }

    private Wrapper servlet;
    private SellerAuctionDAO mockDao;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        mockDao    = mock(SellerAuctionDAO.class);
        servlet    = new Wrapper();
        servlet.setDao(mockDao);

        req        = mock(HttpServletRequest.class);
        resp       = mock(HttpServletResponse.class);
        session    = mock(HttpSession.class);
        dispatcher = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher(anyString())).thenReturn(dispatcher);
    }

    private void stubSellerSession(int userId) {
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("userRole")).thenReturn("SELLER");
        when(session.getAttribute("userId")).thenReturn(userId);
    }

    private SellerAuctionRow dummyRow() {
        return new SellerAuctionRow(1L, "T", BigDecimal.TEN, null,
                BigDecimal.ZERO, 0, Instant.now(), Instant.now().plusSeconds(3600), "Active");
    }

    // ------------------------------------------------------------------ RBAC

    @Nested
    @DisplayName("RBAC checks")
    class Rbac {
        @Test
        @DisplayName("no session → 403")
        void noSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doGet(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
            verifyNoInteractions(mockDao);
        }

        @Test
        @DisplayName("BUYER role → 403")
        void buyerForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("BUYER");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doGet(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
            verifyNoInteractions(mockDao);
        }

        @Test
        @DisplayName("ADMIN role → 403")
        void adminForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("ADMIN");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doGet(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
            verifyNoInteractions(mockDao);
        }
    }

    // ------------------------------------------------------------------ No leakage

    @Nested
    @DisplayName("No leakage of other sellers' auctions")
    class NoLeakage {
        @Test
        @DisplayName("listSellerAuctions always called with session userId, never a user-supplied id")
        void sellerIdFromSessionOnly() throws Exception {
            stubSellerSession(77);
            when(req.getParameter("status")).thenReturn(null);
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("size")).thenReturn(null);
            when(mockDao.listSellerAuctions(anyInt(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDao.countSellerAuctions(anyInt(), any())).thenReturn(0);

            servlet.doGet(req, resp);

            ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
            verify(mockDao).listSellerAuctions(cap.capture(), any(), anyInt(), anyInt());
            assertEquals(77, cap.getValue(),
                    "listSellerAuctions must receive the session userId, not any request param");
        }

        @Test
        @DisplayName("countSellerAuctions also uses session userId")
        void countUsesSessionId() throws Exception {
            stubSellerSession(88);
            when(req.getParameter("status")).thenReturn(null);
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("size")).thenReturn(null);
            when(mockDao.listSellerAuctions(anyInt(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDao.countSellerAuctions(anyInt(), any())).thenReturn(0);

            servlet.doGet(req, resp);

            ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
            verify(mockDao).countSellerAuctions(cap.capture(), any());
            assertEquals(88, cap.getValue());
        }
    }

    // ------------------------------------------------------------------ Status filter

    @Nested
    @DisplayName("Status filter")
    class StatusFilter {
        @Test
        @DisplayName("status=active maps to AuctionStatus.ACTIVE id")
        void activeFilter() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn("active");
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("size")).thenReturn(null);
            when(mockDao.listSellerAuctions(anyInt(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDao.countSellerAuctions(anyInt(), any())).thenReturn(0);

            servlet.doGet(req, resp);

            verify(mockDao).listSellerAuctions(eq(42),
                    eq(AuctionStatus.ACTIVE.getId()), anyInt(), anyInt());
        }

        @Test
        @DisplayName("status=cancelled maps to AuctionStatus.CANCELLED id")
        void cancelledFilter() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn("cancelled");
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("size")).thenReturn(null);
            when(mockDao.listSellerAuctions(anyInt(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDao.countSellerAuctions(anyInt(), any())).thenReturn(0);

            servlet.doGet(req, resp);

            verify(mockDao).listSellerAuctions(eq(42),
                    eq(AuctionStatus.CANCELLED.getId()), anyInt(), anyInt());
        }

        @Test
        @DisplayName("no status param → null filter (all statuses returned)")
        void noFilter() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn(null);
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("size")).thenReturn(null);
            when(mockDao.listSellerAuctions(anyInt(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDao.countSellerAuctions(anyInt(), any())).thenReturn(0);

            servlet.doGet(req, resp);

            verify(mockDao).listSellerAuctions(eq(42), isNull(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("invalid status value → 400")
        void invalidStatus() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn("unknown");

            servlet.doGet(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
            verifyNoInteractions(mockDao);
        }
    }

    // ------------------------------------------------------------------ Pagination

    @Nested
    @DisplayName("Pagination")
    class Pagination {
        @Test
        @DisplayName("default page=1 and size=10 when params absent")
        void defaults() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn(null);
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("size")).thenReturn(null);
            when(mockDao.listSellerAuctions(anyInt(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDao.countSellerAuctions(anyInt(), any())).thenReturn(0);

            servlet.doGet(req, resp);

            verify(mockDao).listSellerAuctions(anyInt(), any(), eq(1), eq(10));
        }

        @Test
        @DisplayName("size is clamped to max 50")
        void sizeClampedToMax() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn(null);
            when(req.getParameter("page")).thenReturn("1");
            when(req.getParameter("size")).thenReturn("999");
            when(mockDao.listSellerAuctions(anyInt(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDao.countSellerAuctions(anyInt(), any())).thenReturn(0);

            servlet.doGet(req, resp);

            verify(mockDao).listSellerAuctions(anyInt(), any(), eq(1), eq(50));
        }

        @Test
        @DisplayName("non-numeric page → 400")
        void nonNumericPage() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn(null);
            when(req.getParameter("page")).thenReturn("abc");
            when(req.getParameter("size")).thenReturn(null);

            servlet.doGet(req, resp);

            verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
        }

        @Test
        @DisplayName("empty result → totalPages=1, no zero-divide")
        void emptyResultTotalPagesIsOne() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn(null);
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("size")).thenReturn(null);
            when(mockDao.listSellerAuctions(anyInt(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDao.countSellerAuctions(anyInt(), any())).thenReturn(0);

            servlet.doGet(req, resp);

            verify(req).setAttribute("totalPages", 1);
            verify(req).setAttribute("total", 0);
        }

        @Test
        @DisplayName("11 items / size 5 → totalPages=3 (ceiling)")
        void totalPagesCeiling() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn(null);
            when(req.getParameter("page")).thenReturn("1");
            when(req.getParameter("size")).thenReturn("5");
            when(mockDao.listSellerAuctions(anyInt(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockDao.countSellerAuctions(anyInt(), any())).thenReturn(11);

            servlet.doGet(req, resp);

            verify(req).setAttribute("totalPages", 3);
        }
    }

    // ------------------------------------------------------------------ Successful forward

    @Nested
    @DisplayName("Successful forward")
    class Forward {
        @Test
        @DisplayName("auction list and pagination attrs set, then forwards to seller JSP")
        void attrsSetBeforeForward() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("status")).thenReturn(null);
            when(req.getParameter("page")).thenReturn(null);
            when(req.getParameter("size")).thenReturn(null);
            List<SellerAuctionRow> rows = List.of(dummyRow());
            when(mockDao.listSellerAuctions(42, null, 1, 10)).thenReturn(rows);
            when(mockDao.countSellerAuctions(42, null)).thenReturn(1);

            servlet.doGet(req, resp);

            verify(req).setAttribute("auctions", rows);
            verify(req).setAttribute("currentPage", 1);
            verify(req).setAttribute("statusFilter", null);
            verify(dispatcher).forward(req, resp);
        }
    }
}
