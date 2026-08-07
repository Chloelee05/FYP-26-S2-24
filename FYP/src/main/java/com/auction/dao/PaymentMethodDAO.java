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
 * ({@code card_number_enc}); only the brand and last 4 are kept in clear for display.
 * CVV is never stored. PayPal accounts store only the linked email in {@code account_ref}.</p>
 *
 * <p>Reads and writes {@code payment_methods} only. Called by the account settings API for the
 * saved payment methods screen, and by the checkout flow through {@link #belongsTo} and
 * {@link #labelFor}. Every query is scoped by {@code user_id} as well as row id, so one member
 * can never read or change another member's payment details.</p>
 */
public class PaymentMethodDAO {

    /**
     * The columns safe to read back. {@code card_number_enc} is deliberately absent: the encrypted
     * PAN is written once and never selected again, so no read path can decrypt it.
     */
    private static final String SELECT_COLS =
            "id, method_type, card_holder, card_brand, card_last4, exp_month, exp_year, "
          + "account_ref, is_default, created_at";

    /** The user's saved methods, default first, then newest. */
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
     * One method belonging to {@code userId}, or {@code null} when the id does not exist or
     * belongs to somebody else. Callers need the {@code method_type} before they can decide
     * which fields an update is even allowed to carry.
     */
    public PaymentMethod findForUser(int userId, long id) {
        String sql = "SELECT " + SELECT_COLS + " FROM payment_methods WHERE id = ? AND user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds a card for the user. Encrypts the PAN, derives brand + last4, and makes
     * the card default when it is the user's first one (or {@code makeDefault}).
     * Returns the new id, or -1 on failure.
     */
    public long add(int userId, String cardHolder, String pan, int expMonth, int expYear, boolean makeDefault) {
        // Strip spaces and dashes so what is encrypted and what is compared are both bare digits.
        String digits = pan.replaceAll("\\D", "");
        String last4 = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
        String brand = detectBrand(digits);
        // AES-GCM through SecurityUtil. The full PAN only exists in clear inside this method; what
        // reaches the table is ciphertext plus the brand and last4 the member identifies it by.
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
        // account_ref carries the bank name here, where a PayPal row carries the email. The column
        // is reused because each method type has exactly one free-text identifier.
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

            // The first method a user adds becomes the default automatically, so an account can
            // never end up with saved methods but nothing selected at checkout. Clearing the old
            // default and inserting the new one share a transaction, so there is no moment where
            // two rows or zero rows are flagged default.
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

    /**
     * Edits a stored card's cardholder name and expiry date, scoped to the owner and to
     * {@code method_type = 'CARD'}. Returns true if a row was changed.
     *
     * <p><b>The PAN is deliberately not updatable.</b> Only the ciphertext, the brand and the
     * last 4 digits of a card number are ever stored, so "change the number" cannot be an
     * edit of the row the user is looking at. The brand and last4 they identify it by both
     * change, and the ciphertext has to be produced afresh. That is an add followed by a
     * delete, and the API says so rather than pretending otherwise. What genuinely does
     * change on a card the member keeps is the name on it and the expiry printed on the
     * front, which is what a bank reissue produces and what this updates.</p>
     */
    public boolean updateCard(int userId, long id, String cardHolder, int expMonth, int expYear) {
        String sql = "UPDATE payment_methods SET card_holder = ?, exp_month = ?, exp_year = ? "
                + "WHERE id = ? AND user_id = ? AND method_type = 'CARD'";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cardHolder);
            ps.setInt(2, expMonth);
            ps.setInt(3, expYear);
            ps.setLong(4, id);
            ps.setInt(5, userId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Repoints a linked PayPal method at a different email, scoped to the owner.
     * The email is the whole identity of a PayPal method, so it is the only editable field.
     */
    public boolean updatePaypal(int userId, long id, String paypalEmail) {
        String sql = "UPDATE payment_methods SET account_ref = ? "
                + "WHERE id = ? AND user_id = ? AND method_type = 'PAYPAL'";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, paypalEmail);
            ps.setLong(2, id);
            ps.setInt(3, userId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Edits a bank method's account-holder name and bank name, scoped to the owner.
     * The account number itself is encrypted and its last 4 digits are what the member
     * recognises the row by, so changing it is an add rather than an edit, for the same reason
     * given on {@link #updateCard}.
     */
    public boolean updateBankTransfer(int userId, long id, String accountHolder, String bankName) {
        String sql = "UPDATE payment_methods SET card_holder = ?, account_ref = ? "
                + "WHERE id = ? AND user_id = ? AND method_type = 'BANK_TRANSFER'";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountHolder);
            ps.setString(2, bankName);
            ps.setLong(3, id);
            ps.setInt(4, userId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    /**
     * Marks a method default and clears the flag on the user's other methods.
     *
     * <p>The promotion runs first and the transaction is rolled back when it matches nothing.
     * Clearing first would mean an id that is not the caller's, from a stale tab or a guessed
     * number, wiped the default they actually had and left the account with none, while the
     * caller was told the change failed.</p>
     */
    public boolean setDefault(int userId, long id) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);
            String sql = "UPDATE payment_methods SET is_default = TRUE WHERE id = ? AND user_id = ?";
            boolean updated;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setInt(2, userId);
                updated = ps.executeUpdate() == 1;
            }
            if (!updated) {
                conn.rollback();
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE payment_methods SET is_default = FALSE WHERE user_id = ? AND id <> ?")) {
                ps.setInt(1, userId);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            conn.commit();
            return true;
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

    /**
     * AES-GCM encryption with a type-specific failure message. Throws rather than storing anything
     * in clear, so a broken key configuration cannot silently leave a plaintext PAN in the table.
     */
    private static String encrypt(String value, String errorMessage) {
        try {
            return SecurityUtil.encrypt(value);
        } catch (Exception e) {
            throw new RuntimeException(errorMessage, e);
        }
    }

    /** How many methods the user already has. Runs on the caller's transaction. */
    private int countForUser(Connection conn, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM payment_methods WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Unsets the default flag across all of the user's methods, ahead of setting a new one. */
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

    /**
     * Card brand from the leading digit, the standard issuer identifier: 4 is Visa, 5 Mastercard,
     * 3 Amex, 6 Discover. This is for display only and does not validate the number.
     */
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
