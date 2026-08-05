package com.auction.realtime;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The live SSE snapshot is the fourth way a blind auction's standing bid could reach a
 * rival bidder: the detail page subscribes to it and re-renders the price on every event.
 *
 * <p>The snapshot query computes {@code current_bid} as {@code MAX(bid_amount)} for every
 * auction type, so the guard has to live in the payload the publisher builds from it —
 * these tests pin it there.</p>
 */
@DisplayName("AuctionEventPublisher — blind auctions")
class AuctionEventPublisherBlindTest {

    private static final long AUCTION_ID = 10L;

    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        ps   = mock(PreparedStatement.class);
        rs   = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getTimestamp("date_created"))
                .thenReturn(Timestamp.from(Instant.now().minusSeconds(3600)));
        when(rs.getBigDecimal("starting_price")).thenReturn(new BigDecimal("100.00"));
        when(rs.getBigDecimal("current_bid")).thenReturn(new BigDecimal("250.00"));
        when(rs.getInt("bid_count")).thenReturn(3);
        when(rs.getString("moderation_state")).thenReturn("active");
        when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
    }

    private Map<String, Object> snapshot(AuctionType type, boolean open) throws Exception {
        when(rs.getInt("auction_type")).thenReturn(type.getId());
        when(rs.getTimestamp("date_end")).thenReturn(Timestamp.from(
                open ? Instant.now().plusSeconds(3600) : Instant.now().minusSeconds(60)));
        try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            return AuctionEventPublisher.buildSnapshot(AUCTION_ID);
        }
    }

    @Test
    @DisplayName("A live blind auction broadcasts no amount")
    void openBlindSnapshotCarriesNoAmount() throws Exception {
        Map<String, Object> body = snapshot(AuctionType.BLIND, true);

        assertTrue(body.get("open") == Boolean.TRUE);
        assertNull(body.get("currentBid"),
                "broadcasting the standing bid would defeat the sealed mechanism entirely");
    }

    @Test
    @DisplayName("The sealed-bid count is still broadcast, so the page can show interest")
    void theSealedCountIsStillBroadcast() throws Exception {
        Map<String, Object> body = snapshot(AuctionType.BLIND, true);

        assertEquals(3, body.get("numBids"));
    }

    @Test
    @DisplayName("Once closed, the snapshot reveals the winning bid")
    void closedBlindSnapshotRevealsTheWinningBid() throws Exception {
        Map<String, Object> body = snapshot(AuctionType.BLIND, false);

        assertEquals(Boolean.FALSE, body.get("open"));
        assertEquals(new BigDecimal("250.00"), body.get("currentBid"));
    }

    @Test
    @DisplayName("An ascending auction still broadcasts its current bid — the guard is type-specific")
    void ascendingSnapshotIsUnaffected() throws Exception {
        Map<String, Object> body = snapshot(AuctionType.PRICE_UP, true);

        assertEquals(new BigDecimal("250.00"), body.get("currentBid"));
        assertEquals(3, body.get("numBids"));
    }

    @Test
    @DisplayName("The snapshot names the auction type, so a client cannot mistake a sealed null for zero")
    void theSnapshotNamesTheType() throws Exception {
        Map<String, Object> body = snapshot(AuctionType.BLIND, true);

        assertEquals(AuctionType.BLIND.getId(), body.get("auctionType"));
    }
}
