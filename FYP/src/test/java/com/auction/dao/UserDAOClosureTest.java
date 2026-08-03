package com.auction.dao;

import com.auction.model.AuctionStatus;
import com.auction.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link UserDAO#closeAccount(int)} — the clean-up account closure implies for a departing
 * member's open business (Seller a-3), on mocked JDBC.
 *
 * <p>Closure used to anonymise the {@code users} row and stop. The member's ACTIVE listings
 * stayed live and biddable, so people could go on bidding — and winning — against a seller
 * who no longer exists and cannot despatch anything, and their open orders were left
 * dangling. The anonymisation itself is unchanged and still the right design: the primary key
 * survives so bids and auctions other members took part in stay valid.</p>
 *
 * <p><b>What these tests cannot prove.</b> Every statement here runs against a Mockito mock,
 * so they verify the SQL that is issued, the values bound to it and the transaction boundary
 * — not that PostgreSQL accepts the SQL or that the row counts come out as expected. In
 * particular, {@code cancel_reason = 'ACCOUNT_CLOSED'} has to satisfy a CHECK constraint that
 * only the real database enforces; that half is covered by applying
 * {@code migration_account_closure.sql} and exercising closure against the live schema.</p>
 */
@DisplayName("UserDAO closeAccount — departing member's open business")
class UserDAOClosureTest {

    private static final int USER_ID = 40;

    private Connection conn;
    private PreparedStatement anonymise;
    private PreparedStatement cancelListings;
    private PreparedStatement selectUnpaid;
    private PreparedStatement cancelUnpaid;
    private PreparedStatement selectRefundable;
    private PreparedStatement raiseRefund;
    private PreparedStatement selectHandover;
    private UserDAO dao;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        when(conn.getAutoCommit()).thenReturn(true);

        anonymise = stubbed("UPDATE users");
        stubbed("telegram_links");
        stubbed("telegram_outbox");
        cancelListings = stubbed("UPDATE auction");
        selectUnpaid = stubbed("'PENDING_PAYMENT' AND");
        cancelUnpaid = stubbed("SET status = 'CANCELLED'");
        selectRefundable = stubbed("o.seller_id = ? AND o.status = 'PAID'");
        raiseRefund = stubbed("SET refund_status = 'REQUESTED'");
        selectHandover = stubbed("shipping_status IS NOT NULL");

        when(anonymise.executeUpdate()).thenReturn(1);
        emptyResult(cancelListings);
        emptyResult(selectUnpaid);
        emptyResult(selectRefundable);
        emptyResult(selectHandover);

