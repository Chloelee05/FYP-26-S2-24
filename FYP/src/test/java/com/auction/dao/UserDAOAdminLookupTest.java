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
 * {@link UserDAO#listAdminUserIds()} — the recipient list every admin-targeted notification
 * is fanned out over.
 *
 * <p>It compared {@code r.role = 'ADMIN'} and {@code s.status = 'Active'} against seed data
 * that spells the role {@code 'Admin'}. PostgreSQL string equality is case-sensitive, so the
 * join matched nothing and the list was always empty: pending-registration, report and
 * support alerts were dropped on the floor from the day they shipped. These tests pin the
 * comparison open on both columns.</p>
 */
@DisplayName("UserDAO — admin recipient lookup")
class UserDAOAdminLookupTest {

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

    private String capturedSql() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        return sql.getValue();
    }

    @Test
    @DisplayName("role is matched case-insensitively, so seeded 'Admin' rows are found")
    void roleComparisonIsCaseInsensitive() throws Exception {
        withDb(() -> {
            dao.listAdminUserIds();
            String sql = capturedSql();
            assertTrue(sql.contains("UPPER(r.role) = 'ADMIN'"),
                    "role must be folded before comparison, or seeded 'Admin' never matches: " + sql);
            assertFalse(sql.contains("r.role = 'ADMIN'"),
                    "a bare equality against 'ADMIN' is the bug this replaces: " + sql);
        });
    }

    @Test
    @DisplayName("status is matched case-insensitively too, for the same reason")
    void statusComparisonIsCaseInsensitive() throws Exception {
        withDb(() -> {
            dao.listAdminUserIds();
            String sql = capturedSql();
            assertTrue(sql.contains("UPPER(s.status) = 'ACTIVE'"), sql);
        });
    }

    @Test
    @DisplayName("returns every matching admin id")
    void collectsAllRows() throws Exception {
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getInt("id")).thenReturn(2, 31);
        withDb(() -> {
            List<Integer> ids = dao.listAdminUserIds();
            assertEquals(List.of(2, 31), ids);
        });
    }

    @Test
    @DisplayName("an empty result is an empty list rather than null")
    void emptyResult() throws Exception {
        when(rs.next()).thenReturn(false);
        withDb(() -> assertTrue(dao.listAdminUserIds().isEmpty()));
    }
}
