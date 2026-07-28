import com.auction.dao.WatchlistDAO;
import com.auction.dao.WatchlistDAO.WatchlistResult;
import com.auction.servlet.api.WatchlistApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@DisplayName("WatchlistApiServlet")
class TestWatchlistApiServlet {

    private static class Wrapper extends WatchlistApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private WatchlistDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(WatchlistDAO.class);
        servlet = new Wrapper();
        servlet.setWatchlistDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("GET requires buyer role")
    void getForbiddenForSeller() throws Exception {
        AuthSession s = ApiTestSupport.newSellerSession(1);
        ApiTestSupport.withBearer(req, s);
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        verify(resp).setStatus(403);
    }

    @Test
    @DisplayName("GET lists watchlist for buyer")
    void getSuccess() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(mockDAO.listByUser(2)).thenReturn(Collections.emptyList());
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        verify(resp).setStatus(200);
        verify(mockDAO).listByUser(2);
    }

    @Test
    @DisplayName("GET ?auctionId= returns the single-auction watching flag")
    void checkSingleAuction() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("99");
        when(mockDAO.existsByUserAndAuction(2, 99L)).thenReturn(true);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(200);
        assertTrue(ApiTestSupport.parse(sw).get("watching").asBoolean());
        // Must not fall back to downloading the entire watchlist.
        verify(mockDAO, never()).listByUser(anyInt());
    }

    @Test
    @DisplayName("GET ?auctionId= reports false when not watched")
    void checkSingleAuctionNotWatched() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("99");
        when(mockDAO.existsByUserAndAuction(2, 99L)).thenReturn(false);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(200);
        assertFalse(ApiTestSupport.parse(sw).get("watching").asBoolean());
    }

    @Test
    @DisplayName("GET ?auctionId=abc → 400")
    void checkRejectsBadId() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("abc");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        verify(resp).setStatus(400);
        verify(mockDAO, never()).existsByUserAndAuction(anyInt(), anyLong());
    }

    @Test
    @DisplayName("POST add to watchlist")
    void addSuccess() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("99");
        when(req.getParameter("action")).thenReturn("add");
        when(mockDAO.add(99L, 2)).thenReturn(WatchlistResult.SUCCESS);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(200);
        verify(mockDAO).add(99L, 2);
    }

    @Test
    @DisplayName("POST remove from watchlist")
    void removeSuccess() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("99");
        when(req.getParameter("action")).thenReturn("remove");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(200);
        verify(mockDAO).remove(99L, 2);
    }
}
