import com.auction.dao.AutoBidDAO;
import com.auction.servlet.api.AutoBidApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.Mockito.*;

@DisplayName("AutoBidApiServlet")
class TestAutoBidApiServlet {

    private static class Wrapper extends AutoBidApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private AutoBidDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(AutoBidDAO.class);
        servlet = new Wrapper();
        servlet.setAutoBidDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("requires buyer role")
    void sellerForbidden() throws Exception {
        AuthSession s = ApiTestSupport.newSellerSession(1);
        ApiTestSupport.withBearer(req, s);
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(403);
    }

    @Test
    @DisplayName("SET upserts auto-bid")
    void setAutoBid() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("10");
        when(req.getParameter("maxAmount")).thenReturn("500");
        when(req.getParameter("action")).thenReturn("SET");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(mockDAO).upsert(10L, 2, new BigDecimal("500"), null);
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("CANCEL deletes auto-bid")
    void cancelAutoBid() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("10");
        when(req.getParameter("action")).thenReturn("CANCEL");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(mockDAO).delete(10L, 2);
        verify(resp).setStatus(200);
    }
}
