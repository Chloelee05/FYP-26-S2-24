import com.auction.dao.AuctionDAO;
import com.auction.servlet.admin.AdminListingsServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;

import static org.mockito.Mockito.*;

@DisplayName("AdminListingsServlet")
class TestAdminListingsServlet {

    private static class Wrapper extends AdminListingsServlet {
        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doGet(req, resp);
        }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            super.doPost(req, resp);
        }
    }

    private AuctionDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        mockDAO = mock(AuctionDAO.class);
        servlet = new Wrapper();
        servlet.setAuctionDAO(mockDAO);
        req     = mock(HttpServletRequest.class);
        resp    = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
    }

    @Test
    @DisplayName("doGet loads listings for moderation")
    void getListings() throws Exception {
        when(req.getSession()).thenReturn(session);
        when(mockDAO.listListingsForModeration()).thenReturn(Collections.emptyList());
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/WEB-INF/views/admin/listings.jsp")).thenReturn(dispatcher);

        servlet.doGet(req, resp);

        verify(req).setAttribute("listings", Collections.emptyList());
        verify(dispatcher).forward(req, resp);
    }

    @Test
    @DisplayName("POST FLAG updates moderation state")
    void flagListing() throws Exception {
        when(req.getSession()).thenReturn(session);
        when(req.getParameter("action")).thenReturn("FLAG");
        when(req.getParameter("auctionId")).thenReturn("42");
        when(mockDAO.incrementReports(42L)).thenReturn(true);
        when(mockDAO.updateModerationState(42L, "flagged")).thenReturn(true);
        when(req.getContextPath()).thenReturn("/app");

        servlet.doPost(req, resp);

        verify(mockDAO).incrementReports(42L);
        verify(mockDAO).updateModerationState(42L, "flagged");
        verify(resp).sendRedirect("/app/admin/listings");
    }

    @Test
    @DisplayName("POST invalid auctionId → 400")
    void invalidAuctionId() throws Exception {
        when(req.getSession()).thenReturn(session);
        when(req.getParameter("action")).thenReturn("FLAG");
        when(req.getParameter("auctionId")).thenReturn("not-a-number");

        servlet.doPost(req, resp);
        verify(resp).sendError(HttpServletResponse.SC_BAD_REQUEST);
    }
}
