package com.auction.dao;

import com.auction.model.SearchResultItem;
import com.auction.util.DBUtil;
import com.auction.util.UserBasedCollaborativeFilter;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

        List<SearchResultItem> cf = collaborativeFiltering(userId, limit + dismissed.size());
        cf.removeIf(item -> dismissed.contains(item.getAuctionId()));
        if (cf.size() > limit) cf = new ArrayList<>(cf.subList(0, limit));

        Set<Long> exclude = new LinkedHashSet<>(dismissed);
        for (SearchResultItem item : cf) exclude.add(item.getAuctionId());

        List<SearchResultItem> combined = new ArrayList<>(cf);

        if (combined.size() < limit) {
            List<SearchResultItem> ubcf = userBasedCosineRecommendations(userId, limit - combined.size(), exclude);
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
          + "SELECT a.auction_id, d.title, d.category, "
          + "  COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) AS current_price, "
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
                return mapRows(rs);
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
        String sql = "INSERT INTO recommendation_events (user_id, auction_id, event_type) VALUES (?, ?, ?)";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (userId != null) ps.setInt(1, userId); else ps.setNull(1, java.sql.Types.BIGINT);
            ps.setLong(2, auctionId);
            ps.setString(3, eventType);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // analytics only — never break the page
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
            "SELECT a.auction_id, d.title, d.category, "
          + "  COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) AS current_price, "
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
                            rs.getString("thumb")));
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
          + "SELECT a.auction_id, d.title, d.category, "
          + "  COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) AS current_price, "
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
          + "SELECT a.auction_id, d.title, d.category, "
          + "  COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) AS current_price, "
          + "  a.date_end, u.username, "
          + "  (SELECT image_url FROM auction_images i WHERE i.auction_id = a.auction_id ORDER BY id LIMIT 1) AS thumb "
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
                return mapRows(rs);
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
            "SELECT a.auction_id, d.title, d.category, "
          + "  COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) AS current_price, "
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
                return mapRows(rs);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<SearchResultItem> mapRows(ResultSet rs) throws Exception {
        List<SearchResultItem> out = new ArrayList<>();
        while (rs.next()) {
            BigDecimal price = rs.getBigDecimal("current_price");
            Timestamp end = rs.getTimestamp("date_end");
            Instant endInstant = end != null ? end.toInstant() : null;
            out.add(new SearchResultItem(
                    rs.getLong("auction_id"),
                    rs.getString("title"),
                    rs.getString("category"),
                    price,
                    endInstant,
                    rs.getString("username"),
                    rs.getString("thumb")));
        }
        return out;
    }
}
