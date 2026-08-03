package com.auction.dao;

import com.auction.dao.BidDAO.BidOutcome;
import com.auction.dao.BidDAO.BidResult;
import com.auction.model.AuctionStatus;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Per-(user, auction) bid rate limiting (anti-spam; BID_TOO_FAST).
 *
 * <p>Every scenario locks the same auction row (auction_id=10, seller=99, ACTIVE, ends in
 * the future, no max-price cap, starting price 10) and bids as buyer=5 unless stated
 * otherwise. The rate-limit window is fixed at 3 seconds via an injected
 * {@link PlatformSettingsDAO} mock, so these tests do not depend on the real
 * {@code platform_settings} default.</p>
 */
@DisplayName("BidDAO — per-(user, auction) rate limit (BID_TOO_FAST)")
class BidDAORateLimitTest {

    private static final long AUCTION_ID = 10L;
    private static final int SELLER_ID = 99;
    private static final int BUYER_ID = 5;
    private static final int RATE_LIMIT_SECONDS = 3;

    private Connection conn;
    private AutoBidDAO autoBidDAO;
    private PlatformSettingsDAO settingsDAO;
    private BidDAO bidDAO;

    private PreparedStatement lockPs;
    private PreparedStatement lastBidPs;
    private PreparedStatement maxBidPs;
    private PreparedStatement topBidderPs;
    private PreparedStatement insertPs;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        autoBidDAO = mock(AutoBidDAO.class);
        settingsDAO = mock(PlatformSettingsDAO.class);
        bidDAO = new BidDAO(autoBidDAO, settingsDAO);

        when(settingsDAO.getInt(eq("bid_rate_limit_seconds"), anyInt())).thenReturn(RATE_LIMIT_SECONDS);

        lockPs = mockAuctionLockStatement();
        lastBidPs = mock(PreparedStatement.class);
        maxBidPs = mock(PreparedStatement.class);
        topBidderPs = mock(PreparedStatement.class);
        insertPs = mock(PreparedStatement.class);

        when(conn.prepareStatement(contains("FOR UPDATE"))).thenReturn(lockPs);
        when(conn.prepareStatement(contains("MAX(bid_time)"))).thenReturn(lastBidPs);
        when(conn.prepareStatement(contains("MAX(bid_amount)"))).thenReturn(maxBidPs);
        when(conn.prepareStatement(contains("ORDER BY bid_amount DESC, bid_time ASC"))).thenReturn(topBidderPs);
        when(conn.prepareStatement(contains("INSERT INTO bids"))).thenReturn(insertPs);

        // No competing bids: floor is the starting price, and nobody currently leads.
        ResultSet maxBidRs = mock(ResultSet.class);
        when(maxBidRs.next()).thenReturn(true);
        when(maxBidRs.getBigDecimal(1)).thenReturn(null);
        when(maxBidPs.executeQuery()).thenReturn(maxBidRs);

