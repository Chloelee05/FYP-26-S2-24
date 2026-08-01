package com.auction.dao;

import com.auction.model.RecommendationProvenance;
import com.auction.model.RecommendationProvenance.Reason;
import com.auction.model.SearchResultItem;
import com.auction.util.DBUtil;
import com.auction.util.SecurityUtil;
import com.auction.util.UserBasedCollaborativeFilter;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Personalised auction recommendations via a hybrid pipeline.
 *
 * <p><b>Pipeline (SCRUM-400):</b></p>
 * <ol>
 *   <li>Item-based collaborative filtering — peer co-occurrence on bids/watchlist</li>
 *   <li>User-based CF with cosine similarity — bids, watchlist, and browse history</li>
 *   <li>Content-based boost — active auctions sharing category or tags with the user's
 *       recent bids / watchlist / browse history (excludes already recommended ids)</li>
 *   <li>Trending filler — bid-count / soonest-ending for cold-start and remaining slots</li>
 * </ol>
 *
 * <p>Pure SQL over existing tables — no external ML dependency — which keeps the
 * approach explainable and defensible for the project's scope.</p>
 */
public class RecommendationDAO {

    /**
     * Returns up to {@code limit} active, open auctions recommended for {@code userId},
     * ranked by CF score, then content similarity, topped up with trending auctions.
     * Auctions the user dismissed ("not interested") are always excluded (SCRUM-74).
     */
    public List<SearchResultItem> recommendForUser(int userId, int limit) {
        Set<Long> dismissed = listDismissedIds(userId);

        List<SearchResultItem> cf = tag(collaborativeFiltering(userId, limit + dismissed.size()),
                Reason.PEER_BIDS, REASON_PEER_BIDS);
        cf.removeIf(item -> dismissed.contains(item.getAuctionId()));
        if (cf.size() > limit) cf = new ArrayList<>(cf.subList(0, limit));

        Set<Long> exclude = new LinkedHashSet<>(dismissed);
        for (SearchResultItem item : cf) exclude.add(item.getAuctionId());

        List<SearchResultItem> combined = new ArrayList<>(cf);

        if (combined.size() < limit) {
            List<SearchResultItem> ubcf = tag(
                    userBasedCosineRecommendations(userId, limit - combined.size(), exclude),
                    Reason.SIMILAR_TASTE, REASON_SIMILAR_TASTE);
            for (SearchResultItem item : ubcf) {
                combined.add(item);
                exclude.add(item.getAuctionId());
            }
        }

        if (combined.size() < limit) {
            List<SearchResultItem> content = contentBased(userId, limit - combined.size(), exclude);
            for (SearchResultItem item : content) {
                combined.add(item);
                exclude.add(item.getAuctionId());
            }
        }

        if (combined.size() >= limit) return combined;

        List<SearchResultItem> filler = trending(limit - combined.size(), exclude, userId);
        combined.addAll(filler);
        return combined;
    }

    // -------------------------------------------------------------------------
    // "Buyers who bid on this also bid on…" (SCRUM-73)
    // -------------------------------------------------------------------------

    /**
     * Auctions most often bid on by the buyers who also bid on {@code auctionId},
     * ranked by co-bidder count. Excludes the viewer's dismissed items when known.
     */
    public List<SearchResultItem> similarByBidders(long auctionId, Integer viewerId, int limit) {
        Set<Long> dismissed = viewerId != null ? listDismissedIds(viewerId) : Set.of();

        StringBuilder sql = new StringBuilder(
            "WITH co_bidders AS ( "
          + "  SELECT DISTINCT user_id FROM bids WHERE auction_id = ? "
          + "), cand AS ( "
          + "  SELECT b.auction_id, COUNT(DISTINCT b.user_id) AS score FROM bids b "
          + "  WHERE b.user_id IN (SELECT user_id FROM co_bidders) AND b.auction_id <> ? "
          + "  GROUP BY b.auction_id "
          + ") "
          + "SELECT a.auction_id, d.title, d.category, a.auction_type, "
          // Blind auctions resolve to the entry price: recommended listings are
          // all still open, so their leading sealed bid must not reach the client.
          + "  CASE WHEN a.auction_type = 3 THEN d.starting_price "
          + "       ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) END AS current_price, "
          + "  a.date_end, u.username, "
          + "  (SELECT image_url FROM auction_images i WHERE i.auction_id = a.auction_id ORDER BY id LIMIT 1) AS thumb "
          + "FROM cand c "
          + "JOIN auction a ON a.auction_id = c.auction_id "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "JOIN users u ON u.id = a.seller_id "
          + "WHERE a.status_id = 1 AND a.moderation_state = 'active' AND a.date_end > now() ");

        List<Long> excl = new ArrayList<>(dismissed);
        if (!excl.isEmpty()) {
            sql.append("AND a.auction_id NOT IN (");
            for (int i = 0; i < excl.size(); i++) sql.append(i == 0 ? "?" : ",?");
            sql.append(") ");
        }
        sql.append("ORDER BY c.score DESC, a.date_end ASC LIMIT ?");

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, auctionId);
            ps.setLong(idx++, auctionId);
            for (Long id : excl) ps.setLong(idx++, id);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return tag(mapRows(rs), Reason.PEER_BIDS, REASON_PEER_BIDS);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Dismissed recommendations (SCRUM-74)
    // -------------------------------------------------------------------------

