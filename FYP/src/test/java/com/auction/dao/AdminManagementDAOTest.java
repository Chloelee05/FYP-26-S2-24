package com.auction.dao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Input guards on the admin management operations.
 *
 * <p>Every guard here rejects before a connection is taken, which is the property being
 * asserted: a malformed admin request must not open a transaction against production, and
 * must not be able to write a value the table's own CHECK constraint would refuse. The
 * behaviour of the operations once past the guards is covered by
 * {@code AdminManagementDAOIntegrationTest}, against a real database, because these
 * statements are mostly SQL and a mock would only assert that the strings did not change.</p>
 */
@DisplayName("AdminManagementDAO — request guards")
class AdminManagementDAOTest {

    private AdminManagementDAO dao;

    @BeforeEach
    void setUp() {
        dao = new AdminManagementDAO();
    }

    /**
     * Runs an assertion that must be satisfied by a guard alone.
     *
     * <p>No database is stubbed on purpose. Every case below has to be rejected before a
     * connection is taken, so a passing test is itself the evidence that no connection was
     * attempted: if a guard regresses and lets the request through, the DAO reaches
     * {@code DBUtil} and the assertion fails on the thrown {@code RuntimeException} rather
     * than on a wrong {@code Outcome}. Stubbing {@code DBUtil} statically would be worse —
     * it loads the class, and loading it is what reads the connection configuration.</p>
     */
    private void guardRejects(Runnable body) {
        body.run();
    }

    @Nested
    @DisplayName("listing content edits")
    class ListingContent {

        @ParameterizedTest
        @ValueSource(strings = { "", "   ", "\t\n" })
        @DisplayName("refuses a blank title")
        void blankTitle(String title) {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.updateListingContent(1, 5L, title, "body", "Electronics", "PRODUCT", "why")));
        }

        @Test
        @DisplayName("refuses a null title")
        void nullTitle() {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.updateListingContent(1, 5L, null, "body", "Electronics", "PRODUCT", "why")));
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "  " })
        @DisplayName("refuses a blank description")
        void blankDescription(String description) {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.updateListingContent(1, 5L, "Title", description, "Electronics", "PRODUCT", "why")));
        }

        @ParameterizedTest
        @ValueSource(strings = { "GOODS", "SERVICES", "OTHER", "'; DROP TABLE users; --" })
        @DisplayName("refuses a kind the listing_kind CHECK constraint would reject")
        void badKind(String kind) {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.updateListingContent(1, 5L, "Title", "body", "Electronics", kind, "why")));
        }
    }

    @Nested
    @DisplayName("listing reclassification")
    class ListingKind {

        @ParameterizedTest
        @ValueSource(strings = { "", "GOODS", "SERVICES", "PRODUCTS" })
        @DisplayName("refuses anything that is not PRODUCT or SERVICE")
        void badKind(String kind) {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.updateListingKind(1, 5L, kind, "why")));
        }

        @Test
        @DisplayName("refuses a null kind")
        void nullKind() {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.updateListingKind(1, 5L, null, "why")));
        }
    }

    @Nested
    @DisplayName("order state correction")
    class OrderStatus {

        @ParameterizedTest
        @ValueSource(strings = { "", "SHIPPED", "REFUNDED", "DELETED", "PAID; DROP TABLE orders" })
        @DisplayName("refuses a status outside the orders_status_check constraint")
        void badStatus(String status) {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.correctOrderStatus(1, 42L, status, "buyer paid by bank transfer")));
        }

        @Test
        @DisplayName("refuses a null status")
        void nullStatus() {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.correctOrderStatus(1, 42L, null, "reason")));
        }

        /**
         * A state change on someone else's transaction with no recorded justification is
         * indistinguishable from tampering when the row is read back later, so the reason is
         * part of the contract rather than an optional note.
         */
        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        @DisplayName("refuses a correction with no reason given")
        void blankReason(String reason) {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.correctOrderStatus(1, 42L, "COMPLETED", reason)));
        }

        @Test
        @DisplayName("refuses a correction with a null reason")
        void nullReason() {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.correctOrderStatus(1, 42L, "COMPLETED", null)));
        }
    }

    @Nested
    @DisplayName("customer deactivation")
    class Deactivation {

        @ParameterizedTest
        @ValueSource(strings = { "", "  " })
        @DisplayName("refuses a deactivation with no reason given")
        void blankReason(String reason) {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.deactivateCustomer(1, 77, 5, reason)));
        }

        @Test
        @DisplayName("refuses a deactivation with a null reason")
        void nullReason() {
            guardRejects(() -> assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.deactivateCustomer(1, 77, 5, null)));
        }
    }

    @Nested
    @DisplayName("live-commitment check")
    class LiveCommitments {

        private Connection conn;
        private PreparedStatement ps;
        private ResultSet rs;

        @BeforeEach
        void stubDb() throws Exception {
            conn = mock(Connection.class);
            ps = mock(PreparedStatement.class);
            rs = mock(ResultSet.class);
            when(conn.prepareStatement(anyString())).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true);
        }

        /**
         * The query asks about the account as seller and as buyer, so the same id has to be
         * bound three times. Binding two of the three silently narrows the check and lets an
         * account with an unpaid order be deactivated.
         */
        @Test
        @DisplayName("binds the account id to all three placeholders")
        void bindsAllPlaceholders() throws Exception {
            when(rs.getBoolean(1)).thenReturn(true);
            assertTrue(dao.hasLiveCommitments(conn, 77));
            verify(ps).setInt(1, 77);
            verify(ps).setInt(2, 77);
            verify(ps).setInt(3, 77);
        }

        @Test
        @DisplayName("considers both live listings and unsettled orders")
        void queriesBothSides() throws Exception {
            when(rs.getBoolean(1)).thenReturn(false);
            assertFalse(dao.hasLiveCommitments(conn, 77));

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn).prepareStatement(sql.capture());
            String q = sql.getValue();
            assertTrue(q.contains("FROM auction"), "should check live listings");
            assertTrue(q.contains("FROM orders"), "should check unsettled orders");
            assertTrue(q.contains("PENDING_PAYMENT"));
            assertTrue(q.contains("PAID"));
        }

        @Test
        @DisplayName("treats an empty result as no commitments rather than throwing")
        void emptyResultIsFalse() throws Exception {
            when(rs.next()).thenReturn(false);
            assertFalse(dao.hasLiveCommitments(conn, 77));
        }
    }
}
