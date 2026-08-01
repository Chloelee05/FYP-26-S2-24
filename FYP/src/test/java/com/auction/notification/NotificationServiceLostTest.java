package com.auction.notification;

import com.auction.dao.NotificationDAO;
import com.auction.dao.TelegramLinkDAO;
import com.auction.dao.TelegramOutboxDAO;
import com.auction.telegram.TelegramConfig;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The LOST notification: who receives it, who must not, and why the four ways an auction can
 * conclude cannot double-send it.
 */
@DisplayName("NotificationService — auction lost")
class NotificationServiceLostTest {

    private static final long AUCTION_ID = 42L;
    private static final int WINNER = 7;
    private static final int LOSER_A = 3;
    private static final int LOSER_B = 5;

    /**
     * A {@link Connection} that answers each of {@code notifyAuctionLost}'s four queries by
     * matching on the SQL, and records the notifications inserted through it.
     */
    private static final class FakeDb {
        final Connection conn = mock(Connection.class);
        final List<Integer> inserted = new ArrayList<>();
        final List<String> insertedTypes = new ArrayList<>();
        /** Recipients who already hold a LOST row, i.e. a previous conclusion path ran. */
        final Set<Integer> alreadyNotified = new HashSet<>();
        List<Integer> losingBidders = List.of();
        String title = "Leica M6";
        BigDecimal topBid = new BigDecimal("1899.00");
        String losersExclusionSql;
        Integer losersExcludedId;

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
            if (sql.contains("MAX(bid_amount)")) return summary();
            if (sql.contains("SELECT DISTINCT user_id FROM bids")) return losers(sql);
            if (sql.contains("SELECT 1 FROM notifications")) return exists();
            if (sql.startsWith("INSERT INTO notifications")) return insert();
            // Any other query (preferences, user lookup) simply finds nothing.
            return empty();
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

        private PreparedStatement losers(String sql) throws Exception {
            losersExclusionSql = sql;
            PreparedStatement ps = mock(PreparedStatement.class);
            doAnswer(i -> { losersExcludedId = i.getArgument(1); return null; })
                    .when(ps).setInt(eq(2), anyInt());
            ResultSet rs = mock(ResultSet.class);
            int[] cursor = { -1 };
            when(rs.next()).thenAnswer(i -> ++cursor[0] < losingBidders.size());
            when(rs.getInt(1)).thenAnswer(i -> losingBidders.get(cursor[0]));
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        }

        private PreparedStatement exists() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            int[] bound = new int[1];
            doAnswer(i -> { bound[0] = i.getArgument(1); return null; })
                    .when(ps).setInt(eq(1), anyInt());
            when(ps.executeQuery()).thenAnswer(i -> {
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(alreadyNotified.contains(bound[0]));
                return rs;
            });
            return ps;
        }

