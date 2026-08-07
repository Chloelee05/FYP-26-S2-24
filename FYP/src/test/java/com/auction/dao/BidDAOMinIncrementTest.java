package com.auction.dao;

import com.auction.dao.BidDAO.BidOutcome;
import com.auction.dao.BidDAO.BidResult;
import com.auction.model.AuctionStatus;
import com.auction.model.AuctionType;
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
 * NEW for the "platform-wide auction rules" admin story: the minimum bid increment guard added
 * in {@code BidDAO#placeBid}.
 *
 * <p>Every scenario locks the same PRICE_UP auction (auction_id=10, seller=99, ACTIVE, ends in
 * the future, no max-price cap, starting price 100, no rate limit, no existing bids so the
 * floor is the starting price) and bids as buyer=5 unless stated otherwise. The minimum
 * increment is fixed at 5.00 via an injected {@link PlatformSettingsDAO} mock, so these tests
 * do not depend on the real {@code platform_settings} default of 0.01.</p>
 */
@DisplayName("BidDAO — platform-wide minimum bid increment guard")
class BidDAOMinIncrementTest {

    private static final long AUCTION_ID = 10L;
    private static final int SELLER_ID = 99;
    private static final int BUYER_ID = 5;
    private static final BigDecimal STARTING_PRICE = new BigDecimal("100");
    private static final BigDecimal MIN_INCREMENT = new BigDecimal("5.00");

    private Connection conn;
    private AutoBidDAO autoBidDAO;
    private PlatformSettingsDAO settingsDAO;
    private BidDAO bidDAO;

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

        // No rate limit in play, so it never interferes with the increment check below.
        when(settingsDAO.getInt(eq("bid_rate_limit_seconds"), anyInt())).thenReturn(0);
        when(settingsDAO.getBigDecimal(eq("min_bid_increment"), any())).thenReturn(MIN_INCREMENT);

        PreparedStatement lockPs = mockAuctionLockStatement();
        lastBidPs = mock(PreparedStatement.class);
        maxBidPs = mock(PreparedStatement.class);
        topBidderPs = mock(PreparedStatement.class);
        insertPs = mock(PreparedStatement.class);

        when(conn.prepareStatement(contains("FOR UPDATE"))).thenReturn(lockPs);
        when(conn.prepareStatement(contains("MAX(bid_time)"))).thenReturn(lastBidPs);
        when(conn.prepareStatement(contains("MAX(bid_amount)"))).thenReturn(maxBidPs);
        when(conn.prepareStatement(contains("ORDER BY bid_amount DESC, bid_time ASC"))).thenReturn(topBidderPs);
        when(conn.prepareStatement(contains("INSERT INTO bids"))).thenReturn(insertPs);

        ResultSet lastBidRs = mock(ResultSet.class);
        when(lastBidRs.next()).thenReturn(true);
        when(lastBidRs.getTimestamp(1)).thenReturn(null);
        when(lastBidPs.executeQuery()).thenReturn(lastBidRs);

        // No competing bids yet: floor is the starting price.
        ResultSet maxBidRs = mock(ResultSet.class);
        when(maxBidRs.next()).thenReturn(true);
        when(maxBidRs.getBigDecimal(1)).thenReturn(null);
        when(maxBidPs.executeQuery()).thenReturn(maxBidRs);

        ResultSet topBidderRs = mock(ResultSet.class);
        when(topBidderRs.next()).thenReturn(false);
        when(topBidderPs.executeQuery()).thenReturn(topBidderRs);
    }

    /** The auction-row lock query: PRICE_UP, ACTIVE, ends in the future, no cap, starting price 100. */
    private PreparedStatement mockAuctionLockStatement() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
        when(rs.getTimestamp("date_end")).thenReturn(Timestamp.from(Instant.now().plusSeconds(3600)));
        when(rs.getString("moderation_state")).thenReturn("active");
        when(rs.getInt("seller_id")).thenReturn(SELLER_ID);
        when(rs.getInt("auction_type")).thenReturn(AuctionType.PRICE_UP.getId());
        when(rs.getBigDecimal("starting_price")).thenReturn(STARTING_PRICE);
        when(rs.getBigDecimal("max_price")).thenReturn(null);
        when(ps.executeQuery()).thenReturn(rs);
        return ps;
    }

    private BidOutcome placeBid(BigDecimal amount) {
        try (MockedStatic<DBUtil> mocked = mockStatic(DBUtil.class)) {
            mocked.when(DBUtil::connectDB).thenReturn(conn);
            return bidDAO.placeBid(AUCTION_ID, BUYER_ID, amount);
        }
    }

    @Test
    @DisplayName("A bid that clears the floor by less than the configured increment is rejected as BID_TOO_LOW")
    void bidBelowIncrementRejected() throws Exception {
        // Floor is 100; +1 clears the old "> floor" rule but not the new 5.00 increment.
        BidOutcome outcome = placeBid(new BigDecimal("101"));

        assertEquals(BidResult.BID_TOO_LOW, outcome.result);
        verify(conn).rollback();
        verify(conn, never()).commit();
        verify(conn, never()).prepareStatement(contains("INSERT INTO bids"));
    }

    @Test
    @DisplayName("A bid that exactly meets the configured increment is accepted")
    void bidMeetingIncrementAccepted() throws Exception {
        BidOutcome outcome = placeBid(STARTING_PRICE.add(MIN_INCREMENT)); // 105

        assertEquals(BidResult.SUCCESS, outcome.result);
        verify(conn).commit();
        verify(conn, never()).rollback();
    }

    @Test
    @DisplayName("A bid that clears the increment by more is accepted")
    void bidAboveIncrementAccepted() throws Exception {
        BidOutcome outcome = placeBid(new BigDecimal("200"));

        assertEquals(BidResult.SUCCESS, outcome.result);
        verify(conn).commit();
    }

    @Test
    @DisplayName("A zero increment disables the new guard entirely, leaving the old floor rule as the only check")
    void zeroIncrementDisablesGuard() throws Exception {
        when(settingsDAO.getBigDecimal(eq("min_bid_increment"), any())).thenReturn(BigDecimal.ZERO);

        // Only 1 cent above the floor: would fail the 5.00 increment, but passes with it disabled
        // because the pre-existing "strictly greater than floor" rule is still the only check left.
        BidOutcome outcome = placeBid(new BigDecimal("100.01"));

        assertEquals(BidResult.SUCCESS, outcome.result);
    }

    @Test
    @DisplayName("The pre-existing floor rule (bid must exceed floor at all) still rejects a bid at or below it, "
            + "independent of the new increment guard")
    void bidAtOrBelowFloorStillRejected() throws Exception {
        BidOutcome outcome = placeBid(STARTING_PRICE); // == floor, not > floor

        assertEquals(BidResult.BID_TOO_LOW, outcome.result);
        verify(conn).rollback();
    }
}
