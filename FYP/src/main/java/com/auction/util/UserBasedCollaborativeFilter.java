package com.auction.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User-based collaborative filtering with cosine similarity (FR4.1).
 *
 * <p>Default interaction weights: bid = 3, watchlist = 2, browse = 1 — a bid commits money
 * and says most about taste, a watchlist entry says less, a page view least. These are only
 * the seeded defaults: the live values are the {@code w_bid} / {@code w_watchlist} /
 * {@code w_browse} rows of {@code recommendation_settings}, which
 * {@code RecommendationDAO.loadInteractionVectors} reads on every ranking pass.</p>
 */
public final class UserBasedCollaborativeFilter {

    private static final double W_BID = 3.0;
    private static final double W_WATCHLIST = 2.0;
    private static final double W_BROWSE = 1.0;

    private UserBasedCollaborativeFilter() {}

    public static List<Long> rankAuctionIds(
            int targetUserId,
            Map<Integer, Map<Long, Double>> userVectors,
            int limit,
            Set<Long> exclude) {
        return rankAuctionIds(targetUserId, userVectors, limit, exclude, 0.0);
    }

    /**
     * Same as {@link #rankAuctionIds(int, Map, int, Set)} but ignores peers whose
     * cosine similarity is below {@code minSimilarity} (admin-tunable threshold).
     */
    public static List<Long> rankAuctionIds(
            int targetUserId,
            Map<Integer, Map<Long, Double>> userVectors,
            int limit,
            Set<Long> exclude,
            double minSimilarity) {

        Map<Long, Double> target = userVectors.get(targetUserId);
        if (target == null || target.isEmpty() || limit <= 0) {
            return List.of();
        }

        Set<Long> excludeAll = new HashSet<>(exclude == null ? Set.of() : exclude);
        excludeAll.addAll(target.keySet());

        Map<Long, Double> scores = new HashMap<>();

        for (Map.Entry<Integer, Map<Long, Double>> peer : userVectors.entrySet()) {
            if (peer.getKey() == targetUserId) continue;
            double sim = cosine(target, peer.getValue());
            if (sim <= 0 || sim < minSimilarity) continue;
            for (Map.Entry<Long, Double> item : peer.getValue().entrySet()) {
                if (excludeAll.contains(item.getKey())) continue;
                scores.merge(item.getKey(), sim * item.getValue(), Double::sum);
            }
        }

        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Comparator.<Map.Entry<Long, Double>>comparingDouble(Map.Entry::getValue).reversed());

        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, Double> e : ranked) {
            out.add(e.getKey());
            if (out.size() >= limit) break;
        }
        return out;
    }

    public static void addInteraction(Map<Integer, Map<Long, Double>> vectors, int userId, long auctionId, double weight) {
        vectors.computeIfAbsent(userId, k -> new HashMap<>())
               .merge(auctionId, weight, Math::max);
    }

    /** Fallback weight used when {@code recommendation_settings} has no {@code w_bid} row. */
    public static double weightBid()       { return W_BID; }
    /** Fallback weight used when {@code recommendation_settings} has no {@code w_watchlist} row. */
    public static double weightWatchlist() { return W_WATCHLIST; }
    /** Fallback weight used when {@code recommendation_settings} has no {@code w_browse} row. */
    public static double weightBrowse()    { return W_BROWSE; }

    static double cosine(Map<Long, Double> a, Map<Long, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;

        double dot = 0, normA = 0, normB = 0;
        Map<Long, Double> smaller = a.size() <= b.size() ? a : b;
        Map<Long, Double> larger  = a.size() <= b.size() ? b : a;

        for (Map.Entry<Long, Double> e : smaller.entrySet()) {
            Double other = larger.get(e.getKey());
            if (other != null) dot += e.getValue() * other;
        }
        for (double v : a.values()) normA += v * v;
        for (double v : b.values()) normB += v * v;
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
