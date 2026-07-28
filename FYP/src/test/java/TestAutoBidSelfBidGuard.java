import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.auction.dao.AutoBidDAO;
import com.auction.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * A seller must never end up bidding on their own listing.
 *
 * <p>{@link com.auction.dao.BidDAO} guards this on all four manual paths (standard,
 * Dutch, Buy It Now, sealed), but the proxy-bidding engine inserts into {@code bids}
 * with its own SQL and therefore needs its own guard. That matters now that buying and
 * selling share one account — before the merge a seller could not reach a bid endpoint
 * at all.</p>
 */
@DisplayName("Auto-bid self-bid guard")
class TestAutoBidSelfBidGuard {

    private static final long AUCTION_ID = 42L;
    private static final int  SELLER_ID  = 7;
    private static final int  BUYER_ID   = 9;

    private Connection mockConn;
    private PreparedStatement sellerStmt;
    private PreparedStatement startingPriceStmt;
    private PreparedStatement topBidStmt;
    private PreparedStatement autoBidsStmt;
    private PreparedStatement insertStmt;

    @BeforeEach
    void setUp() throws Exception {
        mockConn          = mock(Connection.class);
        sellerStmt        = mock(PreparedStatement.class);
        startingPriceStmt = mock(PreparedStatement.class);
        topBidStmt        = mock(PreparedStatement.class);
        autoBidsStmt      = mock(PreparedStatement.class);
        insertStmt        = mock(PreparedStatement.class);

        // Route each query to its own statement so we can assert on the INSERT.
        when(mockConn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("seller_id"))          return sellerStmt;
            if (sql.contains("starting_price"))     return startingPriceStmt;
            if (sql.contains("MAX") || sql.contains("ORDER BY bid_amount")) return topBidStmt;
            if (sql.contains("FROM auto_bids"))     return autoBidsStmt;
            if (sql.startsWith("INSERT INTO bids")) return insertStmt;
            return mock(PreparedStatement.class);
        });

        ResultSet sellerRs = mock(ResultSet.class);
        when(sellerRs.next()).thenReturn(true);
        when(sellerRs.getInt("seller_id")).thenReturn(SELLER_ID);
        when(sellerStmt.executeQuery()).thenReturn(sellerRs);

        ResultSet priceRs = mock(ResultSet.class);
        when(priceRs.next()).thenReturn(true);
        when(priceRs.getBigDecimal("starting_price")).thenReturn(new BigDecimal("10.00"));
        when(startingPriceStmt.executeQuery()).thenReturn(priceRs);

        // No bids placed yet.
        ResultSet topRs = mock(ResultSet.class);
        when(topRs.next()).thenReturn(false);
        when(topBidStmt.executeQuery()).thenReturn(topRs);
    }

    /** Builds the auto_bids result set: a single row for {@code userId}, max $500. */
    private void withSingleAutoBidRow(int userId) throws Exception {
        String encryptedMax = SecurityUtil.encrypt("500.00");
        Timestamp created = Timestamp.from(Instant.ofEpochSecond(1_000_000));

        when(autoBidsStmt.executeQuery()).thenAnswer(inv -> {
            // processAutoBids loops, re-reading the table each round; hand back a
            // fresh single-row cursor every time.
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getInt("user_id")).thenReturn(userId);
            when(rs.getString("max_amount_enc")).thenReturn(encryptedMax);
            when(rs.getBigDecimal("bid_increment")).thenReturn(new BigDecimal("0.01"));
            when(rs.getTimestamp("created_at")).thenReturn(created);
            return rs;
        });
    }

    @Test
    @DisplayName("the seller's own auto-bid row never places a bid")
    void sellerAutoBidIsIgnored() throws Exception {
        withSingleAutoBidRow(SELLER_ID);

        int placed = new AutoBidDAO().processAutoBids(mockConn, AUCTION_ID);

        assertEquals(0, placed, "seller's auto-bid must not fire on their own listing");
        verify(insertStmt, never()).executeUpdate();
    }

    @Test
    @DisplayName("a genuine buyer's auto-bid still fires")
    void buyerAutoBidStillWorks() throws Exception {
        withSingleAutoBidRow(BUYER_ID);

        int placed = new AutoBidDAO().processAutoBids(mockConn, AUCTION_ID);

        // Guard must not have suppressed a legitimate bidder.
        assertTrue(placed > 0, "a non-seller auto-bid should still be placed");
        verify(insertStmt, atLeastOnce()).executeUpdate();
    }
}
