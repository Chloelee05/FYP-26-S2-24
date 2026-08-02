import com.auction.dao.RecommendationDAO;
import com.auction.model.SearchResultItem;
import com.auction.util.DBUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Composition invariants of {@link RecommendationDAO#recommendForUser(int, int)}.
 *
 * <p>The four stages are stubbed at the JDBC boundary with the repo's {@code MockedStatic}
 * convention, so what is asserted here is the pipeline's own contract — the order stages
 * run in, that each stage is told what earlier stages already took, that dismissed items
 * never survive, and that trending is only ever a filler.</p>
 *
 * <p>Cross-stage de-duplication is enforced by SQL {@code NOT IN} rather than in Java, so
 * the exclusion tests assert on the ids actually bound into each query.</p>
 */
@DisplayName("RecommendationDAO pipeline composition")
class TestRecommendationPipeline {

    /** The settings snapshot is cached process-wide, so it must not leak between tests. */
    @BeforeEach
    @AfterEach
    void clearSettingsCache() {
        RecommendationDAO.invalidateSettingsCache();
    }

    // Fragments that uniquely identify each stage's query.
    private static final String Q_DISMISSED = "dismissed_recommendations";
    private static final String Q_PEER_CF   = "peers AS";
    private static final String Q_VECTORS   = "'BROWSE'";
    private static final String Q_FETCH_IDS = "WHERE a.auction_id IN (";
    private static final String Q_CONTENT   = "my_signals";
    private static final String Q_TRENDING  = "bid_count";
    /** The allow-set of auctions user-based CF is permitted to rank. */
    private static final String Q_ELIGIBLE  = "SELECT auction_id FROM auction ";
    private static final String Q_SETTINGS  = "FROM recommendation_settings";

    /** Records the SQL prepared by each stage and the ids bound into it. */
    private static final class Recorder {
        private final Map<String, List<Long>> boundBySql = new LinkedHashMap<>();

        void prepared(String sql) {
            boundBySql.computeIfAbsent(sql, k -> new ArrayList<>());
        }

        void note(String sql, long value) {
            boundBySql.computeIfAbsent(sql, k -> new ArrayList<>()).add(value);
        }

        boolean ran(String fragment) {
            return boundBySql.keySet().stream().anyMatch(sql -> sql.contains(fragment));
        }

        /** The single query whose SQL contains {@code fragment}. */
        String sqlContaining(String fragment) {
            return boundBySql.keySet().stream()
                    .filter(sql -> sql.contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no query ran containing: " + fragment));
        }

        /** Every id bound into whichever query matched {@code fragment}. */
        Set<Long> boundInto(String fragment) {
            return boundBySql.entrySet().stream()
                    .filter(e -> e.getKey().contains(fragment))
                    .flatMap(e -> e.getValue().stream())
                    .collect(Collectors.toSet());
        }
    }

    /** A ResultSet yielding one listing row per id, with the columns mapRow() reads. */
    private static ResultSet listingRows(long... auctionIds) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        if (auctionIds.length == 0) {
            when(rs.next()).thenReturn(false);
            return rs;
        }
        when(rs.next()).thenReturn(true, buildTail(auctionIds.length));

        Long first = auctionIds[0];
        Long[] rest = new Long[auctionIds.length - 1];
        for (int i = 1; i < auctionIds.length; i++) rest[i - 1] = auctionIds[i];
        if (rest.length == 0) when(rs.getLong("auction_id")).thenReturn(first);
        else when(rs.getLong("auction_id")).thenReturn(first, rest);

        when(rs.getString("title")).thenReturn("Listing");
        when(rs.getString("category")).thenReturn("Electronics");
        when(rs.getString("username")).thenReturn("seller");
        return rs;
    }

    /** {@code next()} answers true for the remaining rows, then false forever. */
    private static Boolean[] buildTail(int rowCount) {
        Boolean[] tail = new Boolean[rowCount];
        for (int i = 0; i < rowCount - 1; i++) tail[i] = Boolean.TRUE;
        tail[rowCount - 1] = Boolean.FALSE;
        return tail;
    }

    /** A ResultSet for the interaction-vector union feeding user-based CF. */
    private static ResultSet interactionRows(int[] userIds, long[] auctionIds) throws SQLException {
        return interactionRows(userIds, auctionIds, null);
    }

    /**
     * As above, but with a per-row signal source so the seeded weights (bid 3, watchlist 2,
     * browse 1) separate the candidates and fix their ranking order.
     */
    private static ResultSet interactionRows(int[] userIds, long[] auctionIds, String[] sources)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, buildTail(userIds.length));

        Integer[] restUsers = new Integer[userIds.length - 1];
        for (int i = 1; i < userIds.length; i++) restUsers[i - 1] = userIds[i];
        when(rs.getInt("user_id")).thenReturn(userIds[0], restUsers);

        Long[] restAuctions = new Long[auctionIds.length - 1];
        for (int i = 1; i < auctionIds.length; i++) restAuctions[i - 1] = auctionIds[i];
        when(rs.getLong("auction_id")).thenReturn(auctionIds[0], restAuctions);

        if (sources == null) {
            when(rs.getString("src")).thenReturn("BID");
        } else {
            String[] restSources = new String[sources.length - 1];
            for (int i = 1; i < sources.length; i++) restSources[i - 1] = sources[i];
            when(rs.getString("src")).thenReturn(sources[0], restSources);
        }
        return rs;
    }

    /** A ResultSet of key/value rows for the recommendation_settings query. */
    private static ResultSet settingsRows(Map<String, String> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        List<String> keys = new ArrayList<>(rows.keySet());
        if (keys.isEmpty()) {
            when(rs.next()).thenReturn(false);
            return rs;
        }
        when(rs.next()).thenReturn(true, buildTail(keys.size()));
        String[] restKeys = keys.subList(1, keys.size()).toArray(new String[0]);
        String[] restVals = keys.subList(1, keys.size()).stream().map(rows::get).toArray(String[]::new);
        when(rs.getString("key")).thenReturn(keys.get(0), restKeys);
        when(rs.getString("value")).thenReturn(rows.get(keys.get(0)), restVals);
        return rs;
    }

    private static ResultSet idRows(long... ids) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        if (ids.length == 0) {
            when(rs.next()).thenReturn(false);
            return rs;
        }
        when(rs.next()).thenReturn(true, buildTail(ids.length));
        Long[] rest = new Long[ids.length - 1];
        for (int i = 1; i < ids.length; i++) rest[i - 1] = ids[i];
        if (rest.length == 0) when(rs.getLong(1)).thenReturn(ids[0]);
        else when(rs.getLong(1)).thenReturn(ids[0], rest);
        return rs;
    }

    /**
     * Routes each pipeline stage to its own stubbed ResultSet by matching the SQL, and
     * records the ids each stage binds so exclusion can be asserted.
     */
    private static Connection pipeline(Recorder rec, Map<String, ResultSet> byFragment) throws SQLException {
        // Built up front: stubbing a fresh mock from inside an Answer is not allowed.
        ResultSet fallback = emptyRows();
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            rec.prepared(sql);
            PreparedStatement ps = mock(PreparedStatement.class);
            doAnswer(a -> { rec.note(sql, a.getArgument(1, Long.class)); return null; })
                    .when(ps).setLong(anyInt(), anyLong());
            doAnswer(a -> { rec.note(sql, a.getArgument(1, Integer.class).longValue()); return null; })
                    .when(ps).setInt(anyInt(), anyInt());

            ResultSet match = byFragment.entrySet().stream()
                    .filter(e -> sql.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(fallback);
            doReturn(match).when(ps).executeQuery();
            return ps;
        });
        return conn;
    }

    private static ResultSet emptyRows() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private static List<Long> idsOf(List<SearchResultItem> items) {
        return items.stream().map(SearchResultItem::getAuctionId).collect(Collectors.toList());
    }

    private static List<String> reasonsOf(List<SearchResultItem> items) {
        return items.stream().map(i -> i.getWhy().getReasonCode()).collect(Collectors.toList());
    }

    @Test
    @DisplayName("stages fill the list in order: peer CF, similar taste, content, trending")
    void stagesRunInPipelineOrder() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_PEER_CF, listingRows(1L));
        // User 5 and peer 6 both bid on 10; peer 6 also bid on 2, which becomes the pick.
        stages.put(Q_VECTORS, interactionRows(new int[]{5, 6, 6}, new long[]{10L, 10L, 2L}));
        // 2 is the open auction user-based CF is allowed to rank.
        stages.put(Q_ELIGIBLE, idRows(2L));
        stages.put(Q_FETCH_IDS, listingRows(2L));
        stages.put(Q_CONTENT, listingRows(3L));
        stages.put(Q_TRENDING, listingRows(4L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 4);
        }

        assertEquals(List.of(1L, 2L, 3L, 4L), idsOf(out));
        assertEquals(List.of("PEER_BIDS", "SIMILAR_TASTE", "SAME_CATEGORY", "TRENDING"), reasonsOf(out));
    }

    @Test
    @DisplayName("no auction appears twice in the final list")
    void resultsContainNoDuplicates() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_PEER_CF, listingRows(1L, 2L));
        stages.put(Q_CONTENT, listingRows(3L));
        stages.put(Q_TRENDING, listingRows(4L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 4);
        }

        List<Long> ids = idsOf(out);
        assertEquals(ids.size(), new HashSet<>(ids).size(), "pipeline returned a duplicate auction");
    }

    @Test
    @DisplayName("generators are not told what earlier ones took, so signals can accumulate")
    void generatorsSeeTheWholeCandidateSpace() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_PEER_CF, listingRows(1L, 2L));
        stages.put(Q_CONTENT, listingRows(3L));
        stages.put(Q_TRENDING, listingRows(4L));

        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recommendForUser(5, 4);
        }

        // The stages used to exclude each other's picks by NOT IN. Under re-ranking that
        // would be self-defeating: an auction three signals agree on has to reach the
        // blend from all three, and hiding it after the first sighting is exactly what
        // made the old order a function of stage boundaries rather than of evidence.
        assertFalse(rec.boundInto(Q_CONTENT).containsAll(List.of(1L, 2L)),
                "the content stage is still being denied the peer-CF picks");
        assertFalse(rec.boundInto(Q_TRENDING).containsAll(List.of(1L, 2L, 3L)),
                "the trending stage is still being denied the earlier picks");
    }

    @Test
    @DisplayName("an auction several generators agree on is still returned only once")
    void overlappingCandidatesAreMergedNotRepeated() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        // The same listing surfaces from three separate generators.
        stages.put(Q_PEER_CF, listingRows(1L));
        stages.put(Q_CONTENT, listingRows(1L, 3L));
        stages.put(Q_TRENDING, listingRows(1L, 4L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 4);
        }

        assertEquals(1, idsOf(out).stream().filter(id -> id == 1L).count(),
                "an auction found by several generators was listed more than once");
        // The first generator to produce a card owns its label, because the per-arm CTR
        // breakdown groups on it.
        assertEquals("PEER_BIDS", out.get(0).getWhy().getReasonCode());
        // Agreeing signals must outscore the single-signal candidates.
        assertEquals(1L, out.get(0).getAuctionId());
    }

    @Test
    @DisplayName("a dismissed auction is dropped and excluded from every later stage")
    void dismissedItemsNeverSurvive() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_DISMISSED, idRows(99L));
        // The peer-CF query is stubbed to hand back the dismissed listing anyway, so the
        // Java-side filter is what has to remove it.
        stages.put(Q_PEER_CF, listingRows(99L, 1L));
        stages.put(Q_CONTENT, listingRows(3L));
        stages.put(Q_TRENDING, listingRows(4L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 4);
        }

        assertFalse(idsOf(out).contains(99L), "a dismissed auction was recommended");
        assertTrue(rec.boundInto(Q_CONTENT).contains(99L),
                "content-based stage could re-surface the dismissed auction");
        assertTrue(rec.boundInto(Q_TRENDING).contains(99L),
                "trending filler could re-surface the dismissed auction");
    }

    @Test
    @DisplayName("trending is a candidate generator, but never outranks a personalised card")
    void trendingCompetesRatherThanFills() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_PEER_CF, listingRows(1L, 2L));
        stages.put(Q_TRENDING, listingRows(7L, 8L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 2);
        }

        // Every generator now runs on every request rather than short-circuiting once the
        // page is full — that is what gives the re-ranker something to choose between, and
        // it is also why the query count per request no longer varies.
        assertTrue(rec.ran(Q_TRENDING));
        assertTrue(rec.ran(Q_CONTENT));
        // Popularity is the lowest-weighted component by default, so a page that could be
        // filled with personalised cards still is.
        assertEquals(List.of(1L, 2L), idsOf(out));
        assertEquals(List.of("PEER_BIDS", "PEER_BIDS"), reasonsOf(out));
    }

    @Test
    @DisplayName("the peer-CF stage is never allowed to overrun the requested limit")
    void collaborativeStageIsTruncatedToTheLimit() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        // The query is asked for limit + dismissed.size() rows, so it can return more
        // than the caller wants once the dismissed padding is not needed.
        stages.put(Q_PEER_CF, listingRows(1L, 2L, 3L, 4L, 5L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 2);
        }

        assertEquals(List.of(1L, 2L), idsOf(out));
    }

    @Test
    @DisplayName("a cold-start user with no signal at all still gets trending filler")
    void coldStartFallsThroughToTrending() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_TRENDING, listingRows(7L, 8L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 4);
        }

        assertEquals(List.of(7L, 8L), idsOf(out));
        assertEquals(List.of("TRENDING", "TRENDING"), reasonsOf(out));
        // Trending filler is not personalisation, and the API must not claim otherwise.
        assertFalse(RecommendationDAO.isPersonalised(out));
    }

    @Test
    @DisplayName("trending filler excludes the viewer's own bids and watchlist")
    void trendingFillerExcludesTheViewersOwnItems() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_TRENDING, listingRows(7L));

        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recommendForUser(5, 4);
        }

        // As the final filler this stage would otherwise recommend a buyer the listings
        // they are already bidding on, which the earlier stages all exclude.
        String trendingSql = rec.sqlContaining(Q_TRENDING);
        assertTrue(trendingSql.contains("my_items"),
                "trending filler did not scope out the viewer's own bids and watchlist");
        assertTrue(trendingSql.contains("a.seller_id <> ?"),
                "trending filler did not exclude the viewer's own listings");
    }

    @Test
    @DisplayName("the public trending strip stays a marketplace-wide list")
    void publicTrendingIsNotScopedToAViewer() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_TRENDING, listingRows(7L));

        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().trending(4, Set.of(), null);
        }

        String trendingSql = rec.sqlContaining(Q_TRENDING);
        assertFalse(trendingSql.contains("my_items"),
                "the signed-out trending strip must not be personalised");
    }

    @Test
    @DisplayName("trending counts only bids inside the configured window")
    void trendingCountsOnlyRecentBids() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_TRENDING, listingRows(7L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().trending(4, Set.of(), null);
        }

        String trendingSql = rec.sqlContaining(Q_TRENDING);
        assertTrue(trendingSql.contains("interval '1 day'"),
                "bid_count still counts bids over all time");
        // Settings are unavailable here, so the default window is what the copy must state.
        assertEquals("Trending — collecting the most bids this week", out.get(0).getWhy().getReason());
        assertTrue(rec.boundInto(Q_TRENDING).contains((long) RecommendationDAO.DEFAULT_TRENDING_WINDOW_DAYS));
    }

    // -------------------------------------------------------------------------
    // Candidate truncation ahead of the active-auction filter
    // -------------------------------------------------------------------------

    /**
     * Peer 6 endorses three auctions with separating weights — 90 by bid, 91 by watchlist,
     * 92 by browse — so the ranking order 90 > 91 > 92 is fixed rather than dependent on
     * hash iteration. Only 92 is still open.
     */
    private static Map<String, ResultSet> neighbourhoodToppedByEndedAuctions() throws SQLException {
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_VECTORS, interactionRows(
                new int[]{5, 6, 6, 6, 6},
                new long[]{10L, 10L, 90L, 91L, 92L},
                new String[]{"BID", "BID", "BID", "WATCH", "BROWSE"}));
        stages.put(Q_ELIGIBLE, idRows(92L));
        stages.put(Q_FETCH_IDS, listingRows(92L));
        return stages;
    }

    @Test
    @DisplayName("similar-taste survives a neighbourhood whose top candidates have ended")
    void rankingSkipsEndedCandidatesInsteadOfLosingTheArm() throws Exception {
        Recorder rec = new Recorder();

        Connection conn = pipeline(rec, neighbourhoodToppedByEndedAuctions());
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recommendForUser(5, 1);
        }

        // The stubbed fetch hands back a listing whatever it is asked for, so the ids the
        // ranking actually chose are what has to be asserted. Truncating to the top scorer
        // before the active filter sent 90 — an ended auction — and the arm came back empty.
        Set<Long> fetched = rec.boundInto(Q_FETCH_IDS);
        assertTrue(fetched.contains(92L), "the only open candidate never reached the fetch");
        assertFalse(fetched.contains(90L), "an ended auction was still ranked into the slot");
    }

    @Test
    @DisplayName("the allow-set is scoped to open, moderated auctions the viewer does not own")
    void eligibilityQueryAppliesTheSamePredicatesAsTheFetch() throws Exception {
        Recorder rec = new Recorder();

        Connection conn = pipeline(rec, neighbourhoodToppedByEndedAuctions());
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recommendForUser(5, 1);
        }

        // An allow-set looser than the fetch's own filter would reintroduce the bug for
        // whichever predicate it omitted.
        String sql = rec.sqlContaining(Q_ELIGIBLE);
        assertTrue(sql.contains("status_id = 1"));
        assertTrue(sql.contains("moderation_state = 'active'"));
        assertTrue(sql.contains("date_end > now()"));
        assertTrue(sql.contains("seller_id <> ?"));
        assertTrue(rec.boundInto(Q_ELIGIBLE).contains(5L), "the viewer was not excluded as a seller");
    }

    // -------------------------------------------------------------------------
    // Hybrid re-ranking
    // -------------------------------------------------------------------------

    /** Runs the pipeline against the given stage stubs and settings rows. */
    private static List<SearchResultItem> recommendWithSettings(
            Map<String, ResultSet> stages, Map<String, String> settingRows, int limit)
            throws SQLException {
        Recorder rec = new Recorder();
        Map<String, ResultSet> all = new LinkedHashMap<>(stages);
        all.put(Q_SETTINGS, settingsRows(settingRows));

        RecommendationDAO.invalidateSettingsCache();
        Connection conn = pipeline(rec, all);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            return new RecommendationDAO().recommendForUser(5, limit);
        } finally {
            RecommendationDAO.invalidateSettingsCache();
        }
    }

    @Test
    @DisplayName("a card carries the blended score and the component that drove it")
    void scoreAndDominantComponentAreExposed() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_PEER_CF, listingRows(1L));
        stages.put(Q_TRENDING, listingRows(7L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 4);
        }

        // The "why this?" panel promises a real number, so it has to be one.
        assertTrue(out.get(0).getWhy().getScore() > 0.0);
        assertEquals("CF", out.get(0).getWhy().getDominantComponent());
        assertEquals("POPULARITY", out.get(1).getWhy().getDominantComponent());
        assertTrue(out.get(0).getWhy().getScore() > out.get(1).getWhy().getScore());
        for (SearchResultItem item : out) {
            double score = item.getWhy().getScore();
            assertTrue(score >= 0.0 && score <= 1.0, "score outside [0,1]: " + score);
        }
    }

    @Test
    @DisplayName("two agreeing signals outrank a single stronger one")
    void agreementBeatsASingleSignal() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        // 20 tops the content stage on content alone. 9 is only second there, but the
        // trending stage puts it first, so two components back it.
        stages.put(Q_CONTENT, listingRows(20L, 9L));
        stages.put(Q_TRENDING, listingRows(9L, 30L, 31L, 32L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 6);
        }

        // The old pipeline settled this on stage boundaries: 20 led the content stage, and
        // every content result outranked every trending one regardless of strength.
        assertEquals(9L, idsOf(out).get(0),
                "an auction two components agree on lost to one a single component likes more");
        assertEquals(20L, idsOf(out).get(1));
    }

    @Test
    @DisplayName("zeroing a weight visibly demotes the items that component favoured")
    void zeroingAWeightDemotesItsItems() throws Exception {
        // The demo an admin runs: set w_pop to 0, save, and popular items visibly drop.
        Map<String, ResultSet> before = new LinkedHashMap<>();
        before.put(Q_CONTENT, listingRows(20L, 21L, 3L));
        before.put(Q_TRENDING, listingRows(7L, 8L));
        List<Long> withPopularity = idsOf(recommendWithSettings(before, Map.of(), 6));

        Map<String, ResultSet> after = new LinkedHashMap<>();
        after.put(Q_CONTENT, listingRows(20L, 21L, 3L));
        after.put(Q_TRENDING, listingRows(7L, 8L));
        List<Long> withoutPopularity = idsOf(recommendWithSettings(
                after, Map.of("rerank_w_pop", "0"), 6));

        assertTrue(withPopularity.indexOf(7L) < withPopularity.indexOf(3L),
                "the popular card should start out above the weak content card");
        assertTrue(withoutPopularity.indexOf(3L) < withoutPopularity.indexOf(7L),
                "zeroing the popularity weight did not demote the popular card");
        assertTrue(withoutPopularity.contains(7L),
                "the popular card should be demoted, not dropped from the page");
    }

    @Test
    @DisplayName("all weights zero falls back to stage order rather than to hash order")
    void allWeightsZeroFallsBackToStageOrder() throws Exception {
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_PEER_CF, listingRows(1L, 2L));
        stages.put(Q_CONTENT, listingRows(3L));
        stages.put(Q_TRENDING, listingRows(4L));

        Map<String, String> allZero = new LinkedHashMap<>();
        allZero.put("rerank_w_cf", "0");
        allZero.put("rerank_w_ubcf", "0");
        allZero.put("rerank_w_content", "0");
        allZero.put("rerank_w_pop", "0");
        allZero.put("rerank_w_rec", "0");

        List<SearchResultItem> out = recommendWithSettings(stages, allZero, 4);

        // Ranking by nothing has to mean something deterministic; the pre-re-ranking
        // sequence is the honest answer and is what an admin can reason about.
        assertEquals(List.of(1L, 2L, 3L, 4L), idsOf(out));
        assertEquals(0.0, out.get(0).getWhy().getScore());
        assertEquals("CF", out.get(0).getWhy().getDominantComponent(),
                "with no score to attribute, the generating stage names the component");
    }

    @Test
    @DisplayName("a single candidate scores without dividing by a zero range")
    void singleCandidateDoesNotDivideByZero() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_TRENDING, listingRows(7L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 8);
        }

        assertEquals(1, out.size());
        double score = out.get(0).getWhy().getScore();
        assertFalse(Double.isNaN(score), "min-max divided by a zero range");
        assertFalse(Double.isInfinite(score));
        assertEquals(1.0, score, 1e-9, "the only candidate carries every signal it has");
    }

    @Test
    @DisplayName("a component with no signal anywhere neither promotes nor demotes")
    void absentComponentsDoNotSkewTheScale() throws Exception {
        Recorder rec = new Recorder();
        // Nothing produces a CF or UBCF candidate here, so two of the five components are
        // dead. Their weights must leave the divisor rather than shrinking every score.
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_TRENDING, listingRows(7L, 8L));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 8);
        }

        assertEquals(List.of(7L, 8L), idsOf(out));
        assertEquals(1.0, out.get(0).getWhy().getScore(), 1e-9,
                "the top card should still reach a full score with only popularity alive");
    }

    // -------------------------------------------------------------------------
    // Category diversity cap
    // -------------------------------------------------------------------------

    /** Listing rows whose category is decided per row, for the diversity cap. */
    private static ResultSet categorisedRows(long[] auctionIds, String[] categories) throws SQLException {
        ResultSet rs = listingRows(auctionIds);
        String[] rest = new String[categories.length - 1];
        for (int i = 1; i < categories.length; i++) rest[i - 1] = categories[i];
        when(rs.getString("category")).thenReturn(categories[0], rest);
        return rs;
    }

    @Test
    @DisplayName("one category cannot take the whole page")
    void categoryCapLimitsASingleCategory() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        // What contentBased() produces for a viewer with one Electronics signal: it orders
        // by date_end alone, so soon-ending Electronics can occupy every slot.
        stages.put(Q_CONTENT, categorisedRows(
                new long[]{1L, 2L, 3L, 4L, 5L, 6L},
                new String[]{"Electronics", "Electronics", "Electronics",
                             "Electronics", "Fashion", "Art"}));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 4);
        }

        long electronics = out.stream()
                .filter(i -> "Electronics".equals(i.getCategory())).count();
        // ceil(4 / 3) = 2.
        assertEquals(2, electronics, "a single category still swallowed the page");
        assertEquals(4, out.size(), "the cap shortened the page instead of reordering it");
        assertEquals(List.of(1L, 2L, 5L, 6L), idsOf(out));
    }

    @Test
    @DisplayName("a marketplace with only one live category still fills the page")
    void categoryCapNeverShortensThePage() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_CONTENT, categorisedRows(
                new long[]{1L, 2L, 3L, 4L},
                new String[]{"Art", "Art", "Art", "Art"}));

        List<SearchResultItem> out;
        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            out = new RecommendationDAO().recommendForUser(5, 4);
        }

        // Held-back items are appended once the capped pass runs out, which is the normal
        // state of a small demo database rather than an edge case.
        assertEquals(List.of(1L, 2L, 3L, 4L), idsOf(out));
    }

    @Test
    @DisplayName("a divisor of one switches the cap off")
    void categoryCapCanBeDisabled() throws Exception {
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_CONTENT, categorisedRows(
                new long[]{1L, 2L, 3L, 4L},
                new String[]{"Electronics", "Electronics", "Electronics", "Fashion"}));

        List<SearchResultItem> out = recommendWithSettings(
                stages, Map.of("diversity_category_divisor", "1"), 4);

        assertEquals(List.of(1L, 2L, 3L, 4L), idsOf(out),
                "the cap still reordered the page with the divisor at 1");
    }

    // -------------------------------------------------------------------------
    // Recency decay and the content-based lookback window
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("interaction ages are read in one clock, with the naive bid column pinned to UTC")
    void interactionVectorsCarryAges() throws Exception {
        Recorder rec = new Recorder();
        Connection conn = pipeline(rec, neighbourhoodToppedByEndedAuctions());
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recommendForUser(5, 1);
        }

        String sql = rec.sqlContaining(Q_VECTORS);
        assertTrue(sql.contains("age_days"), "interaction ages never reach the decay");
        // bid_time is timestamp without time zone while added_at and viewed_at are
        // timestamptz; leaving the naive one to the session TimeZone shifts every bid's age.
        assertTrue(sql.contains("bid_time AT TIME ZONE 'UTC'"),
                "bid ages depend on the session TimeZone rather than UTC");
    }

    @Test
    @DisplayName("a fresh signal keeps its weight and an old one is faded")
    void recencyMultiplierFadesWithAge() {
        assertEquals(1.0, RecommendationDAO.recencyMultiplier(0.0, 30.0), 1e-9);
        // One tau of age leaves 1/e of the weight.
        assertEquals(Math.exp(-1), RecommendationDAO.recencyMultiplier(30.0, 30.0), 1e-9);
        assertTrue(RecommendationDAO.recencyMultiplier(90.0, 30.0)
                 < RecommendationDAO.recencyMultiplier(30.0, 30.0));
        assertTrue(RecommendationDAO.recencyMultiplier(365.0, 30.0) > 0.0,
                "decay must fade a signal, never erase or invert it");
    }

    @Test
    @DisplayName("a tau of zero switches decay off rather than dividing by it")
    void recencyDecayCanBeDisabled() {
        assertEquals(1.0, RecommendationDAO.recencyMultiplier(500.0, 0.0), 1e-9);
        assertEquals(1.0, RecommendationDAO.recencyMultiplier(500.0, -3.0), 1e-9);
        assertFalse(Double.isNaN(RecommendationDAO.recencyMultiplier(500.0, 0.0)));
    }

    @Test
    @DisplayName("a timestamp ahead of the database clock cannot inflate a signal")
    void futureTimestampsDoNotAmplify() {
        // exp(+x) would make a row written a second into the future outrank every genuine
        // interaction in the table.
        assertEquals(1.0, RecommendationDAO.recencyMultiplier(-10.0, 30.0), 1e-9);
    }

    @Test
    @DisplayName("the content-based stage windows the viewer's own signals")
    void contentBasedWindowsItsSignals() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_CONTENT, listingRows(3L));

        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recommendForUser(5, 4);
        }

        String sql = rec.sqlContaining(Q_CONTENT);
        assertTrue(sql.contains("bid_time AT TIME ZONE 'UTC' > now() - "),
                "bid signals are windowed against the session TimeZone");
        assertTrue(sql.contains("added_at > now() - "));
        assertTrue(sql.contains("viewed_at > now() - "));
        // A window shorter than the age of the demo data empties the arm, so the default
        // has to stay generous.
        assertTrue(rec.boundInto(Q_CONTENT).contains((long) RecommendationDAO.DEFAULT_CONTENT_WINDOW_DAYS));
        assertTrue(RecommendationDAO.DEFAULT_CONTENT_WINDOW_DAYS >= 180);
    }

    @Test
    @DisplayName("peer endorsements are counted per person, not per bid")
    void peerScoringCountsDistinctBidders() throws Exception {
        Recorder rec = new Recorder();
        Map<String, ResultSet> stages = new LinkedHashMap<>();
        stages.put(Q_PEER_CF, listingRows(1L));

        Connection conn = pipeline(rec, stages);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recommendForUser(5, 4);
        }

        String cfSql = rec.sqlContaining(Q_PEER_CF);
        // One person bidding twenty times must not outweigh twenty people bidding once.
        assertFalse(cfSql.contains("COUNT(*) AS score"),
                "candidate scoring still counts bid rows rather than distinct bidders");
        assertTrue(cfSql.contains("COUNT(DISTINCT user_id) AS score"));
    }

    @Test
    @DisplayName("a conversion only counts a bid placed after the click")
    void conversionRequiresTheBidToFollowTheClick() throws Exception {
        Recorder rec = new Recorder();
        Connection conn = pipeline(rec, Map.of());
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().metrics();
        }

        String convSql = rec.sqlContaining("COUNT(DISTINCT (e.user_id, e.auction_id))");
        // Without the ordering, a bid placed before the recommendation ever appeared was
        // credited to the recommender.
        assertTrue(convSql.contains("b.bid_time AT TIME ZONE 'UTC' > e.created_at"),
                "conversions still ignore whether the bid followed the click");
    }

    // -------------------------------------------------------------------------
    // Arm labelling (reason_code) and the per-arm roll-up
    // -------------------------------------------------------------------------

    /** Captures the INSERT statements recordEvent() attempts, in order. */
    private static Connection recordingInserts(List<String> attempted, boolean reasonColumnExists)
            throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            attempted.add(sql);
            PreparedStatement ps = mock(PreparedStatement.class);
            if (!reasonColumnExists && sql.contains("reason_code")) {
                // What Postgres does when the arm-labelling migration has not been run.
                doThrow(new SQLException("column \"reason_code\" of relation "
                        + "\"recommendation_events\" does not exist")).when(ps).executeUpdate();
            } else {
                doReturn(1).when(ps).executeUpdate();
            }
            return ps;
        });
        return conn;
    }

    @Test
    @DisplayName("an arm label is written alongside the event")
    void recordsTheArmThatProducedTheCard() throws Exception {
        List<String> attempted = new ArrayList<>();
        Connection conn = recordingInserts(attempted, true);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recordEvent(5, 9L, "CLICK", null, "PEER_BIDS");
        }

        assertEquals(1, attempted.size(), "a labelled insert should succeed first time");
        assertTrue(attempted.get(0).contains("reason_code"));
    }

    @Test
    @DisplayName("an unlabelled database still records the event without the arm")
    void fallsBackWhenTheArmColumnIsMissing() throws Exception {
        List<String> attempted = new ArrayList<>();
        Connection conn = recordingInserts(attempted, false);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recordEvent(5, 9L, "IMPRESSION", null, "SIMILAR_TASTE");
        }

        // The labelled attempt fails on an unmigrated database; the event must survive it.
        assertTrue(attempted.size() >= 2, "no fallback insert was attempted");
        assertTrue(attempted.get(0).contains("reason_code"));
        assertFalse(attempted.get(attempted.size() - 1).contains("reason_code"),
                "the final attempt should name only columns that always exist");
    }

    @Test
    @DisplayName("a keyword and an arm both survive a database missing only the arm column")
    void keywordSurvivesAMissingArmColumn() throws Exception {
        List<String> attempted = new ArrayList<>();
        Connection conn = recordingInserts(attempted, false);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recordEvent(5, 9L, "CLICK", "pokemon", "SEARCH_KEYWORD");
        }

        String succeeded = attempted.get(attempted.size() - 1);
        assertTrue(succeeded.contains("source_keyword"),
                "keyword attribution was dropped along with the unavailable arm column");
        assertFalse(succeeded.contains("reason_code"));
    }

    @Test
    @DisplayName("an arm label the pipeline cannot produce is never stored")
    void rejectsAnUnknownArmLabel() throws Exception {
        List<String> attempted = new ArrayList<>();
        Connection conn = recordingInserts(attempted, true);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            // Arm labels arrive from the browser, so a client must not be able to invent
            // rows in the per-arm CTR table.
            new RecommendationDAO().recordEvent(5, 9L, "CLICK", null, "'; DROP TABLE bids--");
        }

        assertEquals(1, attempted.size());
        assertFalse(attempted.get(0).contains("reason_code"));
    }

    @Test
    @DisplayName("the popularity baseline is an accepted arm")
    void acceptsTheTrendingControlArm() throws Exception {
        List<String> attempted = new ArrayList<>();
        Connection conn = recordingInserts(attempted, true);
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            new RecommendationDAO().recordEvent(null, 9L, "IMPRESSION", null,
                    RecommendationDAO.REASON_TRENDING_CONTROL);
        }

        assertTrue(attempted.get(0).contains("reason_code"));
    }

    @Test
    @DisplayName("the per-arm roll-up ignores events recorded before arm labelling")
    void perArmMetricsSkipUnlabelledEvents() throws Exception {
        Recorder rec = new Recorder();
        Connection conn = pipeline(rec, Map.of());
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            assertTrue(new RecommendationDAO().metricsByReason().isEmpty());
        }

        String sql = rec.sqlContaining("GROUP BY e.reason_code");
        // An unlabelled backlog would otherwise dominate whichever arm it was folded into.
        assertTrue(sql.contains("e.reason_code IS NOT NULL"));
        // Per-arm conversions must use the same time ordering as the headline figure.
        assertTrue(sql.contains("b.bid_time AT TIME ZONE 'UTC' > e.created_at"));
    }

    @Test
    @DisplayName("the per-arm roll-up fails soft on an unmigrated database")
    void perArmMetricsFailSoft() {
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenThrow(new SQLException("column reason_code does not exist"));
            assertTrue(new RecommendationDAO().metricsByReason().isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // Admin-tunable settings and their cache
    // -------------------------------------------------------------------------

    /** A settings table returning the given key/value rows. */
    private static Connection settingsConnection(Map<String, String> rows) throws SQLException {
        List<String> keys = new ArrayList<>(rows.keySet());
        ResultSet rs = mock(ResultSet.class);
        if (keys.isEmpty()) {
            when(rs.next()).thenReturn(false);
        } else {
            when(rs.next()).thenReturn(true, buildTail(keys.size()));
            String[] restKeys = keys.subList(1, keys.size()).toArray(new String[0]);
            String[] restVals = keys.subList(1, keys.size()).stream()
                    .map(rows::get).toArray(String[]::new);
            when(rs.getString("key")).thenReturn(keys.get(0), restKeys);
            when(rs.getString("value")).thenReturn(rows.get(keys.get(0)), restVals);
        }

        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        doReturn(rs).when(ps).executeQuery();
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        return conn;
    }

    @Test
    @DisplayName("interaction weights are read from the settings table, not the Java constants")
    void weightsComeFromSettings() throws Exception {
        RecommendationDAO.invalidateSettingsCache();
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("w_bid", "9.5");
        rows.put("w_watchlist", "4.0");
        rows.put("w_browse", "0.5");
        Connection conn = settingsConnection(rows);

        RecommendationDAO.Settings settings;
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            settings = new RecommendationDAO().getSettings();
        }
        RecommendationDAO.invalidateSettingsCache();

        assertEquals(9.5, settings.weightBid);
        assertEquals(4.0, settings.weightWatchlist);
        assertEquals(0.5, settings.weightBrowse);
    }

    @Test
    @DisplayName("an unset weight falls back to the seeded Java default")
    void weightsFallBackToDefaults() throws Exception {
        RecommendationDAO.invalidateSettingsCache();
        Connection conn = settingsConnection(Map.of());

        RecommendationDAO.Settings settings;
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            settings = new RecommendationDAO().getSettings();
        }
        RecommendationDAO.invalidateSettingsCache();

        assertEquals(com.auction.util.UserBasedCollaborativeFilter.weightBid(), settings.weightBid);
        assertEquals(com.auction.util.UserBasedCollaborativeFilter.weightWatchlist(), settings.weightWatchlist);
        assertEquals(com.auction.util.UserBasedCollaborativeFilter.weightBrowse(), settings.weightBrowse);
        assertEquals(RecommendationDAO.DEFAULT_ITEMS_SHOWN, settings.itemsShown);
        assertEquals(RecommendationDAO.DEFAULT_TRENDING_WINDOW_DAYS, settings.trendingWindowDays);
    }

    @Test
    @DisplayName("a nonsensical stored weight is clamped rather than trusted")
    void weightsAreClamped() throws Exception {
        RecommendationDAO.invalidateSettingsCache();
        Map<String, String> rows = new LinkedHashMap<>();
        // A negative weight would turn an interaction into evidence of dislike.
        rows.put("w_bid", "-5");
        rows.put("w_browse", "9999");
        Connection conn = settingsConnection(rows);

        RecommendationDAO.Settings settings;
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            settings = new RecommendationDAO().getSettings();
        }
        RecommendationDAO.invalidateSettingsCache();

        assertEquals(0.0, settings.weightBid);
        assertEquals(100.0, settings.weightBrowse);
    }

    @Test
    @DisplayName("settings are cached across reads within the TTL")
    void settingsAreCached() throws Exception {
        RecommendationDAO.invalidateSettingsCache();
        Connection conn = settingsConnection(Map.of("items_shown", "5"));

        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenReturn(conn);
            RecommendationDAO dao = new RecommendationDAO();
            assertEquals(5, dao.getSettings().itemsShown);
            assertEquals(5, dao.getSettings().itemsShown);
            assertEquals(5, dao.getSettings().itemsShown);
            // Settings are read at least twice per recommendation request; only the first
            // read of a burst should reach the database.
            db.verify(DBUtil::connectDB, times(1));
        }
        RecommendationDAO.invalidateSettingsCache();
    }

    @Test
    @DisplayName("invalidating the cache sends the next read back to the database")
    void invalidationForcesAReload() throws Exception {
        RecommendationDAO.invalidateSettingsCache();
        Connection first = settingsConnection(Map.of("items_shown", "5"));
        Connection second = settingsConnection(Map.of("items_shown", "12"));

        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            RecommendationDAO dao = new RecommendationDAO();
            db.when(DBUtil::connectDB).thenReturn(first);
            assertEquals(5, dao.getSettings().itemsShown);

            db.when(DBUtil::connectDB).thenReturn(second);
            assertEquals(5, dao.getSettings().itemsShown, "still inside the TTL");

            // What saveSettings() does, so an admin's change shows on the next request.
            RecommendationDAO.invalidateSettingsCache();
            assertEquals(12, dao.getSettings().itemsShown);
        }
        RecommendationDAO.invalidateSettingsCache();
    }

    @Test
    @DisplayName("an unmigrated database yields zeroed metrics instead of an error")
    void metricsFailSoft() {
        try (MockedStatic<DBUtil> db = Mockito.mockStatic(DBUtil.class)) {
            db.when(DBUtil::connectDB).thenThrow(new SQLException("relation does not exist"));
            Map<String, Object> metrics = new RecommendationDAO().metrics();

            assertEquals(0L, metrics.get("impressions"));
            assertEquals(0L, metrics.get("clicks"));
            assertEquals(0L, metrics.get("conversions"));
            assertEquals(0.0, metrics.get("clickThroughRate"));
            assertEquals(0.0, metrics.get("conversionRate"));
        }
    }
}
