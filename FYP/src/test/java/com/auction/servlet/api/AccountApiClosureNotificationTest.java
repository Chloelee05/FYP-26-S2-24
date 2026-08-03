package com.auction.servlet.api;

import com.auction.dao.NotificationDAO;
import com.auction.dao.PaymentMethodDAO;
import com.auction.dao.ProfileActivityDAO;
import com.auction.dao.UserDAO;
import com.auction.test.ApiTestSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * POST /api/account/delete — telling the people a closure affected.
 *
 * <p>The notifications are fired from the servlet rather than the DAO, and only after
 * {@code closeAccount} has returned, so nothing is announced about a closure that then rolled
 * back and a failed notification insert cannot stop somebody closing their account.</p>
 */
@DisplayName("AccountApiServlet — account closure notifications")
class AccountApiClosureNotificationTest {

    private static class Wrapper extends AccountApiServlet {
        @Override public void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException {
            super.doPost(req, resp);
        }
    }

    private static final int USER_ID = 40;

    private UserDAO userDAO;
    private NotificationDAO notificationDAO;
    private Wrapper servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;

    @BeforeEach
    void setUp() throws Exception {
        userDAO = mock(UserDAO.class);
        notificationDAO = mock(NotificationDAO.class);
        servlet = new Wrapper();
        servlet.setUserDAO(userDAO);
        servlet.setProfileActivityDAO(mock(ProfileActivityDAO.class));
        servlet.setPaymentMethodDAO(mock(PaymentMethodDAO.class));
        servlet.setNotificationDAO(notificationDAO);

        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        ApiTestSupport.withBearer(req, ApiTestSupport.newBuyerSession(USER_ID));
        when(req.getPathInfo()).thenReturn("/delete");
        when(req.getParameter("confirm")).thenReturn("DELETE");
        ApiTestSupport.bindJsonWriter(resp);
    }

    // ClosureImpact and AffectedOrder are built by the DAO, so their constructors are
    // package-private. Reflection keeps that encapsulation intact rather than widening the
    // production API just for a test.
    private static UserDAO.AffectedOrder affectedOrder(long orderId, int counterpartyId,
                                                       boolean counterpartyIsBuyer, String title)
            throws Exception {
        Constructor<UserDAO.AffectedOrder> c = UserDAO.AffectedOrder.class
                .getDeclaredConstructor(long.class, int.class, boolean.class, String.class);
        c.setAccessible(true);
        return c.newInstance(orderId, counterpartyId, counterpartyIsBuyer, title);
    }

    private static UserDAO.ClosureImpact impact(List<Long> listings,
                                                List<UserDAO.AffectedOrder> cancelled,
                                                List<UserDAO.AffectedOrder> refundDue,
                                                List<UserDAO.AffectedOrder> handover)
            throws Exception {
        Constructor<UserDAO.ClosureImpact> c = UserDAO.ClosureImpact.class
                .getDeclaredConstructor(boolean.class, List.class, List.class, List.class, List.class);
        c.setAccessible(true);
        return c.newInstance(true, listings, cancelled, refundDue, handover);
    }

    private static UserDAO.ClosureImpact empty() throws Exception {
        return impact(List.of(), List.of(), List.of(), List.of());
    }

