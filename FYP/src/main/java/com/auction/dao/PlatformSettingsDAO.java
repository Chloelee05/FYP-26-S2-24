package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic admin-tunable key/value settings for small platform-wide knobs that do not
 * warrant a dedicated table of their own — the bid rate-limit window, the order payment
 * deadline, the login lockout threshold and cooldown.
 *
 * <p>Deliberately not {@code recommendation_settings}: that table is scoped to the
 * recommendation pipeline (see {@link RecommendationDAO}), and these three anti-abuse /
 * lifecycle knobs are unrelated to ranking. Rather than overload a table that already has
 * its own meaning, this mirrors the same proven shape (a {@code key}/{@code value} row store
 * with a TTL-cached snapshot and a hardcoded fallback per key), which is the established
 * convention in this codebase for admin-tunable values.</p>
 */
public class PlatformSettingsDAO {

    private static final long CACHE_TTL_MILLIS = 30_000;
    private static volatile Map<String, String> cached;
    private static volatile long cachedAt;

    /** Drops the cached snapshot so the next read goes back to the database. */
    public static void invalidateCache() {
        cached = null;
        cachedAt = 0L;
    }

    private static Map<String, String> settings() {
        Map<String, String> snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MILLIS) {
            return snapshot;
        }
        Map<String, String> loaded = load();
        cached = loaded;
        cachedAt = System.currentTimeMillis();
        return loaded;
    }

    private static Map<String, String> load() {
        Map<String, String> out = new HashMap<>();
        String sql = "SELECT key, value FROM platform_settings";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (Exception ignored) {
            // Table not migrated yet, or DB unavailable — callers fall back to their default.
        }
        return out;
    }

    /** The int value for {@code key}, or {@code defaultValue} when missing/unparseable. */
    public int getInt(String key, int defaultValue) {
        String v = settings().get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** The long value for {@code key}, or {@code defaultValue} when missing/unparseable. */
    public long getLong(String key, long defaultValue) {
        String v = settings().get(key);
        if (v == null) return defaultValue;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Upserts a setting and invalidates the cache so the next read sees it. */
    public void setValue(String key, String value) {
        String sql = "INSERT INTO platform_settings (key, value, updated_at) VALUES (?, ?, NOW()) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            invalidateCache();
        }
    }
}
