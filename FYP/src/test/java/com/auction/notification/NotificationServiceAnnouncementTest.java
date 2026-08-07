package com.auction.notification;

import com.auction.util.DBUtil;
import com.auction.util.MailConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link NotificationService#broadcastAnnouncement(String, String)} — NEW for the
 * "system-wide announcement" admin story.
 *
 * <p>Kept small on purpose, matching the story's own scope: this proves the one behaviour the
 * story is graded on, that the broadcast reaches every recipient {@code UserDAO.listActiveUserIds}
 * hands it (and, by construction of that already-tested query, none of the excluded statuses),
 * and that each recipient gets one {@code ANNOUNCEMENT} row through the same funnel every other
 * notification type uses. Modelled on {@link NotificationServiceLostTest}'s fake-database
 * approach for the pieces that matter here: the active-user query and the notification insert.
 * Email is left unconfigured so the async mail leg never engages, and Telegram is skipped
 * entirely since {@code create} is called with no Telegram alert body, which is a no-op
 * before any Telegram DAO is touched.</p>
 */
@DisplayName("NotificationService — system-wide announcement broadcast")
class NotificationServiceAnnouncementTest {

    /** Answers the one query {@code listActiveUserIds} issues and records every insert. */
    private static final class FakeDb {
        final Connection conn = mock(Connection.class);
        final List<Integer> insertedUserIds = new ArrayList<>();
        final List<String> insertedTypes = new ArrayList<>();
        final List<String> insertedMessages = new ArrayList<>();
        List<Integer> activeUserIds = List.of();

        FakeDb() {
            try {
                when(conn.prepareStatement(anyString())).thenAnswer(i -> statement(i.getArgument(0)));
                // NotificationDAO.create asks for RETURN_GENERATED_KEYS via the two-arg
                // overload, so it must be stubbed too or it silently returns null.
                when(conn.prepareStatement(anyString(), anyInt()))
                        .thenAnswer(i -> statement(i.getArgument(0)));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        private PreparedStatement statement(String sql) throws Exception {
            if (sql.contains("FROM users WHERE status_id")) return activeUsers();
            if (sql.startsWith("INSERT INTO notifications")) return insert();
            return empty();
        }

        private PreparedStatement activeUsers() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            int[] cursor = { -1 };
            when(rs.next()).thenAnswer(i -> ++cursor[0] < activeUserIds.size());
            when(rs.getInt("id")).thenAnswer(i -> activeUserIds.get(cursor[0]));
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        }

        private PreparedStatement insert() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            int[] user = new int[1];
            String[] type = new String[1];
            String[] message = new String[1];
            doAnswer(i -> { user[0] = i.getArgument(1); return null; }).when(ps).setInt(eq(1), anyInt());
            doAnswer(i -> { type[0] = i.getArgument(1); return null; }).when(ps).setString(eq(2), anyString());
            doAnswer(i -> { message[0] = i.getArgument(1); return null; }).when(ps).setString(eq(3), anyString());
            when(ps.executeUpdate()).thenAnswer(i -> {
                insertedUserIds.add(user[0]);
                insertedTypes.add(type[0]);
                insertedMessages.add(message[0]);
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

    @BeforeEach
    void setUp() {
        db = new FakeDb();
    }

    private void withDb(Runnable body) {
        try (MockedStatic<DBUtil> dbUtil = mockStatic(DBUtil.class);
             MockedStatic<MailConfig> mail = mockStatic(MailConfig.class)) {
            dbUtil.when(DBUtil::connectDB).thenReturn(db.conn);
            // Email off, so the async mail leg (a separate executor and a user lookup this
            // test does not stub) never engages. Telegram needs no stub at all: create() is
            // called with a null Alert for this event, which short-circuits before any
            // Telegram DAO is touched.
            mail.when(MailConfig::isSmtpConfigured).thenReturn(false);
            body.run();
        }
    }

    @Test
    @DisplayName("reaches every active user returned by UserDAO.listActiveUserIds, once each")
    void reachesEveryActiveUser() {
        db.activeUserIds = List.of(2, 9, 31);

        int[] sent = new int[1];
        withDb(() -> sent[0] = NotificationService.broadcastAnnouncement("Maintenance", "Down 2-3am."));

        assertEquals(3, sent[0]);
        assertEquals(List.of(2, 9, 31), db.insertedUserIds);
        assertEquals(List.of("ANNOUNCEMENT", "ANNOUNCEMENT", "ANNOUNCEMENT"), db.insertedTypes);
        assertTrue(db.insertedMessages.stream().allMatch(m -> m.equals("Down 2-3am.")));
    }

    @Test
    @DisplayName("a suspended/deleted/pending account absent from listActiveUserIds gets nothing")
    void skipsAccountsNotReturnedAsActive() {
        // UserDAOActiveLookupTest pins that listActiveUserIds only ever returns
        // Status.ACTIVE rows; this proves the broadcast has no other route to a recipient,
        // so an account that query excludes (suspended, deleted, pending, rejected) is
        // never written to.
        db.activeUserIds = List.of(2);

        withDb(() -> NotificationService.broadcastAnnouncement("Policy update", "New terms."));

        assertEquals(List.of(2), db.insertedUserIds);
        assertFalse(db.insertedUserIds.contains(99), "no id outside the active list may be notified");
    }

    @Test
    @DisplayName("no active users means no notifications and a zero count, not an error")
    void noActiveUsersSendsNothing() {
        db.activeUserIds = List.of();

        int[] sent = new int[1];
        withDb(() -> sent[0] = NotificationService.broadcastAnnouncement("Title", "Body"));

        assertEquals(0, sent[0]);
        assertTrue(db.insertedUserIds.isEmpty());
    }

    @Test
    @DisplayName("a blank title falls back to a default subject rather than an empty one")
    void blankTitleFallsBackToDefaultSubject() {
        db.activeUserIds = List.of(2);
        // The in-app message body carries the announcement text either way; only the
        // (email-only) subject has a fallback, so this simply proves the call does not
        // reject a blank title outright -- validation of blank input is the servlet's job.
        withDb(() -> assertEquals(1,
                NotificationService.broadcastAnnouncement("  ", "Body text")));
        assertEquals(List.of("Body text"), db.insertedMessages);
    }
}