        private PreparedStatement insert() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            int[] user = new int[1];
            String[] type = new String[1];
            doAnswer(i -> { user[0] = i.getArgument(1); return null; })
                    .when(ps).setInt(eq(1), anyInt());
            doAnswer(i -> { type[0] = i.getArgument(1); return null; })
                    .when(ps).setString(eq(2), anyString());
            when(ps.executeUpdate()).thenAnswer(i -> {
                inserted.add(user[0]);
                insertedTypes.add(type[0]);
                // A conclusion path that runs twice must find the row the first one left.
                alreadyNotified.add(user[0]);
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
        when(telegramPrefs.getTelegramPreferences(anyInt()))
                .thenReturn(NotificationDAO.TelegramPreferences.defaults());
        TelegramNotifier.setDaos(links, outbox, telegramPrefs);
    }

    @AfterEach
    void tearDown() {
        TelegramNotifier.setDaos(new TelegramLinkDAO(), new TelegramOutboxDAO(), new NotificationDAO());
    }

    /** Runs {@code body} with the database faked and both optional channels switched off. */
    private void withDb(Runnable body) {
        withDb(false, body);
    }

    private void withDb(boolean telegramConfigured, Runnable body) {
        try (MockedStatic<DBUtil> dbUtil = mockStatic(DBUtil.class);
             MockedStatic<MailConfig> mail = mockStatic(MailConfig.class);
             MockedStatic<TelegramConfig> telegram = mockStatic(TelegramConfig.class)) {
            dbUtil.when(DBUtil::connectDB).thenReturn(db.conn);
            mail.when(MailConfig::isSmtpConfigured).thenReturn(false);
            telegram.when(TelegramConfig::isConfigured).thenReturn(telegramConfigured);
            body.run();
        }
    }

    @Nested
    @DisplayName("Fan-out")
    class FanOut {

        @Test
        @DisplayName("Every losing bidder is told, and the winner is not")
        void everyLoserExceptTheWinner() {
            db.losingBidders = List.of(LOSER_A, LOSER_B);

            withDb(() -> NotificationService.notifyAuctionLost(AUCTION_ID, WINNER));

            assertEquals(List.of(LOSER_A, LOSER_B), db.inserted);
            assertFalse(db.inserted.contains(WINNER), "the winner must not be told they lost");
            assertEquals(List.of("LOST", "LOST"), db.insertedTypes);
        }

        @Test
        @DisplayName("The winner is excluded in SQL, and each bidder is counted once however often they bid")
        void exclusionAndDistinctnessAreInTheQuery() {
            db.losingBidders = List.of(LOSER_A);

            withDb(() -> NotificationService.notifyAuctionLost(AUCTION_ID, WINNER));

            assertTrue(db.losersExclusionSql.contains("DISTINCT user_id"),
                    "a bidder outbid five times gets one message: " + db.losersExclusionSql);
            assertTrue(db.losersExclusionSql.contains("user_id <> ?"), db.losersExclusionSql);
            assertEquals(WINNER, db.losersExcludedId);
        }

        @Test
        @DisplayName("An auction nobody else bid on notifies nobody")
        void noOtherBiddersNotifiesNobody() {
            db.losingBidders = List.of();

            withDb(() -> NotificationService.notifyAuctionLost(AUCTION_ID, WINNER));

            assertTrue(db.inserted.isEmpty());
        }
    }

    @Nested
    @DisplayName("Deduplication across the four conclusion paths")
    class Dedupe {

        @Test
        @DisplayName("Two finalisation paths on the same auction send one message per bidder")
        void twoPathsSendOnce() {
            db.losingBidders = List.of(LOSER_A, LOSER_B);

            withDb(() -> {
                // e.g. the expiry sweep, then a page load lazily finalising the same auction.
                NotificationService.notifyAuctionLost(AUCTION_ID, WINNER);
                NotificationService.notifyAuctionLost(AUCTION_ID, WINNER);
            });

            assertEquals(List.of(LOSER_A, LOSER_B), db.inserted,
                    "the second pass must find the rows the first left and skip them");
        }

        @Test
        @DisplayName("A bidder already told is skipped while a new one is still notified")
        void partialDedupeStillNotifiesTheRest() {
            db.losingBidders = List.of(LOSER_A, LOSER_B);
            db.alreadyNotified.add(LOSER_A);

            withDb(() -> NotificationService.notifyAuctionLost(AUCTION_ID, WINNER));

            assertEquals(List.of(LOSER_B), db.inserted);
        }
    }

    @Nested
    @DisplayName("Telegram leg")
    class Telegram {

        @Test
        @DisplayName("Each recipient is queued under LOST:{auctionId}:{userId} so a re-entry cannot double-send")
        void dedupeKeyIsPerAuctionAndRecipient() {
            db.losingBidders = List.of(LOSER_A, LOSER_B);
            when(links.findByUserId(anyInt())).thenReturn(
                    new TelegramLinkDAO.LinkInfo("someone", Instant.now(), "ciphertext"));

            withDb(true, () -> NotificationService.notifyAuctionLost(AUCTION_ID, WINNER));

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(outbox, times(2)).enqueue(anyInt(), eq("LOST"), eq(AUCTION_ID), anyString(),
                    keys.capture(), eq(0));
            assertEquals(List.of("LOST:42:3", "LOST:42:5"), keys.getAllValues());
            verify(outbox, never()).enqueue(eq(WINNER), anyString(), any(), anyString(),
                    anyString(), anyInt());
        }

        @Test
        @DisplayName("The message carries the title and price but never names the winner")
        void bodyNeverNamesTheWinner() {
            db.losingBidders = List.of(LOSER_A);
            when(links.findByUserId(anyInt())).thenReturn(
                    new TelegramLinkDAO.LinkInfo("someone", Instant.now(), "ciphertext"));

            withDb(true, () -> NotificationService.notifyAuctionLost(AUCTION_ID, WINNER));

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(outbox).enqueue(eq(LOSER_A), eq("LOST"), eq(AUCTION_ID), body.capture(),
                    anyString(), anyInt());
            assertTrue(body.getValue().contains("Leica M6"), body.getValue());
            assertTrue(body.getValue().contains("$1899.00"), body.getValue());
            assertFalse(body.getValue().contains(String.valueOf(WINNER)),
                    "the winner's identity is personal data: " + body.getValue());
        }

        @Test
        @DisplayName("A user who has opted out of LOST still gets the in-app row but no push")
        void optingOutSuppressesOnlyTheTelegramLeg() {
            db.losingBidders = List.of(LOSER_A);
            when(links.findByUserId(anyInt())).thenReturn(
                    new TelegramLinkDAO.LinkInfo("someone", Instant.now(), "ciphertext"));
            when(telegramPrefs.getTelegramPreferences(LOSER_A)).thenReturn(
                    new NotificationDAO.TelegramPreferences(true, true, true, false, true, false));

            withDb(true, () -> NotificationService.notifyAuctionLost(AUCTION_ID, WINNER));

            assertEquals(List.of(LOSER_A), db.inserted);
            verify(outbox, never()).enqueue(anyInt(), anyString(), any(), anyString(),
                    anyString(), anyInt());
        }

        @Test
        @DisplayName("A user who never connected Telegram is not queued at all")
        void unlinkedUsersDoNotFillTheOutbox() {
            db.losingBidders = List.of(LOSER_A);
            when(links.findByUserId(anyInt())).thenReturn(null);

            withDb(true, () -> NotificationService.notifyAuctionLost(AUCTION_ID, WINNER));

            assertEquals(List.of(LOSER_A), db.inserted);
            verify(outbox, never()).enqueue(anyInt(), anyString(), any(), anyString(),
                    anyString(), anyInt());
        }
    }
}
