package com.auction.notification;

import com.auction.dao.NotificationDAO;
import com.auction.dao.TelegramLinkDAO;
import com.auction.dao.TelegramOutboxDAO;
import com.auction.telegram.TelegramConfig;
import com.auction.telegram.TelegramCopy;
import com.auction.util.DBUtil;
import com.auction.util.MailConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The two alerts fired when the scheduled sweep auto-cancels an unpaid winning bid past the
 * payment deadline: one to the non-paying buyer, one to the seller. Mirrors
 * {@code NotificationServiceOrderAlertsTest}'s fake-DB harness.
 */
@DisplayName("NotificationService — order payment timeout (auto-cancel) alerts")
class NotificationServiceOrderTimeoutTest {

    private static final long ORDER_ID = 88L;
    private static final long AUCTION_ID = 42L;
    private static final int BUYER = 7;
    private static final int SELLER = 11;

    private static final class FakeDb {
        final Connection conn = mock(Connection.class);
        final List<String> insertedTypes = new ArrayList<>();

        String title = "Leica M6";
        BigDecimal amount = new BigDecimal("410");

        FakeDb() {
            try {
                when(conn.prepareStatement(anyString())).thenAnswer(i -> statement(i.getArgument(0)));
                when(conn.prepareStatement(anyString(), anyInt()))
                        .thenAnswer(i -> statement(i.getArgument(0)));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        private PreparedStatement statement(String sql) throws Exception {
            if (sql.contains("buyer_username")) return orderSummary();
            if (sql.startsWith("INSERT INTO notifications")) return insert();
            return empty();
        }

        private PreparedStatement orderSummary() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong("auction_id")).thenReturn(AUCTION_ID);
            when(rs.getInt("buyer_id")).thenReturn(BUYER);
            when(rs.getInt("seller_id")).thenReturn(SELLER);
            when(rs.getBigDecimal("amount")).thenReturn(amount);
            when(rs.getString("title")).thenReturn(title);
            when(rs.getString("buyer_username")).thenReturn("chloelee");
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        }

        private PreparedStatement insert() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            String[] type = new String[1];
            doAnswer(i -> { type[0] = i.getArgument(1); return null; }).when(ps).setString(eq(2), anyString());
            when(ps.executeUpdate()).thenAnswer(i -> { insertedTypes.add(type[0]); return 1; });
            ResultSet keys = mock(ResultSet.class);
            when(keys.next()).thenReturn(true);
            when(keys.getLong(1)).thenReturn(1L);
            when(ps.getGeneratedKeys()).thenReturn(keys);
            return ps;
        }

