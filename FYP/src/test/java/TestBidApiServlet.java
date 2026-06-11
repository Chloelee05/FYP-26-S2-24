import com.auction.dao.BidDAO;
import com.auction.dao.BidDAO.BidResult;
import com.auction.model.AuctionType;
import com.auction.servlet.api.BidApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.Mockito.*;

@DisplayName("BidApiServlet")
class TestBidApiServlet {

    private static class Wrapper extends BidApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private BidDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(BidDAO.class);
        servlet = new Wrapper();
        servlet.setBidDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("no auth → 403")
    void noAuth() throws Exception {
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(403);
        verifyNoInteractions(mockDAO);
    }

    @Test
    @DisplayName("seller role → 403")
    void sellerForbidden() throws Exception {
        AuthSession s = ApiTestSupport.newSellerSession(5);
        ApiTestSupport.withBearer(req, s);
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(403);
    }

    @Test
    @DisplayName("missing auctionId → 400")
    void missingAuctionId() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, s);
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(400);
    }

    @Test
    @DisplayName("ascending bid success → 200")
    void ascendingSuccess() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("10");
        when(req.getParameter("bidAmount")).thenReturn("100");
        when(mockDAO.getAuctionTypeId(10L)).thenReturn(AuctionType.PRICE_UP.getId());
        when(mockDAO.placeBid(10L, 5, new BigDecimal("100"))).thenReturn(BidResult.SUCCESS);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(200);
        verify(mockDAO).placeBid(10L, 5, new BigDecimal("100"));
    }

    @Test
    @DisplayName("bid too low → 400")
    void bidTooLow() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(5);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("10");
        when(req.getParameter("bidAmount")).thenReturn("50");
        when(mockDAO.getAuctionTypeId(10L)).thenReturn(AuctionType.PRICE_UP.getId());
        when(mockDAO.placeBid(10L, 5, new BigDecimal("50"))).thenReturn(BidResult.BID_TOO_LOW);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(400);
    }
}
