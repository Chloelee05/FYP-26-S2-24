package com.auction.dao;

import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.admin.Announcement;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for system-wide announcements.
 *
 * <p>{@link #broadcast} is the only write path. It stores the master record and fans the
 * announcement out to one {@code notifications} row per targeted user inside a single
 * transaction, so an announcement can never be half-delivered: either every recipient gets it
 * and the audit row records the reach, or nothing is written at all.</p>
 *
 * <p>The fan-out is one set-based {@code INSERT ... SELECT} rather than a row-per-user loop —
 * a platform-wide broadcast is one statement regardless of how many users exist.</p>
 */
public class AnnouncementDAO {

    private static final String INSERT_SQL =
            "INSERT INTO announcements (title, message, audience, severity, link, created_by) "
            + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id, created_at";

    private static final String SELECT_SQL =
            "SELECT a.id, a.title, a.message, a.audience, a.severity, a.link, "
            + "       a.created_by, a.created_at, a.recipient_count, u.username AS created_by_name "
            + "FROM announcements a "
            + "LEFT JOIN users u ON u.id = a.created_by "
            + "ORDER BY a.created_at DESC, a.id DESC LIMIT ?";

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Stores {@code draft} and delivers it to every active user in its audience.
     *
     * @param draft a composed, already-validated announcement (see {@link Announcement#compose})
     * @return the persisted announcement, carrying its generated id, timestamp and reach
     * @throws RuntimeException if the announcement could not be stored or delivered; nothing
     *                          is written in that case
     */
    public Announcement broadcast(Announcement draft) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            long id;
            Timestamp createdAt;
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                ps.setString(1, draft.getTitle());
                ps.setString(2, draft.getMessage());
                ps.setString(3, draft.getAudience().name());
                ps.setString(4, draft.getSeverity().name());
                setNullable(ps, 5, draft.getLink());
                if (draft.getCreatedBy() != null) ps.setInt(6, draft.getCreatedBy());
                else ps.setNull(6, Types.BIGINT);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Announcement insert returned no id.");
                    id = rs.getLong("id");
                    createdAt = rs.getTimestamp("created_at");
                }
            }

            int recipients = fanOut(conn, draft);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE announcements SET recipient_count = ? WHERE id = ?")) {
                ps.setInt(1, recipients);
                ps.setLong(2, id);
                ps.executeUpdate();
            }

            conn.commit();
            return draft.stored(id, createdAt != null ? createdAt.toInstant() : null, recipients);
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
        }
    }

    /**
     * Writes one notification row per targeted user.
     *
     * @return the number of users the announcement reached
     */
    private int fanOut(Connection conn, Announcement announcement) throws SQLException {
        Role role = announcement.getAudience().role();
        String sql = "INSERT INTO notifications (user_id, type, message, link) "
                + "SELECT u.id, ?, ?, ? FROM users u WHERE u.status_id = ?"
                + (role != null ? " AND u.role_id = ?" : "");

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Announcement.NOTIFICATION_TYPE);
            ps.setString(2, announcement.toNotificationMessage());
            setNullable(ps, 3, announcement.getLink());
            ps.setInt(4, Status.ACTIVE.getId());
            if (role != null) ps.setInt(5, role.getId());
            return ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /** The most recent announcements, newest first, for the admin history view. */
    public List<Announcement> listRecent(int limit) {
        List<Announcement> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /**
     * Email addresses of every active user in {@code audience} — the recipient list for the
     * optional email copy of a broadcast.
     */
    public List<String> recipientEmails(Announcement.Audience audience) {
        Announcement.Audience target = audience != null ? audience : Announcement.Audience.ALL;
        Role role = target.role();
        String sql = "SELECT email FROM users WHERE status_id = ?"
                + (role != null ? " AND role_id = ?" : "");

        List<String> emails = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Status.ACTIVE.getId());
            if (role != null) ps.setInt(2, role.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String email = rs.getString("email");
                    if (email != null && !email.isBlank()) emails.add(email);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return emails;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) ps.setString(index, value);
        else ps.setNull(index, Types.VARCHAR);
    }

    private static Announcement mapRow(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        int createdByValue = rs.getInt("created_by");
        Integer createdBy = rs.wasNull() ? null : createdByValue;

        return new Announcement(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("message"),
                Announcement.Audience.parse(rs.getString("audience")),
                Announcement.Severity.parse(rs.getString("severity")),
                rs.getString("link"),
                createdBy,
                rs.getString("created_by_name"),
                createdAt != null ? createdAt.toInstant() : null,
                rs.getInt("recipient_count"));
    }
}
