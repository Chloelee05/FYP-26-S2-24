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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link UserDAO#listActiveUserIds()} — NEW for the "system-wide announcement" admin story.
 * This is the recipient list {@code NotificationService.broadcastAnnouncement} fans a message
 * out over, so it is worth pinning that it reaches every active account regardless of role and
 * excludes anything not in the {@code ACTIVE} status (suspended, deleted, pending, rejected).
 *
 * <p>Modelled on {@link UserDAOAdminLookupTest}, the existing test for the sibling
 * {@link UserDAO#listAdminUserIds()} lookup.</p>
 */
@DisplayName("UserDAO — active user recipient lookup (announcement broadcast)")
class UserDAOActiveLookupTest {

    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private UserDAO dao;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        dao = new UserDAO();
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

    @Test
    @DisplayName("filters on users.status_id bound to Status.ACTIVE, not a role or a different status")
    void filtersOnActiveStatus() throws Exception {
        when(rs.next()).thenReturn(false);
        withDb(() -> {
            dao.listActiveUserIds();
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn).prepareStatement(sql.capture());
            assertTrue(sql.getValue().contains("status_id"), sql.getValue());
            verify(ps).setInt(1, com.auction.model.Status.ACTIVE.getId());
        });
    }

    @Test
    @DisplayName("returns every active user id, across roles, with no role filter in the query")
    void collectsAllActiveUsers() throws Exception {
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getInt("id")).thenReturn(2, 9, 31);
        withDb(() -> {
            List<Integer> ids = dao.listActiveUserIds();
            assertEquals(List.of(2, 9, 31), ids);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(conn).prepareStatement(sql.capture());
            assertFalse(sql.getValue().toUpperCase().contains("ROLE"),
                    "a broadcast recipient list must not be role-scoped: " + sql.getValue());
        });
    }

    @Test
    @DisplayName("an empty result is an empty list rather than null")
    void emptyResult() throws Exception {
        when(rs.next()).thenReturn(false);
        withDb(() -> assertTrue(dao.listActiveUserIds().isEmpty()));
    }
}
