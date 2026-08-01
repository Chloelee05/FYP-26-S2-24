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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The seller's two alerts: the coalescing live price feed and the auction result.
 *
 * <p>Covers what a sustained bidding war costs the seller in messages, that the price feed
 * stays silent until it is opted into, that the result is masked and cannot be double-sent
 * across the four conclusion paths, and that a queued price alert cannot outlive the auction
 * it describes.</p>
 */
@DisplayName("NotificationService — seller alerts")
class NotificationServiceSellerAlertsTest {

    private static final long AUCTION_ID = 42L;
    private static final int SELLER = 11;
    private static final int WINNER = 7;

    /**
     * A {@link Connection} answering each of the seller paths' queries by matching on the SQL,
     * and recording the in-app notifications inserted through it.
     */
    private static final class FakeDb {
        final Connection conn = mock(Connection.class);
        final List<String> insertedTypes = new ArrayList<>();
        final List<String> insertedMessages = new ArrayList<>();
        final List<String> insertedLinks = new ArrayList<>();
        /** Recipients who already hold a row of a given type, i.e. a path already ran. */
        final Set<String> alreadyNotified = new HashSet<>();

        Integer sellerId = SELLER;
        String title = "Leica M6";
        BigDecimal topBid = new BigDecimal("410");
        int bidCount = 12;
        Instant dateEnd = Instant.now().plusSeconds(3600);
        String winnerUsername = "chloelee";

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
            // bid_count first: the snapshot query also contains MAX(bid_amount).
            if (sql.contains("bid_count")) return snapshot();
            if (sql.contains("MAX(bid_amount)")) return summary();
            if (sql.contains("SELECT seller_id FROM auction")) return single(sellerId);
            if (sql.contains("SELECT title FROM auction_details")) return singleString(title);
            if (sql.contains("SELECT username FROM users")) return singleString(winnerUsername);
            if (sql.contains("SELECT 1 FROM notifications")) return exists();
            if (sql.startsWith("INSERT INTO notifications")) return insert();
            return empty();
        }

