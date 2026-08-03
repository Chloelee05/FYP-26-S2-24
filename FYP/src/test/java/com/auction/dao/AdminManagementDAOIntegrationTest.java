package com.auction.dao;

import com.auction.util.DBUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admin management operations against a real PostgreSQL database.
 *
 * <p>These are the tests a mock cannot stand in for. The operations are almost entirely SQL:
 * a CHECK constraint on {@code listing_kind}, a CASE expression that has to leave
 * {@code paid_at} consistent with the new status, and an audit insert that must land in the
 * same transaction as the change it describes. Mocking the driver would assert that the
 * strings did not change, not that the database accepts them.
 *
 * <p>Opt in with {@code AUCTION_DB_IT=true} and point {@code AUCTION_DB_URL} at a scratch
 * database. Every row created here is namespaced {@code [IT-ADMIN-MGMT]} and removed in
 * {@link #tearDown()}; the suite refuses to run against the hosted database.</p>
 */
@EnabledIfEnvironmentVariable(named = "AUCTION_DB_IT", matches = "true")
@DisplayName("AdminManagementDAO — against a real database")
class AdminManagementDAOIntegrationTest {

    private static final String MARK = "[IT-ADMIN-MGMT]";

    private AdminManagementDAO dao;
    private int adminId;
    private int sellerId;
    private int buyerId;
    private long auctionId;
    private long orderId;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("AUCTION_DB_URL");
        assertNotNull(url, "AUCTION_DB_URL must be set for the integration suite");
        assertFalse(url.contains("render.com"),
                "refusing to run the integration suite against the hosted database");

        dao = new AdminManagementDAO();
        cleanUp();
        seed();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanUp();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void seed() throws Exception {
        try (Connection c = DBUtil.connectDB()) {
            adminId = insertUser(c, "it_mgmt_admin", 1);
            sellerId = insertUser(c, "it_mgmt_seller", 2);
            buyerId = insertUser(c, "it_mgmt_buyer", 2);

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
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO auction_details (id, title, description, category, starting_price, "
                  + "item_condition_id, listing_kind) VALUES (?, ?, ?, ?, 100, 1, 'PRODUCT')")) {
                ps.setLong(1, auctionId);
                ps.setString(2, MARK + " Original Title");
                ps.setString(3, MARK + " Original description text.");
                ps.setString(4, "Electronics");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO orders (auction_id, buyer_id, seller_id, amount, status, created_at) "
                  + "VALUES (?, ?, ?, 100.00, 'PENDING_PAYMENT', now()) RETURNING id")) {
                ps.setLong(1, auctionId);
                ps.setInt(2, buyerId);
                ps.setInt(3, sellerId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    orderId = rs.getLong(1);
                }
            }
        }
    }

    private int insertUser(Connection c, String username, int roleId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (username, email, password, role_id, status_id) "
              + "VALUES (?, ?, 'x', ?, 1) RETURNING id")) {
            ps.setString(1, username);
            ps.setString(2, username + "@it-admin-mgmt.test");
            ps.setInt(3, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private void cleanUp() throws Exception {
        try (Connection c = DBUtil.connectDB(); Statement st = c.createStatement()) {
            st.executeUpdate(
                "DELETE FROM admin_audit_log WHERE admin_id IN "
              + "(SELECT id FROM users WHERE email LIKE '%@it-admin-mgmt.test')");
            st.executeUpdate(
                "DELETE FROM orders WHERE auction_id IN (SELECT id FROM auction_details "
              + "WHERE title LIKE '" + MARK + "%')");
            st.executeUpdate(
                "DELETE FROM auction_details WHERE title LIKE '" + MARK + "%'");
            st.executeUpdate(
                "DELETE FROM auction WHERE seller_id IN "
              + "(SELECT id FROM users WHERE email LIKE '%@it-admin-mgmt.test')");
            st.executeUpdate("DELETE FROM users WHERE email LIKE '%@it-admin-mgmt.test'");
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection c = DBUtil.connectDB();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private int auditRows(String entityType, long entityId, String field) throws Exception {
        try (Connection c = DBUtil.connectDB();
             PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM admin_audit_log WHERE entity_type = ? AND entity_id = ? "
              + "AND (? IS NULL OR field_name = ?)")) {
            ps.setString(1, entityType);
            ps.setLong(2, entityId);
            ps.setString(3, field);
            ps.setString(4, field);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ── listing content ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("listing content correction")
    class ListingContent {

        @Test
        @DisplayName("writes the new copy and records the old value against the admin")
        void editsAndAudits() throws Exception {
            AdminManagementDAO.Outcome outcome = dao.updateListingContent(
                    adminId, auctionId, MARK + " Corrected Title",
                    MARK + " Corrected description.", "Home", "SERVICE",
                    "reported for a misleading title");
            assertEquals(AdminManagementDAO.Outcome.SUCCESS, outcome);

            assertEquals(MARK + " Corrected Title",
                    scalar("SELECT title FROM auction_details WHERE id = " + auctionId));
            assertEquals("Home",
                    scalar("SELECT category FROM auction_details WHERE id = " + auctionId));
            assertEquals("SERVICE",
                    scalar("SELECT listing_kind FROM auction_details WHERE id = " + auctionId));

            assertEquals(MARK + " Original Title",
                    scalar("SELECT old_value FROM admin_audit_log WHERE entity_id = " + auctionId
                         + " AND field_name = 'title'"));
            assertEquals("reported for a misleading title",
                    scalar("SELECT reason FROM admin_audit_log WHERE entity_id = " + auctionId
                         + " AND field_name = 'title'"));
        }

        @Test
        @DisplayName("leaves the seller's price alone")
        void neverTouchesPrice() throws Exception {
            String before = scalar("SELECT starting_price FROM auction_details WHERE id = " + auctionId);
            dao.updateListingContent(adminId, auctionId, MARK + " New", MARK + " New body",
                    null, null, "tidy up");
            assertEquals(before,
                    scalar("SELECT starting_price FROM auction_details WHERE id = " + auctionId));
        }

        @Test
        @DisplayName("keeps the existing category when none is supplied")
        void blankCategoryKeepsCurrent() throws Exception {
            dao.updateListingContent(adminId, auctionId, MARK + " New", MARK + " New body",
                    "  ", null, "tidy up");
            assertEquals("Electronics",
                    scalar("SELECT category FROM auction_details WHERE id = " + auctionId));
        }

        @Test
        @DisplayName("writes nothing at all when the submitted copy is identical")
        void unchangedWritesNoAudit() throws Exception {
            AdminManagementDAO.Outcome outcome = dao.updateListingContent(
                    adminId, auctionId, MARK + " Original Title",
                    MARK + " Original description text.", "Electronics", "PRODUCT", "no-op");
            assertEquals(AdminManagementDAO.Outcome.UNCHANGED, outcome);
            assertEquals(0, auditRows("LISTING", auctionId, null));
        }

        @Test
        @DisplayName("reports a missing listing rather than creating one")
        void missingListing() {
            assertEquals(AdminManagementDAO.Outcome.NOT_FOUND,
                    dao.updateListingContent(adminId, 999_999_999L, "T", "D", "C", "PRODUCT", "r"));
        }

        @Test
        @DisplayName("reads back the editable fields with the bid count for the edit form")
        void readsContentForForm() {
            Map<String, Object> content = dao.getListingContent(auctionId);
            assertNotNull(content);
            assertEquals(MARK + " Original Title", content.get("title"));
            assertEquals("PRODUCT", content.get("listingKind"));
            assertEquals("it_mgmt_seller", content.get("sellerUsername"));
            assertEquals(0, content.get("bidCount"));
        }

        @Test
        @DisplayName("returns null for a listing that does not exist")
        void readsMissingAsNull() {
            assertNull(dao.getListingContent(999_999_999L));
        }
    }

    @Nested
    @DisplayName("product / service reclassification")
    class Reclassification {

        @Test
        @DisplayName("moves a product to a service and logs the change")
        void reclassifies() throws Exception {
            assertEquals(AdminManagementDAO.Outcome.SUCCESS,
                    dao.updateListingKind(adminId, auctionId, "SERVICE", "it is a service"));
            assertEquals("SERVICE",
                    scalar("SELECT listing_kind FROM auction_details WHERE id = " + auctionId));
            assertEquals("PRODUCT",
                    scalar("SELECT old_value FROM admin_audit_log WHERE entity_id = " + auctionId
                         + " AND action = 'SET_KIND'"));
        }

        @Test
        @DisplayName("accepts a lower-case kind from the request body")
        void normalisesCase() throws Exception {
            assertEquals(AdminManagementDAO.Outcome.SUCCESS,
                    dao.updateListingKind(adminId, auctionId, "service", "r"));
            assertEquals("SERVICE",
                    scalar("SELECT listing_kind FROM auction_details WHERE id = " + auctionId));
        }

        @Test
        @DisplayName("is a no-op when the listing is already that kind")
        void alreadyThatKind() throws Exception {
            assertEquals(AdminManagementDAO.Outcome.UNCHANGED,
                    dao.updateListingKind(adminId, auctionId, "PRODUCT", "r"));
            assertEquals(0, auditRows("LISTING", auctionId, "listing_kind"));
        }

        @Test
        @DisplayName("counts listings by kind for the admin filter chips")
        void countsByKind() {
            dao.updateListingKind(adminId, auctionId, "SERVICE", "r");
            Map<String, Integer> counts = dao.countListingsByKind();
            assertTrue(counts.getOrDefault("SERVICE", 0) >= 1);
        }
    }

    @Nested
    @DisplayName("order state correction")
    class OrderCorrection {

        @Test
        @DisplayName("stamps paid_at when an order is corrected to PAID")
        void paidStampsPaidAt() throws Exception {
            assertNull(scalar("SELECT paid_at FROM orders WHERE id = " + orderId));
            assertEquals(AdminManagementDAO.Outcome.SUCCESS,
                    dao.correctOrderStatus(adminId, orderId, "PAID", "paid by bank transfer"));
            assertEquals("PAID", scalar("SELECT status FROM orders WHERE id = " + orderId));
            assertNotNull(scalar("SELECT paid_at FROM orders WHERE id = " + orderId),
                    "an order marked PAID with no paid_at breaks the revenue report");
        }

        @Test
        @DisplayName("stamps both paid_at and completed_at when jumped to COMPLETED")
        void completedStampsBoth() throws Exception {
            assertEquals(AdminManagementDAO.Outcome.SUCCESS,
                    dao.correctOrderStatus(adminId, orderId, "COMPLETED", "delivered in person"));
            assertNotNull(scalar("SELECT paid_at FROM orders WHERE id = " + orderId));
            assertNotNull(scalar("SELECT completed_at FROM orders WHERE id = " + orderId));
        }

        @Test
        @DisplayName("records a cancel reason when cancelled by an admin")
        void cancelledStampsReason() throws Exception {
            assertEquals(AdminManagementDAO.Outcome.SUCCESS,
                    dao.correctOrderStatus(adminId, orderId, "CANCELLED", "duplicate order"));
            assertNotNull(scalar("SELECT cancelled_at FROM orders WHERE id = " + orderId));
            assertNotNull(scalar("SELECT cancel_reason FROM orders WHERE id = " + orderId));
        }

        @Test
        @DisplayName("does not alter the amount the buyer owes")
        void neverTouchesAmount() throws Exception {
            String before = scalar("SELECT amount FROM orders WHERE id = " + orderId);
            dao.correctOrderStatus(adminId, orderId, "PAID", "r");
            assertEquals(before, scalar("SELECT amount FROM orders WHERE id = " + orderId));
        }

        @Test
        @DisplayName("keeps an existing timestamp rather than moving it")
        void preservesExistingTimestamp() throws Exception {
            dao.correctOrderStatus(adminId, orderId, "PAID", "first correction");
            String paidAt = scalar("SELECT paid_at FROM orders WHERE id = " + orderId);
            dao.correctOrderStatus(adminId, orderId, "COMPLETED", "second correction");
            assertEquals(paidAt, scalar("SELECT paid_at FROM orders WHERE id = " + orderId));
        }

        @Test
        @DisplayName("logs the correction with the previous status and the reason")
        void audits() throws Exception {
            dao.correctOrderStatus(adminId, orderId, "PAID", "paid by bank transfer");
            assertEquals("PENDING_PAYMENT",
                    scalar("SELECT old_value FROM admin_audit_log WHERE entity_type = 'ORDER' "
                         + "AND entity_id = " + orderId));
            assertEquals("paid by bank transfer",
                    scalar("SELECT reason FROM admin_audit_log WHERE entity_type = 'ORDER' "
                         + "AND entity_id = " + orderId));
        }

        @Test
        @DisplayName("is a no-op when the order is already in that state")
        void alreadyInState() throws Exception {
            assertEquals(AdminManagementDAO.Outcome.UNCHANGED,
                    dao.correctOrderStatus(adminId, orderId, "PENDING_PAYMENT", "r"));
            assertEquals(0, auditRows("ORDER", orderId, null));
        }

        @Test
        @DisplayName("reports a missing order rather than inserting one")
        void missingOrder() {
            assertEquals(AdminManagementDAO.Outcome.NOT_FOUND,
                    dao.correctOrderStatus(adminId, 999_999_999L, "PAID", "r"));
        }
    }

    @Nested
    @DisplayName("customer deactivation")
    class Deactivation {

        /** status_id 5 is the existing {@code Deleted} status; nothing new is introduced. */
        private static final int DELETED = 5;

        @Test
        @DisplayName("refuses while the account still has an unsettled order")
        void refusedWithLiveOrder() throws Exception {
            assertEquals(AdminManagementDAO.Outcome.INVALID,
                    dao.deactivateCustomer(adminId, buyerId, DELETED, "requested by user"));
            assertEquals("1", scalar("SELECT status_id FROM users WHERE id = " + buyerId));
        }

        @Test
        @DisplayName("soft-deletes once nothing is outstanding, keeping the row")
        void deactivatesWhenClear() throws Exception {
            try (Connection c = DBUtil.connectDB(); Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE orders SET status = 'CANCELLED' WHERE id = " + orderId);
                st.executeUpdate("UPDATE auction SET moderation_state = 'removed' "
                               + "WHERE auction_id = " + auctionId);
            }
            assertEquals(AdminManagementDAO.Outcome.SUCCESS,
                    dao.deactivateCustomer(adminId, buyerId, DELETED, "requested by user"));
            assertEquals(String.valueOf(DELETED),
                    scalar("SELECT status_id FROM users WHERE id = " + buyerId));
            assertNotNull(scalar("SELECT id FROM users WHERE id = " + buyerId),
                    "deactivation must be a soft delete so bids and orders keep their author");
        }

        @Test
        @DisplayName("is a no-op when the account is already deactivated")
        void alreadyDeactivated() throws Exception {
            try (Connection c = DBUtil.connectDB(); Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE users SET status_id = " + DELETED + " WHERE id = " + buyerId);
            }
            assertEquals(AdminManagementDAO.Outcome.UNCHANGED,
                    dao.deactivateCustomer(adminId, buyerId, DELETED, "r"));
        }

        @Test
        @DisplayName("reports a missing account rather than creating one")
        void missingUser() {
            assertEquals(AdminManagementDAO.Outcome.NOT_FOUND,
                    dao.deactivateCustomer(adminId, 999_999_999, DELETED, "r"));
        }
    }

    @Nested
    @DisplayName("audit trail")
    class AuditTrail {

        @Test
        @DisplayName("lists actions newest first with the acting admin's name")
        void listsNewestFirst() throws Exception {
            dao.updateListingKind(adminId, auctionId, "SERVICE", "first action");
            dao.correctOrderStatus(adminId, orderId, "PAID", "second action");

            List<Map<String, Object>> log = dao.listAuditLog(50);
            assertFalse(log.isEmpty());
            Map<String, Object> newest = log.get(0);
            assertEquals("second action", newest.get("reason"));
            assertEquals("it_mgmt_admin", newest.get("adminUsername"));
        }

        @Test
        @DisplayName("honours the row limit")
        void honoursLimit() {
            dao.updateListingContent(adminId, auctionId, MARK + " A", MARK + " B", "Home",
                    "SERVICE", "many field changes at once");
            assertEquals(1, dao.listAuditLog(1).size());
        }
    }
}
