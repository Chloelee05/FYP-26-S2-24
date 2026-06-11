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

import java.util.Collections;

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
