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

    public List<Notification> notificationHistory(int userId){
        String sql = "SELECT id, type, message, link, is_read, created_at "
                + "FROM notifications WHERE user_id = ? ORDER BY created_at DESC ?";
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
}