        private PreparedStatement empty() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(false);
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        }
    }

    private FakeDb db;
    private TelegramLinkDAO links;
    private TelegramOutboxDAO outbox;
    private NotificationDAO telegramPrefs;

    @BeforeEach
    void setUp() {
        db = new FakeDb();
        links = mock(TelegramLinkDAO.class);
        outbox = mock(TelegramOutboxDAO.class);
        telegramPrefs = mock(NotificationDAO.class);
        when(links.findByUserId(anyInt())).thenReturn(
                new TelegramLinkDAO.LinkInfo("someone", Instant.now(), "ciphertext"));
        when(telegramPrefs.getTelegramPreferences(anyInt()))
                .thenReturn(NotificationDAO.TelegramPreferences.defaults());
        TelegramNotifier.setDaos(links, outbox, telegramPrefs);
        TelegramCopy.invalidate();
    }

    @AfterEach
    void tearDown() {
        TelegramNotifier.setDaos(new TelegramLinkDAO(), new TelegramOutboxDAO(), new NotificationDAO());
        TelegramCopy.invalidate();
    }

    private void withDb(Runnable body) {
        try (MockedStatic<DBUtil> dbUtil = mockStatic(DBUtil.class);
             MockedStatic<MailConfig> mail = mockStatic(MailConfig.class);
             MockedStatic<TelegramConfig> telegram =
                     mockStatic(TelegramConfig.class, CALLS_REAL_METHODS)) {
            dbUtil.when(DBUtil::connectDB).thenReturn(db.conn);
            mail.when(MailConfig::isSmtpConfigured).thenReturn(false);
            telegram.when(TelegramConfig::isConfigured).thenReturn(true);
            telegram.when(TelegramConfig::publicBaseUrl).thenReturn(null);
            body.run();
        }
    }

    @Test
    @DisplayName("The non-paying buyer is told their order was cancelled")
    void buyerIsNotified() {
        withDb(() -> NotificationService.notifyOrderPaymentTimeoutBuyer(ORDER_ID));

        verify(outbox).enqueue(eq(BUYER), eq("ORDER_CANCELLED"), eq(AUCTION_ID), anyString(),
                eq("ORDER_PAYMENT_TIMEOUT:" + ORDER_ID), anyInt());
        assertEquals(List.of("ORDER_CANCELLED"), db.insertedTypes);
    }

    @Test
    @DisplayName("The seller is told the buyer didn't pay and the listing closed unsold")
    void sellerIsNotified() {
        withDb(() -> NotificationService.notifyOrderPaymentTimeoutSeller(ORDER_ID));

        verify(outbox).enqueue(eq(SELLER), eq("ORDER_CANCELLED_SELLER"), eq(AUCTION_ID), anyString(),
                eq("ORDER_PAYMENT_TIMEOUT_SELLER:" + ORDER_ID), anyInt());
        assertEquals(List.of("ORDER_CANCELLED_SELLER"), db.insertedTypes);
    }

    @Test
    @DisplayName("Both alerts carry the listing title and mention the deadline/unsold outcome")
    void bodiesAreInformative() {
        withDb(() -> {
            NotificationService.notifyOrderPaymentTimeoutBuyer(ORDER_ID);
            NotificationService.notifyOrderPaymentTimeoutSeller(ORDER_ID);
        });

        var buyerBody = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq(BUYER), anyString(), any(), buyerBody.capture(), anyString(), anyInt());
        assertTrue(buyerBody.getValue().contains("Leica M6"), buyerBody.getValue());

        var sellerBody = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq(SELLER), anyString(), any(), sellerBody.capture(), anyString(), anyInt());
        assertTrue(sellerBody.getValue().toLowerCase().contains("unsold"), sellerBody.getValue());
    }

    @Test
    @DisplayName("Turning off order-update Telegram alerts silences both, but the in-app row still lands")
    void preferenceGatesBothPushes() {
        when(telegramPrefs.getTelegramPreferences(anyInt())).thenReturn(
                new NotificationDAO.TelegramPreferences(true, true, true, true, true, false, false));

        withDb(() -> {
            NotificationService.notifyOrderPaymentTimeoutBuyer(ORDER_ID);
            NotificationService.notifyOrderPaymentTimeoutSeller(ORDER_ID);
        });

        verify(outbox, never()).enqueue(anyInt(), anyString(), any(), anyString(), anyString(), anyInt());
        assertEquals(2, db.insertedTypes.size());
    }

    @Test
    @DisplayName("An order that cannot be read notifies nobody and throws nothing")
    void unknownOrderIsHarmless() throws Exception {
        // Empty order-summary result: statement() still hits orderSummary() branch (sql matches),
        // but the ResultSet reports no row.
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(ps.executeQuery()).thenReturn(rs);
        when(db.conn.prepareStatement(contains("buyer_username"))).thenReturn(ps);

        withDb(() -> {
            NotificationService.notifyOrderPaymentTimeoutBuyer(ORDER_ID);
            NotificationService.notifyOrderPaymentTimeoutSeller(ORDER_ID);
        });

        assertTrue(db.insertedTypes.isEmpty());
        verify(outbox, never()).enqueue(anyInt(), anyString(), any(), anyString(), anyString(), anyInt());
    }
}
