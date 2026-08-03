package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.model.Bid;
import com.auction.model.ListingKind;
import com.auction.model.seller.SellerAuctionRow;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Seller-scoped auction operations.
 *
 * State transitions: PENDING(4) → ACTIVE(1) → FINISHED(2)
 *                                    ↓               ↓
 *                               CANCELLED(3)    CANCELLED(3)*
 * (* cancelling a finished auction is rejected – only PENDING and ACTIVE may be cancelled)
 */
public class SellerAuctionDAO {

    // ------------------------------------------------------------------ cancel

    /**
     * Cancels an auction owned by {@code sellerId} if it is ACTIVE or PENDING.
     * Existing bids are preserved for audit; the auction status is set to CANCELLED.
     *
     * @return true if the row was updated; false means not-found, wrong owner, or wrong state
     */
    public boolean cancelAuction(long auctionId, int sellerId, String cancelReason) throws Exception {
        String sql = "UPDATE auction SET status_id = ?, cancel_reason = ? "
                   + "WHERE auction_id = ? AND seller_id = ? AND status_id IN (?, ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, AuctionStatus.CANCELLED.getId());
            ps.setString(2, cancelReason);
            ps.setLong(3, auctionId);
            ps.setInt(4, sellerId);
            ps.setInt(5, AuctionStatus.ACTIVE.getId());
            ps.setInt(6, AuctionStatus.PENDING.getId());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Outcome of {@link #removeUnit}.
     *
     * <p>{@code LISTING_ENDED} is the interesting one: it means the unit removed was the
     * last one, so the listing was cancelled in the same transaction. It is a success, not
     * a refusal — the old {@code LAST_UNIT} refusal is what made minimum requirement
     * Seller (d) only half-met.</p>
     */
    public enum ReduceQtyResult { SUCCESS, LISTING_ENDED, NOT_FOUND, NOT_OWNER, NOT_ACTIVE, ALREADY_EMPTY }

    /**
     * Removes one unit from a listing, including the last one.
     *
     * <p>Removing a unit while others remain simply decrements {@code quantity}. Removing
     * the <em>final</em> unit means the seller has nothing left to sell, so it also cancels
     * the auction ({@code status_id = CANCELLED}, {@code cancel_reason = reason}) inside the
     * same transaction and reports {@link ReduceQtyResult#LISTING_ENDED}. Ending the listing
     * is the only coherent reading of "quantity is now zero": a live auction that cannot
     * deliver anything would keep taking bids it can never honour.</p>
     *
     * <p>This is why {@code CHECK (quantity >= 1)} was relaxed to {@code >= 0} in
     * migration_seller_maintain_listing.sql. The pair of writes being one transaction is
     * what keeps {@code quantity = 0} from ever being visible on an ACTIVE listing.</p>
     *
     * <p>Row-locked ({@code FOR UPDATE}) so two concurrent removals cannot both read
     * quantity 1 and both decide they are the one ending the listing.</p>
     *
     * @param reason the seller's cancellation reason, used only when the last unit goes
     * @return which of the two things happened, or why neither did
     */
    public ReduceQtyResult removeUnit(long auctionId, int sellerId, String reason) throws Exception {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            int statusId, ownerId, qty;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT a.status_id, a.seller_id, d.quantity "
                  + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
                  + "WHERE a.auction_id = ? FOR UPDATE")) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return ReduceQtyResult.NOT_FOUND; }
                    statusId = rs.getInt("status_id");
                    ownerId = rs.getInt("seller_id");
                    qty = rs.getInt("quantity");
                }
            }
            if (ownerId != sellerId) { conn.rollback(); return ReduceQtyResult.NOT_OWNER; }
            if (statusId != AuctionStatus.ACTIVE.getId() && statusId != AuctionStatus.PENDING.getId()) {
                conn.rollback(); return ReduceQtyResult.NOT_ACTIVE;
            }
            if (qty <= 0) { conn.rollback(); return ReduceQtyResult.ALREADY_EMPTY; }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auction_details SET quantity = quantity - 1 WHERE id = ?")) {
                ps.setLong(1, auctionId);
                ps.executeUpdate();
            }

            if (qty > 1) {
                conn.commit();
                return ReduceQtyResult.SUCCESS;
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auction SET status_id = ?, cancel_reason = ? WHERE auction_id = ?")) {
                ps.setInt(1, AuctionStatus.CANCELLED.getId());
                ps.setString(2, reason);
                ps.setLong(3, auctionId);
                ps.executeUpdate();
            }
            conn.commit();
            return ReduceQtyResult.LISTING_ENDED;
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
        }
    }

    /**
     * Decrements the stock of a listing that has just been won, flooring at zero.
     *
     * <p>Called from inside the caller's transaction by every path that concludes an auction
     * with a winner — {@link com.auction.util.AuctionFinalizer}, {@code OrderDAO.declareWinner},
     * Buy It Now and Dutch acceptance — because until now nothing reduced stock on a sale and
     * a multi-quantity listing's quantity was therefore decorative.</p>
     *
     * <p>This model awards one auction to one winner ({@code winner_id} and the order are both
     * single-valued), so exactly one unit leaves on a conclusion. The auction is FINISHED at
     * that point either way; decrementing is what makes the number the seller sees, and the
     * number they can now edit, mean something. {@code GREATEST(quantity - 1, 0)} rather than a
     * bare subtraction so a legacy row that somehow holds 0 cannot break the CHECK.</p>
     */
    public static void decrementStockForSale(Connection conn, long auctionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE auction_details SET quantity = GREATEST(quantity - 1, 0) WHERE id = ?")) {
            ps.setLong(1, auctionId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ bid-cap (SCRUM-33)

    /**
     * Returns true when {@code bidAmount} does not exceed the max_price cap.
     * Always returns true when the auction has no cap (max_price IS NULL).
     * Hard-ceiling semantics: bid == cap is allowed; bid > cap is rejected.
     * <p>
     * Intended for use by BidDAO (SCRUM-51) before persisting any bid.
     */
    public boolean withinBidCap(long auctionId, BigDecimal bidAmount) throws Exception {
        String sql = "SELECT max_price FROM auction_details WHERE id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false; // auction not found — fail safe
                BigDecimal cap = rs.getBigDecimal("max_price");
                return cap == null || bidAmount.compareTo(cap) <= 0;
            }
        }
    }

    // ------------------------------------------------------------------ edit (SCRUM-37)

    /**
     * Returns the auction data needed to populate the edit form.
     * Returns null when the auction does not exist or is not owned by {@code sellerId}.
     */
    public AuctionEditData getAuctionForEdit(long auctionId, int sellerId) throws Exception {
        String sql =
            "SELECT a.auction_id, a.seller_id, a.status_id, "
          + "       d.title, d.description, d.category, d.listing_kind, d.item_condition_id, d.max_price, "
          + "       d.quantity, d.cost_price, "
          + "       a.date_created AS start_date, a.date_end "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "WHERE a.auction_id = ? AND a.seller_id = ?";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                List<AuctionEditData.ImageEntry> images = fetchImages(conn, auctionId);
                return new AuctionEditData(
                        rs.getLong("auction_id"),
                        rs.getLong("seller_id"),
                        rs.getInt("status_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("category"),
                        rs.getString("listing_kind"),
                        rs.getInt("item_condition_id"),
                        rs.getBigDecimal("max_price"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("cost_price"),
                        rs.getTimestamp("start_date").toInstant(),
                        rs.getTimestamp("date_end").toInstant(),
                        images);
            }
        }
    }

    /** Returns the number of bids placed on the given auction. */
    public int countBids(long auctionId) throws Exception {
        String sql = "SELECT COUNT(*) FROM bids WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Text-and-images-only edit, for the legacy JSP form which has no stock, cost or
     * product/service fields. Passing null for those leaves {@code quantity},
     * {@code cost_price} and {@code listing_kind} untouched.
     */
    public void editAuction(long auctionId, int sellerId,
                            String title, String description,
                            String category, Integer itemConditionId,
                            Instant newEndDate,
                            List<Long> deleteImageIds,
                            List<String> newImageFilenames) throws Exception {
        editAuction(auctionId, sellerId, title, description, category, null, itemConditionId,
                null, null, newEndDate, deleteImageIds, newImageFilenames);
    }

    /**
     * Updates the record for an auction the seller still owns and may edit.
     *
     * <p>Three tiers, by how much a change would move the ground under someone who has
     * already bid:</p>
     * <ul>
     *   <li><b>Always</b> — the end date.</li>
     *   <li><b>Always</b> — {@code quantity} and {@code cost_price}, the two fields minimum
     *       requirement Seller (b) names that were previously write-once. {@code cost_price}
     *       is seller-private bookkeeping that no buyer ever sees and that takes no part in
     *       pricing, so there is nothing for a bid to conflict with. {@code quantity} is stock
     *       information a seller has to be able to correct while a listing is live — that is
     *       the requirement — and the platform already let it be decremented mid-auction with
     *       bids present, so freezing it on the first bid would have been the inconsistency.
     *       The caller clamps it to at least 1: emptying a listing is
     *       {@link #removeUnit}'s job, because it ends the listing and has to say so first.</li>
     *   <li><b>Zero bids only</b> — title, description, category, product/service kind,
     *       condition and images. These define <em>what is being sold</em>, and rewriting them
     *       under a live bid would mean someone had committed money to a different item.
     *       {@code listing_kind} belongs in this tier and not the one above it: turning a
     *       product into a service after someone has bid changes whether anything is going to
     *       be shipped to them.</li>
     * </ul>
     *
     * <p>Precondition enforcement (ownership, editable status) is re-checked here on every
     * submit rather than trusted from the GET that populated the form, to close the TOCTOU
     * window; the bid count is likewise re-read inside the transaction.</p>
     *
     * @param listingKind PRODUCT or SERVICE, or null to leave the existing kind alone
     * @param quantity new unit count, or null to leave it alone
     * @param costPrice new seller-private cost, or null to leave it alone
     * @param deleteImageIds IDs of existing auction_images rows to remove (may be empty)
     * @param newImageFilenames new filenames already written to the upload directory
     */
    public void editAuction(long auctionId, int sellerId,
                            String title, String description,
                            String category, String listingKind, Integer itemConditionId,
                            Integer quantity, BigDecimal costPrice,
                            Instant newEndDate,
                            List<Long> deleteImageIds,
                            List<String> newImageFilenames) throws Exception {
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            try {
                if (!isEditableBy(conn, auctionId, sellerId)) {
                    throw new IllegalStateException("Auction is not editable");
                }

                // End date can always be updated (no bids restriction)
                if (newEndDate != null) {
                    String sql = "UPDATE auction SET date_end = ? WHERE auction_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setTimestamp(1, Timestamp.from(newEndDate));
                        ps.setLong(2, auctionId);
                        ps.executeUpdate();
                    }
                }

                // Stock and the seller's private cost are maintainable regardless of bids.
                updateStockAndCost(conn, auctionId, quantity, costPrice);

                // Title, description, category, condition, images require zero bids
                if (countBidsConn(conn, auctionId) == 0) {
                    updateDetails(conn, auctionId, title, description, category,
                            listingKind, itemConditionId);
                    deleteImages(conn, auctionId, deleteImageIds);
                    insertNewImages(conn, auctionId, newImageFilenames);
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ dashboard (SCRUM-38)

    /**
     * The columns My listings needs for one row. Shared by the legacy status-filtered query
     * and the bucketed one below so the two can never drift in what they return.
     */
    private static final String ROW_SELECT =
            "SELECT a.auction_id, d.title, d.starting_price, d.max_price, d.quantity, "
          + "d.listing_kind, "
          + "COALESCE(MAX(b.bid_amount), 0) AS current_bid, "
          + "COUNT(b.bid_id) AS bid_count, "
          + "a.date_created AS start_date, a.date_end, "
          // Scalar subqueries: the listing thumbnail and its watchlist ("likes") count.
          + "(SELECT ai.image_url FROM auction_images ai "
          + " WHERE ai.auction_id = a.auction_id ORDER BY ai.id LIMIT 1) AS thumbnail_url, "
          + "(SELECT COUNT(*)::int FROM watchlist w WHERE w.auction_id = a.auction_id) AS watch_count, "
          + "CASE WHEN s.status = 'Active' AND a.date_end <= CURRENT_TIMESTAMP "
          + "     THEN 'Finished' ELSE s.status END AS status_name "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "JOIN auction_status  s ON s.id = a.status_id "
          + "LEFT JOIN bids       b ON b.auction_id = a.auction_id "
          + "WHERE a.seller_id = ?";

    private static final String ROW_GROUP_BY =
            " GROUP BY a.auction_id, d.title, d.starting_price, d.max_price, d.quantity, "
          + "d.listing_kind, a.date_created, a.date_end, s.status";

    /**
     * Returns a seller-scoped page of auction rows ordered by end date descending.
     *
     * @param statusId null to include all statuses; otherwise filter to that AuctionStatus id
     * @param page     1-based page number
     * @param pageSize rows per page (caller must clamp to a reasonable max)
     */
    public List<SellerAuctionRow> listSellerAuctions(int sellerId, Integer statusId,
                                                     int page, int pageSize) throws Exception {
        StringBuilder sb = new StringBuilder(ROW_SELECT);
        if (statusId != null) sb.append(" AND a.status_id = ?");
        sb.append(ROW_GROUP_BY)
          .append(" ORDER BY a.date_end DESC")
          .append(" LIMIT ? OFFSET ?");

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            int i = 1;
            ps.setInt(i++, sellerId);
            if (statusId != null) ps.setInt(i++, statusId);
            ps.setInt(i++, pageSize);
            ps.setInt(i, pageSize * (page - 1));

            List<SellerAuctionRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(mapRow(rs));
            }
            return rows;
        }
    }

    /** Total count for pagination — same WHERE clause as listSellerAuctions. */
    public int countSellerAuctions(int sellerId, Integer statusId) throws Exception {
        String sql = "SELECT COUNT(*) FROM auction WHERE seller_id = ?"
                   + (statusId != null ? " AND status_id = ?" : "");
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sellerId);
            if (statusId != null) ps.setInt(2, statusId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ------------------------------------------------------------------ buckets + real pagination

    /**
     * The four groups My listings shows as tabs.
     *
     * <p>These are not stored statuses, which is why a plain {@code status_id} filter could
     * not drive the tabs and why the page previously had to fetch everything and split it in
     * the browser — the reason a seller with more than one page of listings could not reach
     * the rest of them at all. Two of the four need more than {@code status_id}:</p>
     * <ul>
     *   <li>An ACTIVE row whose end date has passed is really finished; the row's status only
     *       catches up when something lazily finalises it. {@link #ROW_SELECT} has always
     *       displayed such a row as "Finished", so the buckets apply the same rule rather than
     *       contradicting the badge next to them.</li>
     *   <li>FINISHED covers both "sold" and "the clock ran out with nobody bidding", which the
     *       audit found being shown as the same word as a seller cancellation. They are split
     *       here on whether any bid exists, so {@code UNSOLD} and {@code CANCELLED} are
     *       genuinely different tabs backed by genuinely different queries.</li>
     * </ul>
     */
    public enum ListingBucket {
        ALL, ACTIVE, FINISHED, UNSOLD, CANCELLED;

        /** Lenient parse for a query parameter; anything unrecognised means "no filter". */
        public static ListingBucket parse(String raw) {
            if (raw == null || raw.isBlank()) return ALL;
            try { return valueOf(raw.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { return ALL; }
        }
    }

    /** True when the auction is over regardless of whether its row has been finalised yet. */
    private static final String EFFECTIVELY_ENDED =
            "(a.status_id = 2 OR (a.status_id = 1 AND a.date_end <= CURRENT_TIMESTAMP))";

    private static final String HAS_BIDS =
            "EXISTS (SELECT 1 FROM bids bx WHERE bx.auction_id = a.auction_id)";

    private static String bucketPredicate(ListingBucket bucket) {
        switch (bucket) {
            case ACTIVE:
                return " AND (a.status_id = 4 OR (a.status_id = 1 AND a.date_end > CURRENT_TIMESTAMP))";
            case FINISHED:
                return " AND " + EFFECTIVELY_ENDED + " AND " + HAS_BIDS;
            case UNSOLD:
                return " AND " + EFFECTIVELY_ENDED + " AND NOT " + HAS_BIDS;
            case CANCELLED:
                return " AND a.status_id = 3";
            case ALL:
            default:
                return "";
        }
    }

    /**
     * Whitelisted sort clauses. A whitelist rather than an interpolated column name because
     * this reaches SQL from a query parameter; anything unrecognised falls back to newest.
     *
     * <p>Every clause ends in {@code a.auction_id DESC}. Without a total order, two rows
     * sharing a sort key can swap places between one page request and the next, which is how
     * paginated lists silently duplicate one row and skip another.</p>
     */
    private static String orderByClause(String sort) {
        String key = sort == null ? "" : sort.trim();
        switch (key) {
            case "oldest":    return " ORDER BY a.date_created ASC, a.auction_id DESC";
            case "priceHigh": return " ORDER BY COALESCE(MAX(b.bid_amount), d.starting_price) DESC, a.auction_id DESC";
            case "priceLow":  return " ORDER BY COALESCE(MAX(b.bid_amount), d.starting_price) ASC, a.auction_id DESC";
            case "likes":     return " ORDER BY watch_count DESC, a.auction_id DESC";
            case "ending":    return " ORDER BY a.date_end ASC, a.auction_id DESC";
            case "newest":
            default:          return " ORDER BY a.date_created DESC, a.auction_id DESC";
        }
    }

    /** {@code null} when there is nothing to search for, else the LIKE pattern to bind. */
    private static String likePattern(String query) {
        if (query == null || query.isBlank()) return null;
        return "%" + query.trim().toLowerCase() + "%";
    }

    /**
     * One page of the seller's listings, filtered, searched and sorted in the database.
     *
     * <p>Filtering, searching and sorting all happen here rather than in the browser because
     * with real pagination they have to: a title search applied to page 1 of 10 rows would
     * silently claim the seller has no listing by that name.</p>
     *
     * @param bucket   which tab (never null; use {@link ListingBucket#ALL} for everything)
     * @param query    case-insensitive title substring, or null/blank for no search
     * @param sort     one of the keys {@link #orderByClause} whitelists
     * @param page     1-based page number
     * @param pageSize rows per page (caller clamps)
     */
    public List<SellerAuctionRow> listSellerAuctions(int sellerId, ListingBucket bucket, String query,
                                                     String sort, int page, int pageSize) throws Exception {
        String like = likePattern(query);
        StringBuilder sb = new StringBuilder(ROW_SELECT)
                .append(bucketPredicate(bucket == null ? ListingBucket.ALL : bucket));
        if (like != null) sb.append(" AND LOWER(d.title) LIKE ?");
        sb.append(ROW_GROUP_BY)
          .append(orderByClause(sort))
          .append(" LIMIT ? OFFSET ?");

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            int i = 1;
            ps.setInt(i++, sellerId);
            if (like != null) ps.setString(i++, like);
            ps.setInt(i++, pageSize);
            ps.setInt(i, pageSize * Math.max(0, page - 1));

            List<SellerAuctionRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(mapRow(rs));
            }
            return rows;
        }
    }

    /** How many rows {@link #listSellerAuctions} would return across every page. */
    public int countSellerAuctions(int sellerId, ListingBucket bucket, String query) throws Exception {
        String like = likePattern(query);
        StringBuilder sb = new StringBuilder(
                "SELECT COUNT(*) FROM auction a JOIN auction_details d ON d.id = a.auction_id "
              + "WHERE a.seller_id = ?")
                .append(bucketPredicate(bucket == null ? ListingBucket.ALL : bucket));
        if (like != null) sb.append(" AND LOWER(d.title) LIKE ?");

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            ps.setInt(1, sellerId);
            if (like != null) ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Row totals for every bucket in one query, so the tab pills count the seller's whole
     * catalogue rather than whichever page happens to be on screen.
     *
     * <p>Honours {@code query} for the same reason: a search that shows "3 Active" while
     * displaying one row would be describing a different set than the one being listed.</p>
     */
    public java.util.Map<String, Integer> countByBucket(int sellerId, String query) throws Exception {
        String like = likePattern(query);
        String sql =
            "SELECT COUNT(*) AS all_count, "
          + "  COUNT(*) FILTER (WHERE a.status_id = 4 OR (a.status_id = 1 AND a.date_end > CURRENT_TIMESTAMP)) AS active_count, "
          + "  COUNT(*) FILTER (WHERE " + EFFECTIVELY_ENDED + " AND " + HAS_BIDS + ") AS finished_count, "
          + "  COUNT(*) FILTER (WHERE " + EFFECTIVELY_ENDED + " AND NOT " + HAS_BIDS + ") AS unsold_count, "
          + "  COUNT(*) FILTER (WHERE a.status_id = 3) AS cancelled_count "
          + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
          + "WHERE a.seller_id = ?"
          + (like != null ? " AND LOWER(d.title) LIKE ?" : "");

        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sellerId);
            if (like != null) ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    out.put("ALL", rs.getInt("all_count"));
                    out.put("ACTIVE", rs.getInt("active_count"));
                    out.put("FINISHED", rs.getInt("finished_count"));
                    out.put("UNSOLD", rs.getInt("unsold_count"));
                    out.put("CANCELLED", rs.getInt("cancelled_count"));
                }
            }
        }
        return out;
    }

    private static SellerAuctionRow mapRow(ResultSet rs) throws SQLException {
        return new SellerAuctionRow(
                rs.getLong("auction_id"),
                rs.getString("title"),
                rs.getBigDecimal("starting_price"),
                rs.getBigDecimal("max_price"),
                rs.getBigDecimal("current_bid"),
                rs.getInt("bid_count"),
                rs.getTimestamp("start_date").toInstant(),
                rs.getTimestamp("date_end").toInstant(),
                rs.getString("status_name"),
                rs.getInt("quantity"),
                rs.getString("thumbnail_url"),
                rs.getInt("watch_count"),
                rs.getString("listing_kind"));
    }

    // ------------------------------------------------------------------ relist

    /**
     * Relists a CANCELLED or FINISHED auction owned by {@code sellerId} by resetting it
     * to PENDING status and clearing the cancel reason.
     *
     * <p>Also restores at least one unit of stock. Two paths can now leave a concluded
     * listing at {@code quantity = 0} — the seller removing the final item, and a sale
     * decrementing the last unit — and relisting is the seller saying they have the item
     * to sell again, so a relist that produced a live auction with nothing behind it would
     * be the one way {@code quantity = 0} could reach an ACTIVE row. {@code GREATEST} rather
     * than a flat 1 so a seller relisting a five-unit lot keeps their five units.</p>
     *
     * @return true if a row was updated
     */
    public boolean relistAuction(long auctionId, int sellerId) throws Exception {
        String sql = "UPDATE auction SET status_id = ?, cancel_reason = NULL "
                   + "WHERE auction_id = ? AND seller_id = ? AND status_id IN (?, ?)";
        try (Connection conn = DBUtil.connectDB()) {
            conn.setAutoCommit(false);
            try {
                boolean relisted;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, AuctionStatus.PENDING.getId());
                    ps.setLong(2, auctionId);
                    ps.setInt(3, sellerId);
                    ps.setInt(4, AuctionStatus.CANCELLED.getId());
                    ps.setInt(5, AuctionStatus.FINISHED.getId());
                    relisted = ps.executeUpdate() > 0;
                }
                if (relisted) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE auction_details SET quantity = GREATEST(quantity, 1) WHERE id = ?")) {
                        ps.setLong(1, auctionId);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
                return relisted;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ private helpers

    private boolean isEditableBy(Connection conn, long auctionId, int sellerId) throws Exception {
        String sql = "SELECT 1 FROM auction WHERE auction_id = ? AND seller_id = ? AND status_id IN (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, sellerId);
            ps.setInt(3, AuctionStatus.ACTIVE.getId());
            ps.setInt(4, AuctionStatus.PENDING.getId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int countBidsConn(Connection conn, long auctionId) throws Exception {
        String sql = "SELECT COUNT(*) FROM bids WHERE auction_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Writes whichever of quantity / cost_price the seller actually supplied.
     *
     * <p>Built dynamically rather than as one fixed UPDATE because null here means "leave it
     * as it is", not "set it to NULL" — a form that omits cost price must not erase a cost
     * price that is already recorded. Clearing a cost price is a separate intent and is
     * expressed by the caller sending {@code 0}, which the column's
     * {@code cost_price >= 0} CHECK accepts.</p>
     */
    private void updateStockAndCost(Connection conn, long auctionId,
                                    Integer quantity, BigDecimal costPrice) throws Exception {
        if (quantity == null && costPrice == null) return;

        StringBuilder sql = new StringBuilder("UPDATE auction_details SET ");
        if (quantity != null) sql.append("quantity = ?");
        if (quantity != null && costPrice != null) sql.append(", ");
        if (costPrice != null) sql.append("cost_price = ?");
        sql.append(" WHERE id = ?");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            if (quantity != null)  ps.setInt(i++, quantity);
            if (costPrice != null) ps.setBigDecimal(i++, costPrice);
            ps.setLong(i, auctionId);
            if (ps.executeUpdate() == 0) throw new Exception("auction_details row not found");
        }
    }

    /**
     * Writes the descriptive fields.
     *
     * <p>{@code listingKind} is normalised and applied with {@code COALESCE(?, listing_kind)}
     * rather than written unconditionally, because null here means "the caller has no opinion"
     * — the legacy JSP form has no such field, and its edits must not reset a service back to
     * a product. The column is NOT NULL, so the COALESCE can only ever resolve to one of the
     * two values the CHECK constraint accepts.</p>
     */
    private void updateDetails(Connection conn, long auctionId,
                               String title, String description,
                               String category, String listingKind,
                               Integer itemConditionId) throws Exception {
        String sql = "UPDATE auction_details SET title = ?, description = ?, category = ?, "
                   + "listing_kind = COALESCE(?, listing_kind), item_condition_id = ? WHERE id = ?";
        ListingKind kind = ListingKind.parse(listingKind);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setString(2, description);
            ps.setString(3, category != null ? category : "");
            if (kind != null) ps.setString(4, kind.name());
            else ps.setNull(4, java.sql.Types.VARCHAR);
            if (itemConditionId != null) ps.setInt(5, itemConditionId);
            else ps.setNull(5, java.sql.Types.INTEGER);
            ps.setLong(6, auctionId);
            if (ps.executeUpdate() == 0) throw new Exception("auction_details row not found");
        }
    }

    /** Deletes image rows that belong to this auction only (auction_id guard prevents IDOR). */
    private void deleteImages(Connection conn, long auctionId,
                              List<Long> deleteImageIds) throws Exception {
        if (deleteImageIds == null || deleteImageIds.isEmpty()) return;
        String sql = "DELETE FROM auction_images WHERE id = ? AND auction_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Long imgId : deleteImageIds) {
                ps.setLong(1, imgId);
                ps.setLong(2, auctionId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertNewImages(Connection conn, long auctionId,
                                 List<String> filenames) throws Exception {
        if (filenames == null || filenames.isEmpty()) return;
        String sql = "INSERT INTO auction_images (auction_id, image_url, upload_date) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            for (String fn : filenames) {
                ps.setLong(1, auctionId);
                ps.setString(2, fn);
                ps.setTimestamp(3, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private List<AuctionEditData.ImageEntry> fetchImages(Connection conn,
                                                         long auctionId) throws Exception {
        String sql = "SELECT id, image_url FROM auction_images WHERE auction_id = ? ORDER BY upload_date";
        List<AuctionEditData.ImageEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AuctionEditData.ImageEntry(rs.getLong("id"), rs.getString("image_url")));
                }
            }
        }
        return list;
    }

    public List<Bid> getBidHistory(Long auctionId, Long sellerId) throws Exception
    {
        String sqlString = "SELECT b.user_id, b.bid_amount, b.bid_time " +
                "FROM bids b " +
                "JOIN auction a ON b.auction_id = a.auction_id " +
                "WHERE b.auction_id = ? AND a.seller_id = ?";
        try(Connection conn = DBUtil.connectDB())
        {
            try(PreparedStatement stmt = conn.prepareStatement(sqlString))
            {
                List<Bid> result = new ArrayList<>();
                stmt.setLong(1, auctionId);
                stmt.setLong(2, sellerId);
                try(ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Bid temp = new Bid();
                        temp.setUser_id(rs.getLong("user_id"));
                        temp.setBid_amount(rs.getFloat("bid_amount"));
                        temp.setBid_time(rs.getTimestamp("bid_time").toInstant());
                        result.add(temp);
                    }
                }
                return result;
            }
        }catch(Exception e)
        {
            throw new Exception("retrieve failed. try again", e);
        }
    }
    // ------------------------------------------------------------------ value types

    public static final class AuctionEditData {
        public final long auctionId;
        public final long sellerId;
        public final int statusId;
        public final String title;
        public final String description;
        public final String category;
        /** PRODUCT or SERVICE; a legacy row that predates the column reads back as PRODUCT. */
        public final String listingKind;
        public final int itemConditionId;
        public final BigDecimal maxPrice;   // null when no cap
        public final int quantity;
        public final BigDecimal costPrice;  // null when the seller never recorded one
        public final Instant startDate;
        public final Instant endDate;
        public final List<ImageEntry> images;

        public AuctionEditData(long auctionId, long sellerId, int statusId,
                               String title, String description, String category,
                               String listingKind,
                               int itemConditionId, BigDecimal maxPrice,
                               int quantity, BigDecimal costPrice,
                               Instant startDate, Instant endDate,
                               List<ImageEntry> images) {
            this.auctionId = auctionId;
            this.sellerId = sellerId;
            this.statusId = statusId;
            this.title = title;
            this.description = description;
            this.category = category;
            ListingKind parsedKind = ListingKind.parse(listingKind);
            this.listingKind = (parsedKind != null ? parsedKind : ListingKind.DEFAULT).name();
            this.itemConditionId = itemConditionId;
            this.maxPrice = maxPrice;
            this.quantity = quantity;
            this.costPrice = costPrice;
            this.startDate = startDate;
            this.endDate = endDate;
            this.images = images;
        }

        public static final class ImageEntry {
            public final long imageId;
            public final String imageUrl;

            public ImageEntry(long imageId, String imageUrl) {
                this.imageId = imageId;
                this.imageUrl = imageUrl;
            }
        }
    }
}