    /** Marks an auction as "not interested" for the user. Idempotent. */
    public boolean dismiss(int userId, long auctionId) {
        String sql = "INSERT INTO dismissed_recommendations (user_id, auction_id) VALUES (?, ?) "
                + "ON CONFLICT (user_id, auction_id) DO NOTHING";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setLong(2, auctionId);
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Auction ids the user dismissed. Empty when the table is missing. */
    public Set<Long> listDismissedIds(int userId) {
        Set<Long> out = new LinkedHashSet<>();
        String sql = "SELECT auction_id FROM dismissed_recommendations WHERE user_id = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        } catch (Exception ignored) {
            // migration not applied yet — treat as no dismissals
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Impression / click events + performance metrics (SCRUM-75)
    // -------------------------------------------------------------------------

    /** Records an IMPRESSION or CLICK for recommendation analytics. Best-effort. */
    public void recordEvent(Integer userId, long auctionId, String eventType) {
        recordEvent(userId, auctionId, eventType, null);
    }

    /**
     * Records an IMPRESSION or CLICK, optionally attributed to the search keyword that
     * surfaced the card. Best-effort: falls back to a keyword-less insert when the
     * explainability migration has not been applied yet.
     */
    public void recordEvent(Integer userId, long auctionId, String eventType, String sourceKeyword) {
        String keyword = normaliseKeyword(sourceKeyword);
        if (keyword != null && insertEvent(userId, auctionId, eventType, keyword)) return;
        insertEvent(userId, auctionId, eventType, null);
    }

    private boolean insertEvent(Integer userId, long auctionId, String eventType, String keyword) {
        String sql = keyword == null
                ? "INSERT INTO recommendation_events (user_id, auction_id, event_type) VALUES (?, ?, ?)"
                : "INSERT INTO recommendation_events (user_id, auction_id, event_type, source_keyword) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (userId != null) ps.setInt(1, userId); else ps.setNull(1, java.sql.Types.BIGINT);
            ps.setLong(2, auctionId);
            ps.setString(3, eventType);
            if (keyword != null) ps.setString(4, keyword);
            ps.executeUpdate();
            return true;
        } catch (Exception ignored) {
            // analytics only — never break the page
            return false;
        }
    }

    /** Records a keyword a visitor searched for. Best-effort; guests store a null user. */
    public void recordSearchKeyword(Integer userId, String keyword) {
        String cleaned = normaliseKeyword(keyword);
        if (cleaned == null) return;
        String sql = "INSERT INTO search_history (user_id, keyword) VALUES (?, ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (userId != null) ps.setInt(1, userId); else ps.setNull(1, java.sql.Types.BIGINT);
            ps.setString(2, cleaned);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // analytics only — never break search
        }
    }

