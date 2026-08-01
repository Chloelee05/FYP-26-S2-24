package com.auction.dao;

import com.auction.model.Notification;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** Persistence for in-app notifications. */
public class NotificationDAO {

    /** Inserts a notification and returns its generated id (or -1 on failure). */
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

    public List<Notification> listForUser(int userId, int limit) {
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

    /** Marks all of a user's notifications read. */
    public int markAllRead(int userId) {
        String sql = "UPDATE notifications SET is_read = TRUE WHERE user_id = ? AND is_read = FALSE";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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

    /** Returns the user's notification preferences, defaulting to all-enabled. */
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
     * <p>Kept apart from {@link Preferences} because the two answer different questions —
     * "should this event produce a notification at all" versus "should it also go to
     * Telegram" — and because reading them separately means an un-migrated database only
     * fails the Telegram query rather than the in-app one.</p>
     */
    public static final class TelegramPreferences {
        /** Master switch: false silences every Telegram message without unlinking. */
        public final boolean enabled;
        public final boolean outbid;
        public final boolean won;
        public final boolean lost;
        public final boolean sellerResult;
        public final boolean sellerPrice;

        public TelegramPreferences(boolean enabled, boolean outbid, boolean won,
                                   boolean lost, boolean sellerResult, boolean sellerPrice) {
            this.enabled = enabled;
            this.outbid = outbid;
            this.won = won;
            this.lost = lost;
            this.sellerResult = sellerResult;
            this.sellerPrice = sellerPrice;
        }

        /** Matches the column defaults: everything on except the noisy seller price feed. */
        public static TelegramPreferences defaults() {
            return new TelegramPreferences(true, true, true, true, true, false);
        }
    }

    /** The user's Telegram preferences, defaulting to {@link TelegramPreferences#defaults()}. */
    public TelegramPreferences getTelegramPreferences(int userId) {
        String sql = "SELECT telegram_enabled, telegram_outbid, telegram_won, telegram_lost, "
                + "telegram_seller_result, telegram_seller_price "
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
                            rs.getBoolean("telegram_seller_price"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return TelegramPreferences.defaults();
    }

    /** UPSERTs only the Telegram columns, leaving the in-app preferences untouched. */
    public boolean saveTelegramPreferences(int userId, TelegramPreferences p) throws Exception {
        String sql = "INSERT INTO notification_preference "
                + "(user_id, telegram_enabled, telegram_outbid, telegram_won, telegram_lost, "
                + " telegram_seller_result, telegram_seller_price) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET "
                + "telegram_enabled = EXCLUDED.telegram_enabled, "
                + "telegram_outbid = EXCLUDED.telegram_outbid, "
                + "telegram_won = EXCLUDED.telegram_won, "
                + "telegram_lost = EXCLUDED.telegram_lost, "
                + "telegram_seller_result = EXCLUDED.telegram_seller_result, "
                + "telegram_seller_price = EXCLUDED.telegram_seller_price";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setBoolean(2, p.enabled);
            ps.setBoolean(3, p.outbid);
            ps.setBoolean(4, p.won);
            ps.setBoolean(5, p.lost);
            ps.setBoolean(6, p.sellerResult);
            ps.setBoolean(7, p.sellerPrice);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new Exception("Failed to save Telegram notification preferences", e);
        }
    }

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
