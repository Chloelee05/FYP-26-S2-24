package com.auction.dao;

import com.auction.model.ListingKind;
import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The management half of "manage database of products, services, customers, auction
 * transactions" (Stakeholder #3b). Moderation, meaning flag, remove, ban and approve, already
 * existed elsewhere; this adds the record-correction operations that "manage" implies,
 * and writes every one of them to {@code admin_audit_log}.
 *
 * <p>Reads and writes {@code auction_details}, {@code orders} and {@code users}, always
 * alongside an insert into {@code admin_audit_log}; also reads {@code auction} and {@code bids}.
 * Called by the admin management API. Every mutating method runs inside
 * {@code DBUtil.runInTransaction} so the change and its audit entry commit together: an edit that
 * left no trail would be indistinguishable from tampering.</p>
 *
 * <h2>Where the line is drawn</h2>
 * <p>An admin here may correct <em>descriptive</em> data and <em>lifecycle</em> state, and
 * may not touch <em>money</em> or <em>ownership</em>:</p>
 * <ul>
 *   <li><b>Allowed:</b> a listing's title, description, category and product/service
 *       kind. These are the fields a moderator finds wrong, and today the only remedy is
 *       removing the whole listing, which punishes the seller for a typo.</li>
 *   <li><b>Refused:</b> a listing's starting price, reserve, Buy-It-Now or quantity. Bids
 *       are offers against a published price; editing it mid-auction rewrites the contract
 *       buyers already bid into. The seller owns those fields.</li>
 *   <li><b>Allowed:</b> correcting an order's lifecycle status, with a mandatory reason,
 *       so a payment that completed out-of-band or a stuck delivery can be reconciled.</li>
 *   <li><b>Refused:</b> an order's amount. That is the settled sale value and feeds
 *       platform revenue; an admin editing it would falsify the platform's own books.</li>
 *   <li><b>Refused:</b> hard deletion of a listing or an order. Both are financial
 *       history. Listings deactivate via {@code moderation_state = 'removed'}; a customer
 *       deactivates to the existing {@code Deleted} status.</li>
 *   <li><b>Refused:</b> creating listings, orders or customers. An admin owns no
 *       inventory, orders derive from auction outcomes, and account creation already has a
 *       registration-and-approval path.</li>
 * </ul>
 */
public class AdminManagementDAO {

    /** Order lifecycle values the {@code orders_status_check} constraint permits. */
    private static final List<String> ORDER_STATUSES =
            List.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED");

    /**
     * The two values {@code auction_details_listing_kind_check} permits. Taken from
     * {@link ListingKind} rather than restated, now that sellers set this field on create and
     * the same value set has to hold on both paths.
     */
    private static final List<String> LISTING_KINDS = ListingKind.names();

    /**
     * Result of a management operation. UNCHANGED is distinct from SUCCESS so the API can tell an
     * admin that their submission matched what was already stored, rather than logging a no-op
     * edit into the audit trail.
     */
    public enum Outcome { SUCCESS, NOT_FOUND, INVALID, UNCHANGED }

    // ── Listings: content correction ─────────────────────────────────────────

