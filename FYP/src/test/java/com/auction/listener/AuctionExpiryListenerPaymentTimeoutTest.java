package com.auction.listener;

import com.auction.dao.OrderDAO;
import com.auction.dao.PlatformSettingsDAO;
import com.auction.notification.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The scheduled sweep's wiring for auto-cancelling unpaid winning bids
 * ({@code AuctionExpiryListener#cancelOverdueUnpaidOrders}, reached from the same 60-second
 * {@code runExpiryPass} as auction finalisation — no second background thread).
 *
 * <p>The method under test is private, so it is invoked by reflection; the collaborators it
 * constructs internally ({@link PlatformSettingsDAO}, {@link OrderDAO}) are intercepted with
 * {@link MockedConstruction} so this stays a unit test with no real database.</p>
 */
@DisplayName("AuctionExpiryListener — auto-cancel unpaid orders wiring")
class AuctionExpiryListenerPaymentTimeoutTest {

    private void invoke() throws Exception {
        Method m = AuctionExpiryListener.class.getDeclaredMethod("cancelOverdueUnpaidOrders");
        m.setAccessible(true);
        m.invoke(null);
    }

    @Test
    @DisplayName("Reads the configured deadline, cancels overdue orders, and notifies both parties per order")
    void cancelsOverdueOrdersAndNotifiesBothParties() throws Exception {
        try (MockedConstruction<PlatformSettingsDAO> settingsMock = mockConstruction(PlatformSettingsDAO.class,
                (mock, ctx) -> {
                    when(mock.getLong(eq("order_payment_timeout_effective_since_epoch_ms"), anyLong()))
                            .thenReturn(Instant.parse("2020-01-01T00:00:00Z").toEpochMilli());
                    when(mock.getInt(eq("order_payment_deadline_hours"), anyInt())).thenReturn(48);
                });
             MockedConstruction<OrderDAO> orderDaoMock = mockConstruction(OrderDAO.class,
                     (mock, ctx) -> when(mock.cancelOverduePendingOrders(any(Duration.class), any(Instant.class)))
                             .thenReturn(List.of(501L, 502L)));
             MockedStatic<NotificationService> notify = mockStatic(NotificationService.class)) {

            invoke();

            assertEquals(1, settingsMock.constructed().size());
            assertEquals(1, orderDaoMock.constructed().size());
            verify(orderDaoMock.constructed().get(0))
                    .cancelOverduePendingOrders(eq(Duration.ofHours(48)), any(Instant.class));

            notify.verify(() -> NotificationService.notifyOrderPaymentTimeoutBuyer(501L));
            notify.verify(() -> NotificationService.notifyOrderPaymentTimeoutSeller(501L));
            notify.verify(() -> NotificationService.notifyOrderPaymentTimeoutBuyer(502L));
            notify.verify(() -> NotificationService.notifyOrderPaymentTimeoutSeller(502L));
        }
    }

    @Test
    @DisplayName("An unmigrated deployment (no effective-since setting) skips the pass rather than guessing a cutoff")
    void skipsWhenUnmigrated() throws Exception {
        try (MockedConstruction<PlatformSettingsDAO> settingsMock = mockConstruction(PlatformSettingsDAO.class,
                (mock, ctx) -> when(mock.getLong(eq("order_payment_timeout_effective_since_epoch_ms"), anyLong()))
                        .thenReturn(-1L));
             MockedConstruction<OrderDAO> orderDaoMock = mockConstruction(OrderDAO.class);
             MockedStatic<NotificationService> notify = mockStatic(NotificationService.class)) {

            invoke();

            assertTrue(orderDaoMock.constructed().isEmpty(),
                    "no OrderDAO should even be constructed when the feature is unmigrated");
            notify.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("No cancelled orders means no notifications are sent")
    void noOverdueOrdersMeansNoNotifications() throws Exception {
        try (MockedConstruction<PlatformSettingsDAO> settingsMock = mockConstruction(PlatformSettingsDAO.class,
                (mock, ctx) -> {
                    when(mock.getLong(eq("order_payment_timeout_effective_since_epoch_ms"), anyLong()))
                            .thenReturn(Instant.parse("2020-01-01T00:00:00Z").toEpochMilli());
                    when(mock.getInt(eq("order_payment_deadline_hours"), anyInt())).thenReturn(48);
                });
             MockedConstruction<OrderDAO> orderDaoMock = mockConstruction(OrderDAO.class,
                     (mock, ctx) -> when(mock.cancelOverduePendingOrders(any(Duration.class), any(Instant.class)))
                             .thenReturn(List.of()));
             MockedStatic<NotificationService> notify = mockStatic(NotificationService.class)) {

            invoke();

            notify.verifyNoInteractions();
        }
    }
}