        ResultSet topBidderRs = mock(ResultSet.class);
        when(topBidderRs.next()).thenReturn(false);
        when(topBidderPs.executeQuery()).thenReturn(topBidderRs);
    }

    /** The auction-row lock query: ACTIVE, ends in the future, no cap, starting price 10. */
    private PreparedStatement mockAuctionLockStatement() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
        when(rs.getTimestamp("date_end")).thenReturn(Timestamp.from(Instant.now().plusSeconds(3600)));
        when(rs.getString("moderation_state")).thenReturn("active");
        when(rs.getInt("seller_id")).thenReturn(SELLER_ID);
        when(rs.getBigDecimal("starting_price")).thenReturn(new BigDecimal("10"));
        when(rs.getBigDecimal("max_price")).thenReturn(null);
        when(ps.executeQuery()).thenReturn(rs);
        return ps;
    }

    private void stubLastBidTime(Instant lastBidTime) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getTimestamp(1)).thenReturn(lastBidTime == null ? null : Timestamp.from(lastBidTime));
        when(lastBidPs.executeQuery()).thenReturn(rs);
    }

    private BidOutcome placeBid(int buyerId, BigDecimal amount) {
        try (MockedStatic<DBUtil> mocked = mockStatic(DBUtil.class)) {
            mocked.when(DBUtil::connectDB).thenReturn(conn);
            return bidDAO.placeBid(AUCTION_ID, buyerId, amount);
        }
    }

    @Test
    @DisplayName("A second bid from the same buyer inside the window is rejected as BID_TOO_FAST")
    void secondBidInsideWindowRejected() throws Exception {
        stubLastBidTime(Instant.now().minusSeconds(1)); // 1s ago, window is 3s

        BidOutcome outcome = placeBid(BUYER_ID, new BigDecimal("50"));

        assertEquals(BidResult.BID_TOO_FAST, outcome.result);
        assertFalse(outcome.isSuccess());
        verify(conn).rollback();
        verify(conn, never()).commit();
        // Nothing was written — a rejected fast bid must not consume any state.
        verify(conn, never()).prepareStatement(contains("INSERT INTO bids"));
        verifyNoInteractions(autoBidDAO);
    }

    @Test
    @DisplayName("A bid after the window has elapsed is accepted")
    void bidAfterWindowElapsedAccepted() throws Exception {
        stubLastBidTime(Instant.now().minusSeconds(10)); // older than the 3s window

        BidOutcome outcome = placeBid(BUYER_ID, new BigDecimal("50"));

        assertEquals(BidResult.SUCCESS, outcome.result);
        verify(conn).commit();
        verify(conn, never()).rollback();
        verify(conn).prepareStatement(contains("INSERT INTO bids"));
    }

    @Test
    @DisplayName("A buyer with no prior bid on this auction is never rate-limited")
    void firstBidNeverRateLimited() throws Exception {
        stubLastBidTime(null);

        BidOutcome outcome = placeBid(BUYER_ID, new BigDecimal("50"));

        assertEquals(BidResult.SUCCESS, outcome.result);
    }

    @Test
    @DisplayName("The rate limit reads this buyer's last bid scoped to (auctionId, buyerId), so it "
            + "cannot see another user's or another auction's bids")
    void lastBidTimeQueryIsScopedToUserAndAuction() throws Exception {
        stubLastBidTime(Instant.now().minusSeconds(1));

        placeBid(BUYER_ID, new BigDecimal("50"));

        verify(lastBidPs).setLong(1, AUCTION_ID);
        verify(lastBidPs).setInt(2, BUYER_ID);
    }

    @Test
    @DisplayName("A rejected fast bid from buyer A does not block a legitimate bid from buyer B "
            + "on the same auction")
    void rejectedFastBidDoesNotBlockADifferentBuyer() throws Exception {
        // Buyer A: bid 1s ago -> rejected.
        stubLastBidTime(Instant.now().minusSeconds(1));
        BidOutcome first = placeBid(BUYER_ID, new BigDecimal("50"));
        assertEquals(BidResult.BID_TOO_FAST, first.result);

        // Buyer B (different user, same auction): has never bid here, so their own
        // last-bid lookup is empty regardless of what buyer A just attempted.
        int otherBuyerId = 7;
        stubLastBidTime(null);
        BidOutcome second = placeBid(otherBuyerId, new BigDecimal("60"));

        assertEquals(BidResult.SUCCESS, second.result,
                "buyer A's rejected fast bid must not have written anything that could block buyer B");
    }

    @Test
    @DisplayName("A rate limit of zero (or negative) disables the check entirely")
    void zeroDisablesRateLimit() throws Exception {
        when(settingsDAO.getInt(eq("bid_rate_limit_seconds"), anyInt())).thenReturn(0);
        stubLastBidTime(Instant.now()); // "just now" — would fail any positive window

        BidOutcome outcome = placeBid(BUYER_ID, new BigDecimal("50"));

        assertEquals(BidResult.SUCCESS, outcome.result);
    }
}
