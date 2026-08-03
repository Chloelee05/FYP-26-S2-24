package com.auction.dao;

import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Auto-cancellation of unpaid winning bids past the configurable payment deadline.
 *
 * <p>Design decision under test throughout: a cancelled order does not re-award the auction
 * to a next-highest bidder and does not relist it — the auction row is never touched, only
 * {@code orders}. See {@code OrderDAO#cancelOverduePendingOrders} and
 * migration_order_payment_timeout.sql for the full reasoning.</p>
 */
@DisplayName("OrderDAO — auto-cancel unpaid orders past the payment deadline")
class OrderDAOPaymentTimeoutTest {

    private static final Duration DEADLINE = Duration.ofHours(48);
    private static final Instant EFFECTIVE_SINCE = Instant.parse("2020-01-01T00:00:00Z");

    private Connection conn;
    private PreparedStatement selectPs;
    private PreparedStatement updatePs;
    private OrderDAO orderDAO;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        selectPs = mock(PreparedStatement.class);
        updatePs = mock(PreparedStatement.class);
        orderDAO = new OrderDAO();

        when(conn.prepareStatement(contains("SELECT id FROM orders"))).thenReturn(selectPs);
        when(conn.prepareStatement(contains("UPDATE orders SET status = 'CANCELLED'")))
                .thenReturn(updatePs);
    }

    private void stubOverdueIds(long... ids) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        if (ids.length == 0) {
            when(rs.next()).thenReturn(false);
        } else {
            Boolean[] nexts = new Boolean[ids.length + 1];
            for (int i = 0; i < ids.length; i++) nexts[i] = true;
            nexts[ids.length] = false;
            when(rs.next()).thenReturn(nexts[0], java.util.Arrays.copyOfRange(nexts, 1, nexts.length));
            Long first = ids[0];
            Long[] rest = new Long[ids.length - 1];
            for (int i = 1; i < ids.length; i++) rest[i - 1] = ids[i];
            when(rs.getLong("id")).thenReturn(first, rest);
        }
        when(selectPs.executeQuery()).thenReturn(rs);
    }

    private List<Long> cancel() {
        try (MockedStatic<DBUtil> mocked = mockStatic(DBUtil.class)) {
            mocked.when(DBUtil::connectDB).thenReturn(conn);
            return orderDAO.cancelOverduePendingOrders(DEADLINE, EFFECTIVE_SINCE);
        }
    }

    @Test
    @DisplayName("An order past the deadline is cancelled with reason PAYMENT_TIMEOUT")
    void overdueOrderIsCancelled() throws Exception {
        stubOverdueIds(101L);

        List<Long> cancelled = cancel();

        assertEquals(List.of(101L), cancelled);
        verify(conn).commit();
        verify(updatePs).setLong(1, 101L);
        verify(updatePs).addBatch();
        verify(updatePs).executeBatch();
    }

    @Test
    @DisplayName("An order within the deadline is left alone (the SELECT excludes it; no UPDATE runs)")
    void orderWithinDeadlineIsUntouched() throws Exception {
        stubOverdueIds(); // simulates the SQL's created_at < cutoff excluding a recent order

        List<Long> cancelled = cancel();

        assertTrue(cancelled.isEmpty());
        verify(conn, never()).prepareStatement(contains("UPDATE orders SET status = 'CANCELLED'"));
        verify(conn).commit();
    }

    @Test
    @DisplayName("A PAID order is never touched by this logic, however old it is — the SELECT is "
            + "scoped to PENDING_PAYMENT only")
    void paidOrderNeverTouchedEvenIfOld() throws Exception {
        stubOverdueIds(); // a real DB filters status = 'PENDING_PAYMENT' out at the SELECT

        cancel();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().stream()
                .anyMatch(s -> s.contains("status = 'PENDING_PAYMENT'")),
                "the SELECT must scope to PENDING_PAYMENT so a PAID order can never be selected");
    }

    @Test
    @DisplayName("Cancelling never touches the auction or auction_details tables — the auction's "
            + "final state (unsold, per the design decision) is left exactly as it was")
    void neverTouchesTheAuctionTables() throws Exception {
        stubOverdueIds(101L, 102L);

        cancel();

        verify(conn, never()).prepareStatement(contains("auction"));
        verify(conn, never()).prepareStatement(contains("winner_id"));
    }

    @Test
    @DisplayName("Multiple overdue orders are all cancelled in one batch")
    void multipleOverdueOrdersAllCancelled() throws Exception {
        stubOverdueIds(201L, 202L, 203L);

        List<Long> cancelled = cancel();

        assertEquals(List.of(201L, 202L, 203L), cancelled);
        verify(updatePs, times(3)).addBatch();
        verify(updatePs).executeBatch();
    }

    @Test
    @DisplayName("Orders that predate the feature's effective-since cutoff are excluded from the "
            + "query, i.e. grandfathered forever")
    void grandfatheredOrdersAreExcludedByTheQuery() throws Exception {
        stubOverdueIds();
        cancel();

        verify(selectPs).setTimestamp(eq(2), eq(Timestamp.from(EFFECTIVE_SINCE)));
    }

    @Test
    @DisplayName("The cutoff passed to the query is Instant.now() minus the configured deadline")
    void cutoffReflectsTheConfiguredDeadline() throws Exception {
        stubOverdueIds();
        Instant before = Instant.now().minus(DEADLINE);
        cancel();
        Instant after = Instant.now().minus(DEADLINE);

        ArgumentCaptor<Timestamp> captor = ArgumentCaptor.forClass(Timestamp.class);
        verify(selectPs).setTimestamp(eq(1), captor.capture());
        Instant cutoff = captor.getValue().toInstant();
        assertFalse(cutoff.isBefore(before.minusSeconds(2)));
        assertFalse(cutoff.isAfter(after.plusSeconds(2)));
    }
}
