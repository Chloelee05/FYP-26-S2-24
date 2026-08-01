package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for {@code telegram_outbox} — the store-and-forward queue between the
 * request that produces a notification and the background worker that delivers it.
 *
 * <p>The queue exists so that Telegram being slow or down can never slow down or fail a
 * bid: {@link #enqueue} is one INSERT inside the caller's own request, and everything
 * that can go wrong afterwards (timeouts, flood control, a user who blocked the bot)
 * happens on the worker thread where it can be retried.</p>
 *
 * <p><b>Coalescing.</b> {@code ux_telegram_outbox_dedupe} is a partial unique index over
 * {@code dedupe_key WHERE status = 'PENDING'}. {@link #enqueue} names that index in its
 * {@code ON CONFLICT} clause, so a burst of the same event on the same listing refreshes
 * the queued row's body instead of queueing a second message. Once a row has been sent
 * the index no longer covers it, so a legitimate later repeat still queues normally.
 * Paired with the initial delay of
 * {@link #enqueue(int, String, Long, String, String, int)} this becomes a rate limiter as
 * well as a deduplicator: whatever arrives during the delay is folded into the one message.</p>
 *
 * <p><b>Claiming.</b> {@link #claimDue(int)} is a single {@code UPDATE … RETURNING} that
 * pushes {@code next_attempt_at} into the future as it hands rows out. That leases each
 * row rather than locking it: the connection is released immediately instead of being
 * held for the length of a paced send, and a worker that dies mid-batch does not strand
 * its rows — the lease simply expires and the next pass picks them up.
 * {@code FOR UPDATE SKIP LOCKED} keeps two instances from claiming the same row.</p>
 */
public class TelegramOutboxDAO {

    /**
     * Delay before the next attempt, indexed by the number of failures recorded so far.
     * Deliberately front-loaded: most failures are a transient 502 from the API edge and
     * clear within seconds, while anything still failing after ten minutes is usually an
     * outage that a tighter loop would only add load to.
     */
    static final int[] BACKOFF_SECONDS = { 10, 30, 120, 600, 1800 };

    /** Failures after which a row is given up on and marked {@code FAILED}. */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * How long a claimed row is leased for. Comfortably longer than a paced batch takes,
     * short enough that a crashed worker's rows come back on their own.
     */
    private static final int LEASE_SECONDS = 120;

    /** Telegram's own ceiling on how long it will ask us to wait, used to clamp a 429. */
    private static final int MAX_RETRY_AFTER_SECONDS = 300;

    /** One queued message, in the terms the worker needs to deliver it. */
    public static final class PendingMessage {
        public final long id;
        public final int userId;
        public final String eventType;
        public final String body;
        /** Failures recorded before this attempt. */
        public final int attempts;

        public PendingMessage(long id, int userId, String eventType, String body, int attempts) {
            this.id = id;
            this.userId = userId;
            this.eventType = eventType;
            this.body = body;
            this.attempts = attempts;
        }
    }

    // -------------------------------------------------------------------------
    // Enqueue
    // -------------------------------------------------------------------------

    /**
     * Queues one message, collapsing it into an existing undelivered message with the
     * same {@code dedupeKey} when there is one.
     *
     * @param dedupeKey collapse key, or {@code null} to always queue a new row
     */
    public void enqueue(int userId, String eventType, Long auctionId, String body, String dedupeKey) {
        enqueue(userId, eventType, auctionId, body, dedupeKey, 0);
    }

    /**
     * Queues one message that must not be sent for {@code initialDelaySeconds}.
     *
     * <p>The delay is what turns the dedupe index into a coalescing window: a first bid
     * queues a row due in two minutes, and every bid until then only rewrites its body, so
     * the seller receives one message carrying the latest price instead of twenty carrying
     * each step. The delay is applied on INSERT only — see {@link #enqueueWithConnection}.</p>
     */
    public void enqueue(int userId, String eventType, Long auctionId, String body,
                        String dedupeKey, int initialDelaySeconds) {
        try (Connection conn = DBUtil.connectDB()) {
            enqueueWithConnection(conn, userId, eventType, auctionId, body, dedupeKey,
                    initialDelaySeconds);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void enqueueWithConnection(Connection conn, int userId, String eventType, Long auctionId,
                               String body, String dedupeKey) throws SQLException {
        enqueueWithConnection(conn, userId, eventType, auctionId, body, dedupeKey, 0);
    }

    void enqueueWithConnection(Connection conn, int userId, String eventType, Long auctionId,
                               String body, String dedupeKey, int initialDelaySeconds)
            throws SQLException {
        // The DO UPDATE refreshes the body only. attempts and next_attempt_at are left
        // alone on purpose, for two reasons that happen to want the same thing:
        //   - a row already backing off after a failure must not be pulled forward into a
        //     tight retry loop, and
        //   - a coalescing price alert must keep the due time its first bid set. Extending
        //     it on every bid would starve the message exactly when the seller most wants
        //     it: a continuously contested auction would push the deadline forever and
        //     never actually send.
        String sql = "INSERT INTO telegram_outbox "
                + "(user_id, event_type, auction_id, body, dedupe_key, next_attempt_at) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP + (? || ' seconds')::interval) "
                + "ON CONFLICT (dedupe_key) WHERE status = 'PENDING' AND dedupe_key IS NOT NULL "
                + "DO UPDATE SET body = EXCLUDED.body, auction_id = EXCLUDED.auction_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setString(2, eventType);
            if (auctionId != null) ps.setLong(3, auctionId); else ps.setNull(3, Types.BIGINT);
            ps.setString(4, body);
            if (dedupeKey != null) ps.setString(5, dedupeKey); else ps.setNull(5, Types.VARCHAR);
            ps.setString(6, String.valueOf(Math.max(initialDelaySeconds, 0)));
            ps.executeUpdate();
        }
    }

    /**
     * Retires any still-queued message under {@code dedupeKey} without sending it.
     *
     * <p>Used when an event has been overtaken: a coalescing {@code PRICE:{auctionId}} alert
     * waiting out its cooldown becomes wrong the moment the auction concludes, and delivering
     * it after the result message would tell the seller their closed listing is "live, no
     * action needed". Dropping it also frees the partial unique index for a later relist.</p>
     *
     * @return the number of messages dropped, normally 0 or 1
     */
    public int cancelPending(String dedupeKey, String reason) {
        if (dedupeKey == null) {
            return 0;
        }
        try (Connection conn = DBUtil.connectDB()) {
            return cancelPendingWithConnection(conn, dedupeKey, reason);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    int cancelPendingWithConnection(Connection conn, String dedupeKey, String reason)
            throws SQLException {
        String sql = "UPDATE telegram_outbox SET status = 'SKIPPED', last_error = ? "
                + "WHERE dedupe_key = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, trim(reason));
            ps.setString(2, dedupeKey);
            return ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // Claim
    // -------------------------------------------------------------------------

    /** Leases up to {@code limit} due messages, oldest first. */
    public List<PendingMessage> claimDue(int limit) {
        try (Connection conn = DBUtil.connectDB()) {
            return claimDueWithConnection(conn, limit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<PendingMessage> claimDueWithConnection(Connection conn, int limit) throws SQLException {
        String sql = "UPDATE telegram_outbox SET next_attempt_at = "
                + "CURRENT_TIMESTAMP + (? || ' seconds')::interval "
                + "WHERE id IN ("
                + "  SELECT id FROM telegram_outbox "
                + "  WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP "
                + "  ORDER BY next_attempt_at ASC, id ASC LIMIT ? FOR UPDATE SKIP LOCKED"
                + ") "
                + "RETURNING id, user_id, event_type, body, attempts";
        List<PendingMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(LEASE_SECONDS));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PendingMessage(
                            rs.getLong("id"),
                            rs.getInt("user_id"),
                            rs.getString("event_type"),
                            rs.getString("body"),
                            rs.getInt("attempts")));
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Outcomes
    // -------------------------------------------------------------------------

    /** Marks a message delivered. */
    public void markSent(long id) {
        run(conn -> markSentWithConnection(conn, id));
    }

    void markSentWithConnection(Connection conn, long id) throws SQLException {
        String sql = "UPDATE telegram_outbox SET status = 'SENT', sent_at = CURRENT_TIMESTAMP, "
                + "attempts = attempts + 1, last_error = NULL "
                + "WHERE id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * Records a retryable failure: schedules the next attempt off {@link #BACKOFF_SECONDS},
     * or gives up with {@code FAILED} once {@link #MAX_ATTEMPTS} failures have accumulated.
     */
    public void markFailed(long id, int attemptsBefore, String error) {
        run(conn -> markFailedWithConnection(conn, id, attemptsBefore, error));
    }

    void markFailedWithConnection(Connection conn, long id, int attemptsBefore, String error)
            throws SQLException {
        int attempts = attemptsBefore + 1;
        if (attempts >= MAX_ATTEMPTS) {
            String sql = "UPDATE telegram_outbox SET status = 'FAILED', attempts = ?, last_error = ? "
                    + "WHERE id = ? AND status = 'PENDING'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, attempts);
                ps.setString(2, trim(error));
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            return;
        }

        String sql = "UPDATE telegram_outbox SET attempts = ?, last_error = ?, "
                + "next_attempt_at = CURRENT_TIMESTAMP + (? || ' seconds')::interval "
                + "WHERE id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, attempts);
            ps.setString(2, trim(error));
            ps.setString(3, String.valueOf(retryDelaySeconds(attempts)));
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    /**
     * Honours a 429 by pushing the next attempt out {@code retryAfterSeconds}, without
     * counting it as a failure — flood control is our pacing being wrong, not the message
     * being undeliverable, so it must not consume the row's retry budget.
     */
    public void delayFor(long id, int retryAfterSeconds) {
        run(conn -> delayForWithConnection(conn, id, retryAfterSeconds));
    }

    void delayForWithConnection(Connection conn, long id, int retryAfterSeconds) throws SQLException {
        int delay = Math.min(Math.max(retryAfterSeconds, 1), MAX_RETRY_AFTER_SECONDS);
        String sql = "UPDATE telegram_outbox SET "
                + "next_attempt_at = CURRENT_TIMESTAMP + (? || ' seconds')::interval "
                + "WHERE id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(delay));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * Retires a message that can never be delivered — the chat is gone, the user blocked
     * the bot, or they have no link any more. Distinct from {@code FAILED}, which means
     * "we ran out of retries" and is worth looking at.
     */
    public void markSkipped(long id, String reason) {
        run(conn -> markSkippedWithConnection(conn, id, reason));
    }

    void markSkippedWithConnection(Connection conn, long id, String reason) throws SQLException {
        String sql = "UPDATE telegram_outbox SET status = 'SKIPPED', last_error = ? "
                + "WHERE id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, trim(reason));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * Backoff for the {@code attempts}-th failure (1-based). Clamped at the last rung so
     * raising {@link #MAX_ATTEMPTS} needs no change here.
     */
    static int retryDelaySeconds(int attempts) {
        int index = Math.min(Math.max(attempts, 1), BACKOFF_SECONDS.length) - 1;
        return BACKOFF_SECONDS[index];
    }

    @FunctionalInterface
    private interface Statement {
        void execute(Connection conn) throws SQLException;
    }

    private void run(Statement statement) {
        try (Connection conn = DBUtil.connectDB()) {
            statement.execute(conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** {@code last_error} is for operators, not for storing an essay. */
    private static String trim(String error) {
        if (error == null || error.isBlank()) return null;
        String t = error.trim();
        return t.length() <= 500 ? t : t.substring(0, 500);
    }
}