        private PreparedStatement snapshot() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("title")).thenReturn(title);
            when(rs.getBigDecimal("top_bid")).thenReturn(topBid);
            when(rs.getInt("bid_count")).thenReturn(bidCount);
            when(rs.getTimestamp("date_end"))
                    .thenReturn(dateEnd == null ? null : Timestamp.from(dateEnd));
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        }

        private PreparedStatement summary() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("title")).thenReturn(title);
            when(rs.getBigDecimal("top_bid")).thenReturn(topBid);
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        }

        private PreparedStatement single(Integer value) throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(value != null, false);
            when(rs.getInt(1)).thenReturn(value == null ? 0 : value);
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        }

        private PreparedStatement singleString(String value) throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(value != null, false);
            when(rs.getString(1)).thenReturn(value);
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        }

        /** Answers "has this user already been told?" on the (user, type) pair bound to it. */
        private PreparedStatement exists() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            int[] user = new int[1];
            String[] type = new String[1];
            doAnswer(i -> { user[0] = i.getArgument(1); return null; })
                    .when(ps).setInt(eq(1), anyInt());
            doAnswer(i -> { type[0] = i.getArgument(1); return null; })
                    .when(ps).setString(eq(2), anyString());
            when(ps.executeQuery()).thenAnswer(i -> {
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(alreadyNotified.contains(user[0] + ":" + type[0]));
                return rs;
            });
            return ps;
        }

        private PreparedStatement insert() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            int[] user = new int[1];
            String[] type = new String[1];
            String[] message = new String[1];
            String[] link = new String[1];
            doAnswer(i -> { user[0] = i.getArgument(1); return null; })
                    .when(ps).setInt(eq(1), anyInt());
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
                // A second conclusion path must find the row the first one left.
                alreadyNotified.add(user[0] + ":" + type[0]);
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
        // A linked seller who has opted into everything, so each test can opt back out of
        // exactly the thing it is about.
        when(links.findByUserId(anyInt())).thenReturn(
                new TelegramLinkDAO.LinkInfo("someone", Instant.now(), "ciphertext"));
        when(telegramPrefs.getTelegramPreferences(anyInt())).thenReturn(
                new NotificationDAO.TelegramPreferences(true, true, true, true, true, true));
        TelegramNotifier.setDaos(links, outbox, telegramPrefs);
        // The copy cache is process-wide and lives 60s. Dropping it here means the bodies
        // asserted below come from TelegramAlerts' built-in defaults (the faked database has
        // no landing_content rows) rather than from whatever an earlier test left cached.
        TelegramCopy.invalidate();
    }

    @AfterEach
    void tearDown() {
        TelegramNotifier.setDaos(new TelegramLinkDAO(), new TelegramOutboxDAO(), new NotificationDAO());
        TelegramCopy.invalidate();
    }

    /** Runs {@code body} with the database faked, email off and Telegram configured. */
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

    private String enqueuedBody(String eventType) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(outbox, atLeastOnce()).enqueue(anyInt(), eq(eventType), any(), body.capture(),
                anyString(), anyInt());
        return body.getValue();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Live price feed — coalescing")
    class PriceFeed {

        @Test
        @DisplayName("Twenty bids in a war queue one row under PRICE:{auctionId}, carrying the latest price")
        void aBiddingWarCollapsesIntoOneMessage() {
            withDb(() -> {
                for (int i = 0; i < 20; i++) {
                    db.topBid = new BigDecimal(400 + i);
                    db.bidCount = 12 + i;
                    NotificationService.notifySellerNewBid(
                            AUCTION_ID, new BigDecimal(400 + i));
                }
            });

            // Twenty enqueues, but all on the one dedupe key: the partial unique index means
            // the last body wins and the queue holds a single PENDING row.
            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
            verify(outbox, times(20)).enqueue(eq(SELLER), eq("SELLER_PRICE"), eq(AUCTION_ID),
                    bodies.capture(), keys.capture(), anyInt());
            assertEquals(1, new HashSet<>(keys.getAllValues()).size(),
                    "every bid must collapse onto the same row: " + keys.getAllValues());
            assertEquals("PRICE:42", keys.getAllValues().get(0));

            String last = bodies.getAllValues().get(19);
            assertTrue(last.contains("$419.00"), "the survivor carries the current figure: " + last);
            assertTrue(last.contains("31 bids"), last);
        }

        @Test
        @DisplayName("The message reports the price as it stands, not the bid that triggered it")
        void bodyCarriesCurrentPriceNotTheTriggeringBid() {
            db.topBid = new BigDecimal("460");

            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            String body = enqueuedBody("SELLER_PRICE");
            assertTrue(body.contains("$460.00"),
                    "by delivery time the triggering bid is history: " + body);
        }

        @Test
        @DisplayName("Outside the endgame the normal cooldown is the row's delay")
        void normalCooldownFarFromTheClose() {
            db.dateEnd = Instant.now().plusSeconds(3600);

            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            verify(outbox).enqueue(eq(SELLER), eq("SELLER_PRICE"), eq(AUCTION_ID), anyString(),
                    eq("PRICE:42"), eq(TelegramConfig.DEFAULT_PRICE_COOLDOWN_SECONDS));
        }

        @Test
        @DisplayName("Inside the last ten minutes the endgame cooldown applies instead")
        void endgameCooldownNearTheClose() {
            db.dateEnd = Instant.now().plusSeconds(5 * 60);

            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            verify(outbox).enqueue(eq(SELLER), eq("SELLER_PRICE"), eq(AUCTION_ID), anyString(),
                    eq("PRICE:42"), eq(TelegramConfig.DEFAULT_PRICE_COOLDOWN_ENDGAME_SECONDS));
        }

        @Test
        @DisplayName("The bidder is never named, to the seller either")
        void theBidderIsNeverNamed() {
            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            String body = enqueuedBody("SELLER_PRICE");
            assertFalse(body.contains("chloelee"), body);
            assertFalse(body.contains(String.valueOf(WINNER)), body);
        }
    }

    @Nested
    @DisplayName("Live price feed — opt-in")
    class OptIn {

        @Test
        @DisplayName("With telegram_seller_price off by default, no price alert is queued at all")
        void defaultsToSilent() {
            // What a seller who has never opened the Notifications tab has.
            when(telegramPrefs.getTelegramPreferences(anyInt()))
                    .thenReturn(NotificationDAO.TelegramPreferences.defaults());
            assertFalse(NotificationDAO.TelegramPreferences.defaults().sellerPrice,
                    "the price feed is the one high-volume alert, so it must be opt-in");

            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            verify(outbox, never()).enqueue(anyInt(), anyString(), any(), anyString(),
                    anyString(), anyInt());
        }

        @Test
        @DisplayName("Opting out of the price feed still leaves the in-app notification")
        void inAppSurvivesTheOptOut() {
            when(telegramPrefs.getTelegramPreferences(anyInt()))
                    .thenReturn(NotificationDAO.TelegramPreferences.defaults());

            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            assertEquals(List.of("NEW_BID"), db.insertedTypes);
        }

        @Test
        @DisplayName("The master switch silences the price feed even when its own column is on")
        void masterSwitchWins() {
            when(telegramPrefs.getTelegramPreferences(anyInt())).thenReturn(
                    new NotificationDAO.TelegramPreferences(false, true, true, true, true, true));

            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            verify(outbox, never()).enqueue(anyInt(), anyString(), any(), anyString(),
                    anyString(), anyInt());
        }

        @Test
        @DisplayName("A seller who never connected Telegram does not fill the outbox")
        void unlinkedSellerIsNotQueued() {
            when(links.findByUserId(anyInt())).thenReturn(null);

            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            assertEquals(List.of("NEW_BID"), db.insertedTypes);
            verify(outbox, never()).enqueue(anyInt(), anyString(), any(), anyString(),
                    anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("Result — sold")
    class Sold {

        @Test
        @DisplayName("The seller is told the final price and a masked buyer, under RESULT:{auctionId}")
        void soldCarriesPriceAndMaskedBuyer() {
            withDb(() -> NotificationService.notifyAuctionWon(AUCTION_ID, WINNER));

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(outbox).enqueue(eq(SELLER), eq("SELLER_RESULT"), eq(AUCTION_ID), body.capture(),
                    eq("RESULT:42"), anyInt());
            assertTrue(body.getValue().contains("Leica M6"), body.getValue());
            assertTrue(body.getValue().contains("$410.00"), body.getValue());
            assertTrue(body.getValue().contains("c***e"), body.getValue());
        }

        @Test
        @DisplayName("The buyer's full username reaches neither the push nor the in-app line")
        void theFullIdentityIsNeverPushed() {
            withDb(() -> NotificationService.notifyAuctionWon(AUCTION_ID, WINNER));

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(outbox).enqueue(eq(SELLER), eq("SELLER_RESULT"), eq(AUCTION_ID), body.capture(),
                    anyString(), anyInt());
            assertFalse(body.getValue().contains("chloelee"),
                    "the unmasked identity belongs on the order page: " + body.getValue());

            String sellerLine = db.insertedMessages.get(db.insertedTypes.indexOf("SOLD"));
            assertFalse(sellerLine.contains("chloelee"),
                    "the notification bell is no place for it either: " + sellerLine);
            assertTrue(sellerLine.contains("c***e"), sellerLine);
            assertTrue(sellerLine.contains("$410.00"), sellerLine);
        }

        @Test
        @DisplayName("The winner still gets their own WON alert")
        void theWinnerIsStillTold() {
            withDb(() -> NotificationService.notifyAuctionWon(AUCTION_ID, WINNER));

            assertEquals(List.of("WON", "SOLD"), db.insertedTypes);
            verify(outbox).enqueue(eq(WINNER), eq("WON"), eq(AUCTION_ID), anyString(),
                    eq("WON:42:7"), anyInt());
        }

        @Test
        @DisplayName("Opting out of seller results suppresses the push but not the in-app row")
        void optingOutOfResults() {
            when(telegramPrefs.getTelegramPreferences(SELLER)).thenReturn(
                    new NotificationDAO.TelegramPreferences(true, true, true, true, false, true));

            withDb(() -> NotificationService.notifyAuctionWon(AUCTION_ID, WINNER));

            assertTrue(db.insertedTypes.contains("SOLD"));
            verify(outbox, never()).enqueue(eq(SELLER), eq("SELLER_RESULT"), any(), anyString(),
                    anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("Result — unsold")
    class Unsold {

        @Test
        @DisplayName("An auction nobody bid on tells the seller it can be relisted")
        void unsoldOffersARelist() {
            withDb(() -> NotificationService.notifyAuctionEndedUnsold(AUCTION_ID));

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(outbox).enqueue(eq(SELLER), eq("SELLER_RESULT"), eq(AUCTION_ID), body.capture(),
                    eq("RESULT:42"), anyInt());
            assertTrue(body.getValue().contains("Leica M6"), body.getValue());
            assertTrue(body.getValue().toLowerCase().contains("relist"), body.getValue());
            assertEquals(List.of("AUCTION_ENDED"), db.insertedTypes);
        }

        @Test
        @DisplayName("The unsold message quotes no price and no buyer, because there is neither")
        void unsoldNamesNoPriceOrBuyer() {
            withDb(() -> NotificationService.notifyAuctionEndedUnsold(AUCTION_ID));

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(outbox).enqueue(eq(SELLER), eq("SELLER_RESULT"), eq(AUCTION_ID), body.capture(),
                    anyString(), anyInt());
            assertFalse(body.getValue().contains("$"), body.getValue());
            assertFalse(body.getValue().contains("***"), body.getValue());
        }
    }

    @Nested
    @DisplayName("Deduplication across the conclusion paths")
    class Dedupe {

        @Test
        @DisplayName("Two conclusion paths on the same auction announce the sale once")
        void twoSoldPathsSendOnce() {
            withDb(() -> {
                // e.g. Buy It Now, then the expiry sweep lazily finalising the same auction.
                NotificationService.notifyAuctionWon(AUCTION_ID, WINNER);
                NotificationService.notifyAuctionWon(AUCTION_ID, WINNER);
            });

            assertEquals(1, db.insertedTypes.stream().filter("SOLD"::equals).count(),
                    "the second path must find the row the first left: " + db.insertedTypes);
            verify(outbox, times(1)).enqueue(eq(SELLER), eq("SELLER_RESULT"), any(), anyString(),
                    eq("RESULT:42"), anyInt());
        }

        @Test
        @DisplayName("Two conclusion paths on an unsold auction announce it once")
        void twoUnsoldPathsSendOnce() {
            withDb(() -> {
                NotificationService.notifyAuctionEndedUnsold(AUCTION_ID);
                NotificationService.notifyAuctionEndedUnsold(AUCTION_ID);
            });

            assertEquals(List.of("AUCTION_ENDED"), db.insertedTypes);
            verify(outbox, times(1)).enqueue(eq(SELLER), eq("SELLER_RESULT"), any(), anyString(),
                    eq("RESULT:42"), anyInt());
        }

        @Test
        @DisplayName("The seller's result link is per-auction, so one concluded listing does not mask the next")
        void resultLinkIsPerAuction() {
            withDb(() -> NotificationService.notifyAuctionWon(AUCTION_ID, WINNER));

            String sellerLink = db.insertedLinks.get(db.insertedTypes.indexOf("SOLD"));
            assertEquals("/auction/42", sellerLink,
                    "a shared dashboard link would dedupe every future sale away");
        }
    }

    @Nested
    @DisplayName("A queued price alert cannot outlive its auction")
    class StalePriceAlerts {

        @Test
        @DisplayName("Concluding a sale drops any price alert still inside its cooldown")
        void saleCancelsTheQueuedPriceAlert() {
            withDb(() -> NotificationService.notifyAuctionWon(AUCTION_ID, WINNER));

            // Otherwise the seller gets the result, then "bidding is live, no action needed".
            verify(outbox).cancelPending(eq("PRICE:42"), anyString());
        }

        @Test
        @DisplayName("Ending unsold drops it too")
        void unsoldCancelsTheQueuedPriceAlert() {
            withDb(() -> NotificationService.notifyAuctionEndedUnsold(AUCTION_ID));

            verify(outbox).cancelPending(eq("PRICE:42"), anyString());
        }

        @Test
        @DisplayName("A bid does not cancel anything — only a conclusion does")
        void biddingDoesNotCancel() {
            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            verify(outbox, never()).cancelPending(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Missing data degrades rather than breaking the bid")
    class Robustness {

        @Test
        @DisplayName("An auction with no seller notifies nobody and throws nothing")
        void noSellerIsHarmless() {
            db.sellerId = null;

            withDb(() -> {
                NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410"));
                NotificationService.notifyAuctionEndedUnsold(AUCTION_ID);
            });

            assertTrue(db.insertedTypes.isEmpty());
        }

        @Test
        @DisplayName("An unresolvable buyer still produces a usable sold message")
        void unknownBuyerStillReads() {
            db.winnerUsername = null;

            withDb(() -> NotificationService.notifyAuctionWon(AUCTION_ID, WINNER));

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(outbox).enqueue(eq(SELLER), eq("SELLER_RESULT"), any(), body.capture(),
                    anyString(), anyInt());
            assertTrue(body.getValue().contains("a verified buyer"), body.getValue());
        }

        @Test
        @DisplayName("An auction with no end date gets the quieter cooldown rather than a guess")
        void unknownEndDateFallsBack() {
            db.dateEnd = null;

            withDb(() -> NotificationService.notifySellerNewBid(AUCTION_ID, new BigDecimal("410")));

            verify(outbox).enqueue(eq(SELLER), eq("SELLER_PRICE"), any(), anyString(),
                    eq("PRICE:42"), eq(TelegramConfig.DEFAULT_PRICE_COOLDOWN_SECONDS));
        }
    }
}
