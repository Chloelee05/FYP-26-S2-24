package com.auction.dao;

import com.auction.telegram.TelegramConfig;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Persistence for the Telegram channel: one-time linking codes and the link itself.
 *
 * <p>Raw values never leave this class in stored form. Chat ids and codes go in as
 * plaintext and are hashed (SHA-256 over a server-side pepper, for lookup) or encrypted
 * (AES-256-GCM, for sending) here, so no caller can accidentally persist the raw value.</p>
 *
 * <p>The single-use guarantee lives in SQL, not in Java: {@link #consumeCode(String)} is
 * one conditional {@code UPDATE ... RETURNING}, so two concurrent redeliveries of the same
 * Telegram update produce exactly one winner. Telegram delivers at least once, so this
 * matters in practice, not just in theory.</p>
 *
 * <p>Reads and writes {@code telegram_link_codes} and {@code telegram_links}. Called by the
 * account settings API when a member starts linking, and by the Telegram webhook when the bot
 * receives the code. Note the two different treatments of a chat id: a peppered SHA-256 hash for
 * looking the link up, and an AES-GCM ciphertext for actually sending to it later.</p>
 */
public class TelegramLinkDAO {

    /** Codes minted per {@code link/start}; both are valid for this long. */
    public static final int CODE_TTL_MINUTES = 10;

    /** A 6-digit code the member types to the bot manually. */
    public static final String KIND_OTP = "OTP";
    /** The longer token embedded in a t.me deep link, so tapping through skips the typing. */
    public static final String KIND_DEEPLINK = "DEEPLINK";

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /** What happened when a chat was linked to an account. */
    public enum LinkStatus {
        /** A new active link was created. */
        LINKED,
        /** This exact chat was already linked to this account, so nothing changed. */
        UNCHANGED
    }

    /** Outcome of {@link #link}, including who to warn when a link moved. */
    public static final class LinkOutcome {
        public final LinkStatus status;
        /**
         * Encrypted chat id of the account's previous Telegram chat, when linking
         * replaced one. Non-null means someone should be told their alerts moved.
         */
        public final String displacedChatIdEncrypted;

        public LinkOutcome(LinkStatus status, String displacedChatIdEncrypted) {
            this.status = status;
            this.displacedChatIdEncrypted = displacedChatIdEncrypted;
        }
    }

    /** The active link for an account, as shown in Account settings. */
    public static final class LinkInfo {
        public final String telegramUsername;
        public final Instant linkedAt;
        /** Ciphertext; decrypt only at the moment of sending. */
        public final String chatIdEncrypted;

        public LinkInfo(String telegramUsername, Instant linkedAt, String chatIdEncrypted) {
            this.telegramUsername = telegramUsername;
            this.linkedAt = linkedAt;
            this.chatIdEncrypted = chatIdEncrypted;
        }
    }

    // -------------------------------------------------------------------------
    // Codes
    // -------------------------------------------------------------------------

    /**
     * Replaces any unused codes the account holds with a fresh pair: the deep-link token
     * and the manual OTP. Both are stored only as hashes, and both resolve through the
     * same {@link #consumeCode(String)}, so the two entry paths cannot diverge.
     *
     * <p>Invalidating the previous batch first means "Get a new code" really does retire
     * the old one, and an attacker cannot widen the guessable set by minting repeatedly.</p>
     */
    public void mintCodes(int userId, String deepLinkToken, String otp) {
        try (Connection conn = DBUtil.connectDB()) {
            mintCodesWithConnection(conn, userId, deepLinkToken, otp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retires the old codes and inserts the two new ones in a single transaction, so there is
     * never a window where the member has no valid code or two live batches at once. The expiry is
     * computed by the database from {@link #CODE_TTL_MINUTES}.
     */
    void mintCodesWithConnection(Connection conn, int userId, String deepLinkToken, String otp)
            throws SQLException {
        // The caller's autocommit setting is saved and restored, because this may be running on a
        // connection that the caller is managing itself.
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            String retire = "UPDATE telegram_link_codes SET used_at = CURRENT_TIMESTAMP "
                    + "WHERE user_id = ? AND used_at IS NULL";
            try (PreparedStatement ps = conn.prepareStatement(retire)) {
                ps.setInt(1, userId);
                ps.executeUpdate();
            }

            String insert = "INSERT INTO telegram_link_codes (user_id, code_hash, kind, expires_at) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP + (? || ' minutes')::interval)";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                // Only the hash is stored. The raw code is shown to the member once and then
                // exists nowhere on the server, so a database dump does not yield usable codes.
                ps.setInt(1, userId);
                ps.setString(2, hash(deepLinkToken));
                ps.setString(3, KIND_DEEPLINK);
                ps.setString(4, String.valueOf(CODE_TTL_MINUTES));
                ps.executeUpdate();

                ps.setInt(1, userId);
                ps.setString(2, hash(otp));
                ps.setString(3, KIND_OTP);
                ps.setString(4, String.valueOf(CODE_TTL_MINUTES));
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * Atomically spends a code and returns the account it belongs to, or {@code null} if
     * it is unknown, already used or expired.
     *
     * <p>One statement does the check and the write together, so a replayed Telegram
     * update or two racing requests can never both succeed.</p>
     */
    public Integer consumeCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        try (Connection conn = DBUtil.connectDB()) {
            return consumeCodeWithConnection(conn, rawCode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The whole redemption in one statement: the WHERE clause is the validity check (known hash,
     * not yet used, not expired), the SET is the spend, and RETURNING reports which account it
     * belonged to. Only one of two racing calls can match the {@code used_at IS NULL} condition.
     */
    Integer consumeCodeWithConnection(Connection conn, String rawCode) throws SQLException {
        String sql = "UPDATE telegram_link_codes SET used_at = CURRENT_TIMESTAMP "
                + "WHERE code_hash = ? AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP "
                + "RETURNING user_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash(rawCode));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /** How many link codes the account has minted since {@code minutes} ago. Feeds the rate limit. */
    public int countCodesMintedSince(int userId, int minutes) {
        String sql = "SELECT COUNT(*)::int FROM telegram_link_codes "
                + "WHERE user_id = ? AND kind = ? "
                + "AND created_at > CURRENT_TIMESTAMP - (? || ' minutes')::interval";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setString(2, KIND_OTP);
            ps.setString(3, String.valueOf(minutes));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Links
    // -------------------------------------------------------------------------

    /**
     * Points {@code userId}'s alerts at {@code chatId}, retiring whatever was there before.
     *
     * <p>Both directions are cleared first, the account's old chat and the chat's old
     * account, because the partial unique indexes allow only one active row for each.
     * Doing it in one transaction is what keeps "link my second account to the same
     * Telegram" and "move my account to a new phone" from colliding.</p>
     */
    public LinkOutcome link(int userId, String chatId, String telegramUsername) {
        try (Connection conn = DBUtil.connectDB()) {
            return linkWithConnection(conn, userId, chatId, telegramUsername);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    LinkOutcome linkWithConnection(Connection conn, int userId, String chatId, String telegramUsername)
            throws SQLException {
        String chatHash = hash(chatId);
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // Same account, same chat: a redelivered update or a double tap. Say so rather
            // than churning the row, so the reply stays correct and no "moved" alert fires.
            String existing = "SELECT chat_id_hash FROM telegram_links WHERE user_id = ? AND active";
            String currentHash = null;
            try (PreparedStatement ps = conn.prepareStatement(existing)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentHash = rs.getString(1);
                    }
                }
            }
            if (chatHash.equals(currentHash)) {
                conn.commit();
                return new LinkOutcome(LinkStatus.UNCHANGED, null);
            }

            // Retire the account's previous chat, keeping its ciphertext so the webhook can
            // warn that chat its alerts have moved. A hijack would look exactly like this, which
            // is why the displaced chat is told rather than silently dropped.
            String displaced = null;
            if (currentHash != null) {
                String select = "SELECT chat_id_encrypted FROM telegram_links WHERE user_id = ? AND active";
                try (PreparedStatement ps = conn.prepareStatement(select)) {
                    ps.setInt(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            displaced = rs.getString(1);
                        }
                    }
                }
                deactivateByUser(conn, userId);
            }

            // Retire any other account currently using this chat, so one Telegram account cannot
            // end up receiving alerts for two members at the same time.
            String releaseChat = "UPDATE telegram_links SET active = FALSE, unlinked_at = CURRENT_TIMESTAMP "
                    + "WHERE chat_id_hash = ? AND active";
            try (PreparedStatement ps = conn.prepareStatement(releaseChat)) {
                ps.setString(1, chatHash);
                ps.executeUpdate();
            }

            String insert = "INSERT INTO telegram_links "
                    + "(user_id, chat_id_hash, chat_id_encrypted, telegram_username) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setInt(1, userId);
                // Both forms of the same chat id: the hash is what lookups match on, the
                // ciphertext is what the delivery worker decrypts when it has a message to send.
                ps.setString(2, chatHash);
                ps.setString(3, SecurityUtil.encrypt(chatId));
                ps.setString(4, trimTo(telegramUsername, 64));
                ps.executeUpdate();
            }

            conn.commit();
            return new LinkOutcome(LinkStatus.LINKED, displaced);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /** Deactivates the account's link. Returns false when there was nothing active. */
    public boolean unlinkUser(int userId) {
        try (Connection conn = DBUtil.connectDB()) {
            return deactivateByUser(conn, userId) > 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Deactivates whatever account this Telegram chat is linked to. Used by {@code /unlink}. */
    public boolean unlinkChat(String chatId) {
        String sql = "UPDATE telegram_links SET active = FALSE, unlinked_at = CURRENT_TIMESTAMP "
                + "WHERE chat_id_hash = ? AND active";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash(chatId));
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The account this chat belongs to, or {@code null}. */
    public Integer findUserIdByChatId(String chatId) {
        String sql = "SELECT user_id FROM telegram_links WHERE chat_id_hash = ? AND active";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash(chatId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The account's active link, or {@code null} when it has none. */
    public LinkInfo findByUserId(int userId) {
        String sql = "SELECT telegram_username, linked_at, chat_id_encrypted "
                + "FROM telegram_links WHERE user_id = ? AND active";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp ts = rs.getTimestamp("linked_at");
                return new LinkInfo(
                        rs.getString("telegram_username"),
                        ts != null ? ts.toInstant() : null,
                        rs.getString("chat_id_encrypted"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Soft-deactivates the account's link rather than deleting the row, so the linking history
     * survives for audit. Shared by {@link #unlinkUser} and the re-link path.
     */
    private static int deactivateByUser(Connection conn, int userId) throws SQLException {
        String sql = "UPDATE telegram_links SET active = FALSE, unlinked_at = CURRENT_TIMESTAMP "
                + "WHERE user_id = ? AND active";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            return ps.executeUpdate();
        }
    }

    /**
     * Peppered SHA-256, lowercase hex. This is the deterministic form used for every lookup, which
     * is why it cannot be salted per row. The pepper lives in the environment, so a database dump
     * on its own cannot be walked back to chat ids or guessed 6-digit codes.
     */
    public static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(TelegramConfig.pepper().getBytes(StandardCharsets.UTF_8));
            md.update((byte) ':');
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Trims to the column width and normalises blank to null, for the display-only username. */
    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }
}
