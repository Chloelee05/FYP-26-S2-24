import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auction.dao.SellerAuctionDAO;
import com.auction.model.AuctionStatus;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.sql.*;

@DisplayName("SellerAuctionDAO")
public class TestSellerAuctionDAO {

    private Connection mockConn;
    private PreparedStatement mockPs;
    private ResultSet mockRs;
    private final SellerAuctionDAO dao = new SellerAuctionDAO();

    @BeforeEach
    void setUp() throws Exception {
        mockConn = mock(Connection.class);
        mockPs   = mock(PreparedStatement.class);
        mockRs   = mock(ResultSet.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
        when(mockPs.executeQuery()).thenReturn(mockRs);
    }

    // ------------------------------------------------------------------ SCRUM-33: withinBidCap

    @Nested
    @DisplayName("withinBidCap – SCRUM-33")
    class WithinBidCap {

        @Test
        @DisplayName("null cap (no max_price set) always allows any bid")
        void nullCapAllowsAnyBid() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getBigDecimal("max_price")).thenReturn(null);

                assertTrue(dao.withinBidCap(1L, new BigDecimal("9999999")));
            }
        }

        @Test
        @DisplayName("bid amount below cap is allowed")
        void bidBelowCapAllowed() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getBigDecimal("max_price")).thenReturn(new BigDecimal("100.00"));

                assertTrue(dao.withinBidCap(1L, new BigDecimal("99.99")));
            }
        }

        @Test
        @DisplayName("bid equal to cap is allowed (hard ceiling – at cap is accepted)")
        void bidAtCapAllowed() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getBigDecimal("max_price")).thenReturn(new BigDecimal("100.00"));

                assertTrue(dao.withinBidCap(1L, new BigDecimal("100.00")));
            }
        }

        @Test
        @DisplayName("bid above cap is rejected")
        void bidAboveCapRejected() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getBigDecimal("max_price")).thenReturn(new BigDecimal("100.00"));

                assertFalse(dao.withinBidCap(1L, new BigDecimal("100.01")));
            }
        }

        @Test
        @DisplayName("auction not found returns false (fail-safe)")
        void auctionNotFoundReturnsFalse() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false); // no row

                assertFalse(dao.withinBidCap(999L, new BigDecimal("50.00")));
            }
        }
    }

    // ------------------------------------------------------------------ SCRUM-34: cancelAuction

    @Nested
    @DisplayName("cancelAuction – SCRUM-34")
    class CancelAuction {

        @Test
        @DisplayName("ACTIVE auction owned by seller is cancelled (returns true)")
        void cancelsActiveAuction() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(1);

                assertTrue(dao.cancelAuction(1L, 42, "Changed my mind"));
                // verify CANCELLED status id was set
                verify(mockPs).setInt(1, AuctionStatus.CANCELLED.getId());
                verify(mockPs).setLong(3, 1L);
                verify(mockPs).setInt(4, 42);
            }
        }

        @Test
        @DisplayName("PENDING auction owned by seller is cancelled (returns true)")
        void cancelsPendingAuction() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(1);

                assertTrue(dao.cancelAuction(5L, 7, null));
            }
        }

        @Test
        @DisplayName("FINISHED auction cannot be cancelled (returns false)")
        void cannotCancelFinishedAuction() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(0); // WHERE status_id IN (1,4) excludes FINISHED

                assertFalse(dao.cancelAuction(2L, 42, null));
            }
        }

        @Test
        @DisplayName("already CANCELLED auction returns false")
        void cannotCancelAlreadyCancelled() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(0);

                assertFalse(dao.cancelAuction(3L, 42, null));
            }
        }

        @Test
        @DisplayName("wrong seller_id returns false (ownership check)")
        void wrongSellerReturnsFalse() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(0); // seller_id mismatch

                assertFalse(dao.cancelAuction(1L, 999, null));
            }
        }

        @Test
        @DisplayName("existing bids are NOT deleted when cancelled – cancel only updates status")
        void bidsPreservedOnCancel() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockPs.executeUpdate()).thenReturn(1);

                dao.cancelAuction(1L, 42, null);

                // only one prepareStatement call (the UPDATE auction); no DELETE on bids
                verify(mockConn, times(1)).prepareStatement(anyString());
                verify(mockPs, never()).executeBatch();
            }
        }
    }

    // ------------------------------------------------------------------ SCRUM-37: countBids

    @Nested
    @DisplayName("countBids – SCRUM-37 precondition")
    class CountBids {

        @Test
        @DisplayName("returns correct bid count")
        void returnsCount() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getInt(1)).thenReturn(3);

                assertEquals(3, dao.countBids(10L));
            }
        }

        @Test
        @DisplayName("zero bids returns 0")
        void zeroBids() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getInt(1)).thenReturn(0);

                assertEquals(0, dao.countBids(10L));
            }
        }
    }

    // ------------------------------------------------------------------ SCRUM-38: dashboard listing

    @Nested
    @DisplayName("listSellerAuctions / countSellerAuctions – SCRUM-38")
    class Dashboard {

        @Test
        @DisplayName("returns rows mapped from result set for seller")
        void returnsRowsForSeller() throws Exception {
            Timestamp ts = Timestamp.from(java.time.Instant.now());
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true).thenReturn(false);
                when(mockRs.getLong("auction_id")).thenReturn(1L);
                when(mockRs.getString("title")).thenReturn("Test Auction");
                when(mockRs.getBigDecimal("starting_price")).thenReturn(new BigDecimal("10.00"));
                when(mockRs.getBigDecimal("max_price")).thenReturn(null);
                when(mockRs.getBigDecimal("current_bid")).thenReturn(new BigDecimal("0"));
                when(mockRs.getInt("bid_count")).thenReturn(0);
                when(mockRs.getTimestamp("start_date")).thenReturn(ts);
                when(mockRs.getTimestamp("date_end")).thenReturn(ts);
                when(mockRs.getString("status_name")).thenReturn("Active");

                var rows = dao.listSellerAuctions(42, null, 1, 10);
                assertEquals(1, rows.size());
                assertEquals("Test Auction", rows.get(0).getTitle());
            }
        }

        @Test
        @DisplayName("empty result returns empty list (not null)")
        void emptyListReturnsEmptyNotNull() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                var rows = dao.listSellerAuctions(42, null, 1, 10);
                assertNotNull(rows);
                assertTrue(rows.isEmpty());
            }
        }

        @Test
        @DisplayName("status filter is applied when provided")
        void statusFilterPassedToQuery() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                dao.listSellerAuctions(42, AuctionStatus.ACTIVE.getId(), 1, 10);

                // with a status filter, the SQL has an extra ? for status_id; verify it's set
                verify(mockPs).setInt(2, AuctionStatus.ACTIVE.getId());
            }
        }

        @Test
        @DisplayName("pagination LIMIT and OFFSET are set correctly for page 2 / size 5")
        void paginationLimitOffset() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                dao.listSellerAuctions(42, null, 2, 5);

                // no status filter: params are seller_id(1), limit(2), offset(3)
                verify(mockPs).setInt(1, 42);
                verify(mockPs).setInt(2, 5);   // LIMIT
                verify(mockPs).setInt(3, 5);   // OFFSET = 5 * (2-1)
            }
        }

        @Test
        @DisplayName("countSellerAuctions returns correct count")
        void countReturnsTotal() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(true);
                when(mockRs.getInt(1)).thenReturn(7);

                assertEquals(7, dao.countSellerAuctions(42, null));
            }
        }

        @Test
        @DisplayName("no leakage – seller_id is always bound from session, not from request param")
        void sellerIdAlwaysBound() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(mockConn);
                when(mockRs.next()).thenReturn(false);

                dao.listSellerAuctions(99, null, 1, 10);

                verify(mockPs).setInt(1, 99); // seller_id = 99, never any other id
            }
        }
    }
}
