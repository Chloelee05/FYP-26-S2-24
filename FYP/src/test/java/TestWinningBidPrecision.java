import com.auction.dao.AuctionDAO;
import com.auction.dao.OrderDAO;
import com.auction.dao.SellerAuctionDAO;
import com.auction.model.AuctionStatus;
import com.auction.util.AuctionFinalizer;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@code auction_details.winning_bid} carries the cents of the bid it is copied from.
 *
 * <p>The audit found a $33.77 winning bid producing a $33.00 order. The column was
 * {@code INTEGER} while {@code bids.bid_amount} is {@code NUMERIC(10,2)}, and the conclusion
 * paths disagreed about how to lose the cents: {@link AuctionFinalizer} truncated with
 * {@code intValue()} while {@link OrderDAO#declareWinner} rounded half-up, so the same auction
 * settled at a different figure depending on which path reached it first.</p>
 *
 * <p><b>What these tests can and cannot prove.</b> They assert that every Java write path binds
 * a {@code BigDecimal} scaled to two decimal places, and that the order amount is read straight
 * back out without an integer coercion. That is the whole of the defect that lived in Java. They
 * cannot prove the value survives storage, because the suite has no database — a
 * {@code setBigDecimal} of 33.77 into a column that is still {@code INTEGER} would satisfy every
 * assertion here and still store 33. The column type is the migration's job and is verified
 * against the production database directly; see migration_seller_maintain_listing.sql.</p>
 */
@DisplayName("winning_bid keeps its cents")
class TestWinningBidPrecision {

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
    }

    /** Everything bound to parameter index 2 as a BigDecimal, i.e. every winning_bid write. */
    private List<BigDecimal> winningBidsWritten() throws Exception {
        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(ps, atLeastOnce()).setBigDecimal(eq(2), captor.capture());
        return captor.getAllValues();
    }

    private List<String> preparedSql() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(sql.capture());
        return sql.getAllValues();
    }

    // ── AuctionFinalizer: the path that used to truncate ──────────────────────

    @Nested
    @DisplayName("AuctionFinalizer")
    class Finalizer {

        /** An ACTIVE auction whose clock has run out, with one bid on it. */
        private void stubEndedAuctionWithTopBid(String topBid) throws Exception {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().minusSeconds(60)));
            when(rs.getInt("user_id")).thenReturn(5);
            when(rs.getBigDecimal("bid_amount")).thenReturn(new BigDecimal(topBid));
        }

        @ParameterizedTest
        @CsvSource({"33.77,33.77", "204.96,204.96", "349.98,349.98", "550.04,550.04", "40,40.00"})
        @DisplayName("the winning bid is written to the cent, not truncated")
        void writesTheBidToTheCent(String bid, String expected) throws Exception {
            stubEndedAuctionWithTopBid(bid);

            AuctionFinalizer.FinalizeResult r = AuctionFinalizer.finalizeIfEnded(conn, 12L);

            assertTrue(r.finalized);
            assertEquals(5, r.winnerId);
            assertEquals(new BigDecimal(expected), winningBidsWritten().get(0));
        }

        @Test
        @DisplayName("nothing is coerced through an int on the way in")
        void neverBindsWinningBidAsInt() throws Exception {
            stubEndedAuctionWithTopBid("33.77");

            AuctionFinalizer.finalizeIfEnded(conn, 12L);

            // setInt(1, winnerId) is expected; setInt on the amount parameter is the old bug.
            verify(ps, never()).setInt(eq(2), anyInt());
        }

        @Test
        @DisplayName("a concluded sale takes a unit out of stock")
        void decrementsStock() throws Exception {
            stubEndedAuctionWithTopBid("33.77");

            AuctionFinalizer.finalizeIfEnded(conn, 12L);

            assertTrue(preparedSql().stream()
                    .anyMatch(s -> s.contains("GREATEST(quantity - 1, 0)")));
        }

        @Test
        @DisplayName("an auction that ended with no bids writes no winning bid and no stock change")
        void noBidsNoWrite() throws Exception {
            when(rs.next()).thenReturn(true, false);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().minusSeconds(60)));

            AuctionFinalizer.FinalizeResult r = AuctionFinalizer.finalizeIfEnded(conn, 12L);

            assertTrue(r.finalized);
            assertEquals(-1, r.winnerId);
            verify(ps, never()).setBigDecimal(eq(2), any());
            assertFalse(preparedSql().stream()
                    .anyMatch(s -> s.contains("GREATEST(quantity - 1, 0)")));
        }

        @Test
        @DisplayName("an auction still running is left alone")
        void stillRunning() throws Exception {
            when(rs.next()).thenReturn(true);
            when(rs.getInt("status_id")).thenReturn(AuctionStatus.ACTIVE.getId());
            when(rs.getTimestamp("date_end"))
                    .thenReturn(Timestamp.from(Instant.now().plusSeconds(3600)));

            assertFalse(AuctionFinalizer.finalizeIfEnded(conn, 12L).finalized);
            verify(ps, never()).setBigDecimal(anyInt(), any());
        }
    }

    // ── OrderDAO: the read-back that becomes the order amount ────────────────

    @Nested
    @DisplayName("OrderDAO.ensureOrderForAuction")
    class OrderAmount {

        @Test
        @DisplayName("the order is created for exactly the winning bid, cents included")
        void orderAmountKeepsCents() throws Exception {
            // First query (existing order) finds nothing; second returns the auction row.
            when(rs.next()).thenReturn(false, true);
            when(rs.getInt("seller_id")).thenReturn(42);
            when(rs.getInt("winner_id")).thenReturn(5);
            when(rs.wasNull()).thenReturn(false);
            when(rs.getBigDecimal("winning_bid")).thenReturn(new BigDecimal("33.77"));

            new OrderDAO().ensureOrderForAuction(conn, 12L);

            ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
            verify(ps).setBigDecimal(eq(4), amount.capture());
            assertEquals(new BigDecimal("33.77"), amount.getValue());
        }

        @Test
        @DisplayName("an auction that already has an order is not charged twice")
        void idempotent() throws Exception {
            when(rs.next()).thenReturn(true);

            new OrderDAO().ensureOrderForAuction(conn, 12L);

            verify(ps, never()).setBigDecimal(eq(4), any());
        }
    }

    // ── Revenue aggregate ────────────────────────────────────────────────────

    @Nested
    @DisplayName("AuctionDAO.sumWinningBidDollars")
    class RevenueSum {

        @ParameterizedTest
        @CsvSource({"1104.98,1105", "204.96,205", "33.49,33", "33.50,34", "0.00,0"})
        @DisplayName("the total is rounded once at the end, not truncated per row")
        void roundsTheTotal(String sum, long expected) throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                when(rs.next()).thenReturn(true);
                when(rs.getBigDecimal(1)).thenReturn(new BigDecimal(sum));

                assertEquals(expected, new AuctionDAO().sumWinningBidDollars());
            }
        }

        @Test
        @DisplayName("no concluded listings is zero, not an exception")
        void nullSumIsZero() throws Exception {
            try (MockedStatic<DBUtil> db = mockStatic(DBUtil.class)) {
                db.when(DBUtil::connectDB).thenReturn(conn);
                when(rs.next()).thenReturn(true);
                when(rs.getBigDecimal(1)).thenReturn(null);

                assertEquals(0L, new AuctionDAO().sumWinningBidDollars());
            }
        }
    }

    // ── The stock decrement all four conclusion paths share ──────────────────

    @Nested
    @DisplayName("Stock follows the sale")
    class StockDecrement {

        @Test
        @DisplayName("one unit leaves per concluded sale, and never goes negative")
        void decrementsOnce() throws Exception {
            SellerAuctionDAO.decrementStockForSale(conn, 12L);

            assertEquals(1, preparedSql().size());
            assertTrue(preparedSql().get(0).contains("GREATEST(quantity - 1, 0)"));
            verify(ps).executeUpdate();
        }
    }
}
