package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin–user support chat threads and messages. */
public class SupportChatDAO {

    public long createThread(int userId, String subject) throws Exception {
        String sql = "INSERT INTO support_threads (user_id, subject) VALUES (?, ?) RETURNING id";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setString(2, subject == null || subject.isBlank() ? "Support request" : subject.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1;
    }

    public List<Map<String, Object>> listThreadsForUser(int userId) throws Exception {
        String sql =
            "SELECT t.id, t.user_id, t.subject, t.status, t.created_at, t.updated_at, "
          + "  (SELECT COUNT(*) FROM support_messages m WHERE m.thread_id = t.id) AS message_count "
          + "FROM support_threads t WHERE t.user_id = ? ORDER BY t.updated_at DESC";
        return queryThreads(sql, userId);
    }

    public List<Map<String, Object>> listThreadsForAdmin() throws Exception {
        String sql =
            "SELECT t.id, t.user_id, t.subject, t.status, t.created_at, t.updated_at, "
          + "  u.username, "
          + "  (SELECT COUNT(*) FROM support_messages m WHERE m.thread_id = t.id) AS message_count "
          + "FROM support_threads t JOIN users u ON u.id = t.user_id "
          + "ORDER BY t.updated_at DESC";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapThread(rs, true));
        }
        return out;
    }

    private List<Map<String, Object>> queryThreads(String sql, int userId) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapThread(rs, false));
            }
        }
        return out;
    }

    private static Map<String, Object> mapThread(ResultSet rs, boolean withUsername) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("userId", rs.getLong("user_id"));
        m.put("subject", rs.getString("subject"));
        m.put("status", rs.getString("status"));
        m.put("createdAt", instant(rs.getTimestamp("created_at")));
        m.put("updatedAt", instant(rs.getTimestamp("updated_at")));
        m.put("messageCount", rs.getInt("message_count"));
        if (withUsername) m.put("username", rs.getString("username"));
        return m;
    }

    public Map<String, Object> getThread(long threadId) throws Exception {
        String sql =
            "SELECT t.id, t.user_id, t.subject, t.status, t.created_at, t.updated_at, u.username "
          + "FROM support_threads t JOIN users u ON u.id = t.user_id WHERE t.id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("userId", rs.getLong("user_id"));
                m.put("username", rs.getString("username"));
                m.put("subject", rs.getString("subject"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", instant(rs.getTimestamp("created_at")));
                m.put("updatedAt", instant(rs.getTimestamp("updated_at")));
                return m;
            }
        }
    }

    public boolean threadBelongsToUser(long threadId, int userId) throws Exception {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM support_threads WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, threadId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public long addMessage(long threadId, int senderId, String body) throws Exception {
        return addMessage(threadId, senderId, body, null);
    }

    public long addMessage(long threadId, int senderId, String body, String attachmentUrl) throws Exception {
        String text = body != null ? body.trim() : "";
        String attach = attachmentUrl != null ? attachmentUrl.trim() : "";
        if (text.isEmpty() && attach.isEmpty()) {
            throw new IllegalArgumentException("Message body or attachment is required.");
        }
        boolean withAttachment = !attach.isEmpty();
        String insert = withAttachment
                ? "INSERT INTO support_messages (thread_id, sender_id, body, attachment_url) VALUES (?, ?, ?, ?) RETURNING id"
                : "INSERT INTO support_messages (thread_id, sender_id, body) VALUES (?, ?, ?) RETURNING id";
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setLong(1, threadId);
                ps.setInt(2, senderId);
                ps.setString(3, text.isEmpty() ? " " : text);
                if (withAttachment) ps.setString(4, attach);
                long msgId;
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return -1; }
                    msgId = rs.getLong(1);
                }
                try (PreparedStatement up = conn.prepareStatement(
                        "UPDATE support_threads SET updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    up.setLong(1, threadId);
                    up.executeUpdate();
                }
                conn.commit();
                return msgId;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public List<Map<String, Object>> listMessages(long threadId) throws Exception {
        String sql =
            "SELECT m.id, m.thread_id, m.sender_id, m.body, m.created_at, u.username, r.role AS sender_role "
          + "FROM support_messages m "
          + "JOIN users u ON u.id = m.sender_id "
          + "JOIN roles r ON r.id = u.role_id "
          + "WHERE m.thread_id = ? ORDER BY m.created_at ASC";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("threadId", rs.getLong("thread_id"));
                    m.put("senderId", rs.getLong("sender_id"));
                    m.put("senderName", rs.getString("username"));
                    String role = rs.getString("sender_role");
                    m.put("senderRole", role != null ? role.toUpperCase() : "");
                    m.put("body", rs.getString("body"));
                    m.put("attachmentUrl", rs.getString("attachment_url"));
                    m.put("createdAt", instant(rs.getTimestamp("created_at")));
                    out.add(m);
                }
            }
        }
        return out;
    }

    public boolean closeThread(long threadId) throws Exception {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE support_threads SET status = 'CLOSED', updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            ps.setLong(1, threadId);
            return ps.executeUpdate() > 0;
        }
    }

    private static String instant(Timestamp ts) {
        Instant i = ts != null ? ts.toInstant() : null;
        return i != null ? i.toString() : null;
    }
}
