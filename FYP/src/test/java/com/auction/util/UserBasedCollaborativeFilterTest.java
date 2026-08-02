package com.auction.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the algorithmic core of the recommender (FR4.1).
 *
 * <p>Every method under test is pure and static, so the whole cosine-similarity and
 * ranking pipeline is exercised here without a database or any Mockito stubbing.</p>
 */
@DisplayName("UserBasedCollaborativeFilter")
class UserBasedCollaborativeFilterTest {

    private static final double EPSILON = 1e-9;

    /** Builds a user vector from alternating auction id / weight pairs. */
    private static Map<Long, Double> vector(double... idWeightPairs) {
        Map<Long, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < idWeightPairs.length; i += 2) {
            out.put((long) idWeightPairs[i], idWeightPairs[i + 1]);
        }
        return out;
    }

    @Nested
    @DisplayName("cosine similarity")
    class Cosine {

        @Test
        @DisplayName("vectors sharing no items are orthogonal")
        void orthogonalVectorsScoreZero() {
            // No overlapping auction ids means a zero dot product, whatever the weights.
            double sim = UserBasedCollaborativeFilter.cosine(
                    vector(1, 3.0, 2, 2.0),
                    vector(3, 3.0, 4, 2.0));
            assertEquals(0.0, sim, EPSILON);
        }

        @Test
        @DisplayName("a vector is perfectly similar to itself")
        void identicalVectorsScoreOne() {
            Map<Long, Double> v = vector(1, 3.0, 2, 2.0, 3, 1.0);
            assertEquals(1.0, UserBasedCollaborativeFilter.cosine(v, vector(1, 3.0, 2, 2.0, 3, 1.0)), EPSILON);
        }

        @Test
        @DisplayName("magnitude is normalised away, so only direction matters")
        void scaledVectorsAreStillIdentical() {
            // Cosine measures the angle: a user who did everything twice as strongly has
            // the same taste, not a different one.
            assertEquals(1.0, UserBasedCollaborativeFilter.cosine(
                    vector(1, 3.0, 2, 2.0),
                    vector(1, 6.0, 2, 4.0)), EPSILON);
        }

        @Test
        @DisplayName("partial overlap scores strictly between 0 and 1")
        void partialOverlapScoresBetween() {
            // a = (1,0) and b = (1,1) over items {1,2}: dot 1, norms 1 and sqrt(2).
            double sim = UserBasedCollaborativeFilter.cosine(vector(1, 1.0), vector(1, 1.0, 2, 1.0));
            assertEquals(1.0 / Math.sqrt(2), sim, EPSILON);
            assertTrue(sim > 0 && sim < 1);
        }

        @Test
        @DisplayName("an empty vector is similar to nothing")
        void emptyVectorScoresZero() {
            assertEquals(0.0, UserBasedCollaborativeFilter.cosine(Map.of(), vector(1, 3.0)), EPSILON);
            assertEquals(0.0, UserBasedCollaborativeFilter.cosine(vector(1, 3.0), Map.of()), EPSILON);
            assertEquals(0.0, UserBasedCollaborativeFilter.cosine(Map.of(), Map.of()), EPSILON);
        }

        @Test
        @DisplayName("similarity does not depend on argument order")
        void isSymmetric() {
            Map<Long, Double> a = vector(1, 3.0, 2, 1.0);
            Map<Long, Double> b = vector(2, 2.0, 3, 5.0, 4, 1.0);
            assertEquals(UserBasedCollaborativeFilter.cosine(a, b),
                         UserBasedCollaborativeFilter.cosine(b, a), EPSILON);
        }

        @Test
        @DisplayName("a zero-weight vector cannot divide by its own zero norm")
        void zeroWeightsScoreZeroRatherThanNaN() {
            double sim = UserBasedCollaborativeFilter.cosine(vector(1, 0.0), vector(1, 3.0));
            assertEquals(0.0, sim, EPSILON);
            assertFalse(Double.isNaN(sim));
        }
    }

    @Nested
    @DisplayName("addInteraction")
    class AddInteraction {

        @Test
        @DisplayName("keeps only the strongest signal per user and auction")
        void keepsStrongestInteraction() {
            // merge(..., Math::max): browsing a listing you already bid on must not
            // downgrade the bid, and must not stack into a fabricated weight of 4.
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            UserBasedCollaborativeFilter.addInteraction(vectors, 5, 100L, UserBasedCollaborativeFilter.weightBrowse());
            UserBasedCollaborativeFilter.addInteraction(vectors, 5, 100L, UserBasedCollaborativeFilter.weightBid());
            UserBasedCollaborativeFilter.addInteraction(vectors, 5, 100L, UserBasedCollaborativeFilter.weightWatchlist());

            assertEquals(1, vectors.get(5).size());
            assertEquals(UserBasedCollaborativeFilter.weightBid(), vectors.get(5).get(100L), EPSILON);
        }

        @Test
        @DisplayName("the strongest signal wins regardless of arrival order")
        void orderDoesNotMatter() {
            Map<Integer, Map<Long, Double>> ascending = new HashMap<>();
            UserBasedCollaborativeFilter.addInteraction(ascending, 5, 100L, 1.0);
            UserBasedCollaborativeFilter.addInteraction(ascending, 5, 100L, 3.0);

            Map<Integer, Map<Long, Double>> descending = new HashMap<>();
            UserBasedCollaborativeFilter.addInteraction(descending, 5, 100L, 3.0);
            UserBasedCollaborativeFilter.addInteraction(descending, 5, 100L, 1.0);

            assertEquals(3.0, ascending.get(5).get(100L), EPSILON);
            assertEquals(3.0, descending.get(5).get(100L), EPSILON);
        }

        @Test
        @DisplayName("separate users and auctions stay in separate buckets")
        void separatesUsersAndAuctions() {
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            UserBasedCollaborativeFilter.addInteraction(vectors, 5, 100L, 3.0);
            UserBasedCollaborativeFilter.addInteraction(vectors, 5, 101L, 2.0);
            UserBasedCollaborativeFilter.addInteraction(vectors, 6, 100L, 1.0);

            assertEquals(Set.of(5, 6), vectors.keySet());
            assertEquals(2, vectors.get(5).size());
            assertEquals(1.0, vectors.get(6).get(100L), EPSILON);
        }

        @Test
        @DisplayName("the documented weight ordering is bid > watchlist > browse")
        void weightsAreOrdered() {
            assertTrue(UserBasedCollaborativeFilter.weightBid() > UserBasedCollaborativeFilter.weightWatchlist());
            assertTrue(UserBasedCollaborativeFilter.weightWatchlist() > UserBasedCollaborativeFilter.weightBrowse());
            assertTrue(UserBasedCollaborativeFilter.weightBrowse() > 0);
        }
    }

    @Nested
    @DisplayName("rankAuctionIds")
    class Ranking {

        /**
         * Target user 1 and peer 2 both like auction 10; peer 2 also likes 11.
         * Peer 3 shares nothing with the target and only likes 12.
         */
        private Map<Integer, Map<Long, Double>> threeUsers() {
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            vectors.put(1, vector(10, 3.0));
            vectors.put(2, vector(10, 3.0, 11, 3.0));
            vectors.put(3, vector(12, 3.0));
            return vectors;
        }

        @Test
        @DisplayName("recommends what a similar peer liked and the target has not seen")
        void recommendsPeerItems() {
            List<Long> out = UserBasedCollaborativeFilter.rankAuctionIds(1, threeUsers(), 10, Set.of());
            // 11 comes from the overlapping peer; 12 belongs to an orthogonal user.
            assertEquals(List.of(11L), out);
        }

        @Test
        @DisplayName("items the target already interacted with are never recommended back")
        void excludesTheTargetsOwnItems() {
            List<Long> out = UserBasedCollaborativeFilter.rankAuctionIds(1, threeUsers(), 10, Set.of());
            assertFalse(out.contains(10L), "auction 10 is already in the target's own vector");
        }

        @Test
        @DisplayName("an explicit exclude set is respected")
        void respectsExcludeAll() {
            List<Long> out = UserBasedCollaborativeFilter.rankAuctionIds(1, threeUsers(), 10, Set.of(11L));
            assertTrue(out.isEmpty());
        }

        @Test
        @DisplayName("a null exclude set is treated as excluding nothing")
        void tolerantOfNullExclude() {
            assertEquals(List.of(11L),
                    UserBasedCollaborativeFilter.rankAuctionIds(1, threeUsers(), 10, null));
        }

        @Test
        @DisplayName("peers below the similarity threshold are ignored")
        void excludesPeersBelowMinSimilarity() {
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            vectors.put(1, vector(10, 1.0));
            // Peer 2 overlaps on 10 but is diluted across nine other items, so its cosine
            // with the target is low; peer 3 overlaps on 10 alone and scores 1.0.
            Map<Long, Double> weak = vector(10, 1.0);
            for (long id = 20; id < 29; id++) weak.put(id, 1.0);
            vectors.put(2, weak);
            vectors.put(3, vector(10, 1.0, 11, 1.0));

            double weakSim = UserBasedCollaborativeFilter.cosine(vectors.get(1), vectors.get(2));
            double strongSim = UserBasedCollaborativeFilter.cosine(vectors.get(1), vectors.get(3));
            assertTrue(weakSim < strongSim, "test fixture must have a weak and a strong peer");

            // A threshold between the two similarities keeps only the strong peer's item.
            double threshold = (weakSim + strongSim) / 2;
            List<Long> filtered = UserBasedCollaborativeFilter.rankAuctionIds(
                    1, vectors, 10, Set.of(), threshold);
            assertEquals(List.of(11L), filtered);

            // With the threshold off, the weak peer's items reappear.
            List<Long> unfiltered = UserBasedCollaborativeFilter.rankAuctionIds(
                    1, vectors, 10, Set.of(), 0.0);
            assertTrue(unfiltered.contains(11L));
            assertTrue(unfiltered.contains(20L));
        }

        @Test
        @DisplayName("a threshold of 1 keeps only peers with identical taste")
        void thresholdOfOneKeepsOnlyExactMatches() {
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            vectors.put(1, vector(10, 1.0));
            vectors.put(2, vector(10, 1.0, 11, 1.0));   // similar, but not identical
            vectors.put(3, vector(10, 5.0, 12, 0.0));   // same direction — cosine 1.0

            List<Long> out = UserBasedCollaborativeFilter.rankAuctionIds(1, vectors, 10, Set.of(), 1.0);
            assertFalse(out.contains(11L), "a peer below cosine 1.0 must be dropped");
        }

        @Test
        @DisplayName("results are ordered by accumulated score, strongest first")
        void ordersByScore() {
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            vectors.put(1, vector(10, 1.0));
            // Both peers are identical to the target, so each contributes its own weight.
            // Auction 11 is endorsed by two peers, 12 by one.
            vectors.put(2, vector(10, 1.0, 11, 1.0, 12, 1.0));
            vectors.put(3, vector(10, 1.0, 11, 1.0));

            assertEquals(List.of(11L, 12L),
                    UserBasedCollaborativeFilter.rankAuctionIds(1, vectors, 10, Set.of()));
        }

        @Test
        @DisplayName("the limit caps how many ids come back")
        void respectsLimit() {
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            vectors.put(1, vector(10, 1.0));
            vectors.put(2, vector(10, 1.0, 11, 3.0, 12, 2.0, 13, 1.0));

            assertEquals(List.of(11L, 12L),
                    UserBasedCollaborativeFilter.rankAuctionIds(1, vectors, 2, Set.of()));
        }

        @Test
        @DisplayName("a non-positive limit returns nothing")
        void nonPositiveLimitReturnsEmpty() {
            assertTrue(UserBasedCollaborativeFilter.rankAuctionIds(1, threeUsers(), 0, Set.of()).isEmpty());
            assertTrue(UserBasedCollaborativeFilter.rankAuctionIds(1, threeUsers(), -3, Set.of()).isEmpty());
        }

        @Test
        @DisplayName("a target with no history gets nothing rather than random items")
        void emptyTargetVectorReturnsEmpty() {
            // Cold start is the trending filler's job, not this stage's — returning peer
            // items to a user with no signal would be unexplainable on the card.
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            vectors.put(1, new HashMap<>());
            vectors.put(2, vector(10, 3.0, 11, 3.0));

            assertTrue(UserBasedCollaborativeFilter.rankAuctionIds(1, vectors, 10, Set.of()).isEmpty());
        }

        @Test
        @DisplayName("an unknown target user gets nothing")
        void unknownTargetReturnsEmpty() {
            assertTrue(UserBasedCollaborativeFilter.rankAuctionIds(99, threeUsers(), 10, Set.of()).isEmpty());
        }

        @Test
        @DisplayName("the only user in the system gets no recommendations")
        void loneUserReturnsEmpty() {
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            vectors.put(1, vector(10, 3.0, 11, 2.0));
            assertTrue(UserBasedCollaborativeFilter.rankAuctionIds(1, vectors, 10, Set.of()).isEmpty());
        }

        @Test
        @DisplayName("no id is ever returned twice, even when several peers endorse it")
        void neverReturnsDuplicates() {
            Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
            vectors.put(1, vector(10, 1.0));
            vectors.put(2, vector(10, 1.0, 11, 1.0));
            vectors.put(3, vector(10, 1.0, 11, 1.0));
            vectors.put(4, vector(10, 1.0, 11, 1.0));

            List<Long> out = UserBasedCollaborativeFilter.rankAuctionIds(1, vectors, 10, Set.of());
            assertEquals(List.of(11L), out);
            assertEquals(out.size(), Set.copyOf(out).size());
        }

        @Test
        @DisplayName("the four-argument overload behaves as an unthresholded call")
        void fourArgOverloadMatchesZeroThreshold() {
            // Retained as public API but currently called only by these tests —
            // RecommendationDAO always supplies the admin-tunable threshold.
            assertEquals(
                    UserBasedCollaborativeFilter.rankAuctionIds(1, threeUsers(), 10, Set.of(), 0.0),
                    UserBasedCollaborativeFilter.rankAuctionIds(1, threeUsers(), 10, Set.of()));
        }
    }
}
