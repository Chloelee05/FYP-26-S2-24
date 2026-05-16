package com.auction.dao;

import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Data-access layer for auto-bid (proxy bidding) storage and resolution (SCRUM-52).
 *
 * <h2>Encryption at rest (SCRUM-296)</h2>
 * <p>{@code max_amount_enc} and {@code note_enc} are stored as AES-256-GCM ciphertexts via
 * {@link SecurityUtil#encrypt(String)}. The buyer's bidding ceiling and any personal note are
 * never stored in plaintext. Decryption is performed exclusively inside this DAO; no raw
 * ciphertext is exposed to the servlet or JSP layer.</p>
 *
 * <h2>Proxy bidding algorithm (SCRUM-268)</h2>
 * <p>{@link #processAutoBids(Connection, long)} runs a loop (capped at {@value #MAX_ROUNDS}
 * rounds) to resolve auto-bid competition:</p>
 * <ol>
 *   <li>Read the current top bid (amount + bidder).</li>
 *   <li>Fetch and decrypt all {@code auto_bids} rows for this auction.</li>
 *   <li>Exclude rows whose {@code user_id} matches the current top bidder.</li>
 *   <li>Find the <em>winner</em>: the competing auto-bidder with the highest decrypted max
 *       (tie-break: earlier {@code created_at}).</li>
 *   <li>Compute the counter-bid:
 *       {@code counter = min(max(floor + 0.01, secondBestMax + 0.01), winner.max)},
 *       where {@code secondBestMax} is the highest max among all auto-bids <em>excluding
 *       the winner</em>. This lets the winner leapfrog competitors in a single round
 *       instead of incrementing penny-by-penny (SCRUM-270 efficient resolution).</li>
 *   <li>Insert the counter-bid and loop.</li>
 * </ol>
 *
 * <p>The loop terminates when no auto-bidder can beat the current top bid.
 * {@link #resolveNextAutoBid(List, BigDecimal, int)} is package-visible so the algorithm
 * can be unit-tested without a database (SCRUM-270).</p>
 */
public class AutoBidDAO {

    private static final Logger LOGGER = Logger.getLogger(AutoBidDAO.class.getName());

    /** Minimum bid step: one cent (matches {@code bids.bid_amount NUMERIC(10,2)}). */
    public static final BigDecimal MIN_INCREMENT = new BigDecimal("0.01");

    /** Safety cap on proxy-bidding rounds per trigger (prevents infinite loops). */
    static final int MAX_ROUNDS = 50;

    // -------------------------------------------------------------------------
    // Store / retrieve / delete
    // -------------------------------------------------------------------------

    /**
     * Creates or replaces the buyer's auto-bid for the given auction.
     *
     * <p>{@code maxAmount} is encrypted before storage (SCRUM-296).
     * {@code note}, if non-blank, is also encrypted.</p>
     *
     * @param auctionId auction the auto-bid applies to
     * @param userId    buyer setting the auto-bid (always from session, never from request)
     * @param maxAmount buyer's maximum bid ceiling; must be positive
     * @param note      optional private note; may be {@code null} or blank
     */
    public void upsert(long auctionId, int userId, BigDecimal maxAmount, String note) {
        String encAmount = SecurityUtil.encrypt(maxAmount.toPlainString());
        String encNote = (note != null && !note.isBlank())
                ? SecurityUtil.encrypt(note.trim()) : null;

        String sql =
                "INSERT INTO auto_bids (auction_id, user_id, max_amount_enc, note_enc) "
                + "VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (auction_id, user_id) DO UPDATE SET "
                + "  max_amount_enc = EXCLUDED.max_amount_enc, "
                + "  note_enc       = EXCLUDED.note_enc, "
                + "  updated_at     = CURRENT_TIMESTAMP";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, userId);
            ps.setString(3, encAmount);
            ps.setString(4, encNote);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the decrypted max-bid amount the user has set for this auction,
     * or {@code null} if the user has no auto-bid registered.
     */
    public BigDecimal getMaxAmountForUser(long auctionId, int userId) {
        String sql = "SELECT max_amount_enc FROM auto_bids WHERE auction_id = ? AND user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String enc = rs.getString("max_amount_enc");
                return new BigDecimal(SecurityUtil.decrypt(enc));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Removes the buyer's auto-bid for this auction (cancel auto-bid).
     *
     * @return {@code true} if a row was deleted; {@code false} if none existed
     */
    public boolean delete(long auctionId, int userId) {
        String sql = "DELETE FROM auto_bids WHERE auction_id = ? AND user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, userId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Proxy-bid resolution (SCRUM-268 / SCRUM-269)
    // -------------------------------------------------------------------------

    /**
     * Processes auto-bids for {@code auctionId} within the supplied JDBC transaction.
     *
     * <p>Called by {@link BidDAO#placeBid} immediately after a manual or auto-bid INSERT,
     * while the auction row lock ({@code SELECT … FOR UPDATE}) is still held. This prevents
     * concurrent bids from racing against the auto-bid loop.</p>
     *
     * @param conn      the live, open connection whose transaction must be managed by the caller
     * @param auctionId the auction to resolve auto-bids for
     * @return number of auto-bid inserts performed (0 = no auto-bids fired)
     */
    public int processAutoBids(Connection conn, long auctionId) throws SQLException {
        BigDecimal startingPrice = fetchStartingPrice(conn, auctionId);
        int placed = 0;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            // Current top bid
            BigDecimal topAmount = null;
            int topBidderId = -1;
            String topSql =
                    "SELECT bid_amount, user_id FROM bids "
                    + "WHERE auction_id = ? ORDER BY bid_amount DESC, bid_time ASC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(topSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        topAmount = rs.getBigDecimal("bid_amount");
                        topBidderId = rs.getInt("user_id");
                    }
                }
            }
            BigDecimal floor = (topAmount == null) ? startingPrice : topAmount.max(startingPrice);

            // Decrypt all auto-bids
            List<AutoBidRow> allBids = fetchAllDecrypted(conn, auctionId);

            CounterBid next = resolveNextAutoBid(allBids, floor, topBidderId);
            if (next == null) break;

            // Insert the counter-bid within the caller's transaction
            String insertSql =
                    "INSERT INTO bids (auction_id, user_id, bid_amount, bid_time) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, auctionId);
                ps.setInt(2, next.userId);
                ps.setBigDecimal(3, next.amount);
                ps.executeUpdate();
            }
            LOGGER.info(String.format(
                    "Auto-bid placed [auctionId=%d, buyerId=%d, amount=%s].",
                    auctionId, next.userId, next.amount.toPlainString()));
            placed++;
        }
        return placed;
    }

    // -------------------------------------------------------------------------
    // Algorithm (package-visible for unit testing — SCRUM-270)
    // -------------------------------------------------------------------------

    /**
     * Pure-function core of the proxy-bidding algorithm; no I/O.
     *
     * <p>Given all current auto-bids (with decrypted max amounts), the current price floor,
     * and who currently holds the top bid, returns the next counter-bid to insert — or
     * {@code null} if no auto-bid fires.</p>
     *
     * <h3>Efficient resolution (SCRUM-270)</h3>
     * <p>The winner bids just above the <em>second-best</em> competitor's max (not just
     * {@code floor + 0.01}). This collapses a two-auto-bidder cascade into a single round:
     * B($150) vs A($100) at floor $10 → B bids $100.01 directly (not $10.01, $10.02, …).</p>
     *
     * @param allBids          all auto-bid rows for this auction (may include top bidder's own row)
     * @param floor            current bid floor (top bid or starting price, whichever is higher)
     * @param currentTopBidder user ID of who currently holds the top bid; {@code -1} if no bids
     * @return counter-bid to place, or {@code null} if no auto-bid can fire
     */
    public static CounterBid resolveNextAutoBid(
            List<AutoBidRow> allBids, BigDecimal floor, int currentTopBidder) {

        if (allBids == null || allBids.isEmpty()) return null;

        // Competing auto-bids: excludes the current top bidder, must have max > floor
        List<AutoBidRow> competitors = new ArrayList<>();
        for (AutoBidRow b : allBids) {
            if (b.userId != currentTopBidder && b.maxAmount.compareTo(floor) > 0) {
                competitors.add(b);
            }
        }
        if (competitors.isEmpty()) return null;

        // Winner = highest max; tie-break by earliest created_at
        competitors.sort(Comparator
                .comparing(AutoBidRow::getMaxAmount).reversed()
                .thenComparing(AutoBidRow::getCreatedAt));
        AutoBidRow winner = competitors.get(0);

        // secondBestMax = highest max among ALL other auto-bids (including current top bidder's)
        BigDecimal secondBestMax = floor;
        for (AutoBidRow b : allBids) {
            if (b.userId != winner.userId && b.maxAmount.compareTo(secondBestMax) > 0) {
                secondBestMax = b.maxAmount;
            }
        }

        // Counter bid: just above secondBest (or just above floor), capped at winner's max
        BigDecimal minNeeded = floor.add(MIN_INCREMENT);
        BigDecimal aboveSecond = secondBestMax.add(MIN_INCREMENT);
        BigDecimal counter = minNeeded.max(aboveSecond).min(winner.maxAmount);

        // Edge: if counter ≤ floor (shouldn't happen given filters above), bail out
        if (counter.compareTo(floor) <= 0) return null;

        return new CounterBid(winner.userId, counter);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private List<AutoBidRow> fetchAllDecrypted(Connection conn, long auctionId)
            throws SQLException {
        List<AutoBidRow> rows = new ArrayList<>();
        String sql = "SELECT user_id, max_amount_enc, created_at FROM auto_bids WHERE auction_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int uid = rs.getInt("user_id");
                    String enc = rs.getString("max_amount_enc");
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    try {
                        BigDecimal max = new BigDecimal(SecurityUtil.decrypt(enc));
                        rows.add(new AutoBidRow(uid, max, createdAt));
                    } catch (Exception e) {
                        // Corrupt ciphertext — skip and log; don't fail the whole transaction
                        LOGGER.warning(String.format(
                                "AutoBidDAO: could not decrypt max_amount for user %d on auction %d; skipping.",
                                uid, auctionId));
                    }
                }
            }
        }
        return rows;
    }

    private BigDecimal fetchStartingPrice(Connection conn, long auctionId) throws SQLException {
        String sql = "SELECT starting_price FROM auction_details WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal sp = rs.getBigDecimal("starting_price");
                    return sp != null ? sp : BigDecimal.ZERO;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    // -------------------------------------------------------------------------
    // Value objects
    // -------------------------------------------------------------------------

    /** An auto-bid row with its decrypted max amount. */
    public static final class AutoBidRow {
        private final int userId;
        private final BigDecimal maxAmount;
        private final Instant createdAt;

        public AutoBidRow(int userId, BigDecimal maxAmount, Instant createdAt) {
            this.userId = userId;
            this.maxAmount = maxAmount;
            this.createdAt = createdAt;
        }

        public int getUserId()          { return userId; }
        public BigDecimal getMaxAmount(){ return maxAmount; }
        public Instant getCreatedAt()   { return createdAt; }
    }

    /** The result of {@link #resolveNextAutoBid}: who bids and how much. */
    public static final class CounterBid {
        public final int userId;
        public final BigDecimal amount;

        public CounterBid(int userId, BigDecimal amount) {
            this.userId = userId;
            this.amount = amount;
        }
    }
}
