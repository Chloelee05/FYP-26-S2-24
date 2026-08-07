package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.model.Status;
import com.auction.model.admin.AdminUserSummary;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Account persistence: registration, login lookups, profile edits, admin listings and PDPA
 * account closure. Owns the {@code users} table and joins {@code roles} and {@code user_status} for
 * display. Closure additionally rewrites {@code auction}, {@code orders}, {@code telegram_links}
 * and {@code telegram_outbox} in one transaction. Called by the auth, account and admin servlets.
 *
 * <p>Two security points worth knowing. {@link #getUserByEmail} selects the password hash because
 * login has to compare it; {@link #getUserById} deliberately does not, so a session-driven profile
 * read can never leak it. Phone and address are stored as AES-GCM ciphertext and this class passes
 * them through untouched, leaving encryption and decryption to {@link SecurityUtil}.</p>
 */
public class UserDAO {
    /** Whether the username is already registered. Used by the sign-up form's availability check. */
    public boolean checkUser(String username){
        try(Connection conn = DBUtil.connectDB()) {

            String sqlString = "SELECT 1 FROM users WHERE username = ? LIMIT 1";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setString(1, username);

            try(ResultSet resultSet = pStatement.executeQuery()) {
                return resultSet.next();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    } //for username validation

    /** Whether the email is already registered. Note this comparison is case-sensitive. */
    public boolean checkEmail(String email){
        try(Connection conn = DBUtil.connectDB()) {

            String sqlString = "SELECT 1 FROM users WHERE email = ? LIMIT 1";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setString(1, email);

            try(ResultSet resultSet = pStatement.executeQuery()) {
                return resultSet.next();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    } // for email validation

    /**
     * Registers an account. The password on the model must already be the salted SHA-256 hash from
     * {@link SecurityUtil}; this method never sees a plaintext password.
     */
    public boolean insertUser(User user)
    {
        try(Connection conn = DBUtil.connectDB()) {

            String sqlString = "INSERT INTO users (username, email, password, role_id, status_id) " +
                    "VALUES(?, ?, ?, ?, ?) ";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setString(1, user.getUsername());
            pStatement.setString(2, user.getEmail());
            pStatement.setString(3, user.getPassword());
            pStatement.setInt(4, user.getRole().getId());
            // New accounts require admin approval before they can sign in.
            pStatement.setInt(5, Status.PENDING.getId());

            int rowsAffected = pStatement.executeUpdate();
            return rowsAffected > 0;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Moves an account between statuses (approve, suspend, reactivate) and stamps when it changed.
     * That timestamp is what the admin suspension list orders on.
     */
    public boolean updateStatus(int id, int status)
    {
        try(Connection conn = DBUtil.connectDB()) {
            String sqlString = "UPDATE users SET status_id = ?, last_status_changed_at = CURRENT_TIMESTAMP WHERE id = ?";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            pStatement.setInt(1, status);
            pStatement.setInt(2, id);
            int rowsAffected = pStatement.executeUpdate();
            return rowsAffected == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Cached: whether {@code users.can_sell} exists (null = not probed yet). */
    private static volatile Boolean canSellColumnPresent;

    /**
     * Loads a user row for login. This is the one read path that selects the password hash, because
     * authentication has to compare against it. Email is matched case-insensitively so a member who
     * types a capital letter can still sign in.
     */
    public User getUserByEmail(String email) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT id, username, email, password, role_id, status_id, two_factor_enabled, two_factor_secret, "
                    + "phone_encrypted, address_encrypted, profile_image_url"
                    + (hasCanSellColumn(conn) ? ", can_sell" : "")
                    + " FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapUserFromResultSet(rs, true);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads profile fields for the signed-in user. The password column is deliberately left out of
     * the projection: nothing on a profile screen needs the hash, so it never enters the model.
     */
    public User getUserById(int id) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT id, username, email, role_id, status_id, date_created, two_factor_enabled, two_factor_secret, "
                    + "phone_encrypted, address_encrypted, profile_image_url"
                    + (hasCanSellColumn(conn) ? ", can_sell" : "")
                    + " FROM users WHERE id = ? LIMIT 1";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                User user = mapUserFromResultSet(rs, false);
                java.sql.Timestamp dc = rs.getTimestamp("date_created");
                if (dc != null) {
                    user.setMemberSince(dc.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate());
                }
                return user;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * True when the production/local schema has {@code users.can_sell}.
     * Deployed DBs that missed {@code migration_seller_capability.sql} still allow login;
     * {@link User#canSell()} falls back to the legacy SELLER role.
     *
     * <p>Probed once against {@code information_schema.columns} and cached in a volatile field
     * under double-checked locking, since the schema cannot change while the app is running.
     * Migrations are additive, so the column either exists from the start or never appears.</p>
     */
    private static boolean hasCanSellColumn(Connection conn) throws SQLException {
        Boolean cached = canSellColumnPresent;
        if (cached != null) {
            return cached;
        }
        synchronized (UserDAO.class) {
            if (canSellColumnPresent != null) {
                return canSellColumnPresent;
            }
            String sql = "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = 'users' "
                    + "AND column_name = 'can_sell' LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                canSellColumnPresent = rs.next();
            }
            return canSellColumnPresent;
        }
    }

    /**
     * Builds a {@link User} from a row. {@code includePassword} is what keeps the hash out of
     * profile reads; only the login path passes true. Phone and address stay as ciphertext here.
     * Package-private for row-mapping unit tests without touching {@link DBUtil}.
     */
    static User mapUserFromResultSet(ResultSet rs, boolean includePassword) throws SQLException {
        User user = new User();
        user.setId(rs.getInt("id"));
        user.setUsername(rs.getString("username"));
        user.setEmail(rs.getString("email"));
        if (includePassword) {
            user.setPassword(rs.getString("password"));
        }
        user.setRole(Role.getRole(rs.getInt("role_id")));
        user.setStatusId(rs.getInt("status_id"));
        user.setTwoFactorEnabled(rs.getBoolean("two_factor_enabled"));
        user.setTwoFactorSecret(rs.getString("two_factor_secret"));
        user.setPhoneEncrypted(rs.getString("phone_encrypted"));
        user.setAddressEncrypted(rs.getString("address_encrypted"));
        user.setProfileImageUrl(rs.getString("profile_image_url"));
        user.setCanSell(readCanSell(rs));
        return user;
    }

    /**
     * Reads the {@code can_sell} capability flag, tolerating result sets that do not
     * carry the column (older queries, or a database where
     * {@code migration_seller_capability.sql} has not been applied yet). In that case
     * {@link User#canSell()} still falls back to the legacy SELLER role.
     */
    private static boolean readCanSell(ResultSet rs) {
        try {
            return rs.getBoolean("can_sell");
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Grants the selling capability to a buyer (SCRUM merged buyer/seller accounts).
     *
     * @return {@code true} when the row was updated; {@code false} when no such user
     */
    public boolean enableSelling(int userId) {
        try (Connection conn = DBUtil.connectDB()) {
            if (!hasCanSellColumn(conn)) {
                // Schema lag: treat legacy Seller role as already enabled.
                return true;
            }
            String sql = "UPDATE users SET can_sell = TRUE WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                return ps.executeUpdate() > 0;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Turns on two-factor sign-in. The secret arrives already encrypted, so the shared key is only
     * ever handled by {@link SecurityUtil} and the plaintext never reaches the database.
     */
    public boolean enableTwoFactor(String email, String encryptedSecret) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE users SET two_factor_enabled = TRUE, two_factor_secret = ? WHERE email = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, encryptedSecret);
            ps.setString(2, email);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Turns two-factor off and clears the stored secret, so re-enabling issues a fresh one. */
    public boolean disableTwoFactor(String email) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE users SET two_factor_enabled = FALSE, two_factor_secret = NULL WHERE email = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, email);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Replaces the stored password. As with registration, the value must already be hashed by the
     * caller; this method does no hashing of its own.
     */
    public boolean updatePassword(String email, String hashedPassword) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE users SET password = ? WHERE email = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, hashedPassword);
            ps.setString(2, email);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** {@code auction.cancel_reason} written on a departing member's live listings. */
    static final String LISTING_CANCEL_REASON =
            "Cancelled automatically: the seller closed their AuctionHub account.";

    /** {@code orders.cancel_reason}, added to the CHECK constraint by migration_account_closure.sql. */
    static final String ORDER_CANCEL_REASON = "ACCOUNT_CLOSED";

    /** {@code orders.refund_reason} raised on the departing seller's paid, undespatched sales. */
    static final String REFUND_REASON =
            "Raised automatically: the seller closed their AuctionHub account before despatch.";

    /**
     * PDPA-aligned account closure: removes identifying data in place (email, username, password,
     * phone, address, 2FA secrets) and marks the row {@link Status#DELETED}. The primary key is
     * retained so auction/bid foreign keys remain valid without exposing the data subject.
     * Any Telegram link and queued Telegram messages are revoked in the same transaction.
     *
     * @return true when the row was anonymised. Prefer {@link #closeAccount(int)} when the
     *         caller wants to notify the counterparties that closure affected.
     */
    public boolean deleteAccount(int userId) {
        return closeAccount(userId).isAnonymised();
    }

    /**
     * Account closure, plus the clean-up that closure implies for the member's open business.
     *
     * <p>Anonymising the {@code users} row is not enough on its own. A departing seller's live
     * listings kept running and stayed biddable, which meant members could go on bidding, and
     * winning, against a seller who no longer exists and cannot despatch anything. Their open
     * orders were left dangling in the same way. All of it happens in the one transaction as the
     * anonymisation, so the account cannot end up closed with its listings still live.</p>
     *
     * <p>The policy, per state:</p>
     * <ul>
     *   <li>ACTIVE and PENDING listings become CANCELLED with a reason naming the closure.
     *       Bids are left in place, as they are for a seller-initiated cancel: they are the
     *       audit trail of a real auction.</li>
     *   <li>PENDING_PAYMENT orders, on either side, become CANCELLED. Nothing has been paid,
     *       so nobody is out of pocket, and neither party is left holding an obligation to a
     *       counterparty who has gone.</li>
     *   <li>PAID sales not yet despatched stay PAID and are flagged
     *       {@code refund_status = 'REQUESTED'}. This is the case the buyer must never lose:
     *       they have paid a seller who has just vanished. Cancelling the order outright
     *       would make their money disappear with it, so instead the order enters the
     *       existing refund queue that {@code OrderDAO#adminResolveRefund} already services,
     *       where an admin approves the refund and the cancellation follows from that. An
     *       order that already carries a refund decision is left alone.</li>
     *   <li>PAID orders already in transit, and the departing member's own paid purchases, are
     *       not touched. The goods are moving; unwinding that is a support matter, not something
     *       to guess at inside a DELETE. The counterparty is told.</li>
     *   <li>COMPLETED orders are not touched. They are finished history.</li>
     * </ul>
     */
    public ClosureImpact closeAccount(int userId) {
        try (Connection conn = DBUtil.connectDB()) {
            return closeAccountWithConnection(conn, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Same as {@link #deleteAccount(int)} but uses an existing connection (for unit tests with a mock).
     */
    boolean deleteAccountWithConnection(Connection conn, int userId) throws SQLException {
        return closeAccountWithConnection(conn, userId).isAnonymised();
    }

    /** Same as {@link #closeAccount(int)} but uses an existing connection (for unit tests). */
    ClosureImpact closeAccountWithConnection(Connection conn, int userId) throws SQLException {
        // Identifying fields are overwritten with unique junk rather than nulled, because email and
        // username carry uniqueness constraints and several closures would otherwise collide. The
        // password is set to a random hash so the row can never be signed into again.
        String token = UUID.randomUUID().toString().replace("-", "");
        String shortTok = token.substring(0, Math.min(16, token.length()));
        String anonymizedEmail = "deleted_" + userId + "_" + shortTok + "@invalid.auction.local";
        String anonymizedUsername = "deleted_u" + userId + "_" + shortTok;
        String randomSecret = UUID.randomUUID().toString() + token;
        String newPasswordHash = SecurityUtil.hashPassword(randomSecret);

        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        String sql = "UPDATE users SET email = ?, username = ?, password = ?, "
                + "phone_encrypted = NULL, address_encrypted = NULL, profile_image_url = NULL, "
                + "two_factor_enabled = FALSE, two_factor_secret = NULL, "
                + "status_id = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, anonymizedEmail);
            ps.setString(2, anonymizedUsername);
            ps.setString(3, newPasswordHash);
            ps.setInt(4, Status.DELETED.getId());
            ps.setInt(5, userId);
            boolean updated = ps.executeUpdate() == 1;
            revokeTelegramChannel(conn, userId);

            // Read the orders that closure leaves in somebody else's hands before anything
            // is rewritten, so the notification list describes the state the member is
            // actually in rather than the state this method just produced.
            List<AffectedOrder> handover = listHandoverOrders(conn, userId);
            List<Long> cancelledListings = cancelOpenListings(conn, userId);
            List<AffectedOrder> cancelledOrders = cancelUnpaidOrders(conn, userId);
            List<AffectedOrder> refundDue = raiseRefundsOnUndespatchedSales(conn, userId);

            conn.commit();
            return new ClosureImpact(updated, cancelledListings, cancelledOrders,
                    refundDue, handover);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * Cancels the departing member's ACTIVE and PENDING listings in the caller's transaction.
     *
     * <p>FINISHED and already-CANCELLED listings are left alone: they are concluded, and a
     * concluded auction's status is a historical fact about what happened, not a live state.
     * Bids survive for the same reason they survive a seller-initiated cancel.</p>
     *
     * @return the cancelled auction ids
     */
    private static List<Long> cancelOpenListings(Connection conn, int userId) throws SQLException {
        // Postgres RETURNING gives back the ids the UPDATE actually touched, so the caller can
        // notify about them without a second query and without a race against another writer.
        List<Long> ids = new ArrayList<>();
        String sql = "UPDATE auction SET status_id = ?, cancel_reason = ? "
                + "WHERE seller_id = ? AND status_id IN (?, ?) "
                + "RETURNING auction_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, AuctionStatus.CANCELLED.getId());
            ps.setString(2, LISTING_CANCEL_REASON);
            ps.setInt(3, userId);
            ps.setInt(4, AuctionStatus.ACTIVE.getId());
            ps.setInt(5, AuctionStatus.PENDING.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    /**
     * Cancels every unpaid order the departing member is on either side of, in the caller's
     * transaction. No money has moved on a {@code PENDING_PAYMENT} order, so cancelling it
     * costs nobody anything and releases the counterparty from an obligation to somebody who
     * has left.
     *
     * @return the cancelled orders, each carrying the counterparty to tell
     */
    private static List<AffectedOrder> cancelUnpaidOrders(Connection conn, int userId)
            throws SQLException {
        // Selected first so the counterparty and item title are captured before the status changes,
        // then updated in a batch. FOR UPDATE OF o locks only the order rows, not the joined
        // auction_details, which nothing here modifies.
        List<AffectedOrder> affected = new ArrayList<>();
        String select = "SELECT o.id, o.buyer_id, o.seller_id, d.title "
                + "FROM orders o JOIN auction_details d ON d.id = o.auction_id "
                + "WHERE o.status = 'PENDING_PAYMENT' AND (o.seller_id = ? OR o.buyer_id = ?) "
                + "FOR UPDATE OF o";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) affected.add(counterpartyOf(rs, userId));
            }
        }
        if (affected.isEmpty()) return affected;

        String update = "UPDATE orders SET status = 'CANCELLED', cancel_reason = ?, "
                + "cancelled_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND status = 'PENDING_PAYMENT'";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            for (AffectedOrder order : affected) {
                ps.setString(1, ORDER_CANCEL_REASON);
                ps.setLong(2, order.getOrderId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return affected;
    }

    /**
     * Flags the departing seller's paid-but-undespatched sales as refund-requested, in the
     * caller's transaction.
     *
     * <p>The order stays {@code PAID}: the buyer's payment is a fact and the amount has to
     * remain on a live row for the refund to be about something. What changes is that the
     * order now sits in the pending-refund queue an admin already works through
     * ({@code OrderDAO#adminResolveRefund}), so approving it performs the same
     * refund-and-cancel transition as any other approved refund. Orders that already carry a
     * refund decision are skipped rather than overwritten, because the buyer may have had one
     * declined and that outcome is not this method's to reverse.</p>
     *
     * @return the flagged orders, each carrying the buyer who is owed the money
     */
    private static List<AffectedOrder> raiseRefundsOnUndespatchedSales(Connection conn, int userId)
            throws SQLException {
        // "Not yet despatched" is a null shipping status or PREPARING. Anything further along means
        // the parcel has left, which is the handover case instead. The UPDATE repeats the status
        // and refund_status conditions so a concurrent change between the two statements loses.
        List<AffectedOrder> affected = new ArrayList<>();
        String select = "SELECT o.id, o.buyer_id, o.seller_id, d.title "
                + "FROM orders o JOIN auction_details d ON d.id = o.auction_id "
                + "WHERE o.seller_id = ? AND o.status = 'PAID' AND o.refund_status IS NULL "
                + "AND (o.shipping_status IS NULL OR o.shipping_status = 'PREPARING') "
                + "FOR UPDATE OF o";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) affected.add(counterpartyOf(rs, userId));
            }
        }
        if (affected.isEmpty()) return affected;

        String update = "UPDATE orders SET refund_status = 'REQUESTED', refund_reason = ?, "
                + "refund_requested_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND status = 'PAID' AND refund_status IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            for (AffectedOrder order : affected) {
                ps.setString(1, REFUND_REASON);
                ps.setLong(2, order.getOrderId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return affected;
    }

    /**
     * The paid orders closure deliberately leaves exactly as they are: sales already handed
     * to a courier, and the departing member's own paid purchases. Read only, because the goods
     * are in motion and the right outcome depends on facts only the two people involved and
     * support can establish: did it arrive, was it as described. The counterparty is told
     * so they are not left wondering why the other name went quiet.
     *
     * <p>Called before any of the write steps, so the list reflects the state closure found rather
     * than the state closure created.</p>
     */
    private static List<AffectedOrder> listHandoverOrders(Connection conn, int userId)
            throws SQLException {
        List<AffectedOrder> affected = new ArrayList<>();
        String sql = "SELECT o.id, o.buyer_id, o.seller_id, d.title "
                + "FROM orders o JOIN auction_details d ON d.id = o.auction_id "
                + "WHERE o.status = 'PAID' AND ("
                + "  (o.seller_id = ? AND o.shipping_status IS NOT NULL "
                + "     AND o.shipping_status <> 'PREPARING') "
                + "  OR o.buyer_id = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) affected.add(counterpartyOf(rs, userId));
            }
        }
        return affected;
    }

    /**
     * Maps an order row to the party that is <em>not</em> the departing member. Which side that is
     * is worked out by comparing the buyer id, since the member can be on either.
     */
    private static AffectedOrder counterpartyOf(ResultSet rs, int departingUserId)
            throws SQLException {
        int buyerId = rs.getInt("buyer_id");
        boolean counterpartyIsBuyer = buyerId != departingUserId;
        return new AffectedOrder(
                rs.getLong("id"),
                counterpartyIsBuyer ? buyerId : rs.getInt("seller_id"),
                counterpartyIsBuyer,
                rs.getString("title"));
    }

    /**
     * What an account closure did beyond anonymising the row, so the caller can tell the
     * people it affected. Returned rather than notified from inside the DAO: the
     * notifications must not be sent until the transaction has actually committed.
     */
    public static final class ClosureImpact {
        private final boolean anonymised;
        private final List<Long> cancelledListingIds;
        private final List<AffectedOrder> cancelledOrders;
        private final List<AffectedOrder> refundDueOrders;
        private final List<AffectedOrder> handoverOrders;

        ClosureImpact(boolean anonymised, List<Long> cancelledListingIds,
                      List<AffectedOrder> cancelledOrders, List<AffectedOrder> refundDueOrders,
                      List<AffectedOrder> handoverOrders) {
            this.anonymised = anonymised;
            this.cancelledListingIds = List.copyOf(cancelledListingIds);
            this.cancelledOrders = List.copyOf(cancelledOrders);
            this.refundDueOrders = List.copyOf(refundDueOrders);
            this.handoverOrders = List.copyOf(handoverOrders);
        }

        /** True when the {@code users} row was found and anonymised. */
        public boolean isAnonymised()                      { return anonymised; }
        /** Listings taken down because their seller left. */
        public List<Long> getCancelledListingIds()         { return cancelledListingIds; }
        /** Unpaid orders cancelled, with the counterparty to tell. */
        public List<AffectedOrder> getCancelledOrders()    { return cancelledOrders; }
        /** Paid, undespatched sales now awaiting an admin refund decision. */
        public List<AffectedOrder> getRefundDueOrders()    { return refundDueOrders; }
        /** Paid orders left untouched because the goods are already in motion. */
        public List<AffectedOrder> getHandoverOrders()     { return handoverOrders; }
    }

    /** One order touched by (or deliberately left alone by) a closure, and who to tell. */
    public static final class AffectedOrder {
        private final long orderId;
        private final int counterpartyId;
        private final boolean counterpartyIsBuyer;
        private final String itemTitle;

        AffectedOrder(long orderId, int counterpartyId, boolean counterpartyIsBuyer,
                      String itemTitle) {
            this.orderId = orderId;
            this.counterpartyId = counterpartyId;
            this.counterpartyIsBuyer = counterpartyIsBuyer;
            this.itemTitle = (itemTitle == null || itemTitle.isBlank()) ? "your order" : itemTitle;
        }

        public long getOrderId()             { return orderId; }
        /** The other party on the order, meaning the one still here and the one to notify. */
        public int getCounterpartyId()       { return counterpartyId; }
        /** True when the counterparty is the buyer, which decides where the alert links to. */
        public boolean isCounterpartyBuyer() { return counterpartyIsBuyer; }
        public String getItemTitle()         { return itemTitle; }
    }

    /**
     * Closes the Telegram channel as part of account deletion, in the caller's transaction.
     *
     * <p>Two things have to happen together with the anonymisation, or a deleted account
     * keeps receiving messages: the link stops being active and its encrypted chat id is
     * erased (it is personal data that account closure is supposed to remove), and any
     * queued messages are marked {@code SKIPPED} so the delivery worker never sends them.</p>
     */
    private static void revokeTelegramChannel(Connection conn, int userId) throws SQLException {
        String unlink = "UPDATE telegram_links SET active = FALSE, "
                + "unlinked_at = CURRENT_TIMESTAMP, chat_id_encrypted = '' "
                + "WHERE user_id = ? AND active";
        try (PreparedStatement ps = conn.prepareStatement(unlink)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }

        String skip = "UPDATE telegram_outbox SET status = 'SKIPPED', "
                + "last_error = 'Account deleted' WHERE user_id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(skip)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Whether some other account already uses this username, compared case-insensitively.
     * The id is excluded so a member re-saving their profile unchanged does not clash with itself.
     */
    public boolean usernameTakenByOtherUser(String username, int excludeUserId) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT 1 FROM users WHERE LOWER(username) = LOWER(?) AND id <> ? LIMIT 1";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            ps.setInt(2, excludeUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@code true} if another row already uses this email (case-insensitive).
     */
    public boolean emailTakenByOtherUser(String email, int excludeUserId) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "SELECT 1 FROM users WHERE LOWER(email) = LOWER(?) AND id <> ? LIMIT 1";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, email);
            ps.setInt(2, excludeUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Persists profile fields; {@code phoneEncrypted} / {@code addressEncrypted} must already be
     * ciphertext from {@link com.auction.util.SecurityUtil#encrypt(String)} or {@code null} to clear.
     */
    public boolean updateProfile(int userId, String username, String email, String phoneEncrypted,
                                 String addressEncrypted, String profileImageUrl) {
        try (Connection conn = DBUtil.connectDB()) {
            String sql = "UPDATE users SET username = ?, email = ?, phone_encrypted = ?, "
                    + "address_encrypted = ?, profile_image_url = ? WHERE id = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, phoneEncrypted);
            ps.setString(4, addressEncrypted);
            ps.setString(5, profileImageUrl);
            ps.setInt(6, userId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Sets just the avatar, used by the upload endpoint so it need not resend the whole profile. */
    public boolean updateProfileImageUrl(int userId, String profileImageUrl) {
        try (Connection conn = DBUtil.connectDB()) {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET profile_image_url = ? WHERE id = ?");
            ps.setString(1, profileImageUrl);
            ps.setInt(2, userId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final ZoneId ADMIN_ZONE = ZoneId.systemDefault();

    /**
     * All active admin account ids (for admin-targeted notifications).
     *
     * <p>The lookup columns are compared case-insensitively because the seed data spells
     * them {@code 'Admin'} / {@code 'Active'} while the enums and the rest of the code spell
     * them in upper case. A literal {@code r.role = 'ADMIN'} matched nothing in PostgreSQL,
     * which silently swallowed every admin alert since the feature shipped: pending registrations,
     * reports and support messages all went nowhere.</p>
     */
    public List<Integer> listAdminUserIds() {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT u.id FROM users u JOIN roles r ON r.id = u.role_id "
                + "JOIN user_status s ON s.id = u.status_id "
                + "WHERE UPPER(r.role) = 'ADMIN' AND UPPER(s.status) = 'ACTIVE'";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt("id"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ids;
    }

    /**
     * NEW for the "system-wide announcement" admin story (as an Admin, send system-wide
     * announcements/notifications to all users). Every account currently allowed to sign
     * in, across every role, which is the recipient list a platform-wide broadcast needs.
     *
     * <p>Modelled directly on {@link #listAdminUserIds()} above, but with no role filter and
     * reading {@code status_id} straight off {@code users} rather than joining
     * {@code user_status}, since the only question here is "is this account live" — the same
     * test {@link #countActiveUsers()} already applies for the admin dashboard's headline
     * count. Suspended, deleted, pending and rejected accounts are all excluded by asking
     * for {@link Status#ACTIVE} specifically rather than "not deleted".</p>
     *
     * <p>This is a pure addition: it does not change {@link #listAdminUserIds()} or any
     * other existing method, and nothing before this story called it.</p>
     */
    public List<Integer> listActiveUserIds() {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT id FROM users WHERE status_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Status.ACTIVE.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt("id"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ids;
    }

    /**
     * Every account for the admin user table, with the activity counts the table shows.
     * Closed accounts are excluded: their fields are anonymised placeholders and there is nothing
     * an admin can usefully do with them.
     */
    public List<AdminUserSummary> listUsersForAdminTable() {
        try (Connection conn = DBUtil.connectDB()) {
            // Bid and listing counts come from correlated subqueries rather than joins, because
            // joining both tables and grouping would multiply the rows before counting them.
            String sql = "SELECT u.id, u.username, u.email, u.role_id, u.status_id, u.date_created"
                    + (hasCanSellColumn(conn) ? ", u.can_sell" : "")
                    + ", (SELECT COUNT(*)::int FROM bids b WHERE b.user_id = u.id) AS bid_count, "
                    + "(SELECT COUNT(*)::int FROM auction a WHERE a.seller_id = u.id) AS listing_count "
                    + "FROM users u "
                    + "WHERE u.status_id <> ? "
                    + "ORDER BY u.id ASC";
            List<AdminUserSummary> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, Status.DELETED.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        LocalDate joined = rs.getTimestamp("date_created").toInstant()
                                .atZone(ADMIN_ZONE).toLocalDate();
                        list.add(new AdminUserSummary(
                                rs.getInt("id"),
                                rs.getString("username"),
                                rs.getString("email"),
                                Role.getRole(rs.getInt("role_id")),
                                rs.getInt("status_id"),
                                joined,
                                rs.getInt("bid_count"),
                                rs.getInt("listing_count"),
                                readCanSell(rs)));
                    }
                }
            }
            return list;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Members on the platform, excluding closed accounts. Shown on the admin dashboard. */
    public int countNonDeletedUsers() {
        return countOneInt("SELECT COUNT(*) FROM users WHERE status_id <> ?", Status.DELETED.getId());
    }

    /** Approved, non-suspended accounts, which is a narrower figure than the count above. */
    public int countActiveUsers() {
        return countOneInt("SELECT COUNT(*) FROM users WHERE status_id = ?", Status.ACTIVE.getId());
    }

    /** Newest sign-ups for the admin activity feed, closed accounts left out. */
    public List<NamedInstantEvent> recentRegistrations(int limit) {
        String sql = "SELECT username, date_created FROM users "
                + "WHERE status_id <> ? "
                + "ORDER BY date_created DESC "
                + "LIMIT ?";
        return loadNamedInstantEvents(sql, Status.DELETED.getId(), limit);
    }

    /**
     * Currently suspended accounts, most recently suspended first. COALESCE falls back to the
     * join date for rows suspended before {@code last_status_changed_at} was being written, which
     * would otherwise sort as nulls and jump to one end of the list.
     */
    public List<NamedInstantEvent> recentSuspensions(int limit) {
        String sql = "SELECT username, COALESCE(last_status_changed_at, date_created) AS ev "
                + "FROM users "
                + "WHERE status_id = ? "
                + "ORDER BY ev DESC "
                + "LIMIT ?";
        return loadNamedInstantEventsByTwoParams(sql, Status.SUSPENDED.getId(), limit);
    }

    /** Runs a two-parameter query whose event timestamp column is named {@code date_created}. */
    private List<NamedInstantEvent> loadNamedInstantEvents(String sql, int excludeDeleted, int limit) {
        try (Connection conn = DBUtil.connectDB()) {
            List<NamedInstantEvent> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, excludeDeleted);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp ts = rs.getTimestamp("date_created");
                        Instant at = ts != null ? ts.toInstant() : Instant.now();
                        out.add(new NamedInstantEvent(rs.getString("username"), at));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The same shape as above, for queries that alias their timestamp column to {@code ev}. */
    private List<NamedInstantEvent> loadNamedInstantEventsByTwoParams(String sql, int statusId, int limit) {
        try (Connection conn = DBUtil.connectDB()) {
            List<NamedInstantEvent> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, statusId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp ts = rs.getTimestamp("ev");
                        Instant at = ts != null ? ts.toInstant() : Instant.now();
                        out.add(new NamedInstantEvent(rs.getString("username"), at));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Runs a COUNT with one bound int and returns the single value, or zero. */
    private static int countOneInt(String sql, int intParam) {
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, intParam);
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

    /** A username paired with when something happened to it, for the admin activity feed. */
    public static final class NamedInstantEvent {
        private final String name;
        private final Instant at;

        public NamedInstantEvent(String name, Instant at) {
            this.name = name;
            this.at = at;
        }

        public String getName() {
            return name;
        }

        public Instant getAt() {
            return at;
        }
    }

    /**
     * Every account as sparse {@link User} objects carrying only id, username, email and role.
     * The password column is fetched by the {@code SELECT *} but never read into the model.
     */
    public List<User> viewAllUsers(){
        try(Connection conn = DBUtil.connectDB()) {
            List<User> userList = new ArrayList<>();
            String sqlString = "SELECT * FROM users";
            PreparedStatement pStatement = conn.prepareStatement(sqlString);
            ResultSet resultSet = pStatement.executeQuery();

            while(resultSet.next())
            {
                User user = new User();
                user.setId(resultSet.getInt("id"));
                user.setUsername(resultSet.getString("username"));
                user.setEmail(resultSet.getString("email"));
                user.setRole(Role.getRole(resultSet.getInt("role_id")));
                userList.add((user));
            }
            return userList;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
