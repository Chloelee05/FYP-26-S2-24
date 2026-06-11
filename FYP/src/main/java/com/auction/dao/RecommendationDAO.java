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
 * Personalised auction recommendations via item-based collaborative filtering.
 *
 * <p>Signal = a buyer's interactions (bids + watchlist). We find "peer" buyers who
 * interacted with the same auctions, then score the <em>other</em> auctions those
 * peers interacted with by co-occurrence frequency ("buyers like you also bid on…").
 * Cold-start / sparse results are topped up with trending active auctions.</p>
 *
 * <p>Pure SQL over existing tables — no external ML dependency — which keeps the
 * approach explainable and defensible for the project's scope.</p>
 */
public class RecommendationDAO {

    /**
     * Returns up to {@code limit} active, open auctions recommended for {@code userId},
     * ranked by collaborative-filtering score and topped up with trending auctions.
     */
    public List<SearchResultItem> recommendForUser(int userId, int limit) {
        List<SearchResultItem> cf = collaborativeFiltering(userId, limit);

        Set<Long> exclude = new LinkedHashSet<>();
        for (SearchResultItem item : cf) exclude.add(item.getAuctionId());

        List<SearchResultItem> combined = new ArrayList<>(cf);

        if (combined.size() < limit) {
            List<SearchResultItem> ubcf = userBasedCosineRecommendations(userId, limit - combined.size(), exclude);
            for (SearchResultItem item : ubcf) {
                combined.add(item);
                exclude.add(item.getAuctionId());
            }
        }

        if (combined.size() >= limit) return combined;

        List<SearchResultItem> filler = trending(limit - combined.size(), exclude, userId);
        combined.addAll(filler);
        return combined;
    }

    /**
     * User-based CF with cosine similarity (FR4.1), using bids, watchlist, and browse history (FR4.3).
     */
    private List<SearchResultItem> userBasedCosineRecommendations(int userId, int limit, Set<Long> exclude) {
        if (limit <= 0) return List.of();

        Map<Integer, Map<Long, Double>> vectors = loadInteractionVectors();
        List<Long> rankedIds = UserBasedCollaborativeFilter.rankAuctionIds(userId, vectors, limit, exclude);
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
