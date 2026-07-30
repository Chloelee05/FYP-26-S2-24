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
    private PaymentMethodDAO mockPaymentDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() {
        mockOrderDAO = mock(OrderDAO.class);
        mockPaymentDAO = mock(PaymentMethodDAO.class);
        servlet = new Wrapper();
        servlet.setOrderDAO(mockOrderDAO);
        servlet.setPaymentMethodDAO(mockPaymentDAO);
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

    // ── Payment ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST pay with a valid method marks the order paid and looks up a receipt label")
    void paySuccess() throws Exception {
        var s = ApiTestSupport.newBuyerSession(7);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/pay");
        when(req.getParameter("orderId")).thenReturn("5");
        when(req.getParameter("paymentMethodId")).thenReturn("3");
        when(mockPaymentDAO.belongsTo(7, 3L)).thenReturn(true);
        when(mockOrderDAO.pay(5L, 7, 3L)).thenReturn(true);
        when(mockOrderDAO.partiesAndAuction(5L)).thenReturn(new int[]{7, 9, 11});
        when(mockPaymentDAO.labelFor(7, 3L)).thenReturn("Visa ****4242");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        verify(mockOrderDAO).pay(5L, 7, 3L);
        verify(mockPaymentDAO).labelFor(7, 3L);   // receipt label resolved for the email
    }

    @Test
    @DisplayName("POST pay rejects a payment method that is not the buyer's")
    void payRejectsForeignMethod() throws Exception {
        var s = ApiTestSupport.newBuyerSession(7);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/pay");
        when(req.getParameter("orderId")).thenReturn("5");
        when(req.getParameter("paymentMethodId")).thenReturn("99");
        when(mockPaymentDAO.belongsTo(7, 99L)).thenReturn(false);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(mockOrderDAO, never()).pay(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("POST pay without an order id → 400")
    void payMissingOrderId() throws Exception {
        var s = ApiTestSupport.newBuyerSession(7);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/pay");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(mockOrderDAO, never()).pay(anyLong(), anyInt(), any());
    }

    // ── Refund ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST refund with a description records the request")
    void refundSuccess() throws Exception {
        var s = ApiTestSupport.newBuyerSession(7);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/refund");
        when(req.getParameter("orderId")).thenReturn("5");
        when(req.getParameter("reason")).thenReturn("Item does not match the listing description at all.");
        when(mockOrderDAO.requestRefund(eq(5L), eq(7), anyString()))
                .thenReturn(OrderDAO.RefundResult.SUCCESS);
        when(mockOrderDAO.partiesAndAuction(5L)).thenReturn(new int[]{7, 9, 11});

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        verify(mockOrderDAO).requestRefund(eq(5L), eq(7), anyString());
    }

    @Test
    @DisplayName("POST refund with too-short reason → 400 and no DAO call")
    void refundReasonTooShort() throws Exception {
        var s = ApiTestSupport.newBuyerSession(7);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/refund");
        when(req.getParameter("orderId")).thenReturn("5");
        when(req.getParameter("reason")).thenReturn("nope");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(mockOrderDAO, never()).requestRefund(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("POST refund is forbidden for admins")
    void refundForbiddenForAdmin() throws Exception {
        var s = ApiTestSupport.newAdminSession(7);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/refund");
        when(req.getParameter("orderId")).thenReturn("5");
        when(req.getParameter("reason")).thenReturn("Item does not match the listing description at all.");

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(403);
        verify(mockOrderDAO, never()).requestRefund(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("POST refund is allowed for a seller-capable account on its own purchase")
    void refundAllowedForSellingBuyer() throws Exception {
        var s = ApiTestSupport.newSellingBuyerSession(7);
        ApiTestSupport.withBearer(req, s);
        when(req.getPathInfo()).thenReturn("/refund");
        when(req.getParameter("orderId")).thenReturn("5");
        when(req.getParameter("reason")).thenReturn("Item does not match the listing description at all.");
        when(mockOrderDAO.requestRefund(anyLong(), anyInt(), anyString()))
                .thenReturn(OrderDAO.RefundResult.SUCCESS);

        ApiTestSupport.bindJsonWriter(resp);
        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        verify(mockOrderDAO).requestRefund(5L, 7, "Item does not match the listing description at all.");
    }
}
