import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.SellerAuctionDAO;
import com.auction.dao.SellerAuctionDAO.AuctionEditData;
import com.auction.model.AuctionStatus;
import com.auction.servlet.seller.EditAuctionServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * SCRUM-37 – EditAuctionServlet tests.
 * Covers: RBAC, zero-bid vs 1+-bid guard, editable-field whitelist, ownership, status check.
 */
@DisplayName("EditAuctionServlet – SCRUM-37")
public class TestEditAuctionServlet {

    private static class Wrapper extends EditAuctionServlet {
        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doGet(req, resp); }
        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException { super.doPost(req, resp); }
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
        servlet.setUploadDir(System.getProperty("java.io.tmpdir"));

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

    private AuctionEditData makeEditData(int statusId, long sellerId) {
        return new AuctionEditData(
                10L, sellerId, statusId,
                "Old Title", "Old Description", null,
                Instant.now(), Instant.now().plusSeconds(3600),
                Collections.emptyList());
    }

    // ------------------------------------------------------------------ GET: RBAC

    @Nested
    @DisplayName("GET – RBAC")
    class GetRbac {
        @Test
        @DisplayName("no session → 403")
        void noSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doGet(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("BUYER → 403")
        void buyerForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("BUYER");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doGet(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    // ------------------------------------------------------------------ GET: load form

    @Nested
    @DisplayName("GET – form loading")
    class GetForm {
        @Test
        @DisplayName("auction not found / not owned → 403")
        void notOwned() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn("10");
            when(mockDao.getAuctionForEdit(10L, 42)).thenReturn(null);

            servlet.doGet(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("FINISHED auction → 403 (not editable)")
        void finishedAuctionForbidden() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn("10");
            when(mockDao.getAuctionForEdit(10L, 42))
                    .thenReturn(makeEditData(AuctionStatus.FINISHED.getId(), 42));

            servlet.doGet(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN, "Auction is not editable");
        }

        @Test
        @DisplayName("CANCELLED auction → 403 (not editable)")
        void cancelledAuctionForbidden() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn("10");
            when(mockDao.getAuctionForEdit(10L, 42))
                    .thenReturn(makeEditData(AuctionStatus.CANCELLED.getId(), 42));

            servlet.doGet(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN, "Auction is not editable");
        }

        @Test
        @DisplayName("bids already placed → forwards to JSP with error (no edit allowed)")
        void hasBidsBlocksEdit() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn("10");
            when(mockDao.getAuctionForEdit(10L, 42))
                    .thenReturn(makeEditData(AuctionStatus.ACTIVE.getId(), 42));
            when(mockDao.countBids(10L)).thenReturn(2);

            servlet.doGet(req, resp);

            verify(req).setAttribute(eq("Error"), contains("bids"));
            verify(dispatcher).forward(req, resp);
        }

        @Test
        @DisplayName("zero bids + ACTIVE → forwards to edit JSP with auction data")
        void zeroBidsAllowsEdit() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn("10");
            AuctionEditData data = makeEditData(AuctionStatus.ACTIVE.getId(), 42);
            when(mockDao.getAuctionForEdit(10L, 42)).thenReturn(data);
            when(mockDao.countBids(10L)).thenReturn(0);

            servlet.doGet(req, resp);

            verify(req).setAttribute("auction", data);
            verify(dispatcher).forward(req, resp);
        }

        @Test
        @DisplayName("zero bids + PENDING → forwards to edit JSP (scheduled auction is editable)")
        void pendingAuctionEditable() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn("10");
            AuctionEditData data = makeEditData(AuctionStatus.PENDING.getId(), 42);
            when(mockDao.getAuctionForEdit(10L, 42)).thenReturn(data);
            when(mockDao.countBids(10L)).thenReturn(0);

            servlet.doGet(req, resp);

