package com.auction.dao;

import com.auction.model.PaymentMethod;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for buyer/seller payment methods. Three types are supported:
 * {@code CARD}, {@code PAYPAL} and {@code BANK_TRANSFER}.
 *
 * <p>For cards and bank accounts the full number is stored AES-GCM encrypted
 * ({@code card_number_enc}); only the brand + last 4 are kept in clear for display.
 * CVV is never stored. PayPal accounts store only the linked email in {@code account_ref}.</p>
 */
public class PaymentMethodDAO {

    private static final String SELECT_COLS =
            "id, method_type, card_holder, card_brand, card_last4, exp_month, exp_year, "
          + "account_ref, is_default, created_at";

    public List<PaymentMethod> listForUser(int userId) {
        String sql = "SELECT " + SELECT_COLS + " FROM payment_methods WHERE user_id = ? "
                + "ORDER BY is_default DESC, created_at DESC";
        List<PaymentMethod> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /**
     * Adds a card for the user. Encrypts the PAN, derives brand + last4, and makes
     * the card default when it is the user's first one (or {@code makeDefault}).
     * Returns the new id, or -1 on failure.
     */
    public long add(int userId, String cardHolder, String pan, int expMonth, int expYear, boolean makeDefault) {
        String digits = pan.replaceAll("\\D", "");
        String last4 = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
        String brand = detectBrand(digits);
        String enc = encrypt(digits, "Card encryption failed");
        return insert(userId, "CARD", cardHolder, brand, last4, expMonth, expYear, enc, null, makeDefault);
    }

    /** Links a PayPal account (identified by email). Returns the new id, or -1 on failure. */
    public long addPaypal(int userId, String paypalEmail, boolean makeDefault) {
        return insert(userId, "PAYPAL", null, "PayPal", null, null, null, null, paypalEmail, makeDefault);
    }

    /**
     * Adds a bank-transfer method. The full account number is encrypted; only the last 4
     * digits and the bank name are kept for display. Returns the new id, or -1 on failure.
     */
    public long addBankTransfer(int userId, String accountHolder, String accountNumber,
                                String bankName, boolean makeDefault) {
        String digits = accountNumber.replaceAll("\\D", "");
        String last4 = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
        String enc = encrypt(digits, "Bank account encryption failed");
        return insert(userId, "BANK_TRANSFER", accountHolder, "Bank", last4, null, null, enc, bankName, makeDefault);
    }

    /**
     * Generic insert shared by all method types. Card-only columns (holder, last4, expiry,
     * encrypted number) may be null for PayPal.
     */
    private long insert(int userId, String type, String holder, String brand, String last4,
                        Integer expMonth, Integer expYear, String numberEnc, String accountRef,
                        boolean makeDefault) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            boolean isDefault = makeDefault || countForUser(conn, userId) == 0;
            if (isDefault) clearDefault(conn, userId);

            String sql = "INSERT INTO payment_methods "
                    + "(user_id, method_type, card_holder, card_brand, card_last4, exp_month, exp_year, "
                    + " card_number_enc, account_ref, is_default) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            long id;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, userId);
                ps.setString(2, type);
                ps.setString(3, holder);
                ps.setString(4, brand);
                ps.setString(5, last4);
                if (expMonth != null) ps.setInt(6, expMonth); else ps.setNull(6, Types.SMALLINT);
                if (expYear != null)  ps.setInt(7, expYear);  else ps.setNull(7, Types.SMALLINT);
                ps.setString(8, numberEnc);
                ps.setString(9, accountRef);
                ps.setBoolean(10, isDefault);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    id = rs.next() ? rs.getLong(1) : -1;
                }
            }
            conn.commit();
            return id;
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
        }
    }

    /** Deletes a method (scoped to owner). Returns true if a row was removed. */
    public boolean delete(int userId, long id) {
        String sql = "DELETE FROM payment_methods WHERE id = ? AND user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setInt(2, userId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Marks a method default and clears the flag on the user's other methods. */
    public boolean setDefault(int userId, long id) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);
            clearDefault(conn, userId);
            String sql = "UPDATE payment_methods SET is_default = TRUE WHERE id = ? AND user_id = ?";
            boolean updated;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setInt(2, userId);
                updated = ps.executeUpdate() == 1;
            }
            conn.commit();
            return updated;
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
        }
    }

    /** Verifies a method belongs to the user (used before placing an order against it). */
    public boolean belongsTo(int userId, long id) {
        String sql = "SELECT 1 FROM payment_methods WHERE id = ? AND user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns a human-readable label for the given method (e.g. "Visa ****4242",
     * "PayPal (a@b.com)", "DBS account ****6789"), scoped to the owner, or null if not found.
     * Used to describe the payment on receipts.
     */
    public String labelFor(int userId, long id) {
        String sql = "SELECT method_type, card_brand, card_last4, account_ref "
                + "FROM payment_methods WHERE id = ? AND user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return PaymentMethod.label(rs.getString("method_type"), rs.getString("card_brand"),
                        rs.getString("card_last4"), rs.getString("account_ref"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String encrypt(String value, String errorMessage) {
        try {
            return SecurityUtil.encrypt(value);
        } catch (Exception e) {
            throw new RuntimeException(errorMessage, e);
        }
    }

    private int countForUser(Connection conn, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM payment_methods WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void clearDefault(Connection conn, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE payment_methods SET is_default = FALSE WHERE user_id = ?")) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    private PaymentMethod map(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        return new PaymentMethod(
                rs.getLong("id"),
                rs.getString("method_type"),
                rs.getString("card_holder"),
                rs.getString("card_brand"),
                rs.getString("card_last4"),
                rs.getInt("exp_month"),
                rs.getInt("exp_year"),
                rs.getString("account_ref"),
                rs.getBoolean("is_default"),
                ts != null ? ts.toInstant() : null);
    }

    private String detectBrand(String digits) {
        if (digits.isEmpty()) return "Card";
        char f = digits.charAt(0);
        if (f == '4') return "Visa";
        if (f == '5') return "Mastercard";
        if (f == '3') return "Amex";
        if (f == '6') return "Discover";
        return "Card";
    }
}
