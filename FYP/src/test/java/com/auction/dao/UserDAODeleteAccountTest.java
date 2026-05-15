package com.auction.dao;

import com.auction.model.Status;
import com.auction.util.SecurityUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SCRUM-190: secure account deletion / anonymisation in {@link UserDAO} (mocked JDBC).
 */
@DisplayName("UserDAO deleteAccount (SCRUM-190)")
class UserDAODeleteAccountTest {

    @Test
    @DisplayName("Anonymizes PII, sets DELETED status, hashes replacement password via SecurityUtil")
    void deleteAccountWithConnection_updatesExpectedColumns() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.prepareStatement(contains("UPDATE users"))).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        UserDAO dao = new UserDAO();
        assertTrue(dao.deleteAccountWithConnection(conn, 7));

        ArgumentCaptor<String> emailCap = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(1), emailCap.capture());
        String email = emailCap.getValue();
        assertTrue(email.startsWith("deleted_7_"), email);
        assertTrue(email.endsWith("@invalid.auction.local"), email);

        ArgumentCaptor<String> userCap = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(2), userCap.capture());
        assertTrue(userCap.getValue().startsWith("deleted_u7_"));

        ArgumentCaptor<String> passCap = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(3), passCap.capture());
        assertTrue(passCap.getValue().startsWith("1$"),
                "password must be SecurityUtil salted hash format");
        assertFalse(SecurityUtil.verifyPassword("wrong-guess", passCap.getValue()));

        verify(ps).setInt(4, Status.DELETED.getId());
        verify(ps).setInt(5, 7);
        verify(conn).commit();
        verify(conn, never()).rollback();
    }

    @Test
    @DisplayName("Returns false when no row matches id")
    void deleteAccountWithConnection_noRow() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(0);

        UserDAO dao = new UserDAO();
        assertFalse(dao.deleteAccountWithConnection(conn, 999));
        verify(conn).commit();
    }

    @Test
    @DisplayName("ROLLBACK on failure")
    void deleteAccountWithConnection_rollsBack() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenThrow(new SQLException("db error"));

        UserDAO dao = new UserDAO();
        assertThrows(SQLException.class, () -> dao.deleteAccountWithConnection(conn, 1));
        verify(conn).rollback();
    }
}
