package com.auction.dao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Queue semantics of {@link TelegramOutboxDAO} over mocked JDBC: coalescing, leasing and
 * the retry arithmetic.
 */
@DisplayName("TelegramOutboxDAO — the delivery queue")
class TelegramOutboxDAOTest {

    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private TelegramOutboxDAO dao;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        dao = new TelegramOutboxDAO();
    }

    private String capturedSql() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        return sql.getValue();
    }

    @Nested
    @DisplayName("Enqueue")
    class Enqueue {

        @Test
        @DisplayName("Collapses onto an existing undelivered message with the same dedupe key")
        void coalescesOntoPendingRow() throws Exception {
            dao.enqueueWithConnection(conn, 7, "OUTBID", 42L, "<b>Camera</b>", "OUTBID:42:7");

            String sql = capturedSql();
            // The arbiter has to name the partial index's predicate, otherwise Postgres cannot
            // infer ux_telegram_outbox_dedupe and the statement fails rather than coalescing.
            assertTrue(sql.contains("ON CONFLICT (dedupe_key)"), sql);
            assertTrue(sql.contains("WHERE status = 'PENDING' AND dedupe_key IS NOT NULL"), sql);
            assertTrue(sql.contains("DO UPDATE SET body = EXCLUDED.body"),
                    "a repeat must refresh the queued text, not queue a second message: " + sql);
        }

        @Test
        @DisplayName("Coalescing keeps the existing backoff rather than restarting it")
        void coalescingDoesNotResetBackoff() throws Exception {
            dao.enqueueWithConnection(conn, 7, "OUTBID", 42L, "body", "OUTBID:42:7");

            String sql = capturedSql();
            assertFalse(sql.contains("next_attempt_at = "),
                    "re-enqueueing a row that is backing off must not pull it forward: " + sql);
            assertFalse(sql.contains("attempts = 0"),
                    "re-enqueueing must not refund the retry budget: " + sql);
        }

        @Test
        @DisplayName("A null dedupe key is stored as NULL, so such messages never collapse")
        void nullDedupeKeyIsBoundAsNull() throws Exception {
            dao.enqueueWithConnection(conn, 7, "WON", 42L, "body", null);
            verify(ps).setNull(5, Types.VARCHAR);
        }

        @Test
        @DisplayName("A null auction id is allowed (account-level messages)")
        void nullAuctionIdIsBoundAsNull() throws Exception {
            dao.enqueueWithConnection(conn, 7, "WON", null, "body", "WON:0:7");
            verify(ps).setNull(3, Types.BIGINT);
        }
    }

    @Nested
    @DisplayName("Claiming a batch")
    class Claim {

        @Test
        @DisplayName("Leases rows in one statement so the connection is not held during sends")
        void claimIsOneLeasingStatement() throws Exception {
            when(rs.next()).thenReturn(false);

            dao.claimDueWithConnection(conn, 20);

            String sql = capturedSql();
            assertTrue(sql.startsWith("UPDATE telegram_outbox SET next_attempt_at"),
                    "claiming must move the row's due time forward, not hold a lock: " + sql);
            assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"),
                    "two instances must not claim the same row: " + sql);
            assertTrue(sql.contains("status = 'PENDING'"), sql);
            assertTrue(sql.contains("next_attempt_at <= CURRENT_TIMESTAMP"),
                    "only due rows may be claimed: " + sql);
            assertTrue(sql.contains("RETURNING"), sql);
        }

        @Test
        @DisplayName("Returns the fields the worker needs, oldest first")
        void claimMapsRows() throws Exception {
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong("id")).thenReturn(9L);
            when(rs.getInt("user_id")).thenReturn(7);
            when(rs.getString("event_type")).thenReturn("OUTBID");
            when(rs.getString("body")).thenReturn("<b>Camera</b>");
            when(rs.getInt("attempts")).thenReturn(2);

            var batch = dao.claimDueWithConnection(conn, 20);

            assertEquals(1, batch.size());
            assertEquals(9L, batch.get(0).id);
            assertEquals(7, batch.get(0).userId);
            assertEquals("OUTBID", batch.get(0).eventType);
            assertEquals(2, batch.get(0).attempts);
            assertTrue(capturedSql().contains("ORDER BY next_attempt_at ASC, id ASC"));
        }
    }

    @Nested
    @DisplayName("Retry arithmetic")
    class Backoff {

        @Test
        @DisplayName("The ladder is 10s, 30s, 2m, 10m, 30m")
        void ladderMatchesTheDesign() {
            assertEquals(10, TelegramOutboxDAO.retryDelaySeconds(1));
            assertEquals(30, TelegramOutboxDAO.retryDelaySeconds(2));
            assertEquals(120, TelegramOutboxDAO.retryDelaySeconds(3));
            assertEquals(600, TelegramOutboxDAO.retryDelaySeconds(4));
            assertEquals(1800, TelegramOutboxDAO.retryDelaySeconds(5));
        }

        @Test
        @DisplayName("The ladder clamps instead of running off the end")
        void ladderClamps() {
            assertEquals(10, TelegramOutboxDAO.retryDelaySeconds(0));
            assertEquals(1800, TelegramOutboxDAO.retryDelaySeconds(99));
        }

        @Test
        @DisplayName("A first failure schedules 10s and counts one attempt")
        void firstFailureSchedulesTenSeconds() throws Exception {
            dao.markFailedWithConnection(conn, 9L, 0, "502 Bad Gateway");

            assertTrue(capturedSql().contains("next_attempt_at = CURRENT_TIMESTAMP"));
            verify(ps).setInt(1, 1);
            verify(ps).setString(3, "10");
        }

        @Test
        @DisplayName("A fourth failure schedules 10m")
        void fourthFailureSchedulesTenMinutes() throws Exception {
            dao.markFailedWithConnection(conn, 9L, 3, "502 Bad Gateway");

            verify(ps).setInt(1, 4);
            verify(ps).setString(3, "600");
        }

        @Test
        @DisplayName("The fifth failure gives up: FAILED, with the error kept for the operator")
        void fifthFailureIsTerminal() throws Exception {
            dao.markFailedWithConnection(conn, 9L, TelegramOutboxDAO.MAX_ATTEMPTS - 1, "502 Bad Gateway");

            String sql = capturedSql();
            assertTrue(sql.contains("status = 'FAILED'"), sql);
            assertFalse(sql.contains("next_attempt_at"),
                    "a row we have given up on must not be scheduled again: " + sql);
            verify(ps).setInt(1, TelegramOutboxDAO.MAX_ATTEMPTS);
            verify(ps).setString(2, "502 Bad Gateway");
        }

        @Test
        @DisplayName("An oversized error message is truncated rather than rejected by the column")
        void errorTextIsTruncated() throws Exception {
            dao.markFailedWithConnection(conn, 9L, 0, "x".repeat(900));

            ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
            verify(ps).setString(eq(2), stored.capture());
            assertEquals(500, stored.getValue().length());
        }
    }

    @Nested
    @DisplayName("Flood control (429)")
    class FloodControl {

        @Test
        @DisplayName("retry_after is honoured without spending an attempt")
        void delayDoesNotIncrementAttempts() throws Exception {
            dao.delayForWithConnection(conn, 9L, 47);

            String sql = capturedSql();
            assertTrue(sql.contains("next_attempt_at = CURRENT_TIMESTAMP"), sql);
            assertFalse(sql.contains("attempts"),
                    "being asked to slow down is our pacing, not a delivery failure: " + sql);
            verify(ps).setString(1, "47");
        }

        @Test
        @DisplayName("A missing or absurd retry_after is clamped to something sane")
        void retryAfterIsClamped() throws Exception {
            dao.delayForWithConnection(conn, 9L, 0);
            verify(ps).setString(1, "1");

            reset(ps);
            dao.delayForWithConnection(conn, 9L, 86_400);
            verify(ps).setString(1, "300");
        }
    }

    @Nested
    @DisplayName("Terminal states")
    class Terminal {

        @Test
        @DisplayName("Sending marks SENT and stamps sent_at, guarded on the row still being PENDING")
        void markSent() throws Exception {
            dao.markSentWithConnection(conn, 9L);

            String sql = capturedSql();
            assertTrue(sql.contains("status = 'SENT'"), sql);
            assertTrue(sql.contains("sent_at = CURRENT_TIMESTAMP"), sql);
            assertTrue(sql.contains("AND status = 'PENDING'"),
                    "a redelivery must not overwrite an already-terminal row: " + sql);
        }

        @Test
        @DisplayName("Undeliverable messages are SKIPPED, which is distinct from FAILED")
        void markSkipped() throws Exception {
            dao.markSkippedWithConnection(conn, 9L, "403 bot was blocked by the user");

            String sql = capturedSql();
            assertTrue(sql.contains("status = 'SKIPPED'"), sql);
            verify(ps).setString(1, "403 bot was blocked by the user");
        }
    }
}
