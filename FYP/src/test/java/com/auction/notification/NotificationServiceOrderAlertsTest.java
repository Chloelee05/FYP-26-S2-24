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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * The post-sale lifecycle alerts: payment, despatch, delivery, completion and refunds.
 *
 * <p>Before this existed, an order moving through its stages produced in-app rows for some
 * steps, nothing at all for others, and never a Telegram message — every {@code notify*} on
 * this half of the system called the six-argument {@code create} and so passed
 * {@code telegram = null}. These tests pin down that all nine now carry a push, that each
 * stage can only announce itself once, that the whole set is governed by the one preference,
 * and above all what the messages must <em>not</em> say.</p>
 */
@DisplayName("NotificationService — order lifecycle alerts")
class NotificationServiceOrderAlertsTest {

    private static final long ORDER_ID = 88L;
    private static final long AUCTION_ID = 42L;
    private static final int BUYER = 7;
    private static final int SELLER = 11;

    /** A {@link Connection} that answers the order lookup and records in-app inserts. */
    private static final class FakeDb {
        final Connection conn = mock(Connection.class);
        final List<String> insertedTypes = new ArrayList<>();
        final List<String> insertedMessages = new ArrayList<>();
        final List<String> insertedLinks = new ArrayList<>();

        boolean orderExists = true;
        String title = "Leica M6";
        BigDecimal amount = new BigDecimal("410");
        String buyerUsername = "chloelee";

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
            when(rs.next()).thenReturn(orderExists, false);
            when(rs.getLong("auction_id")).thenReturn(AUCTION_ID);
            when(rs.getInt("buyer_id")).thenReturn(BUYER);
            when(rs.getInt("seller_id")).thenReturn(SELLER);
            when(rs.getBigDecimal("amount")).thenReturn(amount);
            when(rs.getString("title")).thenReturn(title);
            when(rs.getString("buyer_username")).thenReturn(buyerUsername);
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        }

        private PreparedStatement insert() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            String[] type = new String[1];
            String[] message = new String[1];
            String[] link = new String[1];
            doAnswer(i -> { type[0] = i.getArgument(1); return null; })
                    .when(ps).setString(eq(2), anyString());
            doAnswer(i -> { message[0] = i.getArgument(1); return null; })
                    .when(ps).setString(eq(3), anyString());
            doAnswer(i -> { link[0] = i.getArgument(1); return null; })
                    .when(ps).setString(eq(4), anyString());
            when(ps.executeUpdate()).thenAnswer(i -> {
                insertedTypes.add(type[0]);
                insertedMessages.add(message[0]);
                insertedLinks.add(link[0]);
                return 1;
            });
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

    /** Runs {@code body} with the database faked, email off and Telegram configured. */
    private void withDb(Runnable body) {
        withDb(null, body);
    }

    /** Same, but with a public base URL so the trailing link line can be asserted. */
    private void withDb(String baseUrl, Runnable body) {
        try (MockedStatic<DBUtil> dbUtil = mockStatic(DBUtil.class);
             MockedStatic<MailConfig> mail = mockStatic(MailConfig.class);
             MockedStatic<TelegramConfig> telegram =
                     mockStatic(TelegramConfig.class, CALLS_REAL_METHODS)) {
            dbUtil.when(DBUtil::connectDB).thenReturn(db.conn);
            mail.when(MailConfig::isSmtpConfigured).thenReturn(false);
            telegram.when(TelegramConfig::isConfigured).thenReturn(true);
            telegram.when(TelegramConfig::publicBaseUrl).thenReturn(baseUrl);
            body.run();
        }
    }

