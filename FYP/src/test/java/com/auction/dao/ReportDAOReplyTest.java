package com.auction.dao;

import com.auction.util.DBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Routing of admin replies between the two report tables.
 *
 * <p>{@code seller_reports} and {@code account_reports} number their rows from independent
 * sequences, so id 4 exists in both and names two unrelated reports. The DAO used to fall
 * back from one table to the other when {@code type} was missing or unrecognised, which
 * could attach an admin's reply to a stranger's report; these pin the routing shut.</p>
 */
@DisplayName("ReportDAO — admin reply routing")
class ReportDAOReplyTest {

    private Connection conn;
    private PreparedStatement ps;
    private ReportDAO dao;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        dao = new ReportDAO();
    }

    /** Runs {@code body} with {@link DBUtil#connectDB()} handing back the mocked connection. */
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
    @DisplayName("type=listing updates seller_reports")
    void listingGoesToSellerReports() throws Exception {
        withDb(() -> {
            assertTrue(dao.replyToReport(4L, "listing", "Looked into it."));
            String sql = capturedSql();
            assertTrue(sql.contains("UPDATE seller_reports"), sql);
            assertFalse(sql.contains("account_reports"), sql);
        });
    }

    @Test
    @DisplayName("type=account updates account_reports")
    void accountGoesToAccountReports() throws Exception {
        withDb(() -> {
            assertTrue(dao.replyToReport(4L, "account", "Looked into it."));
            String sql = capturedSql();
            assertTrue(sql.contains("UPDATE account_reports"), sql);
            assertFalse(sql.contains("seller_reports"), sql);
        });
    }

    @Test
    @DisplayName("an id that is absent from its own table does not fall through to the other")
    void missFromOneTableDoesNotRetryTheOther() throws Exception {
        when(ps.executeUpdate()).thenReturn(0);
        withDb(() -> {
            assertFalse(dao.replyToReport(4L, "account", "Looked into it."));
            // One statement only: the second attempt is what could have hit an unrelated
            // listing report that merely shares the number.
            verify(conn, times(1)).prepareStatement(anyString());
            assertTrue(capturedSql().contains("account_reports"));
        });
    }

    @Test
    @DisplayName("a missing type is refused without touching either table")
    void missingTypeIsRefused() throws Exception {
        withDb(() -> {
            assertFalse(dao.replyToReport(4L, null, "Looked into it."));
            verify(conn, never()).prepareStatement(anyString());
        });
    }

    @Test
    @DisplayName("an unrecognised type is refused without touching either table")
    void unknownTypeIsRefused() throws Exception {
        withDb(() -> {
            assertFalse(dao.replyToReport(4L, "auction", "Looked into it."));
            verify(conn, never()).prepareStatement(anyString());
        });
    }

    @Test
    @DisplayName("reportTable maps only the two known discriminators, case-insensitively")
    void reportTableMapping() {
        assertEquals("seller_reports", ReportDAO.reportTable("listing"));
        assertEquals("seller_reports", ReportDAO.reportTable("LISTING"));
        assertEquals("account_reports", ReportDAO.reportTable("account"));
        assertEquals("account_reports", ReportDAO.reportTable("Account"));
        assertNull(ReportDAO.reportTable(null));
        assertNull(ReportDAO.reportTable(""));
        assertNull(ReportDAO.reportTable("seller_reports"));
    }
}
