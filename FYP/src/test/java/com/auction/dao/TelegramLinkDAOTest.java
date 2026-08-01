package com.auction.dao;

import com.auction.telegram.TelegramConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Single-use code consumption and link bookkeeping in {@link TelegramLinkDAO} (mocked JDBC).
 */
@DisplayName("TelegramLinkDAO — one-time codes and links")
class TelegramLinkDAOTest {

    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
    }

    private static MockedStatic<TelegramConfig> stubConfig() {
        MockedStatic<TelegramConfig> config = mockStatic(TelegramConfig.class);
        config.when(TelegramConfig::pepper).thenReturn("unit-test-pepper");
        return config;
    }

    @Test
    @DisplayName("Consuming a code is single-use: a replay of the same code finds nothing")
    void consumeCode_isSingleUse() throws Exception {
        try (MockedStatic<TelegramConfig> ignored = stubConfig()) {
            // The conditional UPDATE matches once; the second attempt sees used_at set.
            when(rs.next()).thenReturn(true, false);
            when(rs.getInt(1)).thenReturn(42);

            TelegramLinkDAO dao = new TelegramLinkDAO();
            assertEquals(42, dao.consumeCodeWithConnection(conn, "123456"));
            assertNull(dao.consumeCodeWithConnection(conn, "123456"),
                    "a redelivered update must not link a second time");
        }
    }

    @Test
    @DisplayName("Consumption is one atomic statement guarded on unused and unexpired")
    void consumeCode_isAtomicAndChecksExpiry() throws Exception {
        try (MockedStatic<TelegramConfig> ignored = stubConfig()) {
            when(rs.next()).thenReturn(false);

            new TelegramLinkDAO().consumeCodeWithConnection(conn, "abc");

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn).prepareStatement(sql.capture());
            String statement = sql.getValue();
            assertTrue(statement.startsWith("UPDATE telegram_link_codes"),
                    "check and write must be the same statement: " + statement);
            assertTrue(statement.contains("used_at IS NULL"), statement);
            assertTrue(statement.contains("expires_at > CURRENT_TIMESTAMP"),
                    "expired codes must be rejected in SQL: " + statement);
            assertTrue(statement.contains("RETURNING user_id"), statement);
        }
    }

    @Test
    @DisplayName("An expired code yields no user")
    void consumeCode_expiredReturnsNull() throws Exception {
        try (MockedStatic<TelegramConfig> ignored = stubConfig()) {
            when(rs.next()).thenReturn(false);
            assertNull(new TelegramLinkDAO().consumeCodeWithConnection(conn, "999999"));
        }
    }

    @Test
    @DisplayName("Codes are stored hashed, never in plaintext")
    void consumeCode_looksUpByHash() throws Exception {
        try (MockedStatic<TelegramConfig> ignored = stubConfig()) {
            when(rs.next()).thenReturn(false);
            new TelegramLinkDAO().consumeCodeWithConnection(conn, "123456");

            ArgumentCaptor<String> arg = ArgumentCaptor.forClass(String.class);
            verify(ps).setString(eq(1), arg.capture());
            assertNotEquals("123456", arg.getValue());
            assertEquals(64, arg.getValue().length(), "SHA-256 hex is 64 characters");
        }
    }

    @Test
    @DisplayName("Minting retires the account's unused codes, then stores both kinds")
    void mintCodes_retiresPreviousBatch() throws Exception {
        try (MockedStatic<TelegramConfig> ignored = stubConfig()) {
            new TelegramLinkDAO().mintCodesWithConnection(conn, 7, "deep-token", "654321");

            verify(conn).prepareStatement(contains("SET used_at = CURRENT_TIMESTAMP"));
            verify(conn).prepareStatement(contains("INSERT INTO telegram_link_codes"));
            verify(ps).setString(3, TelegramLinkDAO.KIND_DEEPLINK);
            verify(ps).setString(3, TelegramLinkDAO.KIND_OTP);
            verify(conn).commit();
        }
    }

    @Test
    @DisplayName("Linking the same chat again reports no change and warns nobody")
    void link_sameChatIsIdempotent() throws Exception {
        try (MockedStatic<TelegramConfig> ignored = stubConfig()) {
            final String existingHash = TelegramLinkDAO.hash("55501");
            when(rs.next()).thenReturn(true);
            when(rs.getString(1)).thenReturn(existingHash);


            TelegramLinkDAO.LinkOutcome outcome =
                    new TelegramLinkDAO().linkWithConnection(conn, 7, "55501", "chloe");

            assertEquals(TelegramLinkDAO.LinkStatus.UNCHANGED, outcome.status);
            assertNull(outcome.displacedChatIdEncrypted);
            verify(conn, never()).prepareStatement(contains("INSERT INTO telegram_links"));
        }
    }

    @Test
    @DisplayName("Re-linking to a new chat retires both sides and returns the displaced chat")
    void link_replacesPreviousChat() throws Exception {
        try (MockedStatic<TelegramConfig> ignored = stubConfig()) {
            String oldChatHash = TelegramLinkDAO.hash("old-chat");
            when(rs.next()).thenReturn(true, true);
            when(rs.getString(1)).thenReturn(oldChatHash, "ciphertext-of-old-chat");

            TelegramLinkDAO.LinkOutcome outcome =
                    new TelegramLinkDAO().linkWithConnection(conn, 7, "new-chat", "chloe");

            assertEquals(TelegramLinkDAO.LinkStatus.LINKED, outcome.status);
            assertEquals("ciphertext-of-old-chat", outcome.displacedChatIdEncrypted,
                    "the previous chat must be reachable so it can be warned");
            verify(conn).prepareStatement(contains("WHERE chat_id_hash = ? AND active"));
            verify(conn).prepareStatement(contains("INSERT INTO telegram_links"));
            verify(conn).commit();
        }
    }

    @Test
    @DisplayName("Unlinking a chat deactivates rather than deletes, keeping the audit trail")
    void unlinkChat_deactivates() throws Exception {
        try (MockedStatic<TelegramConfig> config = stubConfig();
             MockedStatic<com.auction.util.DBUtil> db = mockStatic(com.auction.util.DBUtil.class)) {
            db.when(com.auction.util.DBUtil::connectDB).thenReturn(conn);
            when(ps.executeUpdate()).thenReturn(1);

            assertTrue(new TelegramLinkDAO().unlinkChat("55501"));
            verify(conn).prepareStatement(contains("active = FALSE"));
        }
    }

    @Test
    @DisplayName("Hashing is deterministic, peppered and never echoes the input")
    void hash_isPepperedAndStable() {
        try (MockedStatic<TelegramConfig> config = stubConfig()) {
            String a = TelegramLinkDAO.hash("55501");
            assertEquals(a, TelegramLinkDAO.hash("55501"));
            assertNotEquals(a, TelegramLinkDAO.hash("55502"));
            assertFalse(a.contains("55501"));

            config.when(TelegramConfig::pepper).thenReturn("a-different-pepper");
            assertNotEquals(a, TelegramLinkDAO.hash("55501"),
                    "changing the pepper must invalidate existing hashes");
        }
    }
}