    /**
     * Recommendation performance metrics: impressions, clicks, CTR, and conversion
     * rate (share of clicked recommendations the user subsequently bid on).
     */
    public Map<String, Object> metrics() {
        long impressions = countEvents("IMPRESSION");
        long clicks = countEvents("CLICK");
        long conversions = 0;

        String convSql =
            "SELECT COUNT(DISTINCT (e.user_id, e.auction_id)) FROM recommendation_events e "
          + "WHERE e.event_type = 'CLICK' AND e.user_id IS NOT NULL "
          + "AND EXISTS (SELECT 1 FROM bids b WHERE b.user_id = e.user_id AND b.auction_id = e.auction_id)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(convSql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) conversions = rs.getLong(1);
        } catch (Exception ignored) { }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("impressions", impressions);
        out.put("clicks", clicks);
        out.put("conversions", conversions);
        out.put("clickThroughRate", impressions > 0 ? round4((double) clicks / impressions) : 0.0);
        out.put("conversionRate", clicks > 0 ? round4((double) conversions / clicks) : 0.0);
        return out;
    }

    private long countEvents(String type) {
        String sql = "SELECT COUNT(*) FROM recommendation_events WHERE event_type = ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // -------------------------------------------------------------------------
    // Explainability: why an item was recommended, and how it has performed
    // -------------------------------------------------------------------------

    static final String REASON_PEER_BIDS     = "Buyers who bid on your items also bid on this";
    static final String REASON_SIMILAR_TASTE = "Buyers with similar taste are watching this";
    static final String REASON_TRENDING      = "Trending — collecting the most bids today";

    /** Stages that consume a signal belonging to the viewer; trending filler does not. */
    private static final Set<Reason> PERSONALISED_REASONS = EnumSet.of(
            Reason.SEARCH_KEYWORD, Reason.PEER_BIDS, Reason.SIMILAR_TASTE, Reason.SAME_CATEGORY);

    /** Longest keyword history considered when attributing a card to a search. */
    private static final int VIEWER_KEYWORD_LOOKBACK = 12;
    /** Most keywords shown on a single card. */
    private static final int MAX_KEYWORDS_PER_ITEM = 3;
    /**
     * A masked username is only shown once this many distinct people have clicked, so a
     * single clicker can never be identified by elimination on a quiet listing.
     */
    private static final int MIN_CLICKERS_TO_NAME = 2;

    /**
     * Fills in click performance and search-keyword attribution for a recommendation list,
     * upgrading the reason to "matches your search" where one of the viewer's own recent
     * keywords explains the card. Items without a stage reason default to trending.
     *
     * <p>Only aggregates and masked names are written here — see
     * {@link #attributionDetail(long, int)} for the admin-only per-user view.</p>
     */
    public void attachProvenance(List<SearchResultItem> items, Integer viewerId) {
        if (items == null || items.isEmpty()) return;
        for (SearchResultItem item : items) {
            if (item.getWhy() == null) {
                item.setWhy(new RecommendationProvenance(Reason.TRENDING, REASON_TRENDING));
            }
        }
        applyKeywordAttribution(items, viewerId);
        applyClickStats(items);
    }

    /**
     * Whether at least one item was actually produced by a personalised stage. A signed-in
     * user with no bids, watchlist or browse history falls through to trending filler, so
     * being logged in is not on its own evidence that the list is personalised.
     *
     * <p>Call after {@link #attachProvenance(List, Integer)} so a card upgraded to the
     * viewer's own search keyword is counted.</p>
     */
    public static boolean isPersonalised(List<SearchResultItem> items) {
        if (items == null) return false;
        for (SearchResultItem item : items) {
            RecommendationProvenance why = item.getWhy();
            if (why != null && PERSONALISED_REASONS.contains(why.reason())) return true;
        }
        return false;
    }

    /** Distinct keywords the user searched for, most recently used first. */
    public List<String> recentKeywords(int userId, int limit) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT keyword FROM search_history WHERE user_id = ? "
                + "GROUP BY keyword ORDER BY MAX(created_at) DESC LIMIT ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (Exception ignored) {
            // migration not applied yet — no keyword attribution
        }
        return out;
    }

    private void applyKeywordAttribution(List<SearchResultItem> items, Integer viewerId) {
        Map<Long, List<String>> surfaced = keywordsPerAuction(items);
        List<String> mine = viewerId == null ? List.of() : recentKeywords(viewerId, VIEWER_KEYWORD_LOOKBACK);

        for (SearchResultItem item : items) {
            List<String> keywords = new ArrayList<>();
            String myMatch = null;
            String haystack = ((item.getTitle() == null ? "" : item.getTitle()) + " "
                    + (item.getCategory() == null ? "" : item.getCategory())).toLowerCase(Locale.ROOT);
            for (String keyword : mine) {
                if (normaliseKeyword(keyword) == null) continue;
                if (keywordMatches(haystack, keyword.trim().toLowerCase(Locale.ROOT))) {
                    if (myMatch == null) myMatch = keyword;
                    keywords.add(keyword);
                }
            }
            for (String keyword : surfaced.getOrDefault(item.getAuctionId(), List.of())) {
                if (keywords.stream().noneMatch(k -> k.equalsIgnoreCase(keyword))) keywords.add(keyword);
            }
            if (keywords.size() > MAX_KEYWORDS_PER_ITEM) keywords = keywords.subList(0, MAX_KEYWORDS_PER_ITEM);

            if (myMatch != null) {
                item.setWhy(new RecommendationProvenance(
                        Reason.SEARCH_KEYWORD, "Matches your search for “" + myMatch + "”"));
            }
            item.getWhy().setKeywords(keywords);
        }
    }

    /** Keywords previously attributed to each auction's impressions/clicks, most used first. */
    private Map<Long, List<String>> keywordsPerAuction(List<SearchResultItem> items) {
        Map<Long, List<String>> out = new HashMap<>();
        String sql = "SELECT auction_id, source_keyword, COUNT(*) AS uses FROM recommendation_events "
                + "WHERE source_keyword IS NOT NULL AND auction_id IN (" + placeholders(items.size()) + ") "
                + "GROUP BY auction_id, source_keyword ORDER BY auction_id, uses DESC";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindAuctionIds(ps, items);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> list = out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>());
                    if (list.size() < MAX_KEYWORDS_PER_ITEM) list.add(rs.getString(2));
                }
            }
        } catch (Exception ignored) {
            // migration not applied yet — no aggregate keywords
        }
        return out;
    }

    private void applyClickStats(List<SearchResultItem> items) {
        Map<Long, SearchResultItem> byId = new LinkedHashMap<>();
        for (SearchResultItem item : items) byId.put(item.getAuctionId(), item);

        String countSql = "SELECT auction_id, COUNT(*) AS clicks, COUNT(DISTINCT user_id) AS clickers "
                + "FROM recommendation_events WHERE event_type = 'CLICK' "
                + "AND auction_id IN (" + placeholders(items.size()) + ") GROUP BY auction_id";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            bindAuctionIds(ps, items);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SearchResultItem item = byId.get(rs.getLong("auction_id"));
                    if (item == null) continue;
                    item.getWhy().setClickCount(rs.getLong("clicks"));
                    item.getWhy().setDistinctClickers(rs.getLong("clickers"));
                }
            }
        } catch (Exception ignored) {
            // migration not applied yet — leave click figures at zero
            return;
        }

        String sampleSql = "SELECT e.auction_id, u.username FROM ("
                + "  SELECT DISTINCT ON (auction_id) auction_id, user_id FROM recommendation_events "
                + "  WHERE event_type = 'CLICK' AND user_id IS NOT NULL "
                + "    AND auction_id IN (" + placeholders(items.size()) + ") "
                + "  ORDER BY auction_id, created_at DESC"
                + ") e JOIN users u ON u.id = e.user_id";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sampleSql)) {
            bindAuctionIds(ps, items);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SearchResultItem item = byId.get(rs.getLong(1));
                    if (item == null) continue;
                    if (item.getWhy().getDistinctClickers() < MIN_CLICKERS_TO_NAME) continue;
                    item.getWhy().setClickedByMasked(SecurityUtil.maskUsername(rs.getString(2)));
                }
            }
        } catch (Exception ignored) {
            // masked sample is optional — counts alone still explain the card
        }
    }

    /**
     * ADMIN-only provenance detail for one auction: who clicked, what they searched, and
     * when. Never reachable from a public endpoint — the landing page reads only the
     * aggregates produced by {@link #attachProvenance(List, Integer)}.
     */
    public Map<String, Object> attributionDetail(long auctionId, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("auctionId", auctionId);
        out.put("events", queryList(
                "SELECT e.user_id, u.username, e.event_type, e.source_keyword, e.created_at "
              + "FROM recommendation_events e LEFT JOIN users u ON u.id = e.user_id "
              + "WHERE e.auction_id = ? ORDER BY e.created_at DESC LIMIT ?",
                auctionId, limit,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long uid = rs.getLong(1);
                    row.put("userId", rs.wasNull() ? null : uid);
                    row.put("username", rs.getString(2));
                    row.put("eventType", rs.getString(3));
                    row.put("sourceKeyword", rs.getString(4));
                    row.put("createdAt", instantOf(rs.getTimestamp(5)));
                    return row;
                }));
        out.put("searches", queryList(
                "SELECT s.user_id, u.username, s.keyword, s.created_at "
              + "FROM search_history s LEFT JOIN users u ON u.id = s.user_id "
              + "JOIN auction_details d ON d.id = ? "
              + "WHERE POSITION(LOWER(s.keyword) IN LOWER(d.title)) > 0 "
              + "   OR LOWER(s.keyword) = LOWER(COALESCE(d.category, '')) "
              + "ORDER BY s.created_at DESC LIMIT ?",
                auctionId, limit,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long uid = rs.getLong(1);
                    row.put("userId", rs.wasNull() ? null : uid);
                    row.put("username", rs.getString(2));
                    row.put("keyword", rs.getString(3));
                    row.put("createdAt", instantOf(rs.getTimestamp(4)));
                    return row;
                }));
        return out;
    }

    /** ADMIN-only overview: the recommendations people actually click, and what they search. */
    public Map<String, Object> attributionOverview(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topAuctions", queryList(
                "SELECT e.auction_id, d.title, "
              + "  COUNT(*) FILTER (WHERE e.event_type = 'CLICK') AS clicks, "
              + "  COUNT(*) FILTER (WHERE e.event_type = 'IMPRESSION') AS impressions, "
              + "  COUNT(DISTINCT e.user_id) FILTER (WHERE e.event_type = 'CLICK') AS clickers "
              + "FROM recommendation_events e LEFT JOIN auction_details d ON d.id = e.auction_id "
              + "GROUP BY e.auction_id, d.title ORDER BY clicks DESC, impressions DESC LIMIT ?",
                null, limit,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("auctionId", rs.getLong(1));
                    row.put("title", rs.getString(2));
                    row.put("clicks", rs.getLong(3));
                    row.put("impressions", rs.getLong(4));
                    row.put("distinctClickers", rs.getLong(5));
                    return row;
                }));
        out.put("topKeywords", queryList(
                "SELECT keyword, COUNT(*) AS searches, COUNT(DISTINCT user_id) AS searchers "
              + "FROM search_history GROUP BY keyword ORDER BY searches DESC LIMIT ?",
                null, limit,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("keyword", rs.getString(1));
                    row.put("searches", rs.getLong(2));
                    row.put("searchers", rs.getLong(3));
                    return row;
                }));
        return out;
    }

    private interface RowMapper {
        Map<String, Object> map(ResultSet rs) throws Exception;
    }

    /** Runs an analytics query, returning an empty list when its table is not migrated yet. */
    private List<Map<String, Object>> queryList(String sql, Long auctionId, int limit, RowMapper mapper) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (auctionId != null) ps.setLong(idx++, auctionId);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapper.map(rs));
            }
        } catch (Exception ignored) {
            // analytics only — an un-migrated database reports nothing rather than failing
        }
        return out;
    }

    private static Instant instantOf(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    private void bindAuctionIds(PreparedStatement ps, List<SearchResultItem> items) throws Exception {
        int idx = 1;
        for (SearchResultItem item : items) ps.setLong(idx++, item.getAuctionId());
    }

    /**
     * Shortest keyword worth storing or crediting. One character matches almost every
     * listing, which let a search for "a" overwrite every genuine reason on the page.
     * Two is kept as the floor because real auction searches are this short ("tv", "pc").
     */
    public static final int MIN_KEYWORD_LENGTH = 2;

    /** At or above this length a keyword may match anywhere inside a word. */
    private static final int FREE_SUBSTRING_LENGTH = 3;

    private static String normaliseKeyword(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.length() < MIN_KEYWORD_LENGTH) return null;
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    /**
     * Substring matching is kept for keywords of three characters or more, because the
     * partial hits it allows are the ones buyers expect — "phone" inside "iPhone",
     * "watch" inside "Smartwatch". Two-character keywords are held to a word boundary
     * instead, so "vr" cannot claim credit for "Louvre".
     */
    private static boolean keywordMatches(String haystack, String keyword) {
        if (keyword.length() >= FREE_SUBSTRING_LENGTH) return haystack.contains(keyword);
        for (int at = haystack.indexOf(keyword); at >= 0; at = haystack.indexOf(keyword, at + 1)) {
            int end = at + keyword.length();
            boolean startsWord = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            boolean endsWord = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (startsWord && endsWord) return true;
        }
        return false;
    }

    private static List<SearchResultItem> tag(List<SearchResultItem> items, Reason code, String text) {
        for (SearchResultItem item : items) item.setWhy(new RecommendationProvenance(code, text));
        return items;
    }

    /**
     * Wording for a content-based hit. The category sentence is only used when the
     * candidate's category is one the viewer actually has history in; a row that only
     * qualified on tag overlap says so instead of naming a category the viewer has
     * never browsed.
     */
    private static String contentReason(boolean categoryMatch, String category, String sharedTag) {
        if (categoryMatch && category != null && !category.isBlank()) {
            return "Because you looked at similar " + category;
        }
        if (sharedTag != null && !sharedTag.isBlank()) {
            return "Tagged “" + sharedTag + "”, like items you've viewed";
        }
        return "Similar to items you viewed recently";
    }

    // -------------------------------------------------------------------------
    // Tunable parameters (SCRUM-76)
    // -------------------------------------------------------------------------

    public static final int DEFAULT_ITEMS_SHOWN = 8;
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.1;

    /** Immutable snapshot of the admin-tunable recommendation parameters. */
    public static final class Settings {
        public final int itemsShown;
        public final double similarityThreshold;

        public Settings(int itemsShown, double similarityThreshold) {
            this.itemsShown = itemsShown;
            this.similarityThreshold = similarityThreshold;
        }
    }

    /** Loads settings, falling back to defaults when unset or the table is missing. */
    public Settings getSettings() {
        int items = DEFAULT_ITEMS_SHOWN;
        double threshold = DEFAULT_SIMILARITY_THRESHOLD;
        String sql = "SELECT key, value FROM recommendation_settings";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString("key");
                String value = rs.getString("value");
                try {
                    if ("items_shown".equals(key)) items = Integer.parseInt(value);
                    else if ("similarity_threshold".equals(key)) threshold = Double.parseDouble(value);
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) { }
        return new Settings(Math.max(1, Math.min(24, items)),
                Math.max(0.0, Math.min(1.0, threshold)));
    }

    /** Persists the admin-tunable settings (upsert). */
    public boolean saveSettings(int itemsShown, double similarityThreshold) {
        String sql = "INSERT INTO recommendation_settings (key, value, updated_at) VALUES (?, ?, NOW()) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "items_shown");
            ps.setString(2, String.valueOf(Math.max(1, Math.min(24, itemsShown))));
            ps.addBatch();
            ps.setString(1, "similarity_threshold");
            ps.setString(2, String.valueOf(Math.max(0.0, Math.min(1.0, similarityThreshold))));
            ps.addBatch();
            ps.executeBatch();
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * User-based CF with cosine similarity (FR4.1), using bids, watchlist, and browse history (FR4.3).
     */
    private List<SearchResultItem> userBasedCosineRecommendations(int userId, int limit, Set<Long> exclude) {
        if (limit <= 0) return List.of();

        Map<Integer, Map<Long, Double>> vectors = loadInteractionVectors();
        List<Long> rankedIds = UserBasedCollaborativeFilter.rankAuctionIds(
                userId, vectors, limit, exclude, getSettings().similarityThreshold);
        if (rankedIds.isEmpty()) return List.of();
        return fetchItemsByIds(rankedIds, userId, limit);
    }

    private Map<Integer, Map<Long, Double>> loadInteractionVectors() {
        Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
        String sql =
            "SELECT user_id, auction_id, 'BID' AS src FROM bids "
          + "UNION ALL SELECT user_id, auction_id, 'WATCH' FROM watchlist "
          + "UNION ALL SELECT user_id, auction_id, 'BROWSE' FROM browse_history";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int uid = rs.getInt("user_id");
                long aid = rs.getLong("auction_id");
                double w;
                String src = rs.getString("src");
                if ("BID".equals(src)) {
                    w = UserBasedCollaborativeFilter.weightBid();
                } else if ("WATCH".equals(src)) {
                    w = UserBasedCollaborativeFilter.weightWatchlist();
                } else {
                    w = UserBasedCollaborativeFilter.weightBrowse();
                }
                UserBasedCollaborativeFilter.addInteraction(vectors, uid, aid, w);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return vectors;
    }

    private List<SearchResultItem> fetchItemsByIds(List<Long> auctionIds, int excludeSellerId, int limit) {
        if (auctionIds.isEmpty()) return List.of();

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < auctionIds.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }

        String sql =
            "SELECT a.auction_id, d.title, d.category, a.auction_type, "
          // Blind auctions resolve to the entry price: recommended listings are
          // all still open, so their leading sealed bid must not reach the client.
          + "  CASE WHEN a.auction_type = 3 THEN d.starting_price "
          + "       ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) END AS current_price, "
          + "  a.date_end, u.username, "
          + "  (SELECT image_url FROM auction_images i WHERE i.auction_id = a.auction_id ORDER BY id LIMIT 1) AS thumb "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "JOIN users u ON u.id = a.seller_id "
          + "WHERE a.auction_id IN (" + placeholders + ") "
          + "  AND a.status_id = 1 AND a.moderation_state = 'active' AND a.date_end > now() "
          + "  AND a.seller_id <> ?";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Long id : auctionIds) ps.setLong(idx++, id);
            ps.setInt(idx, excludeSellerId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<Long, SearchResultItem> byId = new HashMap<>();
                while (rs.next()) {
                    long aid = rs.getLong("auction_id");
                    BigDecimal price = rs.getBigDecimal("current_price");
                    Timestamp end = rs.getTimestamp("date_end");
                    Instant endInstant = end != null ? end.toInstant() : null;
                    byId.put(aid, new SearchResultItem(
                            aid,
                            rs.getString("title"),
                            rs.getString("category"),
                            price,
                            endInstant,
                            rs.getString("username"),
                            rs.getString("thumb"),
                            rs.getInt("auction_type")));
                }
                List<SearchResultItem> ordered = new ArrayList<>();
                for (Long id : auctionIds) {
                    SearchResultItem item = byId.get(id);
                    if (item != null) {
                        ordered.add(item);
                        if (ordered.size() >= limit) break;
                    }
                }
                return ordered;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<SearchResultItem> collaborativeFiltering(int userId, int limit) {
        String sql =
            "WITH my_items AS ( "
          + "  SELECT auction_id FROM bids WHERE user_id = ? "
          + "  UNION SELECT auction_id FROM watchlist WHERE user_id = ? "
          + "), peers AS ( "
          + "  SELECT DISTINCT user_id FROM bids "
          + "    WHERE auction_id IN (SELECT auction_id FROM my_items) AND user_id <> ? "
          + "  UNION SELECT DISTINCT user_id FROM watchlist "
          + "    WHERE auction_id IN (SELECT auction_id FROM my_items) AND user_id <> ? "
          + "), cand AS ( "
          + "  SELECT auction_id, SUM(score) AS score FROM ( "
          + "     SELECT auction_id, COUNT(*) AS score FROM bids "
          + "       WHERE user_id IN (SELECT user_id FROM peers) GROUP BY auction_id "
          + "     UNION ALL "
          + "     SELECT auction_id, COUNT(*) AS score FROM watchlist "
          + "       WHERE user_id IN (SELECT user_id FROM peers) GROUP BY auction_id "
          + "  ) s GROUP BY auction_id "
          + ") "
          + "SELECT a.auction_id, d.title, d.category, a.auction_type, "
          // Blind auctions resolve to the entry price: recommended listings are
          // all still open, so their leading sealed bid must not reach the client.
          + "  CASE WHEN a.auction_type = 3 THEN d.starting_price "
          + "       ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) END AS current_price, "
          + "  a.date_end, u.username, "
          + "  (SELECT image_url FROM auction_images i WHERE i.auction_id = a.auction_id ORDER BY id LIMIT 1) AS thumb "
          + "FROM cand c "
          + "JOIN auction a ON a.auction_id = c.auction_id "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "JOIN users u ON u.id = a.seller_id "
          + "WHERE a.status_id = 1 AND a.moderation_state = 'active' AND a.date_end > now() "
          + "  AND a.auction_id NOT IN (SELECT auction_id FROM my_items) "
          + "  AND a.seller_id <> ? "
          + "ORDER BY c.score DESC, a.date_end ASC "
          + "LIMIT ?";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            ps.setInt(4, userId);
            ps.setInt(5, userId);
            ps.setInt(6, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return mapRows(rs);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Content-based recommendations: active auctions that share a category or tag with
     * auctions the user recently bid on, watched, or browsed.
     */
    private List<SearchResultItem> contentBased(int userId, int limit, Set<Long> excludeIds) {
        if (limit <= 0) return List.of();

        StringBuilder sql = new StringBuilder(
            "WITH my_signals AS ( "
          + "  SELECT auction_id FROM bids WHERE user_id = ? "
          + "  UNION SELECT auction_id FROM watchlist WHERE user_id = ? "
          + "  UNION SELECT auction_id FROM browse_history WHERE user_id = ? "
          + "), my_cats AS ( "
          + "  SELECT DISTINCT d.category FROM auction_details d "
          + "  WHERE d.id IN (SELECT auction_id FROM my_signals) "
          + "    AND d.category IS NOT NULL AND TRIM(d.category) <> '' "
          + "), my_tags AS ( "
          + "  SELECT DISTINCT t.tag_id FROM auction_tag_info t "
          + "  WHERE t.auction_id IN (SELECT auction_id FROM my_signals) "
          + ") "
          + "SELECT a.auction_id, d.title, d.category, a.auction_type, "
          // Blind auctions resolve to the entry price: recommended listings are
          // all still open, so their leading sealed bid must not reach the client.
          + "  CASE WHEN a.auction_type = 3 THEN d.starting_price "
          + "       ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) END AS current_price, "
          + "  a.date_end, u.username, "
          + "  (SELECT image_url FROM auction_images i WHERE i.auction_id = a.auction_id ORDER BY id LIMIT 1) AS thumb, "
          // Which arm of the OR below matched, so the reason can be worded honestly.
          + "  COALESCE(d.category IN (SELECT category FROM my_cats), FALSE) AS category_match, "
          + "  (SELECT t.tag_name FROM auction_tag_info ati JOIN tags t ON t.id = ati.tag_id "
          + "     WHERE ati.auction_id = a.auction_id AND ati.tag_id IN (SELECT tag_id FROM my_tags) "
          + "     ORDER BY t.tag_name LIMIT 1) AS shared_tag "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "JOIN users u ON u.id = a.seller_id "
          + "WHERE a.status_id = 1 AND a.moderation_state = 'active' AND a.date_end > now() "
          + "  AND a.seller_id <> ? "
          + "  AND a.auction_id NOT IN (SELECT auction_id FROM my_signals) "
          + "  AND ( "
          + "    d.category IN (SELECT category FROM my_cats) "
          + "    OR EXISTS ( "
          + "      SELECT 1 FROM auction_tag_info ati "
          + "      WHERE ati.auction_id = a.auction_id "
          + "        AND ati.tag_id IN (SELECT tag_id FROM my_tags) "
          + "    ) "
          + "  ) ");

        List<Long> excl = (excludeIds == null) ? new ArrayList<>() : new ArrayList<>(excludeIds);
        if (!excl.isEmpty()) {
            sql.append("AND a.auction_id NOT IN (");
            for (int i = 0; i < excl.size(); i++) sql.append(i == 0 ? "?" : ",?");
            sql.append(") ");
        }
        sql.append("ORDER BY a.date_end ASC LIMIT ?");

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setInt(idx++, userId);
            ps.setInt(idx++, userId);
            ps.setInt(idx++, userId);
            ps.setInt(idx++, userId);
            for (Long id : excl) ps.setLong(idx++, id);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<SearchResultItem> out = new ArrayList<>();
                while (rs.next()) {
                    SearchResultItem item = mapRow(rs);
                    item.setWhy(new RecommendationProvenance(Reason.SAME_CATEGORY,
                            contentReason(rs.getBoolean("category_match"),
                                    item.getCategory(), rs.getString("shared_tag"))));
                    out.add(item);
                }
                return out;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Trending active auctions ordered by bid count then soonest-ending. Used for
     * cold-start users and to top up sparse CF results.
     */
    public List<SearchResultItem> trending(int limit, Set<Long> excludeIds, Integer excludeSellerId) {
        StringBuilder sql = new StringBuilder(
            "SELECT a.auction_id, d.title, d.category, a.auction_type, "
          // Blind auctions resolve to the entry price: recommended listings are
          // all still open, so their leading sealed bid must not reach the client.
          + "  CASE WHEN a.auction_type = 3 THEN d.starting_price "
          + "       ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) END AS current_price, "
          + "  a.date_end, u.username, "
          + "  (SELECT image_url FROM auction_images i WHERE i.auction_id = a.auction_id ORDER BY id LIMIT 1) AS thumb, "
          + "  (SELECT COUNT(*) FROM bids b WHERE b.auction_id = a.auction_id) AS bid_count "
          + "FROM auction a "
          + "JOIN auction_details d ON d.id = a.auction_id "
          + "JOIN users u ON u.id = a.seller_id "
          + "WHERE a.status_id = 1 AND a.moderation_state = 'active' AND a.date_end > now() ");

        List<Long> excl = (excludeIds == null) ? new ArrayList<>() : new ArrayList<>(excludeIds);
        if (!excl.isEmpty()) {
            sql.append("AND a.auction_id NOT IN (");
            for (int i = 0; i < excl.size(); i++) sql.append(i == 0 ? "?" : ",?");
            sql.append(") ");
        }
        if (excludeSellerId != null) sql.append("AND a.seller_id <> ? ");
        sql.append("ORDER BY bid_count DESC, a.date_end ASC LIMIT ?");

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Long id : excl) ps.setLong(idx++, id);
            if (excludeSellerId != null) ps.setInt(idx++, excludeSellerId);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return tag(mapRows(rs), Reason.TRENDING, REASON_TRENDING);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<SearchResultItem> mapRows(ResultSet rs) throws Exception {
        List<SearchResultItem> out = new ArrayList<>();
        while (rs.next()) out.add(mapRow(rs));
        return out;
    }

    private SearchResultItem mapRow(ResultSet rs) throws Exception {
        Timestamp end = rs.getTimestamp("date_end");
        return new SearchResultItem(
                rs.getLong("auction_id"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getBigDecimal("current_price"),
                end != null ? end.toInstant() : null,
                rs.getString("username"),
                rs.getString("thumb"),
                rs.getInt("auction_type"));
    }
}
