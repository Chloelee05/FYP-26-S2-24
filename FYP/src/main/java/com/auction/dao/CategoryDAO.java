package com.auction.dao;

import com.auction.model.admin.Category;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for the {@code categories} table (SCRUM-23).
 *
 * <p><b>Delete policy (SCRUM-217):</b> Categories that have auction listings referencing them
 * (matched by name in {@code auction_details.category}) are <em>restricted</em> from deletion.
 * The caller must invoke {@link #countAuctions(int)} first; only if the count is zero may
 * {@link #softDelete(int)} be called, which sets {@code is_deleted = TRUE} and keeps the row
 * for referential auditing.</p>
 *
 * <p><b>SCRUM-218:</b> Duplicate-name detection ({@link #nameExists}/{@link #nameExistsExcluding}),
 * {@code display_order}, and {@code slug} are all managed here.</p>
 */
public class CategoryDAO {

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * All categories including soft-deleted ones, with auction counts.
     * Ordered by {@code display_order ASC, id ASC}. Used by the admin UI.
     */
    public List<Category> listAll() {
        String sql = "SELECT c.id, c.name, c.description, c.display_order, c.slug, "
                + "c.is_deleted, c.created_at, "
                + "COUNT(ad.id)::int AS auction_count "
                + "FROM categories c "
                + "LEFT JOIN auction_details ad ON LOWER(ad.category) = LOWER(c.name) "
                + "GROUP BY c.id "
                + "ORDER BY c.display_order ASC, c.id ASC";
        return loadList(sql);
    }

    /**
     * Active (non-deleted) categories only, ordered by {@code display_order}.
     * Used by seller/buyer-facing dropdowns.
     */
    public List<Category> listActive() {
        String sql = "SELECT c.id, c.name, c.description, c.display_order, c.slug, "
                + "c.is_deleted, c.created_at, "
                + "COUNT(ad.id)::int AS auction_count "
                + "FROM categories c "
                + "LEFT JOIN auction_details ad ON LOWER(ad.category) = LOWER(c.name) "
                + "WHERE c.is_deleted = FALSE "
                + "GROUP BY c.id "
                + "ORDER BY c.display_order ASC, c.id ASC";
        return loadList(sql);
    }

    /** Returns the active {@link Category} with the given {@code slug}, or {@code null} if not found or soft-deleted. */
    public Category findBySlug(String slug) {
        String sql = "SELECT c.id, c.name, c.description, c.display_order, c.slug, "
                + "c.is_deleted, c.created_at, "
                + "COUNT(ad.id)::int AS auction_count "
                + "FROM categories c "
                + "LEFT JOIN auction_details ad ON LOWER(ad.category) = LOWER(c.name) "
                + "WHERE c.slug = ? AND c.is_deleted = FALSE "
                + "GROUP BY c.id";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /** Returns the {@link Category} with the given {@code id}, or {@code null} if not found. */
    public Category findById(int id) {
        String sql = "SELECT c.id, c.name, c.description, c.display_order, c.slug, "
                + "c.is_deleted, c.created_at, "
                + "COUNT(ad.id)::int AS auction_count "
                + "FROM categories c "
                + "LEFT JOIN auction_details ad ON LOWER(ad.category) = LOWER(c.name) "
                + "WHERE c.id = ? "
                + "GROUP BY c.id";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Duplicate-name checks (SCRUM-218)
    // -------------------------------------------------------------------------

    /** {@code true} if any category (including deleted) already uses this name (case-insensitive). */
    public boolean nameExists(String name) {
        return checkExists("SELECT 1 FROM categories WHERE LOWER(name) = LOWER(?) LIMIT 1", name);
    }

    /**
     * {@code true} if any <em>other</em> category already uses this name (case-insensitive).
     * Used for edit validation to allow keeping the same name unchanged.
     */
    public boolean nameExistsExcluding(String name, int excludeId) {
        String sql = "SELECT 1 FROM categories WHERE LOWER(name) = LOWER(?) AND id <> ? LIMIT 1";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** {@code true} if any category already uses this slug (case-sensitive). */
    public boolean slugExists(String slug) {
        return checkExists("SELECT 1 FROM categories WHERE slug = ? LIMIT 1", slug);
    }

    /**
     * {@code true} if any <em>other</em> category already uses this slug.
     * Used for edit validation.
     */
    public boolean slugExistsExcluding(String slug, int excludeId) {
        String sql = "SELECT 1 FROM categories WHERE slug = ? AND id <> ? LIMIT 1";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            ps.setInt(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Inserts a new category and returns the generated {@code id}, or {@code -1} on failure.
     * The caller is responsible for sanitizing {@code name}/{@code description} and generating
     * a unique {@code slug} before calling this method.
     */
    public int insert(String name, String description, int displayOrder, String slug) {
        String sql = "INSERT INTO categories (name, description, display_order, slug) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, description != null && !description.isBlank() ? description : null);
            ps.setInt(3, displayOrder);
            ps.setString(4, slug);
            int rows = ps.executeUpdate();
            if (rows == 1) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return -1;
    }

    /**
     * Updates an existing, non-deleted category.
     *
     * @return {@code true} if exactly one row was updated
     */
    public boolean update(int id, String name, String description, int displayOrder, String slug) {
        String sql = "UPDATE categories SET name = ?, description = ?, display_order = ?, slug = ? "
                + "WHERE id = ? AND is_deleted = FALSE";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description != null && !description.isBlank() ? description : null);
            ps.setInt(3, displayOrder);
            ps.setString(4, slug);
            ps.setInt(5, id);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Soft-deletes the category by setting {@code is_deleted = TRUE}.
     * Callers <strong>must</strong> verify {@link #countAuctions(int)} == 0 first
     * (restrict policy, SCRUM-217).
     *
     * @return {@code true} if the row was updated
     */
    public boolean softDelete(int id) {
        return updateDeletedFlag(id, true);
    }

    /**
     * Reverses a soft-delete by setting {@code is_deleted = FALSE}.
     *
     * @return {@code true} if the row was updated
     */
    public boolean restore(int id) {
        return updateDeletedFlag(id, false);
    }

    // -------------------------------------------------------------------------
    // Auction linkage count (SCRUM-217 delete policy)
    // -------------------------------------------------------------------------

    /**
     * Returns the number of auction listings in {@code auction_details} whose {@code category}
     * string matches the name of the category with the given {@code id} (case-insensitive).
     * Used to enforce the restrict-delete policy: if {@code count > 0}, deletion is blocked.
     */
    public int countAuctions(int id) {
        String sql = "SELECT COUNT(*)::int FROM auction_details ad "
                + "JOIN categories c ON LOWER(ad.category) = LOWER(c.name) "
                + "WHERE c.id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean updateDeletedFlag(int id, boolean deleted) {
        String sql = "UPDATE categories SET is_deleted = ? WHERE id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, deleted);
            ps.setInt(2, id);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean checkExists(String sql, String param) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Category> loadList(String sql) {
        List<Category> out = new ArrayList<>();
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

    private static Category mapRow(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        return new Category(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("display_order"),
                rs.getString("slug"),
                rs.getBoolean("is_deleted"),
                ts != null ? ts.toLocalDateTime() : null,
                rs.getInt("auction_count"));
    }
}