    private String messageTo(int userId) {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationDAO).create(eq(userId), anyString(), message.capture(), anyString());
        return message.getValue();
    }

    @Test
    @DisplayName("the buyer of a cancelled unpaid order is told no payment was taken")
    void buyerOfCancelledOrderIsReassured() throws Exception {
        when(userDAO.closeAccount(USER_ID)).thenReturn(
                impact(List.of(6L), List.of(affectedOrder(12L, 3, true, "Vintage Rolex")),
                        List.of(), List.of()));

        servlet.doPost(req, resp);

        verify(resp).setStatus(200);
        verify(notificationDAO).create(eq(3), eq("ORDER_CANCELLED"), anyString(), eq("/purchases"));
        String message = messageTo(3);
        assertTrue(message.contains("Vintage Rolex"), message);
        assertTrue(message.contains("No payment was taken"), message);
    }

    @Test
    @DisplayName("the seller of a cancelled unpaid order is told they can relist")
    void sellerOfCancelledOrderCanRelist() throws Exception {
        when(userDAO.closeAccount(USER_ID)).thenReturn(
                impact(List.of(), List.of(affectedOrder(13L, 27, false, "Monarch")),
                        List.of(), List.of()));

        servlet.doPost(req, resp);

        verify(notificationDAO).create(eq(27), eq("ORDER_CANCELLED_SELLER"), anyString(), eq("/sales"));
        assertTrue(messageTo(27).contains("relist"), messageTo(27));
    }

    @Test
    @DisplayName("a buyer who already paid is told their money is not lost")
    void paidBuyerIsToldRefundIsComing() throws Exception {
        when(userDAO.closeAccount(USER_ID)).thenReturn(
                impact(List.of(), List.of(),
                        List.of(affectedOrder(10L, 1, true, "Cathedral Ring")), List.of()));
        when(userDAO.listAdminUserIds()).thenReturn(List.of());

        servlet.doPost(req, resp);

        verify(notificationDAO).create(eq(1), eq("ACCOUNT_CLOSED_REFUND"), anyString(), eq("/purchases"));
        String message = messageTo(1);
        assertTrue(message.contains("Cathedral Ring"), message);
        assertTrue(message.contains("not lost"), message);
    }

    @Test
    @DisplayName("admins are told about refunds only they can approve — the seller has gone")
    void adminsGetTheRefundQueue() throws Exception {
        when(userDAO.closeAccount(USER_ID)).thenReturn(
                impact(List.of(), List.of(),
                        List.of(affectedOrder(10L, 1, true, "Cathedral Ring"),
                                affectedOrder(11L, 3, true, "Sweet Alhambra watch")),
                        List.of()));
        when(userDAO.listAdminUserIds()).thenReturn(List.of(2, 31));

        servlet.doPost(req, resp);

        verify(notificationDAO).create(eq(2), eq("ADMIN_ACCOUNT_CLOSURE_REFUND"),
                contains("2 paid, undespatched orders"), eq("/admin/orders"));
        verify(notificationDAO).create(eq(31), eq("ADMIN_ACCOUNT_CLOSURE_REFUND"),
                anyString(), eq("/admin/orders"));
    }

    @Test
    @DisplayName("no refunds means no admin alert at all")
    void noRefundsNoAdminAlert() throws Exception {
        when(userDAO.closeAccount(USER_ID)).thenReturn(empty());

        servlet.doPost(req, resp);

        verify(userDAO, never()).listAdminUserIds();
        verify(notificationDAO, never()).create(anyInt(), eq("ADMIN_ACCOUNT_CLOSURE_REFUND"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("a counterparty on an in-transit order is told the order is unchanged")
    void handoverCounterpartyIsInformed() throws Exception {
        when(userDAO.closeAccount(USER_ID)).thenReturn(
                impact(List.of(), List.of(), List.of(),
                        List.of(affectedOrder(7L, 3, true, "Brown Penny Loafers"))));

        servlet.doPost(req, resp);

        verify(notificationDAO).create(eq(3), eq("ACCOUNT_CLOSED_COUNTERPARTY"),
                contains("unchanged"), eq("/purchases"));
    }

    @Test
    @DisplayName("a failed anonymisation notifies nobody and answers 500")
    void failedClosureNotifiesNobody() throws Exception {
        Constructor<UserDAO.ClosureImpact> c = UserDAO.ClosureImpact.class
                .getDeclaredConstructor(boolean.class, List.class, List.class, List.class, List.class);
        c.setAccessible(true);
        when(userDAO.closeAccount(USER_ID)).thenReturn(
                c.newInstance(false, List.of(), List.of(), List.of(), List.of()));

        servlet.doPost(req, resp);

        verify(resp).setStatus(500);
        verify(notificationDAO, never()).create(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("closure without the confirmation word does not close or notify anything")
    void confirmIsStillRequired() throws Exception {
        when(req.getParameter("confirm")).thenReturn(null);

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(userDAO, never()).closeAccount(anyInt());
        verify(notificationDAO, never()).create(anyInt(), anyString(), anyString(), anyString());
    }
}
