import com.auction.dao.OrderDAO;
import com.auction.dao.PaymentMethodDAO;
import com.auction.servlet.api.OrderApiServlet;
import com.auction.test.ApiTestSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.mockito.Mockito.*;

@DisplayName("OrderApiServlet")
class TestOrderApiServlet {

    private static class Wrapper extends OrderApiServlet {
        @Override public void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doGet(req, resp);
        }
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private OrderDAO mockOrderDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockOrderDAO = mock(OrderDAO.class);
        servlet = new Wrapper();
        servlet.setOrderDAO(mockOrderDAO);
        servlet.setPaymentMethodDAO(mock(PaymentMethodDAO.class));
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("GET requires auth")
    void unauthorized() throws Exception {
        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("GET lists orders for user")
    void listOrders() throws Exception {
        var s = ApiTestSupport.newBuyerSession(7);
        ApiTestSupport.withBearer(req, s);
        when(mockOrderDAO.listForUser(7)).thenReturn(Collections.emptyList());

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doGet(req, resp);
        verify(resp).setStatus(200);
        verify(mockOrderDAO).listForUser(7);
    }
}
