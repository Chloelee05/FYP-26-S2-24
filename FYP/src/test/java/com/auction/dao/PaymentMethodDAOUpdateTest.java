package com.auction.dao;

import com.auction.model.PaymentMethod;
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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link PaymentMethodDAO} — the update path added for "maintain (create/update/delete)
 * personal account details", plus the ordering fix in {@link PaymentMethodDAO#setDefault}.
 *
 * <p>Every statement is scoped to the owner <em>and</em> to the method type, so a card row
 * cannot be turned into a PayPal row by a request that claims a different type, and one
 * member can never edit another's stored method.</p>
 */
@DisplayName("PaymentMethodDAO — update path")
class PaymentMethodDAOUpdateTest {

    private Connection conn;
    private PreparedStatement ps;
    private PaymentMethodDAO dao;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        dao = new PaymentMethodDAO();
    }

    private void withDb(ThrowingRunnable body) throws Exception {
        try (MockedStatic<DBUtil> dbUtil = mockStatic(DBUtil.class)) {
            dbUtil.when(DBUtil::connectDB).thenReturn(conn);
            body.run();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private String capturedSql() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        return sql.getValue();
    }

    // ── findForUser ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findForUser reads one row scoped to the owner")
    void findForUserIsOwnerScoped() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong("id")).thenReturn(5L);
        when(rs.getString("method_type")).thenReturn("CARD");
        when(rs.getString("card_holder")).thenReturn("Alice Tan");
        when(rs.getString("card_brand")).thenReturn("Visa");
        when(rs.getString("card_last4")).thenReturn("4242");
        when(rs.getInt("exp_month")).thenReturn(12);
        when(rs.getInt("exp_year")).thenReturn(2030);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));

        withDb(() -> {
            PaymentMethod found = dao.findForUser(8, 5L);
            assertNotNull(found);
            assertEquals("CARD", found.getMethodType());
            assertEquals("Alice Tan", found.getCardHolder());
            String sql = capturedSql();
            assertTrue(sql.contains("WHERE id = ? AND user_id = ?"), sql);
            verify(ps).setLong(1, 5L);
            verify(ps).setInt(2, 8);
        });
    }

    @Test
    @DisplayName("findForUser returns null for an id that is not the caller's")
    void findForUserMissIsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        withDb(() -> assertNull(dao.findForUser(8, 99L)));
    }

    // ── updateCard ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateCard writes holder + expiry only, scoped to owner and CARD")
    void updateCardScopeAndColumns() throws Exception {
        withDb(() -> {
            assertTrue(dao.updateCard(8, 5L, "Alice B Tan", 9, 2031));
            String sql = capturedSql();
            assertTrue(sql.contains("SET card_holder = ?, exp_month = ?, exp_year = ?"), sql);
            assertTrue(sql.contains("AND user_id = ?"), sql);
            assertTrue(sql.contains("AND method_type = 'CARD'"), sql);
            // The encrypted PAN, brand and last4 are never rewritten by an edit.
            assertFalse(sql.contains("card_number_enc"), sql);
            assertFalse(sql.contains("card_brand"), sql);
            assertFalse(sql.contains("card_last4"), sql);

            verify(ps).setString(1, "Alice B Tan");
            verify(ps).setInt(2, 9);
            verify(ps).setInt(3, 2031);
            verify(ps).setLong(4, 5L);
            verify(ps).setInt(5, 8);
        });
    }

    @Test
    @DisplayName("updateCard is false when the row is not the caller's")
    void updateCardMissIsFalse() throws Exception {
        when(ps.executeUpdate()).thenReturn(0);
        withDb(() -> assertFalse(dao.updateCard(8, 99L, "Mallory", 1, 2031)));
    }

    // ── updatePaypal / updateBankTransfer ───────────────────────────────────────

    @Test
    @DisplayName("updatePaypal rewrites only account_ref, scoped to PAYPAL")
    void updatePaypalScope() throws Exception {
        withDb(() -> {
            assertTrue(dao.updatePaypal(8, 6L, "new@paypal.com"));
            String sql = capturedSql();
            assertTrue(sql.contains("SET account_ref = ?"), sql);
            assertTrue(sql.contains("AND method_type = 'PAYPAL'"), sql);
            verify(ps).setString(1, "new@paypal.com");
            verify(ps).setLong(2, 6L);
            verify(ps).setInt(3, 8);
        });
    }

    @Test
    @DisplayName("updateBankTransfer rewrites holder + bank name, scoped to BANK_TRANSFER")
    void updateBankScope() throws Exception {
        withDb(() -> {
            assertTrue(dao.updateBankTransfer(8, 7L, "Alice B Tan", "OCBC"));
            String sql = capturedSql();
            assertTrue(sql.contains("SET card_holder = ?, account_ref = ?"), sql);
            assertTrue(sql.contains("AND method_type = 'BANK_TRANSFER'"), sql);
            assertFalse(sql.contains("card_number_enc"), sql);
            verify(ps).setString(1, "Alice B Tan");
            verify(ps).setString(2, "OCBC");
            verify(ps).setLong(3, 7L);
            verify(ps).setInt(4, 8);
        });
    }

    // ── setDefault ordering ─────────────────────────────────────────────────────

    @Test
    @DisplayName("setDefault on an id that is not the caller's leaves the existing default alone")
    void setDefaultMissDoesNotClearTheRealDefault() throws Exception {
        PreparedStatement promote = mock(PreparedStatement.class);
        PreparedStatement clear = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("is_default = TRUE"))).thenReturn(promote);
        when(conn.prepareStatement(contains("is_default = FALSE"))).thenReturn(clear);
        when(promote.executeUpdate()).thenReturn(0);

        withDb(() -> {
            assertFalse(dao.setDefault(8, 99L));
            // Clearing before promoting would have wiped the member's real default and left
            // the account with none, while still reporting failure.
            verify(clear, never()).executeUpdate();
            verify(conn).rollback();
            verify(conn, never()).commit();
        });
    }

    @Test
    @DisplayName("setDefault promotes, then clears the flag on the member's other methods")
    void setDefaultPromotesThenClears() throws Exception {
        PreparedStatement promote = mock(PreparedStatement.class);
        PreparedStatement clear = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("is_default = TRUE"))).thenReturn(promote);
        when(conn.prepareStatement(contains("is_default = FALSE"))).thenReturn(clear);
        when(promote.executeUpdate()).thenReturn(1);
        when(clear.executeUpdate()).thenReturn(1);

        withDb(() -> {
            assertTrue(dao.setDefault(8, 5L));
            verify(promote).setLong(1, 5L);
            verify(promote).setInt(2, 8);
            // The newly promoted row must be excluded from the clear, or it demotes itself.
            verify(clear).setInt(1, 8);
            verify(clear).setLong(2, 5L);
            verify(conn).commit();
        });
    }
}
