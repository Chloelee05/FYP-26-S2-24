package com.auction.dao;

import com.auction.util.DBUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NEW for the "report filters by date range, category and seller" admin story:
 * {@code AdminReportDAO#generateRevenueReport(LocalDate, LocalDate, String)}, the filtered
 * overload alongside the pre-existing no-arg {@code generateRevenueReport()} (which this suite
 * never calls, and which is left completely untouched).
 *
 * <p>Each test stubs a fresh connection where every statement returns an empty/zero result, so
 * assertions focus on the filter clause and bound-parameter wiring rather than the report's
 * cosmetic body text.</p>
 */
@DisplayName("AdminReportDAO — filtered generateRevenueReport(from, to, category)")
class AdminReportDAOFilterTest {

    private final AdminReportDAO dao = new AdminReportDAO();

    /** Stubs a connection where every prepared statement returns a single zero/empty row. */
    private Connection mockConnection() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            String sql = inv.getArgument(0);
            if (sql.contains("ORDER BY rev DESC")) {
                when(rs.next()).thenReturn(false); // no sellers match
            } else {
                when(rs.next()).thenReturn(true);
                when(rs.getBigDecimal(1)).thenReturn(BigDecimal.ZERO);
            }
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        });
        return conn;
    }

    private String generate(Connection conn, LocalDate from, LocalDate to, String category) {
        try (MockedStatic<DBUtil> mocked = mockStatic(DBUtil.class)) {
            mocked.when(DBUtil::connectDB).thenReturn(conn);
            return dao.generateRevenueReport(from, to, category);
        }
    }

    @Test
    @DisplayName("with no filters at all, the header says so and no AND clause is added to any query")
    void noFiltersDescribedAndNoClauseAdded() throws Exception {
        Connection conn = mockConnection();

        String report = generate(conn, null, null, null);

        assertTrue(report.contains("Filters applied: none (showing all data)"));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        for (String sql : sqlCaptor.getAllValues()) {
            assertFalse(sql.contains(" AND "), "unfiltered query must carry no filter clause: " + sql);
        }
    }

    @Test
    @DisplayName("a date range alone binds two timestamp parameters and is named in the header")
    void dateRangeAloneBindsTimestamps() throws Exception {
        Connection conn = mockConnection();
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 1, 31);

        String report = generate(conn, from, to, null);

        assertTrue(report.contains("Filters applied: from 2025-01-01, to 2025-01-31"));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        long dateFilteredQueries = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.contains(">= ?") && sql.contains("< ?"))
                .count();
        // Revenue, orders and top-sellers queries all carry the date range.
        assertEquals(3, dateFilteredQueries);
        assertFalse(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("LOWER(")),
                "no category filter was requested, so no query should carry a LOWER(...) clause");
    }

    @Test
    @DisplayName("a category alone binds a case-insensitive match and is named in the header")
    void categoryAloneBindsLowerMatch() throws Exception {
        Connection conn = mockConnection();

        String report = generate(conn, null, null, "Electronics");

        assertTrue(report.contains("Filters applied: category = Electronics"));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        long categoryFilteredQueries = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.contains("LOWER(d.category) = LOWER(?)"))
                .count();
        assertEquals(3, categoryFilteredQueries);
    }

    @Test
    @DisplayName("both filters together bind date params before the category param, per bindParams' ordering")
    void combinedFiltersBindDatesBeforeCategory() throws Exception {
        Connection conn = mockConnection();
        LocalDate from = LocalDate.of(2025, 6, 1);
        LocalDate to = LocalDate.of(2025, 6, 30);

        // Capture the revenue-report statement specifically to check bind order.
        PreparedStatement revenuePs = mock(PreparedStatement.class);
        ResultSet revenueRs = mock(ResultSet.class);
        when(revenueRs.next()).thenReturn(true);
        when(revenueRs.getBigDecimal(1)).thenReturn(BigDecimal.ZERO);
        when(revenuePs.executeQuery()).thenReturn(revenueRs);
        when(conn.prepareStatement(contains("FROM auction_details d"))).thenReturn(revenuePs);

        generate(conn, from, to, "Books");

        verify(revenuePs).setTimestamp(eq(1), any());
        verify(revenuePs).setTimestamp(eq(2), any());
        verify(revenuePs).setString(eq(3), eq("Books"));
    }

    @Test
    @DisplayName("category matching trims surrounding whitespace before binding")
    void categoryIsTrimmedBeforeBinding() throws Exception {
        Connection conn = mockConnection();

        String report = generate(conn, null, null, "  Books  ");

        assertTrue(report.contains("Filters applied: category = Books"));
    }

    @Test
    @DisplayName("a blank category is treated the same as no category at all")
    void blankCategoryTreatedAsNoFilter() throws Exception {
        Connection conn = mockConnection();

        String report = generate(conn, null, null, "   ");

        assertTrue(report.contains("Filters applied: none (showing all data)"));
    }
}