        dao = new UserDAO();
    }

    /** Registers a mock statement for every SQL string containing {@code marker}. */
    private PreparedStatement stubbed(String marker) throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains(marker))).thenReturn(ps);
        return ps;
    }

    private static void emptyResult(PreparedStatement ps) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(ps.executeQuery()).thenReturn(rs);
    }

    /**
     * Makes {@code ps} return one order row. {@code buyerId}/{@code sellerId} decide which
     * side of the order the departing member is on.
     */
    private static void oneOrderRow(PreparedStatement ps, long orderId, int buyerId,
                                    int sellerId, String title) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong("id")).thenReturn(orderId);
        when(rs.getInt("buyer_id")).thenReturn(buyerId);
        when(rs.getInt("seller_id")).thenReturn(sellerId);
        when(rs.getString("title")).thenReturn(title);
        when(ps.executeQuery()).thenReturn(rs);
    }

    // ── Listings ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("live listings")
    class Listings {

        @Test
        @DisplayName("ACTIVE and PENDING listings are cancelled with a reason naming the closure")
        void cancelsOpenListings() throws Exception {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getLong(1)).thenReturn(6L, 9L);
            when(cancelListings.executeQuery()).thenReturn(rs);

            UserDAO.ClosureImpact impact = dao.closeAccountWithConnection(conn, USER_ID);

            assertEquals(List.of(6L, 9L), impact.getCancelledListingIds());
            verify(cancelListings).setInt(1, AuctionStatus.CANCELLED.getId());
            verify(cancelListings).setString(2, UserDAO.LISTING_CANCEL_REASON);
            verify(cancelListings).setInt(3, USER_ID);
            verify(cancelListings).setInt(4, AuctionStatus.ACTIVE.getId());
            verify(cancelListings).setInt(5, AuctionStatus.PENDING.getId());
        }

        @Test
        @DisplayName("FINISHED and already-CANCELLED listings are left alone")
        void onlyOpenStatesAreTouched() throws Exception {
            dao.closeAccountWithConnection(conn, USER_ID);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn, atLeastOnce()).prepareStatement(sql.capture());
            String listingSql = sql.getAllValues().stream()
                    .filter(s -> s.startsWith("UPDATE auction"))
                    .findFirst().orElseThrow();
            assertTrue(listingSql.contains("status_id IN (?, ?)"), listingSql);
            assertTrue(listingSql.contains("seller_id = ?"), listingSql);
        }
    }

    // ── Orders ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("open orders")
    class Orders {

        @Test
        @DisplayName("an unpaid sale is cancelled and the buyer is named as the one to tell")
        void unpaidSaleCancelled() throws Exception {
            oneOrderRow(selectUnpaid, 12L, 3, USER_ID, "Vintage Rolex");

            UserDAO.ClosureImpact impact = dao.closeAccountWithConnection(conn, USER_ID);

            assertEquals(1, impact.getCancelledOrders().size());
            UserDAO.AffectedOrder order = impact.getCancelledOrders().get(0);
            assertEquals(12L, order.getOrderId());
            assertEquals(3, order.getCounterpartyId());
            assertTrue(order.isCounterpartyBuyer());
            assertEquals("Vintage Rolex", order.getItemTitle());

            verify(cancelUnpaid).setString(1, UserDAO.ORDER_CANCEL_REASON);
            verify(cancelUnpaid).setLong(2, 12L);
            verify(cancelUnpaid).executeBatch();
        }

        @Test
        @DisplayName("an unpaid purchase is cancelled and the seller is the one to tell")
        void unpaidPurchaseCancelled() throws Exception {
            oneOrderRow(selectUnpaid, 13L, USER_ID, 27, "Monarch");

            UserDAO.ClosureImpact impact = dao.closeAccountWithConnection(conn, USER_ID);

            UserDAO.AffectedOrder order = impact.getCancelledOrders().get(0);
            assertEquals(27, order.getCounterpartyId());
            assertFalse(order.isCounterpartyBuyer(), "the seller is the party still here");
        }

        @Test
        @DisplayName("with nothing unpaid, no cancellation statement is issued at all")
        void noUnpaidOrdersNoUpdate() throws Exception {
            dao.closeAccountWithConnection(conn, USER_ID);
            verify(cancelUnpaid, never()).executeBatch();
        }

        @Test
        @DisplayName("a paid, undespatched sale is flagged for refund rather than cancelled")
        void paidUndespatchedSaleRaisesRefund() throws Exception {
            oneOrderRow(selectRefundable, 10L, 1, USER_ID, "Cathedral Ring");

            UserDAO.ClosureImpact impact = dao.closeAccountWithConnection(conn, USER_ID);

            assertEquals(1, impact.getRefundDueOrders().size());
            UserDAO.AffectedOrder order = impact.getRefundDueOrders().get(0);
            assertEquals(10L, order.getOrderId());
            assertEquals(1, order.getCounterpartyId(), "the buyer who is owed the money");
            assertTrue(order.isCounterpartyBuyer());

            verify(raiseRefund).setString(1, UserDAO.REFUND_REASON);
            verify(raiseRefund).setLong(2, 10L);
            verify(raiseRefund).executeBatch();
            // The buyer paid. Cancelling the order would make their payment vanish with the
            // seller, so the order stays PAID and enters the admin refund queue instead.
            verify(cancelUnpaid, never()).executeBatch();
        }

        @Test
        @DisplayName("an order that already carries a refund decision is not overwritten")
        void existingRefundDecisionIsSkipped() throws Exception {
            oneOrderRow(selectRefundable, 10L, 1, USER_ID, "Cathedral Ring");
            dao.closeAccountWithConnection(conn, USER_ID);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn, atLeastOnce()).prepareStatement(sql.capture());
            String select = sql.getAllValues().stream()
                    .filter(s -> s.contains("o.seller_id = ? AND o.status = 'PAID'"))
                    .findFirst().orElseThrow();
            assertTrue(select.contains("o.refund_status IS NULL"), select);
            String update = sql.getAllValues().stream()
                    .filter(s -> s.contains("SET refund_status = 'REQUESTED'"))
                    .findFirst().orElseThrow();
            assertTrue(update.contains("refund_status IS NULL"),
                    "the write re-checks the condition, so a concurrent request cannot be clobbered: " + update);
        }

        @Test
        @DisplayName("a sale already in transit is reported but never written to")
        void inFlightSaleIsReadOnly() throws Exception {
            oneOrderRow(selectHandover, 7L, 3, USER_ID, "Brown Penny Loafers");

            UserDAO.ClosureImpact impact = dao.closeAccountWithConnection(conn, USER_ID);

            assertEquals(1, impact.getHandoverOrders().size());
            assertEquals(3, impact.getHandoverOrders().get(0).getCounterpartyId());
            verify(selectHandover, never()).executeUpdate();
            verify(selectHandover, never()).executeBatch();
        }

        @Test
        @DisplayName("only PREPARING / not-yet-shipped sales qualify for the automatic refund")
        void refundOnlyBeforeDespatch() throws Exception {
            dao.closeAccountWithConnection(conn, USER_ID);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn, atLeastOnce()).prepareStatement(sql.capture());
            String select = sql.getAllValues().stream()
                    .filter(s -> s.contains("o.seller_id = ? AND o.status = 'PAID'"))
                    .findFirst().orElseThrow();
            assertTrue(select.contains("shipping_status IS NULL OR o.shipping_status = 'PREPARING'"),
                    select);
        }
    }

    // ── Transaction and the preserved anonymisation ─────────────────────────────

    @Nested
    @DisplayName("transaction")
    class Transaction {

        @Test
        @DisplayName("the anonymisation and every clean-up commit together, once")
        void oneTransaction() throws Exception {
            dao.closeAccountWithConnection(conn, USER_ID);

            verify(conn).setAutoCommit(false);
            verify(conn, times(1)).commit();
            verify(conn, never()).rollback();
            verify(anonymise).setInt(4, Status.DELETED.getId());
            verify(anonymise).setInt(5, USER_ID);
        }

        @Test
        @DisplayName("a clean-up failure rolls the anonymisation back with it")
        void cleanupFailureRollsBackAnonymisation() throws Exception {
            when(cancelListings.executeQuery()).thenThrow(new SQLException("constraint"));

            assertThrows(SQLException.class, () -> dao.closeAccountWithConnection(conn, USER_ID));
            verify(conn).rollback();
            verify(conn, never()).commit();
        }

        @Test
        @DisplayName("deleteAccount still answers a plain boolean for the legacy JSP path")
        void legacyBooleanApiPreserved() throws Exception {
            assertTrue(dao.deleteAccountWithConnection(conn, USER_ID));

            when(anonymise.executeUpdate()).thenReturn(0);
            assertFalse(dao.deleteAccountWithConnection(conn, USER_ID));
        }
    }
}
