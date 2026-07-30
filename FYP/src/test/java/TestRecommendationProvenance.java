import com.auction.dao.RecommendationDAO;
import com.auction.model.RecommendationProvenance;
import com.auction.model.RecommendationProvenance.Reason;
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

    @Test
    @DisplayName("a one-character search keyword is never stored")
    void ignoresTooShortKeyword() {
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            new RecommendationDAO().recordSearchKeyword(5, " a ");
            db.verify(DBUtil::connectDB, never());
        }
    }

    /** Routes the viewer's keyword history to {@code mine}, and every other query to empty. */
    private static Connection connectionWithKeywordHistory(String mine) throws SQLException {
        ResultSet history = mock(ResultSet.class);
        when(history.next()).thenReturn(true, false);
        when(history.getString(1)).thenReturn(mine);

        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            when(ps.executeQuery()).thenReturn(
                    sql.contains("FROM search_history") ? history : mock(ResultSet.class));
            return ps;
        });
        return conn;
    }

    @Test
    @DisplayName("a one-character keyword never overrides a card's real reason")
    void tooShortKeywordDoesNotOverrideReason() throws Exception {
        List<SearchResultItem> items = List.of(item(7L, "Pokemon card lot", "Collectibles"));
        Connection conn = connectionWithKeywordHistory("a");
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().attachProvenance(items, 5);
        }

        var why = items.get(0).getWhy();
        assertEquals("TRENDING", why.getReasonCode());
        assertTrue(why.getKeywords().isEmpty());
    }

    @Test
    @DisplayName("a two-character keyword is credited only on a word boundary")
    void shortKeywordNeedsAWordBoundary() throws Exception {
        List<SearchResultItem> louvre = List.of(item(7L, "Louvre gallery print", "Art"));
        List<SearchResultItem> headset = List.of(item(8L, "VR headset", "Electronics"));
        Connection first = connectionWithKeywordHistory("vr");
        Connection second = connectionWithKeywordHistory("vr");
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(first);
            new RecommendationDAO().attachProvenance(louvre, 5);
            db.when(DBUtil::connectDB).thenReturn(second);
            new RecommendationDAO().attachProvenance(headset, 5);
        }

        assertEquals("TRENDING", louvre.get(0).getWhy().getReasonCode());
        assertEquals("SEARCH_KEYWORD", headset.get(0).getWhy().getReasonCode());
        assertEquals("Matches your search for “vr”", headset.get(0).getWhy().getReason());
    }

    @Test
    @DisplayName("a longer keyword still matches inside a compound word")
    void longerKeywordKeepsSubstringMatching() throws Exception {
        List<SearchResultItem> items = List.of(item(9L, "Apple iPhone 15", "Electronics"));
        Connection conn = connectionWithKeywordHistory("phone");
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().attachProvenance(items, 5);
        }
        assertEquals("SEARCH_KEYWORD", items.get(0).getWhy().getReasonCode());
    }

    @Test
    @DisplayName("only a genuinely personalised stage counts as personalised")
    void personalisedIsDerivedFromProvenance() {
        assertFalse(RecommendationDAO.isPersonalised(null));
        assertFalse(RecommendationDAO.isPersonalised(List.of()));

        SearchResultItem plain = item(1L, "Untagged", "Art");
        assertFalse(RecommendationDAO.isPersonalised(List.of(plain)));

        for (Reason reason : List.of(Reason.PEER_BIDS, Reason.SIMILAR_TASTE,
                Reason.SAME_CATEGORY, Reason.SEARCH_KEYWORD)) {
            SearchResultItem filler = item(2L, "Filler", "Art");
            filler.setWhy(new RecommendationProvenance(Reason.TRENDING, "Trending"));
            SearchResultItem hit = item(3L, "Hit", "Art");
            hit.setWhy(new RecommendationProvenance(reason, "because"));
            assertTrue(RecommendationDAO.isPersonalised(List.of(filler, hit)), reason.name());
        }

        SearchResultItem trending = item(4L, "Filler", "Art");
        trending.setWhy(new RecommendationProvenance(Reason.TRENDING, "Trending"));
        assertFalse(RecommendationDAO.isPersonalised(List.of(trending, trending)));
    }

    /**
     * Drives {@link RecommendationDAO#recommendForUser(int, int)} far enough to reach the
     * content-based stage, returning a single candidate row with the given match path.
     */
    private static String contentBasedReason(boolean categoryMatch, String sharedTag) throws Exception {
        ResultSet content = mock(ResultSet.class);
        when(content.next()).thenReturn(true, false);
        when(content.getLong("auction_id")).thenReturn(42L);
        when(content.getString("title")).thenReturn("Silk scarf");
        when(content.getString("category")).thenReturn("Fashion");
        when(content.getBigDecimal("current_price")).thenReturn(BigDecimal.TEN);
        when(content.getTimestamp("date_end")).thenReturn(null);
        when(content.getString("username")).thenReturn("seller");
        when(content.getString("thumb")).thenReturn(null);
        when(content.getBoolean("category_match")).thenReturn(categoryMatch);
        when(content.getString("shared_tag")).thenReturn(sharedTag);

        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            when(ps.executeQuery()).thenReturn(
                    sql.contains("my_signals") ? content : mock(ResultSet.class));
            return ps;
        });

        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            List<SearchResultItem> out = new RecommendationDAO().recommendForUser(5, 1);
            assertEquals(1, out.size());
            assertEquals("SAME_CATEGORY", out.get(0).getWhy().getReasonCode());
            return out.get(0).getWhy().getReason();
        }
    }

    @Test
    @DisplayName("a tag-only match never claims the viewer browsed that category")
    void tagMatchDoesNotClaimCategoryAffinity() throws Exception {
        // contentBased() also accepts rows on tag overlap alone, so the candidate's own
        // category is no evidence the viewer has ever looked at it.
        String reason = contentBasedReason(false, "vintage");
        assertEquals("Tagged “vintage”, like items you've viewed", reason);
        assertFalse(reason.contains("Fashion"));
    }

    @Test
    @DisplayName("a tag-only match with no readable tag falls back to a neutral sentence")
    void tagMatchWithoutTagNameStaysVague() throws Exception {
        assertEquals("Similar to items you viewed recently", contentBasedReason(false, null));
    }

    @Test
    @DisplayName("a real category match still names the category")
    void categoryMatchNamesTheCategory() throws Exception {
        assertEquals("Because you looked at similar Fashion", contentBasedReason(true, "vintage"));
    }
}
