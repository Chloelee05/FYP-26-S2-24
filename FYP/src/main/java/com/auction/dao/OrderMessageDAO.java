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

/**
 * Direct buyer&lt;-&gt;seller conversations attached to an order.
 *
 * <p>Distinct from {@link SupportChatDAO} (user&lt;-&gt;admin support). Only the order's
 * buyer or seller may read/write a conversation; access is verified in {@link #isParticipant}.</p>
 *
 * <p>Writes {@code order_messages} and reads it joined against {@code orders},
 * {@code auction_details} and {@code users}. Called by the order messaging API servlet, which must
 * call {@link #isParticipant} before any read or write, since none of the list methods take a
 * viewer id except {@link #listConversations} and {@link #getConversationHeader}.</p>
 */
public class OrderMessageDAO {

    /** True if {@code userId} is the buyer or seller of {@code orderId}. */
    public boolean isParticipant(long orderId, int userId) {
        String sql = "SELECT 1 FROM orders WHERE id = ? AND (buyer_id = ? OR seller_id = ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Inserts a message and bumps nothing else. Returns the new id, or -1.
     *
     * <p>Rejects a whitespace-only body before touching the database, so an accidental empty send
     * does not create a row that would then surface as the conversation's last message.</p>
     */
    public long addMessage(long orderId, int senderId, String body) {
        String text = body != null ? body.trim() : "";
        if (text.isEmpty()) throw new IllegalArgumentException("Message body is required.");
        // Postgres RETURNING gives back the generated id in the same round trip as the insert.
        String sql = "INSERT INTO order_messages (order_id, sender_id, body) VALUES (?, ?, ?) RETURNING id";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setInt(2, senderId);
            ps.setString(3, text);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return -1;
    }

    /** Ordered (oldest first) messages for one order. */
    public List<Map<String, Object>> listMessages(long orderId) {
        String sql =
            "SELECT m.id, m.order_id, m.sender_id, m.body, m.created_at, u.username AS sender_name "
          + "FROM order_messages m JOIN users u ON u.id = m.sender_id "
          + "WHERE m.order_id = ? ORDER BY m.created_at ASC";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("orderId", rs.getLong("order_id"));
                    m.put("senderId", rs.getLong("sender_id"));
                    m.put("senderName", rs.getString("sender_name"));
                    m.put("body", rs.getString("body"));
                    m.put("createdAt", instant(rs.getTimestamp("created_at")));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /**
     * Conversations for a user: one row per order that has at least one message,
     * framed relative to the requesting user (role + counterparty).
     */
    public List<Map<String, Object>> listConversations(int userId) {
        // The result set is an inbox: order and item identity from orders and auction_details, both
        // party names from users, plus a preview of the newest message and a total count. The two
        // users joins are separate aliases (bu, su) because one order references the users table
        // twice, once as buyer and once as seller. Java then picks whichever name is the
        // counterparty for the viewer.
        String sql =
            "SELECT o.id AS order_id, o.auction_id, d.title, o.buyer_id, o.seller_id, o.status, "
          + "  bu.username AS buyer_name, su.username AS seller_name, "
          // Correlated subqueries pull the latest message body and time. Aggregating the join
          // instead would multiply the order row by its message count.
          + "  (SELECT body       FROM order_messages m WHERE m.order_id = o.id ORDER BY m.created_at DESC LIMIT 1) AS last_body, "
          + "  (SELECT created_at FROM order_messages m WHERE m.order_id = o.id ORDER BY m.created_at DESC LIMIT 1) AS last_at, "
          + "  (SELECT COUNT(*)   FROM order_messages m WHERE m.order_id = o.id) AS message_count "
          + "FROM orders o "
          + "JOIN auction_details d ON d.id = o.auction_id "
          + "JOIN users bu ON bu.id = o.buyer_id "
          + "JOIN users su ON su.id = o.seller_id "
          // Authorization lives in the WHERE clause: a user only ever sees orders they are a party
          // to. EXISTS then hides orders nobody has messaged yet, so the inbox is not padded with
          // every past purchase.
          + "WHERE (o.buyer_id = ? OR o.seller_id = ?) "
          + "  AND EXISTS (SELECT 1 FROM order_messages m WHERE m.order_id = o.id) "
          + "ORDER BY last_at DESC";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean isBuyer = rs.getLong("buyer_id") == userId;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderId", rs.getLong("order_id"));
                    m.put("auctionId", rs.getLong("auction_id"));
                    m.put("title", rs.getString("title"));
                    m.put("status", rs.getString("status"));
                    m.put("role", isBuyer ? "buyer" : "seller");
                    m.put("counterparty", isBuyer ? rs.getString("seller_name") : rs.getString("buyer_name"));
                    m.put("lastBody", rs.getString("last_body"));
                    m.put("lastAt", instant(rs.getTimestamp("last_at")));
                    m.put("messageCount", rs.getInt("message_count"));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /**
     * Lightweight header for a single order so the UI can show a thread before any messages exist.
     *
     * @return null when the order does not exist or the viewer is neither party, which the API maps
     *         to a 404 so a stranger cannot probe for valid order ids
     */
    public Map<String, Object> getConversationHeader(long orderId, int userId) {
        String sql =
            "SELECT o.id AS order_id, o.auction_id, d.title, o.buyer_id, o.seller_id, o.status, "
          + "  bu.username AS buyer_name, su.username AS seller_name "
          + "FROM orders o "
          + "JOIN auction_details d ON d.id = o.auction_id "
          + "JOIN users bu ON bu.id = o.buyer_id "
          + "JOIN users su ON su.id = o.seller_id "
          + "WHERE o.id = ? AND (o.buyer_id = ? OR o.seller_id = ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                boolean isBuyer = rs.getLong("buyer_id") == userId;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderId", rs.getLong("order_id"));
                m.put("auctionId", rs.getLong("auction_id"));
                m.put("title", rs.getString("title"));
                m.put("status", rs.getString("status"));
                m.put("role", isBuyer ? "buyer" : "seller");
                m.put("counterparty", isBuyer ? rs.getString("seller_name") : rs.getString("buyer_name"));
                return m;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Formats a nullable SQL timestamp as an ISO-8601 string for the JSON payload. */
    private static String instant(Timestamp ts) {
        Instant i = ts != null ? ts.toInstant() : null;
        return i != null ? i.toString() : null;
    }
}
