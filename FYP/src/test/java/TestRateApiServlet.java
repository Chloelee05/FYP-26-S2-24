import com.auction.dao.RatingDAO;
import com.auction.dao.RatingDAO.RatingResult;
import com.auction.servlet.api.RateApiServlet;
import com.auction.test.ApiTestSupport;
import com.auction.util.AuthSession;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("RateApiServlet")
class TestRateApiServlet {

    private static class Wrapper extends RateApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private RatingDAO mockDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockDAO = mock(RatingDAO.class);
        servlet = new Wrapper();
        servlet.setRatingDAO(mockDAO);
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("POST requires buyer role")
    void sellerForbidden() throws Exception {
        AuthSession s = ApiTestSupport.newSellerSession(1);
        ApiTestSupport.withBearer(req, s);
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(403);
    }

    @Test
    @DisplayName("POST success submits rating")
    void submitSuccess() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getParameter("auctionId")).thenReturn("10");
        when(req.getParameter("score")).thenReturn("5");
        when(mockDAO.insertRating(10L, 2, 5, null)).thenReturn(RatingResult.SUCCESS);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);
        verify(resp).setStatus(200);
    }

    @Test
    @DisplayName("GET /check returns rated flag")
    void checkRated() throws Exception {
        AuthSession s = ApiTestSupport.newBuyerSession(2);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/check");
        when(req.getParameter("auctionId")).thenReturn("10");
        when(mockDAO.existsByBuyerAndAuction(2, 10L)).thenReturn(true);

        StringWriter sw = ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);

        JsonNode body = ApiTestSupport.parse(sw);
        verify(resp).setStatus(200);
        assertTrue(body.get("rated").asBoolean());
    }
}
