package com.auction.dao;

import com.auction.dao.BidDAO.BidOutcome;
import com.auction.dao.BidDAO.BidResult;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BidDAO#placeBid} under genuine concurrency, against a real PostgreSQL database.
 *
 * <p>Every other test of {@code placeBid} in this suite drives it on one thread with a mocked
 * {@code Connection}, which can only assert that the SQL text did not change. The guarantee the
 * method actually rests on — {@code SELECT … FOR UPDATE} serialising two bids that arrive at the
 * same instant (SCRUM-265) — is invisible to a mock, because a mock has no row to lock and no
 * second thread to block. A single-threaded test passes just as happily against an
 * implementation with the lock deleted.</p>
 *
 * <p>So this class does the one thing that distinguishes a correct auction from a broken one:
 * it fires many bidders at a single auction simultaneously and checks the invariants that
 * only hold if the lock is real.</p>
 *
 * <ul>
 *   <li><b>One winner per price.</b> When every bidder offers the identical amount, exactly one
 *       is accepted. Two accepted bids at the same price is the defect SCRUM-265 names.</li>
 *   <li><b>No lost updates.</b> The number of rows in {@code bids} equals the number of
 *       {@code SUCCESS} results — no accepted bid vanishes, and no row appears without one.</li>
 *   <li><b>Monotonic price.</b> Reading the accepted bids in {@code bid_id} order, the amounts
 *       strictly increase. Because the insert happens while the auction row is locked,
 *       {@code bid_id} order is lock-acquisition order; a bid accepted below one already
 *       committed would mean the floor was read outside the lock.</li>
 *   <li><b>The leader is the highest bid.</b> After the dust settles the top bidder is the
 *       buyer who offered the maximum accepted amount, not whoever happened to commit last.</li>
 * </ul>
 *
 * <p>Two design points worth stating, because both are easy to get wrong and would make the
 * test assert nothing. Each concurrent bidder is a <em>different</em> buyer: the per-(buyer,
 * auction) rate limit would otherwise reject every thread but the first with
 * {@link BidResult#BID_TOO_FAST}, and the test would pass without the row lock ever mattering.
 * And bid amounts are spaced by the platform's configured {@code min_bid_increment} rather than
 * a hardcoded penny, so an administrator who has raised that setting on the scratch database
 * does not turn every bid into a {@link BidResult#BID_TOO_LOW} rejection.</p>
 *
 * <p>Opt in with {@code AUCTION_DB_IT=true} and point {@code AUCTION_DB_URL} at a scratch
 * database, exactly like {@link AdminManagementDAOIntegrationTest}. Every row created here is
 * namespaced {@code [IT-BID-CONC]} and removed in {@link #tearDown()}; the suite refuses to run
 * against the hosted database.</p>
 */
@EnabledIfEnvironmentVariable(named = "AUCTION_DB_IT", matches = "true")
@DisplayName("BidDAO.placeBid — concurrent bidders against a real database")
class BidDAOConcurrencyIntegrationTest {

    private static final String MARK = "[IT-BID-CONC]";
    private static final String EMAIL_DOMAIN = "@it-bid-conc.test";

    /**
     * Bidders racing for one auction in a single round.
     *
     * <p>Held below the ten-connection ceiling {@link DBUtil} configures on the Hikari pool.
     * More threads than connections would not deepen the race — the extra threads would queue
     * on the pool rather than on the auction row — but it would replace a clear failure with a
     * connection timeout, and the point here is to test the row lock, not the pool.</p>
     */
    private static final int BIDDERS = 8;

    /**
     * How many times each race is replayed at a fresh price level.
     *
     * <p>Not decoration. The window between reading {@code MAX(bid_amount)} and inserting is
     * well under a millisecond, so a single round of eight threads usually serialises by luck
     * even with the lock deleted — verified by deleting it, at which point a one-round version
     * of both race tests still passed. Twenty independent rounds turn "might catch it" into
     * "does catch it": with the lock removed, the same-amount race below fails within the first
     * few rounds every time it is run.</p>
     */
    private static final int RACE_ROUNDS = 20;

    /** Rounds used by the response-time measurement, to gather a distribution worth quoting. */
    private static final int TIMING_ROUNDS = 5;

    /**
     * One fresh buyer per bid attempt.
     *
     * <p>Reusing a buyer across rounds would hit the per-(buyer, auction) rate limit and return
     * {@link BidResult#BID_TOO_FAST} instead of exercising the floor comparison at all.</p>
     */
    private static final int TOTAL_BUYERS = BIDDERS * RACE_ROUNDS;

    private static final BigDecimal STARTING_PRICE = new BigDecimal("100.00");

    private BidDAO dao;
    private PlatformSettingsDAO settings;
    private int sellerId;
    private final List<Integer> buyerIds = new ArrayList<>();
    private long auctionId;

    /** The platform's minimum step over the floor, as this database is actually configured. */
    private BigDecimal minIncrement;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("AUCTION_DB_URL");
        assertNotNull(url, "AUCTION_DB_URL must be set for the integration suite");
        assertFalse(url.contains("render.com"),
                "refusing to run the integration suite against the hosted database");

        dao = new BidDAO();
        // The settings cache is process-wide and lives for 30 seconds. Dropping it here means the
        // increment and rate limit this test reads are the ones placeBid will read a moment later,
        // rather than a snapshot left behind by whichever test ran before.
        PlatformSettingsDAO.invalidateCache();
        settings = new PlatformSettingsDAO();
        minIncrement = settings.getBigDecimal("min_bid_increment", BidDAO.DEFAULT_MIN_BID_INCREMENT);
        if (minIncrement == null || minIncrement.compareTo(BigDecimal.ZERO) <= 0) {
            minIncrement = new BigDecimal("0.01");
        }

        cleanUp();
        seed();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanUp();
    }

    // ── the races ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("identical simultaneous bids: exactly one is accepted, every round (SCRUM-265)")
    void sameAmountRaceAcceptsExactlyOne() throws Exception {
        BigDecimal floor = STARTING_PRICE;

        for (int round = 0; round < RACE_ROUNDS; round++) {
            final BigDecimal amount = floor.add(minIncrement);
            final int offset = round * BIDDERS;

            List<BidOutcome> outcomes =
                    race(BIDDERS, i -> dao.placeBid(auctionId, buyerIds.get(offset + i), amount));

            assertOnlyExpectedRejections(outcomes, round);
            assertEquals(1, countOf(outcomes, BidResult.SUCCESS),
                    "round " + round + " at " + amount + ": " + countOf(outcomes, BidResult.SUCCESS)
                  + " of " + BIDDERS + " bidders offering the same amount were accepted. One "
                  + "auction cannot sell twice at one price; the floor was read outside the lock.");
            assertEquals(BIDDERS - 1, countOf(outcomes, BidResult.BID_TOO_LOW),
                    "round " + round + ": every losing bidder should be told the price had moved");

            floor = amount;
        }

        assertEquals(RACE_ROUNDS, bidRowCount(),
                "one accepted bid per round should leave exactly " + RACE_ROUNDS + " rows");
        assertEquals(0, floor.compareTo(highestBidInDatabase()),
                "the final stored price should be the last winning bid");
    }

    @Test
    @DisplayName("simultaneous ascending bids: no lost updates and the price only ever rises")
    void ascendingRaceKeepsThePriceMonotonic() throws Exception {
        // A step comfortably above the configured minimum, so each bidder's amount is a valid
        // raise on the one below it and the only thing that can reject a bid is losing the race.
        BigDecimal step = minIncrement.max(new BigDecimal("1.00"));
        BigDecimal base = STARTING_PRICE;
        int accepted = 0;

        for (int round = 0; round < RACE_ROUNDS; round++) {
            final BigDecimal roundBase = base;
            final int offset = round * BIDDERS;

            List<BidOutcome> outcomes = race(BIDDERS, i -> dao.placeBid(
                    auctionId,
                    buyerIds.get(offset + i),
                    roundBase.add(step.multiply(BigDecimal.valueOf(i + 1L)))));

            assertOnlyExpectedRejections(outcomes, round);
            accepted += countOf(outcomes, BidResult.SUCCESS);
            base = base.add(step.multiply(BigDecimal.valueOf(BIDDERS)));
        }

        assertTrue(accepted >= RACE_ROUNDS,
                "at least one bid per round should have been accepted, got " + accepted);

        List<BigDecimal> stored = acceptedAmountsInInsertOrder();
        assertEquals(accepted, stored.size(),
                "the database holds " + stored.size() + " bids but placeBid reported " + accepted
              + " successes, so a bid was either lost or written without being reported");

        // The invariant the lock exists to provide. Insert order is lock order, so a later row
        // holding a lower amount would mean two transactions read the same floor and both won.
        for (int i = 1; i < stored.size(); i++) {
            assertTrue(stored.get(i).compareTo(stored.get(i - 1)) > 0,
                    "bid " + stored.get(i) + " was accepted after " + stored.get(i - 1)
                  + ", so the price went down: the floor was read outside the lock");
        }
        assertEquals(stored.size(), new java.util.HashSet<>(stored).size(),
                "two accepted bids share a price, which one auction cannot have");

        BigDecimal highestAccepted = Collections.max(stored);
        assertEquals(0, highestAccepted.compareTo(highestBidInDatabase()));
        assertEquals(buyerOf(highestAccepted), topBidder(),
                "the auction is led by someone other than the highest bidder");
    }

    @Test
    @DisplayName("one buyer bidding twice at once is rate-limited, not double-counted")
    void sameBuyerRacingItselfIsRateLimited() throws Exception {
        int rateLimitSeconds =
                settings.getInt("bid_rate_limit_seconds", BidDAO.DEFAULT_BID_RATE_LIMIT_SECONDS);
        Assumptions.assumeTrue(rateLimitSeconds > 0,
                "bid_rate_limit_seconds is disabled on this database, so there is no limit to test");

        BigDecimal amount = STARTING_PRICE.add(minIncrement);
        int buyer = buyerIds.get(0);

        // Same buyer, same auction, same instant. The rate-limit check reads that buyer's own
        // last bid time under the auction lock and runs before the floor comparison, so the
        // losers here must be rejected as too fast rather than too low.
        List<BidOutcome> outcomes = race(4, i -> dao.placeBid(auctionId, buyer, amount));

        assertEquals(1, countOf(outcomes, BidResult.SUCCESS),
                "one buyer had two simultaneous bids accepted on the same auction");
        assertEquals(3, countOf(outcomes, BidResult.BID_TOO_FAST),
                "the repeat bids should hit the rate limit; got " + summarise(outcomes));
        assertEquals(1, bidRowCount());
    }

    @Test
    @DisplayName("stays inside the 3-second place-bid target from NFR 6.1 while contended")
    void meetsTheDocumentedResponseTimeTarget() throws Exception {
        // The technical documentation states a target of under three seconds for a transactional
        // bid. That number has never been measured against a contended auction, which is the only
        // condition under which it is in any doubt, since that is when callers queue on the lock.
        BigDecimal step = minIncrement.max(new BigDecimal("1.00"));
        List<Long> timingsMicros = new ArrayList<>();
        BigDecimal base = STARTING_PRICE;

        for (int round = 0; round < TIMING_ROUNDS; round++) {
            final int offset = round * BIDDERS;
            final BigDecimal roundBase = base;
            List<long[]> samples = race(BIDDERS, i -> {
                BigDecimal amount = roundBase.add(step.multiply(BigDecimal.valueOf(i + 1L)));
                long t0 = System.nanoTime();
                dao.placeBid(auctionId, buyerIds.get(offset + i), amount);
                return new long[]{ (System.nanoTime() - t0) / 1_000L };
            });
            samples.forEach(s -> timingsMicros.add(s[0]));
            base = base.add(step.multiply(BigDecimal.valueOf(BIDDERS)));
        }

        Collections.sort(timingsMicros);
        long medianMicros = timingsMicros.get(timingsMicros.size() / 2);
        long p95Micros = timingsMicros.get((int) Math.floor(timingsMicros.size() * 0.95) - 1);
        long maxMicros = timingsMicros.get(timingsMicros.size() - 1);

        // Printed rather than only asserted, because these are the numbers the performance
        // section of the documentation needs and there is nowhere else to read them from.
        System.out.printf(
                "placeBid under %d-way contention over %d rounds (%d samples): "
              + "median %.1f ms, p95 %.1f ms, max %.1f ms%n",
                BIDDERS, TIMING_ROUNDS, timingsMicros.size(),
                medianMicros / 1000.0, p95Micros / 1000.0, maxMicros / 1000.0);

        assertTrue(maxMicros < 3_000_000L,
                "slowest contended bid took " + (maxMicros / 1000.0) + " ms, over the 3000 ms "
              + "target stated in NFR 6.1");
    }

    // ── concurrency harness ──────────────────────────────────────────────────

    /** A worker body that may fail; {@link BidDAO#placeBid} throws only unchecked exceptions. */
    private interface Worker<T> {
        T run(int index) throws Exception;
    }

    /**
     * Runs {@code n} workers and releases them all at once.
     *
     * <p>The start gate is what makes this a race rather than {@code n} sequential calls that
     * happen to be on different threads: without it the first worker would usually finish
     * before the last one had been scheduled, the auction row would never be contended, and
     * every assertion in this class would hold for an implementation with no lock at all.</p>
     */
    private <T> List<T> race(int n, Worker<T> worker) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                final int index = i;
                Callable<T> task = () -> {
                    ready.countDown();
                    start.await();
                    return worker.run(index);
                };
                futures.add(pool.submit(task));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "workers never reached the start gate");
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                // 60 seconds is far beyond any legitimate wait on the row lock, so exceeding it
                // means the transactions have deadlocked rather than merely queued.
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Fails on any rejection other than losing the race.
     *
     * <p>Without this, a misconfigured fixture — an expired auction, a self-bid, a rate limit
     * catching every thread — would show up as "no successes" and read like a lock failure,
     * sending the reader after a bug that is not there.</p>
     */
    private void assertOnlyExpectedRejections(List<BidOutcome> outcomes, int round) {
        List<BidResult> unexpected = outcomes.stream()
                .map(o -> o.result)
                .filter(r -> r != BidResult.SUCCESS && r != BidResult.BID_TOO_LOW)
                .collect(Collectors.toList());
        assertTrue(unexpected.isEmpty(),
                "round " + round + ": bids were rejected for a reason unrelated to the race, so "
              + "the fixture is wrong rather than the lock: " + summarise(outcomes));
    }

    private static int countOf(List<BidOutcome> outcomes, BidResult result) {
        return (int) outcomes.stream().filter(o -> o.result == result).count();
    }

    private static String summarise(List<BidOutcome> outcomes) {
        return outcomes.stream().map(o -> o.result.name()).collect(Collectors.joining(", "));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void seed() throws Exception {
        try (Connection c = DBUtil.connectDB()) {
            sellerId = insertUser(c, "it_conc_seller");
            buyerIds.clear();
            buyerIds.addAll(insertBuyers(c, TOTAL_BUYERS));

            // PRICE_UP (1), ACTIVE (1), ending a week out so the clock cannot close the auction
            // mid-race and turn a lock failure into AUCTION_CLOSED.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO auction (seller_id, auction_type, status_id, date_created, "
                  + "date_end, moderation_state) "
                  + "VALUES (?, 1, 1, now(), now() + interval '7 days', 'active') "
                  + "RETURNING auction_id")) {
                ps.setInt(1, sellerId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    auctionId = rs.getLong(1);
                }
            }
            // No max_price: a cap would reject the upper bids in the ascending race for a reason
            // that has nothing to do with concurrency.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO auction_details (id, title, description, category, starting_price, "
                  + "item_condition_id) VALUES (?, ?, ?, 'Electronics', ?, 1)")) {
                ps.setLong(1, auctionId);
                ps.setString(2, MARK + " Contended Auction");
                ps.setString(3, MARK + " Fixture for the concurrent bidding suite.");
                ps.setBigDecimal(4, STARTING_PRICE);
                ps.executeUpdate();
            }
        }
    }

    /**
     * The whole bidder pool in one statement.
     *
     * <p>{@link #RACE_ROUNDS} rounds of {@link #BIDDERS} needs {@value #TOTAL_BUYERS} accounts,
     * and this runs again before every test method. One round trip per account would put more
     * time into fixture setup than into the races themselves.</p>
     */
    private List<Integer> insertBuyers(Connection c, int count) throws Exception {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO users (username, email, password, role_id, status_id) VALUES ");
        for (int i = 0; i < count; i++) {
            sql.append(i == 0 ? "" : ", ").append("(?, ?, 'x', 2, 1)");
        }
        sql.append(" RETURNING id");

        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < count; i++) {
                String username = String.format("it_conc_buyer_%03d", i);
                ps.setString(i * 2 + 1, username);
                ps.setString(i * 2 + 2, username + EMAIL_DOMAIN);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
            }
        }
        assertEquals(count, ids.size(), "expected " + count + " bidder accounts");
        return ids;
    }

    /** Role 2 (buyer) and status 1 (active), matching the lookup seed data. */
    private int insertUser(Connection c, String username) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (username, email, password, role_id, status_id) "
              + "VALUES (?, ?, 'x', 2, 1) RETURNING id")) {
            ps.setString(1, username);
            ps.setString(2, username + EMAIL_DOMAIN);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private void cleanUp() throws Exception {
        try (Connection c = DBUtil.connectDB(); Statement st = c.createStatement()) {
            String ourAuctions =
                    "SELECT auction_id FROM auction WHERE seller_id IN "
                  + "(SELECT id FROM users WHERE email LIKE '%" + EMAIL_DOMAIN + "')";
            st.executeUpdate("DELETE FROM bids WHERE auction_id IN (" + ourAuctions + ")");
            st.executeUpdate("DELETE FROM auto_bids WHERE auction_id IN (" + ourAuctions + ")");
            st.executeUpdate("DELETE FROM auction_details WHERE id IN (" + ourAuctions + ")");
            st.executeUpdate(
                    "DELETE FROM auction WHERE seller_id IN "
                  + "(SELECT id FROM users WHERE email LIKE '%" + EMAIL_DOMAIN + "')");
            st.executeUpdate("DELETE FROM users WHERE email LIKE '%" + EMAIL_DOMAIN + "'");
        }
    }

    // ── database reads ───────────────────────────────────────────────────────

    private int bidRowCount() throws Exception {
        try (Connection c = DBUtil.connectDB();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM bids WHERE auction_id = ?")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Accepted amounts ordered by {@code bid_id}, which is insert order.
     *
     * <p>Ordering by {@code bid_time} would not do: it is written with {@code CURRENT_TIMESTAMP},
     * which in PostgreSQL is the transaction start time, so two bids that queued on the lock can
     * carry timestamps in the opposite order to the one they were actually accepted in. The
     * identity sequence is allocated at insert, inside the lock, so it is the real order.</p>
     */
    private List<BigDecimal> acceptedAmountsInInsertOrder() throws Exception {
        List<BigDecimal> out = new ArrayList<>();
        try (Connection c = DBUtil.connectDB();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bid_amount FROM bids WHERE auction_id = ? ORDER BY bid_id")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getBigDecimal(1));
                }
            }
        }
        return out;
    }

    private BigDecimal highestBidInDatabase() throws Exception {
        try (Connection c = DBUtil.connectDB();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MAX(bid_amount) FROM bids WHERE auction_id = ?")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private Integer topBidder() throws Exception {
        try (Connection c = DBUtil.connectDB();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM bids WHERE auction_id = ? "
                   + "ORDER BY bid_amount DESC, bid_id ASC LIMIT 1")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private Integer buyerOf(BigDecimal amount) throws Exception {
        try (Connection c = DBUtil.connectDB();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM bids WHERE auction_id = ? AND bid_amount = ?")) {
            ps.setLong(1, auctionId);
            ps.setBigDecimal(2, amount);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }
}
