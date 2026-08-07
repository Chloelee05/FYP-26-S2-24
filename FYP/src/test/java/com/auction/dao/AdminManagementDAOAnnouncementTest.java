package com.auction.dao;

import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link AdminManagementDAO#recordAnnouncementBroadcast} — NEW for the "system-wide
 * announcement" admin story. Confirms the broadcast is written into the existing
 * {@code admin_audit_log} convention (same table, same columns as every other admin
 * management action in this class) rather than a new log of its own.
 */
@DisplayName("AdminManagementDAO — announcement audit entry")
class AdminManagementDAOAnnouncementTest {

    private AdminManagementDAO dao;
    private Connection conn;
    private PreparedStatement ps;

    @BeforeEach
    void setUp() throws Exception {
        dao = new AdminManagementDAO();
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
    }

    private void withDb(Runnable body) {
        try (MockedStatic<DBUtil> dbUtil = mockStatic(DBUtil.class)) {
            dbUtil.when(DBUtil::connectDB).thenReturn(conn);
            body.run();
        }
    }

    @Test
    @DisplayName("writes into admin_audit_log, the same table every other admin action uses")
    void writesToTheExistingAuditLog() throws Exception {
        withDb(() -> dao.recordAnnouncementBroadcast(7, "Maintenance", "Down 2-3am.", 42));

        verify(conn).prepareStatement(contains("INSERT INTO admin_audit_log"));
        verify(ps).setInt(1, 7);
        verify(ps).setString(2, "ANNOUNCEMENT");
        verify(ps).setLong(3, 0L);
        verify(ps).setString(4, "BROADCAST");
        verify(ps).setString(5, "title");
        verify(ps).setString(6, null);
        verify(ps).setString(7, "Maintenance (sent to 42 recipient(s))");
        verify(ps).setString(8, "Down 2-3am.");
        verify(ps).executeUpdate();
    }
}
