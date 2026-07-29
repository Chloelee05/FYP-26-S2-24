package com.auction.dao;

import com.auction.model.admin.LandingContentItem;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data-access layer for {@code landing_content} — the admin-editable copy on the
 * landing page.
 *
 * <p>The set of editable fields is defined by the rows seeded in
 * {@code migration_landing_content.sql}, not by a list in code: {@link #allKeys()} is
 * the authorization allowlist for writes, and {@link #resetToDefault(String, Integer)}
 * restores the seeded text. New fields are therefore added by migration alone.</p>
 */
public class LandingContentDAO {

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Every field as {@code content_key -> content_value}, in display order.
     * This is the payload the public landing endpoint serves.
     */
    public Map<String, String> findAllValues() {
        Map<String, String> out = new LinkedHashMap<>();
        String sql = "SELECT content_key, content_value FROM landing_content "
                + "ORDER BY display_order ASC, content_key ASC";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString("content_key"), rs.getString("content_value"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Every field with its admin-form metadata, in display order. Used by the admin UI. */
    public List<LandingContentItem> listAll() {
        List<LandingContentItem> out = new ArrayList<>();
        String sql = "SELECT content_key, content_group, label, content_value, default_value, "
                + "multiline, display_order, updated_at, updated_by FROM landing_content "
                + "ORDER BY display_order ASC, content_key ASC";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapRow(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** The keys that may be written, i.e. the rows the migration created. */
    public Set<String> allKeys() {
        Set<String> out = new LinkedHashSet<>();
        String sql = "SELECT content_key FROM landing_content";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString("content_key"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Updates the given fields in one transaction, stamping {@code adminId} as the editor.
     * Keys with no matching row are ignored; callers validate against {@link #allKeys()}
     * first so an unknown key is reported rather than silently dropped.
     *
     * @return the number of rows actually updated
     */
    public int updateAll(Map<String, String> values, Integer adminId) {
        if (values == null || values.isEmpty()) return 0;
        String sql = "UPDATE landing_content SET content_value = ?, updated_at = NOW(), updated_by = ? "
                + "WHERE content_key = ?";
        try {
            return DBUtil.runInTransaction(conn -> {
                int updated = 0;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (Map.Entry<String, String> e : values.entrySet()) {
                        ps.setString(1, e.getValue());
                        setAdmin(ps, 2, adminId);
                        ps.setString(3, e.getKey());
                        ps.addBatch();
                    }
                    for (int rows : ps.executeBatch()) {
                        if (rows > 0) updated += rows;
                    }
                }
                return updated;
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Restores one field to the default seeded by the migration.
     *
     * @return {@code true} if the row existed and was updated
     */
    public boolean resetToDefault(String key, Integer adminId) {
        String sql = "UPDATE landing_content SET content_value = default_value, updated_at = NOW(), "
                + "updated_by = ? WHERE content_key = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setAdmin(ps, 1, adminId);
            ps.setString(2, key);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Restores every field in one group to its seeded default.
     *
     * @return the number of fields reset
     */
    public int resetGroup(String group, Integer adminId) {
        String sql = "UPDATE landing_content SET content_value = default_value, updated_at = NOW(), "
                + "updated_by = ? WHERE content_group = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setAdmin(ps, 1, adminId);
            ps.setString(2, group);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void setAdmin(PreparedStatement ps, int index, Integer adminId) throws SQLException {
        if (adminId == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, adminId);
    }

    private static LandingContentItem mapRow(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("updated_at");
        int editorId = rs.getInt("updated_by");
        Integer editor = rs.wasNull() ? null : editorId;
        return new LandingContentItem(
                rs.getString("content_key"),
                rs.getString("content_group"),
                rs.getString("label"),
                rs.getString("content_value"),
                rs.getString("default_value"),
                rs.getBoolean("multiline"),
                rs.getInt("display_order"),
                ts != null ? ts.toLocalDateTime() : null,
                editor);
    }
}
