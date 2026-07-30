import com.auction.dao.RecommendationDAO;
import com.auction.model.SearchResultItem;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("RecommendationDAO explainability")
class TestRecommendationProvenance {

    private static SearchResultItem item(long id, String title, String category) {
        return new SearchResultItem(id, title, category, BigDecimal.TEN,
                Instant.parse("2026-12-31T00:00:00Z"), "seller", null);
    }

    /** Routes each analytics query to its own stubbed ResultSet by matching the SQL. */
    private static Connection connectionAnswering(ResultSet keywords, ResultSet counts, ResultSet sample)
            throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = sql.contains("source_keyword IS NOT NULL") ? keywords
                    : sql.contains("AS clicks") ? counts
                    : sql.contains("DISTINCT ON") ? sample
                    : mock(ResultSet.class);
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        });
        return conn;
    }

    private static ResultSet emptyResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    @Test
    @DisplayName("attaches click counts, keywords and a masked clicker to each item")
    void attachesProvenance() throws Exception {
        ResultSet keywords = mock(ResultSet.class);
        when(keywords.next()).thenReturn(true, false);
        when(keywords.getLong(1)).thenReturn(7L);
        when(keywords.getString(2)).thenReturn("pokemon");

        ResultSet counts = mock(ResultSet.class);
        when(counts.next()).thenReturn(true, false);
        when(counts.getLong("auction_id")).thenReturn(7L);
        when(counts.getLong("clicks")).thenReturn(12L);
        when(counts.getLong("clickers")).thenReturn(3L);

        ResultSet sample = mock(ResultSet.class);
        when(sample.next()).thenReturn(true, false);
        when(sample.getLong(1)).thenReturn(7L);
        when(sample.getString(2)).thenReturn("buyer2");

        List<SearchResultItem> items = List.of(item(7L, "Pokemon card lot", "Collectibles"));
        Connection conn = connectionAnswering(keywords, counts, sample);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().attachProvenance(items, null);
        }

        var why = items.get(0).getWhy();
        assertNotNull(why);
        assertEquals(12L, why.getClickCount());
        assertEquals(3L, why.getDistinctClickers());
        assertEquals(List.of("pokemon"), why.getKeywords());
        // Masked, never the raw username — this is rendered on the public landing page.
        assertNotNull(why.getClickedByMasked());
        assertFalse(why.getClickedByMasked().contains("buyer2"));
    }

    @Test
    @DisplayName("a single clicker is never named, even in masked form")
    void withholdsMaskedNameForLoneClicker() throws Exception {
        ResultSet counts = mock(ResultSet.class);
        when(counts.next()).thenReturn(true, false);
        when(counts.getLong("auction_id")).thenReturn(7L);
        when(counts.getLong("clicks")).thenReturn(4L);
        when(counts.getLong("clickers")).thenReturn(1L);

        ResultSet sample = mock(ResultSet.class);
        when(sample.next()).thenReturn(true, false);
        when(sample.getLong(1)).thenReturn(7L);
        when(sample.getString(2)).thenReturn("buyer2");

        List<SearchResultItem> items = List.of(item(7L, "Pokemon card lot", "Collectibles"));
        Connection conn = connectionAnswering(emptyResultSet(), counts, sample);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().attachProvenance(items, null);
        }

        assertEquals(4L, items.get(0).getWhy().getClickCount());
        assertNull(items.get(0).getWhy().getClickedByMasked());
    }

    @Test
    @DisplayName("falls back to a trending reason and zero counts when the tables are missing")
    void failsSoftWithoutMigration() throws Exception {
        List<SearchResultItem> items = List.of(item(7L, "Pokemon card lot", "Collectibles"));
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenThrow(new SQLException("relation search_history does not exist"));
            RecommendationDAO dao = new RecommendationDAO();

            assertDoesNotThrow(() -> dao.attachProvenance(items, 5));
            assertTrue(dao.recentKeywords(5, 10).isEmpty());
            assertDoesNotThrow(() -> dao.recordSearchKeyword(5, "pokemon"));
            assertTrue(((List<?>) dao.attributionDetail(7L, 10).get("events")).isEmpty());
            assertTrue(((List<?>) dao.attributionOverview(10).get("topKeywords")).isEmpty());
        }

        var why = items.get(0).getWhy();
        assertNotNull(why);
        assertEquals("TRENDING", why.getReasonCode());
        assertEquals(0L, why.getClickCount());
        assertTrue(why.getKeywords().isEmpty());
    }

    @Test
    @DisplayName("a blank search keyword is never stored")
    void ignoresBlankKeyword() {
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            new RecommendationDAO().recordSearchKeyword(5, "   ");
            db.verify(DBUtil::connectDB, never());
        }
    }
}
