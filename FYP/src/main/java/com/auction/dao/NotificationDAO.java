package com.auction.dao;

import com.auction.model.Notification;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for in-app notifications and the per-user delivery preferences behind them.
 *
 * <p>Reads and writes {@code notifications} and {@code notification_preference}. Called by the
 * notification API servlet for the bell menu, and by the event producers: bidding (outbid),
 * auction close (won, lost), the watchlist ending-soon job and order lifecycle changes. The
 * Telegram side of the preferences is consumed by {@link TelegramOutboxDAO}.</p>
 */
public class NotificationDAO {

    /**
     * Inserts a notification and returns its generated id (or -1 on failure).
     *
     * @param type  event category such as OUTBID or WON, used by the UI to pick an icon
     * @param link  optional in-app deep link, also used by {@link #exists} as the de-duplication key
     */
    public long create(int userId, String type, String message, String link) {
        String sql = "INSERT INTO notifications (user_id, type, message, link) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            ps.setString(2, type);
            ps.setString(3, message);
            if (link != null) ps.setString(4, link); else ps.setNull(4, java.sql.Types.VARCHAR);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return -1;
    }

    /**
     * Whether this user has already been told about this exact event. Callers use it to make
     * notification sending idempotent, since auctions are finalized lazily and the same close can
     * be processed by more than one request.
     *
     * <p>Returns false rather than throwing on a database error, so a failed check degrades to a
     * possible duplicate notification instead of blocking the operation that triggered it.</p>
     */
    public boolean exists(int userId, String type, String link) {
        String sql = "SELECT 1 FROM notifications WHERE user_id = ? AND type = ? AND link = ? LIMIT 1";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setString(2, type);
            ps.setString(3, link);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The newest notifications for the bell dropdown.
     *
     * @param limit how many to return; the dropdown shows a short list, not the full history
     */
    public List<Notification> listForUser(int userId, int limit) {
        // id breaks ties on created_at, because several notifications can be written in the same
        // transaction and share a timestamp. Without it the order would be unstable between reads.
        String sql = "SELECT id, type, message, link, is_read, created_at "
                + "FROM notifications WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?";
        List<Notification> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("created_at");
                    out.add(new Notification(
                            rs.getLong("id"),
                            rs.getString("type"),
                            rs.getString("message"),
                            rs.getString("link"),
                            rs.getBoolean("is_read"),
                            ts != null ? ts.toInstant() : null));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Unread count for the badge on the bell icon. */
    public int countUnread(int userId) {
        String sql = "SELECT COUNT(*)::int FROM notifications WHERE user_id = ? AND is_read = FALSE";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    /** Marks one notification read (scoped to the owner). */
    public boolean markRead(int userId, long notificationId) {
        // user_id is part of the WHERE clause, not just the id, so passing someone else's
        // notification id updates nothing instead of marking their notification read.
        String sql = "UPDATE notifications SET is_read = TRUE WHERE id = ? AND user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, notificationId);
            ps.setInt(2, userId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Marks all of a user's notifications read. Returns how many were still unread. */
    public int markAllRead(int userId) {
        // The is_read = FALSE filter keeps the write to the rows that actually change, and makes
        // the return value a meaningful count rather than the user's whole notification history.
        String sql = "UPDATE notifications SET is_read = TRUE WHERE user_id = ? AND is_read = FALSE";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The full unpaginated history for the dedicated notifications page, newest first. */
    public List<Notification> notificationHistory(int userId) throws Exception{
        String sql = "SELECT id, type, message, link, is_read, created_at "
                + "FROM notifications WHERE user_id = ? ORDER BY created_at DESC";
        List<Notification> result = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("created_at");
                    result.add(new Notification(
                            rs.getLong("id"),
                            rs.getString("type"),
                            rs.getString("message"),
                            rs.getString("link"),
                            rs.getBoolean("is_read"),
                            ts != null ? ts.toInstant() : null));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    /** Per-user notification preferences (all default to enabled when no row exists). */
    public static final class Preferences {
        public final boolean outbid;
        public final boolean endingSoon;
        public final boolean wonAuction;

        public Preferences(boolean outbid, boolean endingSoon, boolean wonAuction) {
            this.outbid = outbid;
            this.endingSoon = endingSoon;
            this.wonAuction = wonAuction;
        }
    }

    /**
     * Returns the user's notification preferences, defaulting to all-enabled.
     *
     * <p>A missing row means the user has never opened the settings page, which is treated as
     * consent to the defaults rather than as opting out of everything.</p>
     */
    public Preferences getUserPreferences(int userId) {
        String sql = "SELECT out_bided, ending_soon, won_auction FROM notification_preference WHERE user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Preferences(
                            rs.getBoolean("out_bided"),
                            rs.getBoolean("ending_soon"),
                            rs.getBoolean("won_auction"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new Preferences(true, true, true);
    }

    /**
     * Per-user Telegram delivery preferences.
     *
     * <p>Kept apart from {@link Preferences} because the two answer different questions.
     * {@link Preferences} decides whether an event produces a notification at all;
     * this decides whether it also goes to Telegram. Reading them separately also means an
     * un-migrated database only fails the Telegram query rather than the in-app one.</p>
     */
    public static final class TelegramPreferences {
        /** Master switch: false silences every Telegram message without unlinking. */
        public final boolean enabled;
        public final boolean outbid;
        public final boolean won;
        public final boolean lost;
        public final boolean sellerResult;
        public final boolean sellerPrice;
        /**
         * One switch for the whole post-sale lifecycle: payment, despatch, delivery,
         * completion and refunds. Deliberately not a column per stage, because these are the
         * bounded, non-repeating consequences of a transaction the member is already
         * party to, and nobody sensibly wants to be told their parcel shipped but not
         * that it arrived.
         */
        public final boolean orderUpdates;

        public TelegramPreferences(boolean enabled, boolean outbid, boolean won,
                                   boolean lost, boolean sellerResult, boolean sellerPrice,
                                   boolean orderUpdates) {
            this.enabled = enabled;
            this.outbid = outbid;
            this.won = won;
            this.lost = lost;
            this.sellerResult = sellerResult;
            this.sellerPrice = sellerPrice;
            this.orderUpdates = orderUpdates;
        }

        /** Matches the column defaults: everything on except the noisy seller price feed. */
        public static TelegramPreferences defaults() {
            return new TelegramPreferences(true, true, true, true, true, false, true);
        }
    }

    /** The user's Telegram preferences, defaulting to {@link TelegramPreferences#defaults()}. */
    public TelegramPreferences getTelegramPreferences(int userId) {
        String sql = "SELECT telegram_enabled, telegram_outbid, telegram_won, telegram_lost, "
                + "telegram_seller_result, telegram_seller_price, telegram_order_updates "
                + "FROM notification_preference WHERE user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new TelegramPreferences(
                            rs.getBoolean("telegram_enabled"),
                            rs.getBoolean("telegram_outbid"),
                            rs.getBoolean("telegram_won"),
                            rs.getBoolean("telegram_lost"),
                            rs.getBoolean("telegram_seller_result"),
                            rs.getBoolean("telegram_seller_price"),
                            rs.getBoolean("telegram_order_updates"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return TelegramPreferences.defaults();
    }

    /**
     * UPSERTs only the Telegram columns, leaving the in-app preferences untouched.
     *
     * <p>Both preference groups live in one {@code notification_preference} row, so the DO UPDATE
     * clause names the Telegram columns explicitly. Rewriting the whole row would silently reset
     * the in-app switches that {@link #saveUserPreferences} owns.</p>
     */
    public boolean saveTelegramPreferences(int userId, TelegramPreferences p) throws Exception {
        String sql = "INSERT INTO notification_preference "
                + "(user_id, telegram_enabled, telegram_outbid, telegram_won, telegram_lost, "
                + " telegram_seller_result, telegram_seller_price, telegram_order_updates) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET "
                + "telegram_enabled = EXCLUDED.telegram_enabled, "
                + "telegram_outbid = EXCLUDED.telegram_outbid, "
                + "telegram_won = EXCLUDED.telegram_won, "
                + "telegram_lost = EXCLUDED.telegram_lost, "
                + "telegram_seller_result = EXCLUDED.telegram_seller_result, "
                + "telegram_seller_price = EXCLUDED.telegram_seller_price, "
                + "telegram_order_updates = EXCLUDED.telegram_order_updates";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setBoolean(2, p.enabled);
            ps.setBoolean(3, p.outbid);
            ps.setBoolean(4, p.won);
            ps.setBoolean(5, p.lost);
            ps.setBoolean(6, p.sellerResult);
            ps.setBoolean(7, p.sellerPrice);
            ps.setBoolean(8, p.orderUpdates);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new Exception("Failed to save Telegram notification preferences", e);
        }
    }

    /**
     * UPSERTs the three in-app switches, the mirror of {@link #saveTelegramPreferences}. Creates
     * the preference row on first save, which is why it is an INSERT with ON CONFLICT rather than
     * a plain UPDATE.
     */
    public boolean saveUserPreferences(int userId, boolean out_bided, boolean ending_soon, boolean won_auction) throws Exception {
        String sql = "INSERT INTO notification_preference (user_id, out_bided, ending_soon, won_auction) " +
                "VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (user_id) DO UPDATE " +
                "SET out_bided = EXCLUDED.out_bided, " +
                "ending_soon = EXCLUDED.ending_soon, " +
                "won_auction = EXCLUDED.won_auction";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setBoolean(2, out_bided);
            ps.setBoolean(3, ending_soon);
            ps.setBoolean(4, won_auction);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new Exception("Failed to save user preferences", e);
        }
    }
}