    /** A listing's editable descriptive fields, for pre-filling the admin edit form. */
    public Map<String, Object> getListingContent(long auctionId) {
        String sql = "SELECT d.id, d.title, d.description, d.category, d.listing_kind, "
                   + "  a.moderation_state, u.username AS seller_username, "
                   // The bid count is shown next to the form so a moderator can see they are about
                   // to edit a listing people have already bid on.
                   + "  (SELECT COUNT(*) FROM bids b WHERE b.auction_id = a.auction_id) AS bid_count "
                   + "FROM auction_details d "
                   + "JOIN auction a ON a.auction_id = d.id "
                   + "JOIN users u ON u.id = a.seller_id "
                   + "WHERE d.id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("auctionId", rs.getLong("id"));
                out.put("title", rs.getString("title"));
                out.put("description", rs.getString("description"));
                out.put("category", rs.getString("category"));
                out.put("listingKind", rs.getString("listing_kind"));
                out.put("moderationState", rs.getString("moderation_state"));
                out.put("sellerUsername", rs.getString("seller_username"));
                out.put("bidCount", rs.getInt("bid_count"));
                return out;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Corrects a listing's title, description, category and product/service kind.
     *
     * <p>Price fields are deliberately absent from the signature; see the class comment.
     * Every changed field is written to the audit log with its previous value, so a seller
     * disputing an edit can be shown exactly what an admin changed and why.</p>
     */
    public Outcome updateListingContent(int adminId, long auctionId, String title,
                                        String description, String category,
                                        String listingKind, String reason) {
        if (isBlank(title) || isBlank(description)) return Outcome.INVALID;
        String kind = listingKind == null ? null : listingKind.trim().toUpperCase(Locale.ROOT);
        if (kind != null && !LISTING_KINDS.contains(kind)) return Outcome.INVALID;

        try {
            return DBUtil.runInTransaction(conn -> {
                Map<String, String> before = readListingFields(conn, auctionId);
                if (before == null) return Outcome.NOT_FOUND;

                // Omitted kind or blank category means "leave it alone", so the stored value is
                // carried forward instead of being nulled by a partial form submission.
                String newKind = kind != null ? kind : before.get("listing_kind");
                String newCategory = isBlank(category) ? before.get("category") : category.trim();

                // Each audit() writes one row per field that actually differs and reports whether
                // it wrote. If none did, the submission changed nothing and the UPDATE is skipped.
                boolean changed = false;
                changed |= audit(conn, adminId, "LISTING", auctionId, "EDIT_CONTENT",
                        "title", before.get("title"), title.trim(), reason);
                changed |= audit(conn, adminId, "LISTING", auctionId, "EDIT_CONTENT",
                        "description", before.get("description"), description.trim(), reason);
                changed |= audit(conn, adminId, "LISTING", auctionId, "EDIT_CONTENT",
                        "category", before.get("category"), newCategory, reason);
                changed |= audit(conn, adminId, "LISTING", auctionId, "EDIT_CONTENT",
                        "listing_kind", before.get("listing_kind"), newKind, reason);
                if (!changed) return Outcome.UNCHANGED;

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE auction_details SET title = ?, description = ?, category = ?, "
                      + "listing_kind = ? WHERE id = ?")) {
                    ps.setString(1, title.trim());
                    ps.setString(2, description.trim());
                    ps.setString(3, newCategory);
                    ps.setString(4, newKind);
                    ps.setLong(5, auctionId);
                    return ps.executeUpdate() == 1 ? Outcome.SUCCESS : Outcome.NOT_FOUND;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Reclassifies a listing between PRODUCT and SERVICE without touching its copy. */
    public Outcome updateListingKind(int adminId, long auctionId, String listingKind, String reason) {
        String kind = listingKind == null ? "" : listingKind.trim().toUpperCase(Locale.ROOT);
        if (!LISTING_KINDS.contains(kind)) return Outcome.INVALID;
        try {
            return DBUtil.runInTransaction(conn -> {
                Map<String, String> before = readListingFields(conn, auctionId);
                if (before == null) return Outcome.NOT_FOUND;
                if (kind.equals(before.get("listing_kind"))) return Outcome.UNCHANGED;

                audit(conn, adminId, "LISTING", auctionId, "SET_KIND",
                        "listing_kind", before.get("listing_kind"), kind, reason);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE auction_details SET listing_kind = ? WHERE id = ?")) {
                    ps.setString(1, kind);
                    ps.setLong(2, auctionId);
                    return ps.executeUpdate() == 1 ? Outcome.SUCCESS : Outcome.NOT_FOUND;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The four editable fields as they stand right now, read on the caller's transaction so the
     * before-values written to the audit log match what the UPDATE is about to overwrite.
     * Returns null when the listing does not exist.
     */
    private Map<String, String> readListingFields(Connection conn, long auctionId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT title, description, category, listing_kind FROM auction_details WHERE id = ?")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, String> out = new LinkedHashMap<>();
                out.put("title", rs.getString("title"));
                out.put("description", rs.getString("description"));
                out.put("category", rs.getString("category"));
                out.put("listing_kind", rs.getString("listing_kind"));
                return out;
            }
        }
    }

    // ── Auction transactions: order state correction ─────────────────────────

    /**
     * Moves an order to another lifecycle status, recording who did it and why.
     *
     * <p>The order's {@code amount} is never touched. Timestamps are kept consistent with
     * the new status so the order does not end up marked PAID with no {@code paid_at},
     * which is the sort of half-corrected row that breaks the revenue report later.</p>
     */
    public Outcome correctOrderStatus(int adminId, long orderId, String status, String reason) {
        String target = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!ORDER_STATUSES.contains(target)) return Outcome.INVALID;
        // A reason is mandatory: an unexplained state change on someone else's transaction
        // is indistinguishable from tampering when it is read back months later.
        if (isBlank(reason)) return Outcome.INVALID;

        try {
            return DBUtil.runInTransaction(conn -> {
                String current;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status FROM orders WHERE id = ?")) {
                    ps.setLong(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return Outcome.NOT_FOUND;
                        current = rs.getString("status");
                    }
                }
                if (target.equals(current)) return Outcome.UNCHANGED;

                audit(conn, adminId, "ORDER", orderId, "CORRECT_STATUS",
                        "status", current, target, reason);

                // The CASE WHEN blocks stamp whichever timestamp the target status implies, and the
                // COALESCE inside each one keeps an existing timestamp if the order already passed
                // through that state. Moving PAID to COMPLETED must not rewrite the original
                // paid_at. The same target string is bound five times, once per CASE.
                String sql =
                    "UPDATE orders SET status = ?, "
                  + "  paid_at = CASE WHEN ? IN ('PAID','COMPLETED') "
                  + "                 THEN COALESCE(paid_at, now()) ELSE paid_at END, "
                  + "  completed_at = CASE WHEN ? = 'COMPLETED' "
                  + "                      THEN COALESCE(completed_at, now()) ELSE completed_at END, "
                  + "  cancelled_at = CASE WHEN ? = 'CANCELLED' "
                  + "                      THEN COALESCE(cancelled_at, now()) ELSE cancelled_at END, "
                  + "  cancel_reason = CASE WHEN ? = 'CANCELLED' "
                  + "                       THEN COALESCE(cancel_reason, 'ADMIN_CANCELLED') "
                  + "                       ELSE cancel_reason END "
                  + "WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, target);
                    ps.setString(2, target);
                    ps.setString(3, target);
                    ps.setString(4, target);
                    ps.setString(5, target);
                    ps.setLong(6, orderId);
                    return ps.executeUpdate() == 1 ? Outcome.SUCCESS : Outcome.NOT_FOUND;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Customers ────────────────────────────────────────────────────────────

    /**
     * Deactivates a customer account to the existing {@code Deleted} status.
     *
     * <p>This is the Delete the requirement's "manage ... customers" was missing. It is a
     * soft delete: the row stays so their bids, orders and reviews keep their author, and
     * so the action stays reversible. Refused while the account still has a live listing or
     * an unsettled order, because deactivating mid-auction would strand the counterparty.</p>
     */
    public Outcome deactivateCustomer(int adminId, int userId, int deletedStatusId, String reason) {
        if (isBlank(reason)) return Outcome.INVALID;
        try {
            return DBUtil.runInTransaction(conn -> {
                Integer currentStatus = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status_id FROM users WHERE id = ?")) {
                    ps.setInt(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return Outcome.NOT_FOUND;
                        currentStatus = rs.getInt("status_id");
                    }
                }
                if (currentStatus == deletedStatusId) return Outcome.UNCHANGED;
                if (hasLiveCommitments(conn, userId)) return Outcome.INVALID;

                audit(conn, adminId, "CUSTOMER", userId, "DEACTIVATE",
                        "status_id", String.valueOf(currentStatus),
                        String.valueOf(deletedStatusId), reason);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET status_id = ?, last_status_changed_at = now() WHERE id = ?")) {
                    ps.setInt(1, deletedStatusId);
                    ps.setInt(2, userId);
                    return ps.executeUpdate() == 1 ? Outcome.SUCCESS : Outcome.NOT_FOUND;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** True while the account still has a live listing or an order that has not settled. */
    public boolean hasLiveCommitments(Connection conn, int userId) throws Exception {
        // One round trip returning a single boolean: true if the user is selling something still
        // running, or is either side of an order that has not reached COMPLETED or CANCELLED.
        String sql =
            "SELECT (EXISTS (SELECT 1 FROM auction a WHERE a.seller_id = ? "
          + "                AND a.moderation_state = 'active' AND a.date_end > now())) "
          + "    OR (EXISTS (SELECT 1 FROM orders o WHERE (o.seller_id = ? OR o.buyer_id = ?) "
          + "                AND o.status IN ('PENDING_PAYMENT', 'PAID')))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    // ── Audit trail ──────────────────────────────────────────────────────────

    /**
     * Records one field change, returning false when the value did not actually change.
     * Callers OR the results together to decide whether an UPDATE is worth running.
     */
    private boolean audit(Connection conn, int adminId, String entityType, long entityId,
                          String action, String field, String oldValue, String newValue,
                          String reason) throws Exception {
        if (java.util.Objects.equals(oldValue, newValue)) return false;
        String sql = "INSERT INTO admin_audit_log "
                   + "(admin_id, entity_type, entity_id, action, field_name, old_value, new_value, reason) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, adminId);
            ps.setString(2, entityType);
            ps.setLong(3, entityId);
            ps.setString(4, action);
            ps.setString(5, field);
            ps.setString(6, oldValue);
            ps.setString(7, newValue);
            ps.setString(8, isBlank(reason) ? null : reason.trim());
            ps.executeUpdate();
        }
        return true;
    }

    /** Most recent admin management actions, newest first. */
    public List<Map<String, Object>> listAuditLog(int limit) {
        String sql = "SELECT l.id, l.entity_type, l.entity_id, l.action, l.field_name, "
                   + "  l.old_value, l.new_value, l.reason, l.created_at, u.username AS admin_username "
                   + "FROM admin_audit_log l JOIN users u ON u.id = l.admin_id "
                   + "ORDER BY l.created_at DESC, l.id DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // Clamped rather than trusted, so a caller-supplied limit cannot ask for the whole log.
            ps.setInt(1, Math.max(1, Math.min(500, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("adminUsername", rs.getString("admin_username"));
                    row.put("entityType", rs.getString("entity_type"));
                    row.put("entityId", rs.getLong("entity_id"));
                    row.put("action", rs.getString("action"));
                    row.put("field", rs.getString("field_name"));
                    row.put("oldValue", truncate(rs.getString("old_value")));
                    row.put("newValue", truncate(rs.getString("new_value")));
                    row.put("reason", rs.getString("reason"));
                    Timestamp at = rs.getTimestamp("created_at");
                    row.put("at", at == null ? null : at.toInstant().toString());
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return rows;
    }

    /** Listing counts by product/service kind, for the admin listings page summary. */
    public Map<String, Integer> countListingsByKind() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("PRODUCT", 0);
        out.put("SERVICE", 0);
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT listing_kind, COUNT(*) FROM auction_details GROUP BY listing_kind");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /**
     * Auction id to product/service kind, so the moderation table can show the column.
     * Loaded in one pass and joined in Java, avoiding a per-row lookup from the listings page.
     */
    public Map<Long, String> listingKinds() {
        Map<Long, String> out = new LinkedHashMap<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, listing_kind FROM auction_details");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getLong(1), rs.getString(2));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Shortens a logged value for the audit table; a full description would flood the row. */
    private static String truncate(String v) {
        if (v == null) return null;
        return v.length() <= 200 ? v : v.substring(0, 200) + "…";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