            verify(req).setAttribute("auction", data);
            verify(dispatcher).forward(req, resp);
        }
    }

    // ------------------------------------------------------------------ POST: RBAC

    @Nested
    @DisplayName("POST – RBAC")
    class PostRbac {
        @Test
        @DisplayName("no session → 403")
        void noSession() throws Exception {
            when(req.getSession(false)).thenReturn(null);
            servlet.doPost(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("BUYER → 403")
        void buyerForbidden() throws Exception {
            when(req.getSession(false)).thenReturn(session);
            when(session.getAttribute("userRole")).thenReturn("BUYER");
            when(session.getAttribute("userId")).thenReturn(1);
            servlet.doPost(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    // ------------------------------------------------------------------ POST: field validation

    @Nested
    @DisplayName("POST – field validation")
    class PostValidation {
        @Test
        @DisplayName("blank title → error forwarded, DAO not called")
        void blankTitleRejected() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn(null);
            when(req.getParameter("auction_id")).thenReturn("10");
            when(req.getParameter("title")).thenReturn("   ");
            when(req.getParameter("description")).thenReturn("Some description");
            when(req.getParts()).thenReturn(Collections.emptyList());

            servlet.doPost(req, resp);

            verify(req).setAttribute(eq("Error"), anyString());
            verify(dispatcher).forward(req, resp);
            verifyNoInteractions(mockDao);
        }

        @Test
        @DisplayName("blank description → error forwarded, DAO not called")
        void blankDescriptionRejected() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn(null);
            when(req.getParameter("auction_id")).thenReturn("10");
            when(req.getParameter("title")).thenReturn("Good Title");
            when(req.getParameter("description")).thenReturn("");
            when(req.getParts()).thenReturn(Collections.emptyList());

            servlet.doPost(req, resp);

            verify(req).setAttribute(eq("Error"), anyString());
            verify(dispatcher).forward(req, resp);
            verifyNoInteractions(mockDao);
        }
    }

    // ------------------------------------------------------------------ POST: edit logic

    @Nested
    @DisplayName("POST – edit logic")
    class PostEdit {
        @Test
        @DisplayName("bids placed after GET (TOCTOU) → DAO throws IllegalStateException → error forwarded")
        void toctouBidsPlacedAfterGet() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn(null);
            when(req.getParameter("auction_id")).thenReturn("10");
            when(req.getParameter("title")).thenReturn("New Title");
            when(req.getParameter("description")).thenReturn("New Description");
            when(req.getParameterValues("delete_image_ids")).thenReturn(null);
            when(req.getParts()).thenReturn(Collections.emptyList());
            doThrow(new IllegalStateException("Bids already placed; auction cannot be edited"))
                    .when(mockDao).editAuction(anyLong(), anyInt(), anyString(), anyString(), any(), any());

            servlet.doPost(req, resp);

            verify(req).setAttribute(eq("Error"), contains("Bids"));
            verify(dispatcher).forward(req, resp);
        }

        @Test
        @DisplayName("only seller's own auction id is passed to DAO (no IDOR)")
        void sellerIdFromSession() throws Exception {
            stubSellerSession(55);
            when(req.getParameter("id")).thenReturn(null);
            when(req.getParameter("auction_id")).thenReturn("10");
            when(req.getParameter("title")).thenReturn("Title");
            when(req.getParameter("description")).thenReturn("Desc");
            when(req.getParameterValues("delete_image_ids")).thenReturn(null);
            when(req.getParts()).thenReturn(Collections.emptyList());
            when(req.getContextPath()).thenReturn("/app");

            servlet.doPost(req, resp);

            // editAuction must receive sellerId = 55 (from session), not any other value
            verify(mockDao).editAuction(eq(10L), eq(55), anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("success → redirect to auction page")
        void successRedirects() throws Exception {
            stubSellerSession(42);
            when(req.getParameter("id")).thenReturn(null);
            when(req.getParameter("auction_id")).thenReturn("10");
            when(req.getParameter("title")).thenReturn("Updated Title");
            when(req.getParameter("description")).thenReturn("Updated Desc");
            when(req.getParameterValues("delete_image_ids")).thenReturn(null);
            when(req.getParts()).thenReturn(Collections.emptyList());
            when(req.getContextPath()).thenReturn("/app");

            servlet.doPost(req, resp);

            verify(resp).sendRedirect("/app/auction?id=10");
        }
    }
}
