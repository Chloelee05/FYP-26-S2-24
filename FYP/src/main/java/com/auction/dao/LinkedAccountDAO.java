package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Third-party sign-in links (SCRUM-17). A user can link one account per
 * provider; a provider account can belong to only one user.
 *
 * <p>Reads and writes {@code linked_accounts}. Called by the account settings API for the
 * link/unlink screen, and by the auth servlet during OAuth sign-in to resolve a provider identity
 * back to a local user id.</p>
 */
public class LinkedAccountDAO {

    /** Providers currently accepted by the API. */
    public static final List<String> SUPPORTED_PROVIDERS = List.of("google");

    /** Rows shown in Account Settings → Linked Accounts. */
    public List<Map<String, Object>> listForUser(int userId) {
        String sql = "SELECT provider, provider_email, linked_at FROM linked_accounts "
                   + "WHERE user_id = ? ORDER BY provider";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("provider", rs.getString("provider"));
                    row.put("email", rs.getString("provider_email"));
                    java.sql.Timestamp ts = rs.getTimestamp("linked_at");
                    row.put("linkedAt", ts != null ? ts.toInstant() : (Instant) null);
                    out.add(row);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public enum LinkResult { SUCCESS, ALREADY_LINKED_TO_OTHER_USER }

    /**
     * Links (or re-links) a provider account to a user.
     *
     * @param providerUid the provider's own stable id for the account, which is what sign-in
     *                    matches on; the email is stored for display only and can change
     */
    public LinkResult link(int userId, String provider, String providerUid, String email) {
        // Refuse if this provider account already belongs to a different user. Without this check
        // two local accounts could both sign in through the same Google identity.
        Integer owner = findUserIdByProvider(provider, providerUid);
        if (owner != null && owner != userId) return LinkResult.ALREADY_LINKED_TO_OTHER_USER;

        // ON CONFLICT on (user_id, provider) turns a repeat link into a re-link, refreshing the
        // provider uid and email if the user changed them on the provider side.
        String sql = "INSERT INTO linked_accounts (user_id, provider, provider_uid, provider_email) "
                   + "VALUES (?, ?, ?, ?) "
                   + "ON CONFLICT (user_id, provider) DO UPDATE SET "
                   + "  provider_uid = EXCLUDED.provider_uid, "
                   + "  provider_email = EXCLUDED.provider_email, "
                   + "  linked_at = CURRENT_TIMESTAMP";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setString(2, provider);
            ps.setString(3, providerUid);
            ps.setString(4, email);
            ps.executeUpdate();
            return LinkResult.SUCCESS;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Removes the link; returns false if there was nothing to unlink. */
    public boolean unlink(int userId, String provider) {
        String sql = "DELETE FROM linked_accounts WHERE user_id = ? AND provider = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setString(2, provider);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Resolves the local user for a provider account, or null. Used for OAuth sign-in. */
    public Integer findUserIdByProvider(String provider, String providerUid) {
        String sql = "SELECT user_id FROM linked_accounts WHERE provider = ? AND provider_uid = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, provider);
            ps.setString(2, providerUid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("user_id") : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