    private String enqueued(int userId, String eventType, String dedupeKey) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq(userId), eq(eventType), eq(AUCTION_ID), body.capture(),
                eq(dedupeKey), anyInt());
        return body.getValue();
    }

    private void assertNoPushes() {
        verify(outbox, never()).enqueue(anyInt(), anyString(), any(), anyString(), anyString(), anyInt());
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Payment")
    class Payment {

        @Test
        @DisplayName("The buyer is told their money arrived, keyed on the order")
        void buyerGetsAPaymentConfirmation() {
            withDb(() -> NotificationService.notifyBuyerPaymentReceipt(ORDER_ID, "Visa ****4242"));

            String body = enqueued(BUYER, "ORDER_PAYMENT", "ORDER_PAYMENT:88");
            assertTrue(body.contains("Leica M6"), body);
            assertTrue(body.contains("$410.00"), body);
        }

        @Test
        @DisplayName("The card hint stays in the receipt and out of the push")
        void thePaymentMethodIsNotPushed() {
            withDb(() -> NotificationService.notifyBuyerPaymentReceipt(ORDER_ID, "Visa ****4242"));

            String body = enqueued(BUYER, "ORDER_PAYMENT", "ORDER_PAYMENT:88");
            assertFalse(body.contains("4242"),
                    "a lock screen is the wrong place for a payment instrument: " + body);
            assertFalse(body.contains("Visa"), body);
        }

        @Test
        @DisplayName("The seller is told the buyer paid, with the buyer masked")
        void sellerGetsThePaidAlert() {
            withDb(() -> NotificationService.notifyOrderPaid(ORDER_ID));

            String body = enqueued(SELLER, "ORDER_PAID", "ORDER_PAID:88");
            assertTrue(body.contains("c***e"), body);
            assertFalse(body.contains("chloelee"),
                    "the unmasked identity belongs on the order page: " + body);
            assertTrue(body.contains("$410.00"), body);
        }
    }

    @Nested
    @DisplayName("Shipping")
    class Shipping {

        @Test
        @DisplayName("SHIPPED now tells the buyer something, in both channels")
        void shippedIsAnnounced() {
            withDb(() -> NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "SHIPPED"));

            String body = enqueued(BUYER, "ORDER_SHIPPED", "ORDER_SHIPPED:88");
            assertTrue(body.contains("Leica M6"), body);
            assertEquals(List.of("ORDER_SHIPPED"), db.insertedTypes,
                    "this step used to produce no notification at all");
        }

        @Test
        @DisplayName("IN_TRANSIT reaches the buyer as out for delivery")
        void inTransitIsAnnounced() {
            withDb(() -> NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "IN_TRANSIT"));

            String body = enqueued(BUYER, "ORDER_IN_TRANSIT", "ORDER_IN_TRANSIT:88");
            assertTrue(body.toLowerCase().contains("out for delivery"), body);
        }

        @Test
        @DisplayName("DELIVERED asks the buyer to confirm receipt")
        void deliveredIsAnnounced() {
            withDb(() -> NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "DELIVERED"));

            String body = enqueued(BUYER, "ORDER_DELIVERED", "ORDER_DELIVERED:88");
            assertTrue(body.toLowerCase().contains("confirm receipt"), body);
        }

        @Test
        @DisplayName("PREPARING stays silent, because the payment message already said it")
        void preparingIsSilent() {
            withDb(() -> NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "PREPARING"));

            assertTrue(db.insertedTypes.isEmpty(),
                    "PREPARING is set by the payment itself, so it would be a second message "
                            + "for one event: " + db.insertedTypes);
            assertNoPushes();
        }

        @Test
        @DisplayName("An unrecognised or absent stage notifies nobody rather than guessing")
        void unknownStageIsSilent() {
            withDb(() -> {
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "LOST_IN_SPACE");
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, null);
            });

            assertTrue(db.insertedTypes.isEmpty());
            assertNoPushes();
        }

        @Test
        @DisplayName("Each stage has its own key, so the three of them cannot collapse into one")
        void everyStageKeepsItsOwnKey() {
            withDb(() -> {
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "SHIPPED");
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "IN_TRANSIT");
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "DELIVERED");
            });

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(outbox, times(3)).enqueue(eq(BUYER), anyString(), eq(AUCTION_ID), anyString(),
                    keys.capture(), anyInt());
            assertEquals(List.of("ORDER_SHIPPED:88", "ORDER_IN_TRANSIT:88", "ORDER_DELIVERED:88"),
                    keys.getAllValues());
        }
    }

    @Nested
    @DisplayName("Completion")
    class Completion {

        @Test
        @DisplayName("The seller learns the sale is finished, with the buyer masked")
        void sellerGetsTheCompletion() {
            withDb(() -> NotificationService.notifySellerReceiptConfirmed(ORDER_ID));

            String body = enqueued(SELLER, "ORDER_COMPLETED", "ORDER_COMPLETED:88");
            assertTrue(body.contains("c***e"), body);
            assertFalse(body.contains("chloelee"), body);
            assertTrue(body.contains("$410.00"), body);
        }
    }

    @Nested
    @DisplayName("Refunds")
    class Refunds {

        @Test
        @DisplayName("The seller is told a refund was asked for, without the buyer's reason")
        void sellerGetsTheRequest() {
            withDb(() -> NotificationService.notifySellerRefundRequested(ORDER_ID));

            String body = enqueued(SELLER, "REFUND_REQUESTED", "REFUND_REQUESTED:88");
            assertTrue(body.contains("c***e"), body);
            assertTrue(body.toLowerCase().contains("my sales"), body);
        }

        @Test
        @DisplayName("An approved refund tells the buyer where the money goes")
        void buyerGetsAnApproval() {
            withDb(() -> NotificationService.notifyBuyerRefundResolved(ORDER_ID, true));

            String body = enqueued(BUYER, "REFUND_APPROVED", "REFUND_RESULT:88");
            assertTrue(body.contains("$410.00"), body);
            assertTrue(body.toLowerCase().contains("approved"), body);
        }

        @Test
        @DisplayName("A declined refund says the order is still open")
        void buyerGetsADecline() {
            withDb(() -> NotificationService.notifyBuyerRefundResolved(ORDER_ID, false));

            String body = enqueued(BUYER, "REFUND_REJECTED", "REFUND_RESULT:88");
            assertTrue(body.toLowerCase().contains("declined"), body);
        }

        @Test
        @DisplayName("Both outcomes share one key, since a request has exactly one answer")
        void oneRequestOneAnswer() {
            withDb(() -> {
                NotificationService.notifyBuyerRefundResolved(ORDER_ID, true);
                NotificationService.notifyBuyerRefundResolved(ORDER_ID, false);
            });

            verify(outbox).enqueue(eq(BUYER), eq("REFUND_APPROVED"), any(), anyString(),
                    eq("REFUND_RESULT:88"), anyInt());
            verify(outbox).enqueue(eq(BUYER), eq("REFUND_REJECTED"), any(), anyString(),
                    eq("REFUND_RESULT:88"), anyInt());
        }

        @Test
        @DisplayName("An admin override still reaches the buyer, named as the admin")
        void adminOverrideIsAttributed() {
            withDb(() -> NotificationService.notifyBuyerRefundResolved(
                    ORDER_ID, true, "An AuctionHub admin"));

            assertEquals(List.of("REFUND_APPROVED"), db.insertedTypes);
            assertTrue(db.insertedMessages.get(0).startsWith("An AuctionHub admin approved"),
                    db.insertedMessages.get(0));
        }
    }

    @Nested
    @DisplayName("The order-updates preference governs all of them")
    class Preference {

        @Test
        @DisplayName("It is on by default, unlike the seller price feed")
        void onByDefault() {
            assertTrue(NotificationDAO.TelegramPreferences.defaults().orderUpdates,
                    "these are the bounded consequences of a sale the member is already in");
        }

        @Test
        @DisplayName("Turning it off silences every stage push but leaves the in-app rows")
        void offSuppressesEveryPush() {
            when(telegramPrefs.getTelegramPreferences(anyInt())).thenReturn(
                    new NotificationDAO.TelegramPreferences(true, true, true, true, true, false, false));

            withDb(() -> {
                NotificationService.notifyBuyerPaymentReceipt(ORDER_ID, null);
                NotificationService.notifyOrderPaid(ORDER_ID);
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "SHIPPED");
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "IN_TRANSIT");
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "DELIVERED");
                NotificationService.notifySellerReceiptConfirmed(ORDER_ID);
                NotificationService.notifySellerRefundRequested(ORDER_ID);
                NotificationService.notifyBuyerRefundResolved(ORDER_ID, true);
            });

            assertNoPushes();
            assertEquals(8, db.insertedTypes.size(),
                    "the in-app bell is a different channel and keeps working: " + db.insertedTypes);
        }

        @Test
        @DisplayName("The master switch silences them even with the column on")
        void masterSwitchWins() {
            when(telegramPrefs.getTelegramPreferences(anyInt())).thenReturn(
                    new NotificationDAO.TelegramPreferences(false, true, true, true, true, false, true));

            withDb(() -> NotificationService.notifyOrderPaid(ORDER_ID));

            assertNoPushes();
        }

        @Test
        @DisplayName("A party who never connected Telegram does not fill the outbox")
        void unlinkedPartyIsNotQueued() {
            when(links.findByUserId(anyInt())).thenReturn(null);

            withDb(() -> NotificationService.notifyOrderPaid(ORDER_ID));

            assertEquals(List.of("ORDER_PAID"), db.insertedTypes);
            assertNoPushes();
        }
    }

    @Nested
    @DisplayName("Links point at the order, not the closed auction")
    class Links {

        private static final String BASE = "https://example.test/online-auction";

        @Test
        @DisplayName("Buyer-facing alerts link to My purchases")
        void buyerAlertsLinkToPurchases() {
            withDb(BASE, () -> NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "SHIPPED"));

            String body = enqueued(BUYER, "ORDER_SHIPPED", "ORDER_SHIPPED:88");
            assertTrue(body.contains("<a href=\"" + BASE + "/purchases\">View order</a>"), body);
        }

        @Test
        @DisplayName("Seller-facing alerts link to My sales")
        void sellerAlertsLinkToSales() {
            withDb(BASE, () -> NotificationService.notifyOrderPaid(ORDER_ID));

            String body = enqueued(SELLER, "ORDER_PAID", "ORDER_PAID:88");
            assertTrue(body.contains("<a href=\"" + BASE + "/sales\">View order</a>"), body);
        }

        @Test
        @DisplayName("With no public address configured the message simply carries no link")
        void noBaseUrlMeansNoLink() {
            withDb(() -> NotificationService.notifyOrderPaid(ORDER_ID));

            String body = enqueued(SELLER, "ORDER_PAID", "ORDER_PAID:88");
            assertFalse(body.contains("<a href"), body);
        }
    }

    @Nested
    @DisplayName("Privacy")
    class Privacy {

        @Test
        @DisplayName("No order alert carries an email address, a phone number or an address")
        void nothingPersonalLeaks() {
            db.buyerUsername = "chloe.lee@example.com";

            withDb("https://example.test/online-auction", () -> {
                NotificationService.notifyBuyerPaymentReceipt(ORDER_ID, "PayPal (chloe@example.com)");
                NotificationService.notifyOrderPaid(ORDER_ID);
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "SHIPPED");
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "IN_TRANSIT");
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "DELIVERED");
                NotificationService.notifySellerReceiptConfirmed(ORDER_ID);
                NotificationService.notifySellerRefundRequested(ORDER_ID);
                NotificationService.notifyBuyerRefundResolved(ORDER_ID, true);
            });

            ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
            verify(outbox, times(8)).enqueue(anyInt(), anyString(), any(), bodies.capture(),
                    anyString(), anyInt());
            for (String body : bodies.getAllValues()) {
                assertFalse(body.contains("@example.com"),
                        "a contact detail reached a push message: " + body);
                assertFalse(body.contains("chloe.lee"), body);
            }
        }

        @Test
        @DisplayName("Buyer-facing alerts name nobody at all")
        void buyerAlertsNameNobody() {
            withDb(() -> {
                NotificationService.notifyBuyerPaymentReceipt(ORDER_ID, null);
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "SHIPPED");
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "DELIVERED");
                NotificationService.notifyBuyerRefundResolved(ORDER_ID, false);
            });

            ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
            verify(outbox, times(4)).enqueue(eq(BUYER), anyString(), any(), bodies.capture(),
                    anyString(), anyInt());
            for (String body : bodies.getAllValues()) {
                assertFalse(body.contains("***"),
                        "a buyer knows whose listing they bought, so even a masked handle is "
                                + "surplus: " + body);
            }
        }
    }

    @Nested
    @DisplayName("Missing data degrades rather than breaking the order action")
    class Robustness {

        @Test
        @DisplayName("An order that cannot be read notifies nobody and throws nothing")
        void unknownOrderIsHarmless() {
            db.orderExists = false;

            withDb(() -> {
                NotificationService.notifyOrderPaid(ORDER_ID);
                NotificationService.notifyBuyerPaymentReceipt(ORDER_ID, null);
                NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "SHIPPED");
                NotificationService.notifySellerReceiptConfirmed(ORDER_ID);
                NotificationService.notifySellerRefundRequested(ORDER_ID);
                NotificationService.notifyBuyerRefundResolved(ORDER_ID, true);
            });

            assertTrue(db.insertedTypes.isEmpty());
            assertNoPushes();
        }

        @Test
        @DisplayName("A buyer with no usable name still produces a readable seller message")
        void unknownBuyerStillReads() {
            db.buyerUsername = null;

            withDb(() -> NotificationService.notifyOrderPaid(ORDER_ID));

            String body = enqueued(SELLER, "ORDER_PAID", "ORDER_PAID:88");
            assertTrue(body.contains("a verified buyer"), body);
        }

        @Test
        @DisplayName("An order with no amount reads as a phrase rather than a blank")
        void unknownAmountStillReads() {
            db.amount = null;

            withDb(() -> NotificationService.notifyOrderPaid(ORDER_ID));

            String body = enqueued(SELLER, "ORDER_PAID", "ORDER_PAID:88");
            assertTrue(body.contains("the closing price"), body);
        }

        @Test
        @DisplayName("A listing title carrying brace text is not used as a placeholder")
        void titlesCannotInjectPlaceholders() {
            db.title = "Vintage {price} lens";

            withDb(() -> NotificationService.notifyOrderShippingAdvanced(ORDER_ID, "SHIPPED"));

            String body = enqueued(BUYER, "ORDER_SHIPPED", "ORDER_SHIPPED:88");
            assertTrue(body.contains("Vintage {price} lens"),
                    "the title must survive intact: " + body);
        }
    }
}
