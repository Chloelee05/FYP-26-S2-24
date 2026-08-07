package com.auction.dao;

import com.auction.model.AuctionBidHistoryEntry;
import com.auction.model.AuctionDetail;
import com.auction.model.AuctionStatus;
import com.auction.model.AuctionType;
import com.auction.model.Bid;
import com.auction.model.ItemCondition;
import com.auction.util.DBUtil;
import com.auction.util.DutchClock;
import com.auction.util.SecurityUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for bid placement (SCRUM-51), covering all three auction types plus the
 * public auction detail and bid history reads.
 *
 * <p>Writes {@code bids} and, on a concluding action, {@code auction.status_id} and
 * {@code auction_details.winner_id}/{@code winning_bid}. Reads {@code auction},
 * {@code auction_details}, {@code users} and {@code auction_images}. Called by
 * {@code BidApiServlet} and {@code AuctionApiServlet}, and by the legacy {@code /protected/bid}
 * servlet. Collaborates with {@link AutoBidDAO} for proxy bidding and {@link PlatformSettingsDAO}
 * for the rate-limit window.</p>
 *
 * <p>Each auction type has its own entry point, because the mechanisms are not variations of one
 * another: {@link #placeBid} for ascending, {@link #acceptDutchBid} for the declining clock,
 * {@link #placeSealedBid} for blind, and {@link #buyItNow} for the fixed-price shortcut. Every one
 * of them re-checks the auction type, so calling the wrong one is refused rather than misbehaving.</p>
 *
 * <p>Transactional safety (SCRUM-263): {@link #placeBid} opens a single
 * JDBC transaction, acquires a row-level lock on the {@code auction} row via
 * {@code SELECT … FOR UPDATE}, re-validates all preconditions inside the lock,
 * then inserts the bid or rolls back. This prevents TOCTOU races on concurrent
 * bids (SCRUM-265).</p>
 *
 * <p>Minimum increment (SCRUM-263): A new bid must exceed the greater of the
 * current highest bid and the starting price. Because {@code bids.bid_amount} is
 * {@code NUMERIC(10,2)}, the effective minimum meaningful step is {@code 0.01}.
 * Equal bids are always rejected ({@code >}, not {@code >=}).</p>
 *
 * <p>Auto-bid integration (SCRUM-52): After each successful manual bid insert,
 * {@link AutoBidDAO#processAutoBids(Connection, long)} is called within the same
 * transaction to fire any proxy counter-bids before the lock is released.</p>
 *
 * <p>Max-price cap (SCRUM-263): The seller-set ceiling from
 * {@code auction_details.max_price} is re-checked inside the transaction.</p>
 *
 * <p>Blind confidentiality: a live sealed auction must never reveal what anyone bid.
 * {@link #HIDE_LIVE_SEALED_BIDS} enforces that in SQL on the history queries, and
 * {@link #placeBid} refuses blind auctions outright so a rejection message cannot be used to
 * probe the leading amount.</p>
 *
 * <p>IDOR prevention (SCRUM-295): {@code buyerId} is <em>always</em> taken
 * from the session (never from a request parameter); {@code auctionId} is parsed
 * as {@code long} (rejects non-numeric input) and then looked up in the DB.</p>
 */
public class BidDAO {

    /** Maximum page size for public bid history (SCRUM-58). */
    public static final int MAX_BID_HISTORY_PAGE_SIZE = 50;

    /** Fallback rate-limit window when {@code platform_settings} has no row yet. */
    public static final int DEFAULT_BID_RATE_LIMIT_SECONDS = 3;

    /**
     * NEW for the "platform-wide auction rules" admin story: fallback minimum bid increment
     * when {@code platform_settings} has no row yet, matching the seeded default in
     * {@code migration_platform_auction_rules.sql} exactly. Set to one cent, the smallest step
     * {@code bids.bid_amount NUMERIC(10,2)} can represent — i.e. today's existing, unwritten
     * floor from the SCRUM-263 class comment above — so a database that has not run that
     * migration yet still behaves exactly as it does today.
     */
    public static final BigDecimal DEFAULT_MIN_BID_INCREMENT = new BigDecimal("0.01");

    private final AutoBidDAO autoBidDAO;
    private final PlatformSettingsDAO platformSettingsDAO;

    public BidDAO() {
        this.autoBidDAO = new AutoBidDAO();
        this.platformSettingsDAO = new PlatformSettingsDAO();
    }

    /** Injection constructor for testing (allows mocking {@link AutoBidDAO}). */
    public BidDAO(AutoBidDAO autoBidDAO) {
        this.autoBidDAO = autoBidDAO;
        this.platformSettingsDAO = new PlatformSettingsDAO();
    }

    /** Injection constructor for testing (allows mocking both collaborators). */
    public BidDAO(AutoBidDAO autoBidDAO, PlatformSettingsDAO platformSettingsDAO) {
        this.autoBidDAO = autoBidDAO;
        this.platformSettingsDAO = platformSettingsDAO;
    }

    /** Outcome codes returned by {@link #placeBid}. */
    public enum BidResult {
        SUCCESS,
        AUCTION_NOT_FOUND,
        /** Auction status is not ACTIVE, or end date has passed. */
        AUCTION_CLOSED,
        /** Auction moderation state is not {@code 'active'}. */
        AUCTION_REMOVED,
        /** The bidder is the seller of this auction (self-bid disallowed). */
        SELF_BID,
        /** Bid amount ≤ current floor (current highest bid or starting price). */
        BID_TOO_LOW,
        /**
         * This buyer placed a bid on this same auction less than the configured rate-limit
         * window ago (anti-spam; see {@code platform_settings.bid_rate_limit_seconds}). This is
         * not anti-sniping. The project's answer to sniping is proxy auto-bid, unchanged here.
         */
        BID_TOO_FAST,
        /** Bid amount exceeds the seller-set max-price cap. */
        EXCEEDS_MAX_PRICE,
        /** Sealed (blind) auction: this buyer has already submitted a bid. */
        ALREADY_BID,
        /** Wrong strategy for the requested action (e.g. accept on a non-Dutch auction). */
        WRONG_AUCTION_TYPE
    }

    /**
     * What {@link #placeBid} did, including who lost the lead because of it.
     *
     * <p>The leader is captured twice, before the manual bid is inserted and again after
     * {@link AutoBidDAO#processAutoBids} has resolved every proxy counter-bid, because those
     * two facts together are the only reliable way to know who was displaced. Reading the bid
     * table afterwards cannot tell them apart: by the time the transaction commits, an
     * auto-bidder who counter-bid is simultaneously the current leader and the highest bidder
     * other than the caller, so any "runner-up" query names the winner.</p>
     */
    public static final class BidOutcome {
        public final BidResult result;
        /** Who held the top bid before this bid was placed; {@code null} if there were none. */
        public final Integer previousTopBidderId;
        /** Who holds the top bid now, after proxy auto-bids; {@code null} if there are none. */
        public final Integer finalTopBidderId;
        /** The buyer whose manual bid this was. */
        public final int buyerId;

        BidOutcome(BidResult result, Integer previousTopBidderId, Integer finalTopBidderId, int buyerId) {
            this.result = result;
            this.previousTopBidderId = previousTopBidderId;
            this.finalTopBidderId = finalTopBidderId;
            this.buyerId = buyerId;
        }

        /** A rejection, with no leader information to report. */
        public static BidOutcome of(BidResult result) {
            return new BidOutcome(result, null, null, 0);
        }

        public boolean isSuccess() {
            return result == BidResult.SUCCESS;
        }

        /**
         * The bidder this bid actually knocked off the top, or {@code null} when nobody was.
         *
         * <p>Two cases. If the caller's bid still stands, they took the lead from whoever held
         * it before. If it does not, a proxy auto-bid outbid them within the same transaction,
         * and the person displaced is the caller themselves, which is exactly the case the
         * old runner-up lookup got backwards.</p>
         *
         * <p>Returns {@code null} when the answer would be the current leader, so nobody is
         * ever told they were outbid by themselves.</p>
         */
        public Integer displacedBidderId() {
            // No known leader means no leader information was captured, not that the caller
            // displaced somebody. A successful bid always leaves someone on top.
            if (!isSuccess() || finalTopBidderId == null) {
                return null;
            }
            Integer displaced = finalTopBidderId == buyerId
                    ? previousTopBidderId
                    : Integer.valueOf(buyerId);
            if (displaced == null || displaced.equals(finalTopBidderId)) {
                return null;
            }
            return displaced;
        }
    }

    // -------------------------------------------------------------------------
    // Place bid (SCRUM-263 + SCRUM-265)
    // -------------------------------------------------------------------------

    /**
     * Atomically places a bid on the auction identified by {@code auctionId}.
     *
     * <p>All validations run inside a single serializable transaction protected by
     * {@code SELECT … FOR UPDATE} on the auction row. Concurrent callers block
     * until the lock is released, which prevents duplicate-amount bids (SCRUM-265).</p>
     *
     * <p>Ascending and Dutch auctions only. Blind is refused here, see the guard below.</p>
     *
     * @param auctionId  ID of the target auction (parsed server-side, not trusted from client)
     * @param buyerId    ID of the authenticated buyer (read from session, not from request)
     * @param bidAmount  proposed bid amount; must be positive
     * @return a {@link BidOutcome} carrying success or the specific rejection reason, plus
     *         the leader before and after auto-bid resolution
     */
    public BidOutcome placeBid(long auctionId, int buyerId, BigDecimal bidAmount) {
        if (bidAmount == null || bidAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BidOutcome.of(BidResult.BID_TOO_LOW);
        }

        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            // SCRUM-265: lock the auction row to serialize concurrent bids. Everything the
            // validation needs is read in this one locked statement: lifecycle state, moderation
            // state, owner, type, and the two prices. Holding the lock from here to the commit is
            // what makes the floor check and the insert a single indivisible step.
            String lockSql =
                    "SELECT a.auction_id, a.status_id, a.date_end, "
                    + "a.moderation_state, a.seller_id, a.auction_type, "
                    + "d.starting_price, d.max_price "
                    + "FROM auction a "
                    + "JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.auction_id = ? "
                    + "FOR UPDATE";

            int typeId;
            int statusId;
            Instant dateEnd;
            String moderationState;
            int sellerId;
            BigDecimal startingPrice;
            BigDecimal maxPrice;

            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return BidOutcome.of(BidResult.AUCTION_NOT_FOUND);
                    }
                    statusId = rs.getInt("status_id");
                    dateEnd = rs.getTimestamp("date_end").toInstant();
                    moderationState = rs.getString("moderation_state");
                    sellerId = rs.getInt("seller_id");
                    typeId = rs.getInt("auction_type");
                    startingPrice = rs.getBigDecimal("starting_price");
                    if (startingPrice == null) startingPrice = BigDecimal.ZERO;
                    maxPrice = rs.getBigDecimal("max_price"); // null = no cap
                }
            }

            // An ascending bid has no meaning on a sealed auction, and running one anyway
            // reveals the thing the mechanism exists to hide: the floor below is
            // MAX(bid_amount), so BID_TOO_LOW answers "is the top sealed bid above X?" for
            // any X the caller cares to try, and a few probes give up the leading bid.
            // BidApiServlet routes BLIND to placeSealedBid, but the legacy /protected/bid
            // servlet calls this method for every auction type, so the guard belongs here
            // with the ones acceptDutchBid, buyItNow and placeSealedBid already carry.
            if (typeId == AuctionType.BLIND.getId()) {
                conn.rollback();
                return BidOutcome.of(BidResult.WRONG_AUCTION_TYPE);
            }

            // SCRUM-263: auction must be ACTIVE and not expired
            if (statusId != AuctionStatus.ACTIVE.getId() || Instant.now().isAfter(dateEnd)) {
                conn.rollback();
                return BidOutcome.of(BidResult.AUCTION_CLOSED);
            }
            // A listing an admin flagged or removed stays visible but stops taking bids.
            if (!"active".equals(moderationState)) {
                conn.rollback();
                return BidOutcome.of(BidResult.AUCTION_REMOVED);
            }
            // SCRUM-266: self-bid guard
            if (sellerId == buyerId) {
                conn.rollback();
                return BidOutcome.of(BidResult.SELF_BID);
            }

            // Anti-spam rate limit: reject a repeat bid from this same buyer, on this same
            // auction, inside the configured window. Scoped to (buyerId, auctionId) only:
            // it reads that buyer's own last bid_time on this auction, so a rejected fast bid
            // never writes any state and can never block a different buyer or a different
            // auction. This is rate limiting, not anti-sniping: the auction clock and the
            // proxy auto-bid path are both untouched.
            int rateLimitSeconds = platformSettingsDAO.getInt(
                    "bid_rate_limit_seconds", DEFAULT_BID_RATE_LIMIT_SECONDS);
            if (rateLimitSeconds > 0) {
                Instant lastBidTime = lastBidTime(conn, auctionId, buyerId);
                if (lastBidTime != null
                        && Instant.now().isBefore(lastBidTime.plusSeconds(rateLimitSeconds))) {
                    conn.rollback();
                    return BidOutcome.of(BidResult.BID_TOO_FAST);
                }
            }

            // Current highest bid, read under the same lock so nothing can slip in between this
            // and the insert. Null when nobody has bid yet.
            BigDecimal currentMax;
            String maxBidSql = "SELECT MAX(bid_amount) FROM bids WHERE auction_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(maxBidSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    currentMax = rs.next() ? rs.getBigDecimal(1) : null;
                }
            }

            // Floor = max(startingPrice, currentMax). Taking the maximum covers a historic bid
            // recorded below the listing's own opening price, which would otherwise lower the bar.
            BigDecimal floor = (currentMax == null) ? startingPrice
                    : currentMax.max(startingPrice);

            // NEW for the "platform-wide auction rules" admin story: a platform-wide minimum
            // bid increment over the floor, read fresh from PlatformSettingsDAO exactly like the
            // rate limit above. Deliberately its own isolated early return rather than folded
            // into the existing floor comparison just below, so that check's own SCRUM-263 intent
            // ("strictly greater than floor") stays literally unmodified; this can only make that
            // check stricter, never bypass it. It has to come after floor is computed, so it
            // cannot sit any earlier in the method.
            //
            // Applies to placeBid (ascending, PRICE_UP) only. DUTCH_AUCTION's price is computed
            // by DutchClock with no buyer-chosen amount at all (acceptDutchBid), and BLIND's
            // sealed bid (placeSealedBid) is compared only against the starting price, never
            // against a visible current bid, since revealing "is your bid within one increment
            // of the leader" would leak the same hidden amount the mechanism exists to protect.
            // Neither has a "step above the previous highest" to enforce, so this guard leaves
            // both untouched. With the seeded default (0.01) equal to the pre-existing effective
            // floor, this changes nothing until an admin actually raises it.
            BigDecimal minIncrement = platformSettingsDAO.getBigDecimal(
                    "min_bid_increment", DEFAULT_MIN_BID_INCREMENT);
            if (minIncrement != null && minIncrement.compareTo(BigDecimal.ZERO) > 0
                    && bidAmount.compareTo(floor.add(minIncrement)) < 0) {
                conn.rollback();
                return BidOutcome.of(BidResult.BID_TOO_LOW);
            }

            // SCRUM-263/SCRUM-267: bid must be strictly greater than floor
            if (bidAmount.compareTo(floor) <= 0) {
                conn.rollback();
                return BidOutcome.of(BidResult.BID_TOO_LOW);
            }

            // Max-price cap check (SCRUM-263)
            if (maxPrice != null && bidAmount.compareTo(maxPrice) > 0) {
                conn.rollback();
                return BidOutcome.of(BidResult.EXCEEDS_MAX_PRICE);
            }

            // Who is being displaced has to be read before the insert: afterwards the caller
            // is the leader and the previous holder is indistinguishable from any other bidder.
            Integer previousTopBidder = topBidderId(conn, auctionId);

            // All checks passed, so the manual bid goes in.
            String insertSql =
                    "INSERT INTO bids (auction_id, user_id, bid_amount, bid_time) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, auctionId);
                ps.setInt(2, buyerId);
                ps.setBigDecimal(3, bidAmount);
                ps.executeUpdate();
            }

            // SCRUM-52: trigger proxy auto-bids within the same transaction
            autoBidDAO.processAutoBids(conn, auctionId);

            // Read the leader again: a proxy auto-bid may already have taken the lead back
            // off the caller, in which case the caller is the one who has been outbid.
            Integer finalTopBidder = topBidderId(conn, auctionId);

            conn.commit();
            return new BidOutcome(BidResult.SUCCESS, previousTopBidder, finalTopBidder, buyerId);

        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) { }
            }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException ignored) { }
            }
        }
    }

    /**
     * Who holds the top bid, read inside the caller's transaction, or {@code null} when there
     * are no bids.
     *
     * <p>Ordered {@code bid_amount DESC, bid_time ASC} to match {@code AuctionFinalizer} and
     * {@link AutoBidDAO#processAutoBids}, so whoever this returns is the person who
     * would win if the auction ended now. Ties on time cannot decide anything here because
     * every bid on an auction has a distinct amount.</p>
     */
    public static Integer topBidderId(Connection conn, long auctionId) throws SQLException {
        String sql = "SELECT user_id FROM bids WHERE auction_id = ? "
                + "ORDER BY bid_amount DESC, bid_time ASC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("user_id") : null;
            }
        }
    }

    /**
     * The most recent {@code bid_time} this buyer has on this auction, or {@code null} when
     * they have not bid on it yet. Read inside the caller's locked transaction so the rate
     * limit check sees a consistent view alongside the floor check.
     */
    static Instant lastBidTime(Connection conn, long auctionId, int buyerId) throws SQLException {
        String sql = "SELECT MAX(bid_time) FROM bids WHERE auction_id = ? AND user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, buyerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    return ts != null ? ts.toInstant() : null;
                }
            }
        }
        return null;
    }

    /** Returns the {@code auction_type} id, or -1 when the auction does not exist. */
    public int getAuctionTypeId(long auctionId) {
        String sql = "SELECT auction_type FROM auction WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Dutch auction: accept the current descending clock price (first acceptance wins)
    // -------------------------------------------------------------------------

    /**
     * Accepts the current Dutch clock price for {@code auctionId}. The first valid
     * acceptance records a winning bid at the computed clock price and finishes the
     * auction. Row-locked to serialise concurrent acceptances (only the first wins).
     *
     * <p>The price is not sent by the client. It is recomputed here from the listing's start
     * price, floor price and the two timestamps through {@link DutchClock}, the same shared
     * calculation the list and detail pages display, so a buyer cannot claim a lower figure than
     * the clock actually shows. The insert, the finish, the stock decrement and the order creation
     * are one transaction.</p>
     */
    public BidResult acceptDutchBid(long auctionId, int buyerId) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            String lockSql =
                    "SELECT a.status_id, a.date_created, a.date_end, a.moderation_state, "
                    + "a.seller_id, a.auction_type, d.starting_price, d.dutch_floor_price "
                    + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.auction_id = ? FOR UPDATE";

            int statusId, sellerId, typeId;
            Instant dateCreated, dateEnd;
            String moderationState;
            BigDecimal startingPrice, dutchFloor;
            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return BidResult.AUCTION_NOT_FOUND; }
                    statusId = rs.getInt("status_id");
                    dateCreated = rs.getTimestamp("date_created").toInstant();
                    dateEnd = rs.getTimestamp("date_end").toInstant();
                    moderationState = rs.getString("moderation_state");
                    sellerId = rs.getInt("seller_id");
                    typeId = rs.getInt("auction_type");
                    startingPrice = rs.getBigDecimal("starting_price");
                    if (startingPrice == null) startingPrice = BigDecimal.ZERO;
                    dutchFloor = rs.getBigDecimal("dutch_floor_price");
                }
            }

            if (typeId != AuctionType.DUTCH_AUCTION.getId()) { conn.rollback(); return BidResult.WRONG_AUCTION_TYPE; }
            if (statusId != AuctionStatus.ACTIVE.getId() || Instant.now().isAfter(dateEnd)) {
                conn.rollback(); return BidResult.AUCTION_CLOSED;
            }
            if (!"active".equals(moderationState)) { conn.rollback(); return BidResult.AUCTION_REMOVED; }
            if (sellerId == buyerId) { conn.rollback(); return BidResult.SELF_BID; }

            BigDecimal clockPrice = DutchClock.currentPrice(
                    startingPrice, dutchFloor, dateCreated, dateEnd, Instant.now());

            String insertSql = "INSERT INTO bids (auction_id, user_id, bid_amount, bid_time) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, auctionId);
                ps.setInt(2, buyerId);
                ps.setBigDecimal(3, clockPrice);
                ps.executeUpdate();
            }

            // First acceptance ends the auction and records the winner.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auction SET status_id = ? WHERE auction_id = ?")) {
                ps.setInt(1, AuctionStatus.FINISHED.getId());
                ps.setLong(2, auctionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auction_details SET winner_id = ?, winning_bid = ? WHERE id = ?")) {
                ps.setInt(1, buyerId);
                // The clock price to the cent. A descending clock lands on fractional figures
                // almost by definition, so rounding here charged the buyer something they never
                // accepted; winning_bid is NUMERIC(12,2) as of
                // migration_seller_maintain_listing.sql.
                ps.setBigDecimal(2, clockPrice.setScale(2, java.math.RoundingMode.HALF_UP));
                ps.setLong(3, auctionId);
                ps.executeUpdate();
            }
            // Both run on this connection, so a Dutch acceptance either takes stock and produces an
            // order or does neither.
            SellerAuctionDAO.decrementStockForSale(conn, auctionId);
            new OrderDAO().ensureOrderForAuction(conn, auctionId);

            conn.commit();
            return BidResult.SUCCESS;
        } catch (Exception e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) { } }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Buy It Now (SCRUM-40): purchase at fixed BIN price on ascending auctions
    // -------------------------------------------------------------------------

    /**
     * Purchases an ACTIVE ascending auction at its Buy It Now price. Mirrors
     * {@link #acceptDutchBid}: row-lock, insert winning bid, finish auction, create order.
     */
    public BidResult buyItNow(long auctionId, int buyerId) {
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            String lockSql =
                    "SELECT a.status_id, a.date_end, a.moderation_state, a.seller_id, a.auction_type, "
                    + "d.buy_it_now_price "
                    + "FROM auction a JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.auction_id = ? FOR UPDATE";

            int statusId, sellerId, typeId;
            Instant dateEnd;
            String moderationState;
            BigDecimal binPrice;
            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return BidResult.AUCTION_NOT_FOUND; }
                    statusId = rs.getInt("status_id");
                    dateEnd = rs.getTimestamp("date_end").toInstant();
                    moderationState = rs.getString("moderation_state");
                    sellerId = rs.getInt("seller_id");
                    typeId = rs.getInt("auction_type");
                    binPrice = rs.getBigDecimal("buy_it_now_price");
                }
            }

            if (typeId != AuctionType.PRICE_UP.getId()) { conn.rollback(); return BidResult.WRONG_AUCTION_TYPE; }
            if (binPrice == null || binPrice.compareTo(BigDecimal.ZERO) <= 0) {
                conn.rollback();
                return BidResult.WRONG_AUCTION_TYPE;
            }
            if (statusId != AuctionStatus.ACTIVE.getId() || Instant.now().isAfter(dateEnd)) {
                conn.rollback(); return BidResult.AUCTION_CLOSED;
            }
            if (!"active".equals(moderationState)) { conn.rollback(); return BidResult.AUCTION_REMOVED; }
            if (sellerId == buyerId) { conn.rollback(); return BidResult.SELF_BID; }

            String insertSql = "INSERT INTO bids (auction_id, user_id, bid_amount, bid_time) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, auctionId);
                ps.setInt(2, buyerId);
                ps.setBigDecimal(3, binPrice);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auction SET status_id = ? WHERE auction_id = ?")) {
                ps.setInt(1, AuctionStatus.FINISHED.getId());
                ps.setLong(2, auctionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auction_details SET winner_id = ?, winning_bid = ? WHERE id = ?")) {
                ps.setInt(1, buyerId);
                // The advertised Buy It Now price to the cent, so the order matches the price
                // the buyer was shown when they clicked.
                ps.setBigDecimal(2, binPrice.setScale(2, java.math.RoundingMode.HALF_UP));
                ps.setLong(3, auctionId);
                ps.executeUpdate();
            }
            SellerAuctionDAO.decrementStockForSale(conn, auctionId);
            new OrderDAO().ensureOrderForAuction(conn, auctionId);

            conn.commit();
            return BidResult.SUCCESS;
        } catch (Exception e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) { } }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Blind (sealed-bid) auction: one hidden bid per buyer; revealed at close
    // -------------------------------------------------------------------------

    /**
     * Returns the given buyer's (sealed) bid amount on an auction, or null if they
     * have not bid. Used to show a "sealed bid submitted" state on blind auctions.
     */
    public BigDecimal getUserBidAmount(long auctionId, int userId) {
        String sql = "SELECT bid_amount FROM bids WHERE auction_id = ? AND user_id = ? "
                + "ORDER BY bid_amount DESC LIMIT 1";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal("bid_amount") : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Records a sealed bid for a BLIND auction. No floor-vs-others check (bids are
     * hidden); the amount must merely meet the starting price. Each buyer may submit
     * only one sealed bid.
     */
    public BidResult placeSealedBid(long auctionId, int buyerId, BigDecimal bidAmount) {
        if (bidAmount == null || bidAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BidResult.BID_TOO_LOW;
        }
        Connection conn = null;
        try {
            conn = DBUtil.connectDB();
            conn.setAutoCommit(false);

            String lockSql =
                    "SELECT a.status_id, a.date_end, a.moderation_state, a.seller_id, a.auction_type, "
                    + "d.starting_price FROM auction a JOIN auction_details d ON d.id = a.auction_id "
                    + "WHERE a.auction_id = ? FOR UPDATE";

            int statusId, sellerId, typeId;
            Instant dateEnd;
            String moderationState;
            BigDecimal startingPrice;
            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return BidResult.AUCTION_NOT_FOUND; }
                    statusId = rs.getInt("status_id");
                    dateEnd = rs.getTimestamp("date_end").toInstant();
                    moderationState = rs.getString("moderation_state");
                    sellerId = rs.getInt("seller_id");
                    typeId = rs.getInt("auction_type");
                    startingPrice = rs.getBigDecimal("starting_price");
                    if (startingPrice == null) startingPrice = BigDecimal.ZERO;
                }
            }

            if (typeId != AuctionType.BLIND.getId()) { conn.rollback(); return BidResult.WRONG_AUCTION_TYPE; }
            if (statusId != AuctionStatus.ACTIVE.getId() || Instant.now().isAfter(dateEnd)) {
                conn.rollback(); return BidResult.AUCTION_CLOSED;
            }
            if (!"active".equals(moderationState)) { conn.rollback(); return BidResult.AUCTION_REMOVED; }
            if (sellerId == buyerId) { conn.rollback(); return BidResult.SELF_BID; }
            if (bidAmount.compareTo(startingPrice) < 0) { conn.rollback(); return BidResult.BID_TOO_LOW; }

            // One sealed bid per buyer
            String existsSql = "SELECT 1 FROM bids WHERE auction_id = ? AND user_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(existsSql)) {
                ps.setLong(1, auctionId);
                ps.setInt(2, buyerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) { conn.rollback(); return BidResult.ALREADY_BID; }
                }
            }

            String insertSql = "INSERT INTO bids (auction_id, user_id, bid_amount, bid_time) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, auctionId);
                ps.setInt(2, buyerId);
                ps.setBigDecimal(3, bidAmount);
                ps.executeUpdate();
            }

            conn.commit();
            return BidResult.SUCCESS;
        } catch (Exception e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) { } }
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fetch auction detail for the public detail page (SCRUM-264)
    // -------------------------------------------------------------------------

    /**
     * Loads the full public detail of an auction by its ID.
     *
     * @param auctionId the auction to load
     * @return fully-populated {@link AuctionDetail}, or {@code null} if not found
     */
    public AuctionDetail findByIdForDisplay(long auctionId) {
        String sql =
                "SELECT a.auction_id, a.status_id, a.date_created, a.date_end, a.moderation_state, "
                + "a.seller_id, a.auction_type, "
                + "u.username AS seller_username, "
                + "d.title, d.description, d.category, d.item_condition_id, d.starting_price, d.max_price, "
                + "d.quantity, d.cost_price, d.dutch_floor_price, d.buy_it_now_price, d.listing_kind, "
                + "COALESCE(MAX(b.bid_amount), d.starting_price) AS current_bid, "
                + "COUNT(b.bid_id)::int AS bid_count "
                + "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "JOIN users u ON u.id = a.seller_id "
                + "LEFT JOIN bids b ON b.auction_id = a.auction_id "
                + "WHERE a.auction_id = ? "
                + "GROUP BY a.auction_id, a.status_id, a.date_created, a.date_end, a.moderation_state, "
                + "         a.seller_id, a.auction_type, u.username, d.title, d.description, d.category, "
                + "         d.item_condition_id, d.starting_price, d.max_price, "
                + "         d.quantity, d.cost_price, d.dutch_floor_price, d.buy_it_now_price, "
                + "         d.listing_kind";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                Instant dateEnd = rs.getTimestamp("date_end").toInstant();
                int statusId = rs.getInt("status_id");
                String modState = rs.getString("moderation_state");
                Instant dateCreatedInner = rs.getTimestamp("date_created") != null
                        ? rs.getTimestamp("date_created").toInstant() : Instant.EPOCH;
                Instant nowInner = Instant.now();
                boolean open = statusId == AuctionStatus.ACTIVE.getId()
                        && "active".equals(modState)
                        && nowInner.isBefore(dateEnd)
                        && !nowInner.isBefore(dateCreatedInner);

                BigDecimal startingPrice = rs.getBigDecimal("starting_price");
                if (startingPrice == null) startingPrice = BigDecimal.ZERO;
                BigDecimal currentBid = rs.getBigDecimal("current_bid");
                if (currentBid == null) currentBid = startingPrice;

                List<String> images = fetchImages(conn, auctionId);

                int conditionId = rs.getInt("item_condition_id");
                String conditionName;
                try {
                    conditionName = ItemCondition.getItemCondition(conditionId).getDisplayName();
                } catch (IllegalArgumentException e) {
                    conditionName = "";
                }

                AuctionDetail detail = new AuctionDetail(
                        rs.getLong("auction_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("category"),
                        conditionName,
                        startingPrice,
                        currentBid,
                        rs.getInt("bid_count"),
                        rs.getBigDecimal("max_price"),
                        dateEnd,
                        rs.getInt("seller_id"),
                        rs.getString("seller_username"),
                        images,
                        open);
                detail.setAuctionTypeId(rs.getInt("auction_type"));
                detail.setDutchFloorPrice(rs.getBigDecimal("dutch_floor_price"));
                detail.setBuyItNowPrice(rs.getBigDecimal("buy_it_now_price"));
                Timestamp created = rs.getTimestamp("date_created");
                detail.setDateCreated(created != null ? created.toInstant() : null);
                detail.setQuantity(rs.getInt("quantity"));
                detail.setCostPrice(rs.getBigDecimal("cost_price"));
                detail.setListingKind(rs.getString("listing_kind"));
                return detail;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> fetchImages(Connection conn, long auctionId) throws SQLException {
        List<String> urls = new ArrayList<>();
        String sql = "SELECT image_url FROM auction_images WHERE auction_id = ? ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) urls.add(rs.getString("image_url"));
            }
        }
        return urls;
    }

    // -------------------------------------------------------------------------
    // Public bid history (SCRUM-58)
    // -------------------------------------------------------------------------

    /**
     * Excludes every row of a blind auction that is still running.
     *
     * <p>{@link com.auction.servlet.api.AuctionApiServlet} short-circuits the sealed case
     * before it ever reaches this DAO, but it is not the only caller: the legacy JSP
     * endpoints {@code /auction-bids} and {@code /auction/{id}} read the same history with
     * no such check, and served the full list of sealed amounts to anyone who asked. The
     * guard therefore lives here, where it covers every caller including the next one
     * somebody writes. A concluded blind auction is public and is not filtered.</p>
     */
    private static final String HIDE_LIVE_SEALED_BIDS =
            "AND NOT EXISTS (SELECT 1 FROM auction a WHERE a.auction_id = b.auction_id "
          + "                AND a.auction_type = " + AuctionType.BLIND.getId()
          + "                AND a.status_id = " + AuctionStatus.ACTIVE.getId()
          + "                AND a.date_end > CURRENT_TIMESTAMP) ";

    /**
     * Returns a paginated page of bids for an auction, newest first.
     *
     * <p><b>Masking (SCRUM-361):</b> The current highest bidder's username is partially
     * masked via {@link SecurityUtil#maskUsername(String)}; all other bidders are fully
     * masked via {@link SecurityUtil#maskUsernameFully(String)}. Raw usernames never
     * leave the DAO.</p>
     *
     * @param auctionId auction primary key
     * @param page      1-based page number
     * @param pageSize  rows per page (caller should clamp to [1, {@link #MAX_BID_HISTORY_PAGE_SIZE}])
     * @return ordered list; empty when the auction has no bids
     */
    public List<AuctionBidHistoryEntry> getBidHistory(long auctionId, int page, int pageSize) {
        Integer leaderUserId = findCurrentLeaderUserId(auctionId);
        int offset = pageSize * (page - 1);

        String sql =
                "SELECT b.bid_amount, b.bid_time, b.user_id, u.username "
                + "FROM bids b "
                + "JOIN users u ON u.id = b.user_id "
                + "WHERE b.auction_id = ? "
                + HIDE_LIVE_SEALED_BIDS
                + "ORDER BY b.bid_time DESC "
                + "LIMIT ? OFFSET ?";

        List<AuctionBidHistoryEntry> list = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, pageSize);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int bidderId = rs.getInt("user_id");
                    String rawUsername = rs.getString("username");
                    boolean isLeader = leaderUserId != null && leaderUserId == bidderId;
                    String masked = isLeader
                            ? SecurityUtil.maskUsername(rawUsername)
                            : SecurityUtil.maskUsernameFully(rawUsername);

                    Timestamp bidTs = rs.getTimestamp("bid_time");
                    BigDecimal amount = rs.getBigDecimal("bid_amount");
                    if (amount == null) amount = BigDecimal.ZERO;

                    list.add(new AuctionBidHistoryEntry(
                            amount,
                            bidTs != null ? bidTs.toInstant() : null,
                            masked,
                            isLeader));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /**
     * Same as {@link #getBidHistory(long, int, int)} but also marks each entry with
     * {@code isSelf = true} when the bid was placed by {@code viewerUserId}.
     * Sorted by {@code bid_amount DESC, bid_time DESC} so the current leader always
     * appears at the top regardless of timing.
     *
     * @param viewerUserId authenticated buyer's userId, or 0 / negative for anonymous
     */
    public List<AuctionBidHistoryEntry> getBidHistory(long auctionId, int page, int pageSize,
                                                      int viewerUserId) {
        Integer leaderUserId = findCurrentLeaderUserId(auctionId);
        int offset = pageSize * (page - 1);

        String sql =
                "SELECT b.bid_amount, b.bid_time, b.user_id, u.username "
                + "FROM bids b "
                + "JOIN users u ON u.id = b.user_id "
                + "WHERE b.auction_id = ? "
                + HIDE_LIVE_SEALED_BIDS
                + "ORDER BY b.bid_amount DESC, b.bid_time DESC "
                + "LIMIT ? OFFSET ?";

        List<AuctionBidHistoryEntry> list = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setInt(2, pageSize);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int bidderId = rs.getInt("user_id");
                    String rawUsername = rs.getString("username");
                    boolean isLeader = leaderUserId != null && leaderUserId == bidderId;
                    boolean isSelf   = viewerUserId > 0 && viewerUserId == bidderId;
                    // Self bid: show unmasked (only visible to the bidder themselves).
                    // Others: partial mask for leader, full mask for the rest.
                    String masked = isSelf
                            ? rawUsername
                            : (isLeader
                                ? SecurityUtil.maskUsername(rawUsername)
                                : SecurityUtil.maskUsernameFully(rawUsername));

                    Timestamp bidTs = rs.getTimestamp("bid_time");
                    BigDecimal amount = rs.getBigDecimal("bid_amount");
                    if (amount == null) amount = BigDecimal.ZERO;

                    list.add(new AuctionBidHistoryEntry(
                            amount,
                            bidTs != null ? bidTs.toInstant() : null,
                            masked,
                            isLeader,
                            isSelf));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /** Total bid count for an auction (used for pagination). */
    public int countBidHistory(long auctionId) {
        String sql = "SELECT COUNT(*)::int FROM bids WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    /** Returns {@code true} when an auction row exists (any moderation state). */
    public boolean auctionExists(long auctionId) {
        String sql = "SELECT 1 FROM auction WHERE auction_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the {@code user_id} of the current highest bidder, or {@code null} when
     * no bids exist. Ties on amount are broken by latest {@code bid_time}.
     */
    Integer findCurrentLeaderUserId(long auctionId) {
        String sql =
                "SELECT user_id FROM bids WHERE auction_id = ? "
                + "ORDER BY bid_amount DESC, bid_time DESC LIMIT 1";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("user_id");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public List<Bid> auctionBidHistory(Long auction_id)throws Exception
    {
        String sqlString = "SELECT user_id, bid_amount, bid_time FROM bids WHERE auction_id =?";
        try(Connection conn = DBUtil.connectDB())
        {
        try(PreparedStatement stmt = conn.prepareStatement(sqlString))
        {
            List<Bid> result = new ArrayList<>();
            stmt.setLong(1, auction_id);
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
}
