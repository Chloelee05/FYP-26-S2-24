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
 *
 * <h2>How the similarity works</h2>
 * <p>Each user is described by a vector: one number per auction they have interacted with,
 * the number being the weight of the strongest interaction. Two users are compared by
 * cosine similarity, which measures whether their vectors point in the same direction
 * rather than how long they are. In plain terms it asks how much of two people's attention
 * fell on the same listings, and it deliberately ignores how much attention each of them
 * paid overall. That matters here because activity varies enormously: a member who has bid
 * on two hundred listings should not come out similar to everybody simply by having touched
 * more things. The result runs from 0 for no overlap up to 1 for identical taste, and only
 * peers scoring above zero (and above the admin-set threshold) contribute.</p>
 *
 * <p>Each qualifying peer then votes for the auctions they interacted with and the target
 * user has not, weighted by how similar that peer is. A strong endorsement from a close
 * match therefore counts for more than the same endorsement from a distant one.</p>
 */
public final class UserBasedCollaborativeFilter {

    private static final double W_BID = 3.0;
    private static final double W_WATCHLIST = 2.0;
    private static final double W_BROWSE = 1.0;

    private UserBasedCollaborativeFilter() {}

    /**
     * Ranks up to {@code limit} auctions for {@code targetUserId} from the interaction
     * vectors of everyone else, considering every peer with any overlap at all.
     *
     * @param userVectors every user's interaction vector, keyed by user id
     * @param exclude     auctions to leave out on top of the ones the target has already
     *                    interacted with, which are always excluded
     */
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
        return rankAuctionIds(targetUserId, userVectors, limit, exclude, minSimilarity, null);
    }

    /**
     * Ranks candidates for {@code targetUserId}, keeping only auctions present in
     * {@code eligible}.
     *
     * <p>The allow-set exists because truncating to {@code limit} and <em>then</em> asking
     * the database whether those auctions are still open silently loses the whole arm: a
     * neighbourhood whose highest-scoring items have all ended returns {@code limit} dead
     * ids and no recommendation at all. Interaction vectors are built from history, and
     * history is mostly closed auctions, so this is the normal case rather than an edge
     * one. Filtering here means {@code limit} live candidates come back whenever that many
     * exist anywhere in the neighbourhood, however far down the ranking they sit.</p>
     *
     * <p>{@code null} disables the filter and restores the unrestricted behaviour.</p>
     */
    public static List<Long> rankAuctionIds(
            int targetUserId,
            Map<Integer, Map<Long, Double>> userVectors,
            int limit,
            Set<Long> exclude,
            double minSimilarity,
            Set<Long> eligible) {

        Map<Long, Double> target = userVectors.get(targetUserId);
        if (target == null || target.isEmpty() || limit <= 0) {
            return List.of();
        }

        // Never recommend something the target has already bid on, watched or viewed.
        Set<Long> excludeAll = new HashSet<>(exclude == null ? Set.of() : exclude);
        excludeAll.addAll(target.keySet());

        Map<Long, Double> scores = new HashMap<>();

        for (Map.Entry<Integer, Map<Long, Double>> peer : userVectors.entrySet()) {
            if (peer.getKey() == targetUserId) continue;
            double sim = cosine(target, peer.getValue());
            // Zero similarity means no shared listings at all, so this peer's history says
            // nothing about the target's taste.
            if (sim <= 0 || sim < minSimilarity) continue;
            for (Map.Entry<Long, Double> item : peer.getValue().entrySet()) {
                if (excludeAll.contains(item.getKey())) continue;
                if (eligible != null && !eligible.contains(item.getKey())) continue;
                // Each peer's interest in an item counts in proportion to how similar that
                // peer is, and several peers backing the same item add up.
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

    /**
     * Records one interaction, keeping the strongest weight seen for that user and auction.
     *
     * <p>Callers pass weights that have already been faded by
     * {@code RecommendationDAO.recencyMultiplier}, so the maximum is taken over
     * <em>decayed</em> values: what survives is the strongest piece of evidence still
     * standing, not the strongest kind of evidence ever recorded. That is deliberate, and
     * it has a visible consequence — a page view yesterday (about 0.97) now outweighs a bid
     * from four months ago (about 0.79), inverting the nominal bid &gt; watchlist &gt;
     * browse ordering. A stale bid genuinely is weaker evidence of current taste than a
     * fresh visit, so the inversion is the intended reading rather than a defect.</p>
     *
     * <p>Summing instead of maximising was rejected: it would let one person reloading a
     * listing fifty times manufacture an arbitrarily large weight, which is the same abuse
     * the peer-CF stage already guards against by counting distinct bidders.</p>
     */
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

    /**
     * Cosine similarity between two interaction vectors, from 0 for no shared listings to
     * 1 for identical taste.
     *
     * <p>The dot product sums the weights of the auctions both users touched, so it grows
     * with genuine overlap. Dividing by both vector lengths cancels out sheer volume of
     * activity, which is what stops a very busy account from looking similar to everyone.
     * Vectors are sparse maps rather than arrays, since only a handful of the platform's
     * auctions appear in any one user's history.</p>
     */
    static double cosine(Map<Long, Double> a, Map<Long, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;

        double dot = 0, normA = 0, normB = 0;
        // Only shared keys contribute to the dot product, so walk the shorter map and look
        // each key up in the longer one rather than iterating over both in full.
        Map<Long, Double> smaller = a.size() <= b.size() ? a : b;
        Map<Long, Double> larger  = a.size() <= b.size() ? b : a;

        for (Map.Entry<Long, Double> e : smaller.entrySet()) {
            Double other = larger.get(e.getKey());
            if (other != null) dot += e.getValue() * other;
        }
        // Vector lengths, which need every entry and not just the shared ones.
        for (double v : a.values()) normA += v * v;
        for (double v : b.values()) normB += v * v;
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
