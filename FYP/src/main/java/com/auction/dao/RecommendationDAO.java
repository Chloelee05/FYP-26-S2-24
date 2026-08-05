package com.auction.dao;

import com.auction.model.RecommendationProvenance;
import com.auction.model.RecommendationProvenance.Component;
import com.auction.model.RecommendationProvenance.Reason;
import com.auction.model.SearchResultItem;
import com.auction.util.DBUtil;
import com.auction.util.DutchClock;
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
 * <p><b>Candidate generators (SCRUM-400):</b></p>
 * <ol>
 *   <li>Item-based collaborative filtering — peer co-occurrence on bids/watchlist</li>
 *   <li>User-based CF with cosine similarity — bids, watchlist, and browse history</li>
 *   <li>Content-based — active auctions sharing category or tags with the user's
 *       bids / watchlist / browse history inside the configured window</li>
 *   <li>Trending — recent bid count, which also covers cold start</li>
 * </ol>
 *
 * <p>The four used to run in sequence, each filling whatever slots the previous one left,
 * so the final order was decided by stage boundaries rather than by any score: an auction
 * three signals agreed on weakly could sit below one a single signal happened to like. They
 * are now candidate generators feeding a single re-ranking pass — see {@code rerank} — that
 * blends five min-max normalised components under admin-tunable weights. The stage that
 * first produced a card still supplies its {@link Reason}, because that is the label the
 * per-arm CTR breakdown groups by.</p>
 *
 * <p>Pure SQL over existing tables — no external ML dependency — which keeps the
 * approach explainable and defensible for the project's scope.</p>
 */
public class RecommendationDAO {

    // -------------------------------------------------------------------------
    // Admin-tunable parameter defaults (SCRUM-76)
    //
    // These are only the fallbacks used when recommendation_settings has no row for a
    // key, or has not been migrated yet. The live values are read from the database by
    // getSettings(), so nothing here is a behavioural constant an admin cannot override.
    // -------------------------------------------------------------------------

    public static final int DEFAULT_ITEMS_SHOWN = 8;
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.1;
    /** How far back "trending" looks when counting bids. */
    public static final int DEFAULT_TRENDING_WINDOW_DAYS = 7;
    /**
     * Decay constant τ, in days, applied to interaction weights as {@code exp(-Δdays / τ)}.
     * At 30 days a month-old signal is worth about 37% of a fresh one and a fortnight-old
     * one about 63%, which fades stale taste without erasing the history a sparse
     * marketplace depends on. Zero switches decay off entirely.
     */
    public static final double DEFAULT_RECENCY_TAU_DAYS = 30.0;
    /**
     * How far back the content-based stage looks for the viewer's own signals.
     *
     * <p>Deliberately generous. A window shorter than the age of the available history
     * empties the personalised stages outright, which is the failure this whole exercise
     * exists to prevent, and a listing browsed six months ago is still weak evidence of
     * taste. Freshness is expressed by {@link #DEFAULT_RECENCY_TAU_DAYS} weighting signals
     * down, not by this window cutting them off.</p>
     */
    public static final int DEFAULT_CONTENT_WINDOW_DAYS = 180;

    // Hybrid re-ranking weights. Ordered so that, on a candidate carrying one signal only,
    // the arms rank in the same sequence the stages used to run in — collaborative first,
    // popularity last — while still letting a candidate that several signals agree on
    // overtake one that a single signal likes a lot. They are starting points chosen for
    // being explainable rather than tuned figures; there is no offline evaluation set here
    // to fit them against, and pretending otherwise would be dishonest.
    public static final double DEFAULT_W_CF = 1.0;
    public static final double DEFAULT_W_UBCF = 0.9;
    public static final double DEFAULT_W_CONTENT = 0.7;
    public static final double DEFAULT_W_POPULARITY = 0.4;
    public static final double DEFAULT_W_RECENCY = 0.2;

    /**
     * How many candidates each generator is asked for, as a multiple of the page size.
     *
     * <p>The stages no longer top each other up, so each needs enough depth for the
     * re-ranker to have something to choose between; two pages' worth keeps the union
     * broad without turning four bounded queries into four unbounded ones.</p>
     */
    private static final int CANDIDATE_MULTIPLIER = 2;

    /**
     * The extra columns {@link DutchClock#listedPrice} needs to re-price a descending listing
     * when a row is mapped. Every query below computes {@code current_price} from the bids
     * table, which for a Dutch auction is the price its clock started at rather than the one
     * on offer now — see {@link SearchDAO} for the same pair of column lists.
     *
     * <p>Selected, never filtered or ordered on: candidate selection and ordering are
     * unchanged by their presence.</p>
     */
    private static final String DUTCH_CLOCK_COLUMNS =
            "  d.starting_price, d.dutch_floor_price, a.date_created, ";

    /**
     * Divisor behind the per-category cap, {@code ceil(limit / divisor)}.
     *
     * <p>Three lets a single category take about a third of the page. The content-based
     * stage orders only by {@code date_end}, so a viewer who opened one Electronics listing
     * could watch soon-ending Electronics take every remaining slot; the cap keeps the page
     * from collapsing onto one interest without pretending to be a real diversification
     * objective. A divisor of 1 raises the cap to the whole page and switches it off.</p>
     */
    public static final int DEFAULT_DIVERSITY_DIVISOR = 3;

    /**
     * Returns up to {@code limit} active, open auctions recommended for {@code userId},
     * ranked by CF score, then content similarity, topped up with trending auctions.
     * Auctions the user dismissed ("not interested") are always excluded (SCRUM-74).
     */
    public List<SearchResultItem> recommendForUser(int userId, int limit) {
        Set<Long> dismissed = listDismissedIds(userId);
        Settings settings = getSettings();
        int pool = limit * CANDIDATE_MULTIPLIER + dismissed.size();

        // The four stages are candidate generators now, not a chain that tops itself up.
        // Crucially they are no longer told what earlier stages already took: an auction
        // several signals agree on has to reach the re-ranker from all of them, and
        // excluding it after the first sighting is precisely what made the old ordering a
        // function of stage boundaries instead of evidence.
        Map<Long, Candidate> byId = new LinkedHashMap<>();
        collect(byId, tag(collaborativeFiltering(userId, pool), Reason.PEER_BIDS, REASON_PEER_BIDS),
                Component.CF, 0);
        collect(byId, tag(userBasedCosineRecommendations(userId, pool, dismissed),
                Reason.SIMILAR_TASTE, REASON_SIMILAR_TASTE), Component.UBCF, 1);
        collect(byId, contentBased(userId, pool, dismissed), Component.CONTENT, 2);
        collect(byId, trending(pool, dismissed, userId), Component.POPULARITY, 3);

        // The peer-CF query has no exclusion parameter of its own, so its dismissals are
        // still dropped here.
        byId.keySet().removeAll(dismissed);
        if (byId.isEmpty()) return new ArrayList<>();

        return rerank(new ArrayList<>(byId.values()), limit, settings);
    }

    // -------------------------------------------------------------------------
    // Hybrid re-ranking
    //
    // score(i) = ( w_cf·ĉf(i) + w_ubcf·ûb(i) + w_content·ĉt(i)
    //              + w_pop·p̂op(i) + w_rec·r̂ec(i) ) / Σ w over components present
    //
    // Each component is min-max normalised across the candidate set, so the five signals
    // are compared on one scale rather than on whatever units their stage happened to
    // produce. The stages order their own output but do not expose the numbers behind it,
    // so a candidate's raw component score is its rank within the stage that produced it:
    // top of a stage scores 1, bottom scores 1/n. That is coarser than the underlying
    // co-bidder counts and cosine sums, but it needs no change to four tested queries and
    // it is monotone in each stage's own ordering, which is the property the blend relies
    // on.
    // -------------------------------------------------------------------------

    /** A candidate auction and the per-component evidence supporting it. */
    private static final class Candidate {
        final SearchResultItem item;
        /** The stage that first produced it, kept for a stable tiebreak and fallback. */
        final Component source;
        final int stageOrder;
        final int stageIndex;
        final double[] raw = new double[Component.values().length];
        /**
         * Each generating stage's own explanation, kept so the card can be labelled with
         * the signal that actually ranked it. Null for RECENCY, which no stage generates.
         */
        final RecommendationProvenance[] whyByComponent =
                new RecommendationProvenance[Component.values().length];

        Candidate(SearchResultItem item, Component source, int stageOrder, int stageIndex) {
            this.item = item;
            this.source = source;
            this.stageOrder = stageOrder;
            this.stageIndex = stageIndex;
        }
    }

    /**
     * Folds one stage's output into the candidate set, recording its within-stage rank as
     * that component's raw score and keeping that stage's own explanation.
     *
     * <p>An auction several stages agree on arrives here several times. The first sighting
     * owns the {@link SearchResultItem}, but every sighting's {@link RecommendationProvenance}
     * is retained so {@link #labelWithStrongestArm} can pick the one matching the signal
     * that actually ranked the card. Keeping only the first would make the arm a function
     * of the order the generators happen to run in, which is exactly the property the
     * re-ranking pass exists to remove.</p>
     */
    private static void collect(Map<Long, Candidate> byId, List<SearchResultItem> items,
                                Component component, int stageOrder) {
        int n = items.size();
        for (int i = 0; i < n; i++) {
            SearchResultItem item = items.get(i);
            int stageIndex = i;
            Candidate candidate = byId.computeIfAbsent(item.getAuctionId(),
                    id -> new Candidate(item, component, stageOrder, stageIndex));
            candidate.raw[component.ordinal()] = (double) (n - i) / n;
            candidate.whyByComponent[component.ordinal()] = item.getWhy();
        }
    }

    /**
     * Labels a card with the arm whose weighted contribution was largest, rather than with
     * whichever generator produced it first.
     *
     * <p>Under the old sequential pipeline the two were the same thing: a card belonged to
     * exactly one stage. Once stages became generators over a shared candidate space, the
     * first-sighting label started describing generator order instead of evidence — a
     * listing the content stage ranked top could be labelled SIMILAR_TASTE purely because
     * user-based CF ran earlier, and the per-arm CTR breakdown would then be measuring
     * stage order too.</p>
     *
     * <p>{@code RECENCY} is skipped: it is a component every candidate carries, not a
     * stage, so it has no explanation of its own and no arm to attribute a click to. A
     * card dominated by it keeps the label of whichever stage did produce it.</p>
     */
    private static void labelWithStrongestArm(Candidate candidate, double[] weights,
                                              double[][] normalised, int index) {
        RecommendationProvenance best = null;
        double bestContribution = 0;
        for (int k = 0; k < weights.length; k++) {
            RecommendationProvenance why = candidate.whyByComponent[k];
            if (why == null) continue;
            double contribution = weights[k] * normalised[k][index];
            if (contribution > bestContribution) {
                bestContribution = contribution;
                best = why;
            }
        }
        if (best != null) candidate.item.setWhy(best);
    }

    private List<SearchResultItem> rerank(List<Candidate> candidates, int limit, Settings settings) {
        fillRecencyComponent(candidates);

        double[] weights = {
                settings.weightCf, settings.weightUbcf, settings.weightContent,
                settings.weightPopularity, settings.weightRecency,
        };

        Component[] components = Component.values();
        double[][] normalised = new double[components.length][];
        double weightPresent = 0;
        for (int k = 0; k < components.length; k++) {
            normalised[k] = normaliseComponent(candidates, k);
            // A component nothing in the candidate set carries is left out of the divisor
            // rather than dragging every score down by a constant. It contributes zero to
            // every candidate either way, so the ordering is untouched and only the printed
            // number stays comparable.
            if (componentIsPresent(candidates, k)) weightPresent += weights[k];
        }

        // Every weight zeroed leaves nothing to sort by, and returning hash order would look
        // like a bug rather than a setting. Falling back to the stage sequence gives the
        // pre-re-ranking behaviour, which is a defensible answer to "rank by nothing".
        boolean scored = weightPresent > 0;

        double[] scores = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            double total = 0;
            double best = 0;
            Component dominant = null;
            for (int k = 0; k < components.length; k++) {
                double contribution = weights[k] * normalised[k][i];
                total += contribution;
                if (contribution > best) {
                    best = contribution;
                    dominant = components[k];
                }
            }
            scores[i] = scored ? total / weightPresent : 0.0;
            // Swap the arm before the score is written, so the figure lands on the
            // provenance the card is actually served with. With nothing scoring above zero
            // no swap happens and the first generator's label stands.
            if (scored) labelWithStrongestArm(candidate, weights, normalised, i);
            candidate.item.getWhy().setScore(round4(scores[i]));
            // With nothing scoring above zero, naming the stage that produced the card is
            // more honest than naming whichever component sorts first.
            candidate.item.getWhy().setDominantComponent(dominant != null ? dominant : candidate.source);
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) order.add(i);
        order.sort((a, b) -> {
            if (scored) {
                int byScore = Double.compare(scores[b], scores[a]);
                if (byScore != 0) return byScore;
            }
            // Ties resolve to the old stage sequence, then to the id, so the same inputs
            // always produce the same page.
            Candidate ca = candidates.get(a);
            Candidate cb = candidates.get(b);
            if (ca.stageOrder != cb.stageOrder) return Integer.compare(ca.stageOrder, cb.stageOrder);
            if (ca.stageIndex != cb.stageIndex) return Integer.compare(ca.stageIndex, cb.stageIndex);
            return Long.compare(ca.item.getAuctionId(), cb.item.getAuctionId());
        });

        List<SearchResultItem> ranked = new ArrayList<>();
        for (int i : order) ranked.add(candidates.get(i).item);
        return capPerCategory(ranked, limit, settings.diversityDivisor);
    }

    /**
     * Takes the top {@code limit} of an already-ranked list, allowing at most
     * {@code ceil(limit / divisor)} auctions from any one category.
     *
     * <p>Items held back by the cap are not discarded: once the capped pass runs out of
     * candidates they are appended in score order, so the page never comes back shorter
     * than it would have without the cap. That matters when a marketplace genuinely only
     * has one category of live listing, which is the normal state of a small demo
     * database.</p>
     *
     * <p>Maximal Marginal Relevance would diversify on actual item-item similarity rather
     * than on the category label, and would express the trade-off as a tunable λ instead of
     * a hard quota. It also needs an item-item similarity function this codebase does not
     * have, over listings whose only shared structure is a free-text category and a sparse
     * tag table. Noted as future work; the quota buys most of the visible benefit for none
     * of that machinery.</p>
     */
    private static List<SearchResultItem> capPerCategory(
            List<SearchResultItem> ranked, int limit, int divisor) {
        if (divisor <= 1) {
            return ranked.size() > limit ? new ArrayList<>(ranked.subList(0, limit)) : ranked;
        }
        // Applied even when every candidate fits on the page. Nothing is dropped in that
        // case, but the top of the strip is what a visitor actually reads, so a diverse
        // opening is worth having whether or not the tail gets trimmed.

        int cap = (int) Math.ceil((double) limit / divisor);
        Map<String, Integer> taken = new HashMap<>();
        List<SearchResultItem> kept = new ArrayList<>();
        List<SearchResultItem> overflow = new ArrayList<>();

        for (SearchResultItem item : ranked) {
            if (kept.size() >= limit) break;
            String category = categoryKey(item.getCategory());
            int used = taken.getOrDefault(category, 0);
            if (used < cap) {
                taken.put(category, used + 1);
                kept.add(item);
            } else {
                overflow.add(item);
            }
        }

        for (SearchResultItem item : overflow) {
            if (kept.size() >= limit) break;
            kept.add(item);
        }
        return kept;
    }

    /** Blank categories share one bucket, matching the column's own 'Other' default. */
    private static String categoryKey(String category) {
        if (category == null || category.isBlank()) return "other";
        return category.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Min-max normalises one component across the candidate set.
     *
     * <p>Two degenerate cases have to be answered rather than divided through. A component
     * no candidate carries normalises to zero everywhere, so it neither promotes nor
     * demotes anything. A component every candidate carries <em>identically</em> — the
     * single-candidate page, most obviously — has a zero range, and normalising to one
     * rather than to zero keeps "everybody has this signal" distinct from "nobody does".
     * Neither case divides by zero.</p>
     */
    private static double[] normaliseComponent(List<Candidate> candidates, int component) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Candidate candidate : candidates) {
            double value = candidate.raw[component];
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        double[] out = new double[candidates.size()];
        if (max <= 0) return out;
        double range = max - min;
        for (int i = 0; i < candidates.size(); i++) {
            out[i] = range == 0 ? 1.0 : (candidates.get(i).raw[component] - min) / range;
        }
        return out;
    }

    private static boolean componentIsPresent(List<Candidate> candidates, int component) {
        for (Candidate candidate : candidates) {
            if (candidate.raw[component] > 0) return true;
        }
        return false;
    }

    /**
     * Fills the recency component from how soon each candidate closes, measured against the
     * latest-ending candidate so the raw value is non-negative like every other component's
     * and the soonest-ending auction scores highest.
     *
     * <p>This is auction urgency, not the freshness of the viewer's own history — that is
     * handled separately by {@link #recencyMultiplier(double, double)} inside the
     * interaction vectors. A candidate with no end date is treated as the least urgent
     * rather than skipped.</p>
     */
    private static void fillRecencyComponent(List<Candidate> candidates) {
        int component = Component.RECENCY.ordinal();
        long latest = Long.MIN_VALUE;
        for (Candidate candidate : candidates) {
            Instant end = candidate.item.getEndDate();
            if (end != null) latest = Math.max(latest, end.getEpochSecond());
        }
        if (latest == Long.MIN_VALUE) return;

        for (Candidate candidate : candidates) {
            Instant end = candidate.item.getEndDate();
            candidate.raw[component] = end == null ? 0.0 : (double) (latest - end.getEpochSecond());
        }
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
          + DUTCH_CLOCK_COLUMNS
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

    /**
     * The landing page's lower "Trending Auctions" strip. It is not produced by the
     * recommendation pipeline at all, so it has no {@link Reason} of its own, but labelling
     * it gives the personalised arms a non-personalised popularity baseline to be read
     * against.
     *
     * <p>This is <b>not</b> a randomised experiment. Nobody is assigned to an arm: the two
     * strips sit at different heights on the same page, so the higher one enjoys a position
     * advantage that no amount of data will cancel out. Read it as a same-page comparison
     * against a popularity baseline, and nothing stronger.</p>
     */
    public static final String REASON_TRENDING_CONTROL = "TRENDING_CONTROL";

    /** Records an IMPRESSION or CLICK for recommendation analytics. Best-effort. */
    public void recordEvent(Integer userId, long auctionId, String eventType) {
        recordEvent(userId, auctionId, eventType, null, null);
    }

    /** Records an IMPRESSION or CLICK attributed to a search keyword but to no arm. */
    public void recordEvent(Integer userId, long auctionId, String eventType, String sourceKeyword) {
        recordEvent(userId, auctionId, eventType, sourceKeyword, null);
    }

    /**
     * Records an IMPRESSION or CLICK, optionally attributed to the search keyword that
     * surfaced the card and to the pipeline arm that produced it.
     *
     * <p>Both optional columns arrived in later migrations, and naming a column the
     * database does not have yet fails the whole insert. Rather than lose the event, the
     * write is retried with progressively fewer columns until one succeeds.</p>
     */
    public void recordEvent(Integer userId, long auctionId, String eventType,
                            String sourceKeyword, String reasonCode) {
        String keyword = normaliseKeyword(sourceKeyword);
        String reason = normaliseReasonCode(reasonCode);

        if (keyword != null && reason != null) {
            if (insertEvent(userId, auctionId, eventType, keyword, reason)) return;
            if (insertEvent(userId, auctionId, eventType, keyword, null)) return;
            if (insertEvent(userId, auctionId, eventType, null, reason)) return;
        } else if (keyword != null) {
            if (insertEvent(userId, auctionId, eventType, keyword, null)) return;
        } else if (reason != null) {
            if (insertEvent(userId, auctionId, eventType, null, reason)) return;
        }
        insertEvent(userId, auctionId, eventType, null, null);
    }

    /**
     * Arm labels arrive from the browser, so only names the pipeline can actually produce
     * are stored. An unrecognised label is dropped rather than written, which keeps the
     * per-arm CTR table free of rows a client invented.
     */
    private static String normaliseReasonCode(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) return null;
        if (REASON_TRENDING_CONTROL.equals(cleaned)) return cleaned;
        for (Reason known : Reason.values()) {
            if (known.name().equals(cleaned)) return cleaned;
        }
        return null;
    }

    private boolean insertEvent(Integer userId, long auctionId, String eventType,
                                String keyword, String reasonCode) {
        StringBuilder cols = new StringBuilder("user_id, auction_id, event_type");
        StringBuilder vals = new StringBuilder("?, ?, ?");
        if (keyword != null)    { cols.append(", source_keyword"); vals.append(", ?"); }
        if (reasonCode != null) { cols.append(", reason_code");    vals.append(", ?"); }

        String sql = "INSERT INTO recommendation_events (" + cols + ") VALUES (" + vals + ")";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (userId != null) ps.setInt(1, userId); else ps.setNull(1, java.sql.Types.BIGINT);
            ps.setLong(2, auctionId);
            ps.setString(3, eventType);
            int idx = 4;
            if (keyword != null)    ps.setString(idx++, keyword);
            if (reasonCode != null) ps.setString(idx, reasonCode);
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
     * A bid only converts a click when it was placed <em>after</em> that click. Without the
     * ordering the metric also counted people who had already bid on the listing before the
     * recommendation ever appeared, which credits the recommender for bids it did not cause.
     *
     * <p>{@code bids.bid_time} is a naive timestamp written from {@code CURRENT_TIMESTAMP},
     * so it is pinned to UTC here rather than left to the session's TimeZone.</p>
     */
    private static final String CLICK_PRECEDES_BID =
            "AND b.bid_time AT TIME ZONE 'UTC' > e.created_at ";

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
          + "AND EXISTS (SELECT 1 FROM bids b WHERE b.user_id = e.user_id AND b.auction_id = e.auction_id "
          + CLICK_PRECEDES_BID + ")";
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

    /**
     * The same impression / click / conversion figures as {@link #metrics()}, broken down by
     * the arm that produced the card, so collaborative filtering can be read against the
     * content-based stage and against the {@link #REASON_TRENDING_CONTROL} popularity strip.
     *
     * <p>Rows recorded before arm labelling existed carry no {@code reason_code} and are
     * left out rather than lumped into whichever arm happens to be first — an unlabelled
     * backlog would otherwise silently dominate every comparison.</p>
     *
     * <p>Returns an empty list on an unmigrated database, like every other analytics read
     * here, so the admin page still renders.</p>
     */
    public List<Map<String, Object>> metricsByReason() {
        String sql =
            "SELECT e.reason_code, "
          + "  COUNT(*) FILTER (WHERE e.event_type = 'IMPRESSION') AS impressions, "
          + "  COUNT(*) FILTER (WHERE e.event_type = 'CLICK') AS clicks, "
          + "  COUNT(DISTINCT (e.user_id, e.auction_id)) FILTER ( "
          + "    WHERE e.event_type = 'CLICK' AND e.user_id IS NOT NULL "
          + "      AND EXISTS (SELECT 1 FROM bids b "
          + "                   WHERE b.user_id = e.user_id AND b.auction_id = e.auction_id "
          + "                     " + CLICK_PRECEDES_BID + ")) AS conversions "
          + "FROM recommendation_events e "
          + "WHERE e.reason_code IS NOT NULL "
          + "GROUP BY e.reason_code "
          + "ORDER BY impressions DESC, e.reason_code";

        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long impressions = rs.getLong("impressions");
                long clicks = rs.getLong("clicks");
                long conversions = rs.getLong("conversions");

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("reasonCode", rs.getString("reason_code"));
                row.put("impressions", impressions);
                row.put("clicks", clicks);
                row.put("conversions", conversions);
                row.put("clickThroughRate", impressions > 0 ? round4((double) clicks / impressions) : 0.0);
                row.put("conversionRate", clicks > 0 ? round4((double) conversions / clicks) : 0.0);
                out.add(row);
            }
        } catch (Exception ignored) {
            // analytics only — an un-migrated database reports nothing rather than failing
        }
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

    /**
     * Trending wording that states the window actually used by the query, so the card can
     * never promise "today" while the SQL counts a week. Callers that have no window to
     * hand fall back to {@link #REASON_TRENDING}, worded for the default.
     */
    static String trendingReason(int windowDays) {
        if (windowDays <= 1) return "Trending — collecting the most bids today";
        if (windowDays == 7) return "Trending — collecting the most bids this week";
        return "Trending — collecting the most bids in the last " + windowDays + " days";
    }

    static final String REASON_TRENDING = trendingReason(DEFAULT_TRENDING_WINDOW_DAYS);

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
                RecommendationProvenance upgraded = new RecommendationProvenance(
                        Reason.SEARCH_KEYWORD, "Matches your search for “" + myMatch + "”");
                // The reason is being restated, not recomputed: the card still earned its
                // place with the score the re-ranker gave it, so carry that across rather
                // than resetting the "why this?" panel to zero.
                upgraded.setScore(item.getWhy().getScore());
                upgraded.setDominantComponent(item.getWhy().dominantComponentCode());
                item.setWhy(upgraded);
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
    // Tunable parameters (SCRUM-76) — defaults live at the top of the class
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of the admin-tunable recommendation parameters.
     *
     * <p>The knobs are numerous enough that a positional constructor stopped being
     * readable, so anything beyond the original six is set through {@link Builder}. The
     * short constructors are kept because call sites and tests that only care about the
     * page size and the threshold should not have to name every other field.</p>
     */
    public static final class Settings {
        public final int itemsShown;
        public final double similarityThreshold;
        public final int trendingWindowDays;
        /** Interaction weights fed into the user-based CF vectors. */
        public final double weightBid;
        public final double weightWatchlist;
        public final double weightBrowse;
        /**
         * Decay constant τ, in days, for {@code exp(-Δdays / τ)} on interaction weights.
         * Zero disables decay and restores the flat weighting.
         */
        public final double recencyTauDays;
        /** How far back the content-based stage looks for the viewer's own signals. */
        public final int contentWindowDays;
        /** Hybrid re-ranking weights, one per scored component. */
        public final double weightCf;
        public final double weightUbcf;
        public final double weightContent;
        public final double weightPopularity;
        public final double weightRecency;
        /** Divisor behind the per-category cap during final assembly. */
        public final int diversityDivisor;

        /** Keeps the pre-window call sites and tests working on the defaults. */
        public Settings(int itemsShown, double similarityThreshold) {
            this(itemsShown, similarityThreshold, DEFAULT_TRENDING_WINDOW_DAYS);
        }

        public Settings(int itemsShown, double similarityThreshold, int trendingWindowDays) {
            this(itemsShown, similarityThreshold, trendingWindowDays,
                    UserBasedCollaborativeFilter.weightBid(),
                    UserBasedCollaborativeFilter.weightWatchlist(),
                    UserBasedCollaborativeFilter.weightBrowse());
        }

        public Settings(int itemsShown, double similarityThreshold, int trendingWindowDays,
                        double weightBid, double weightWatchlist, double weightBrowse) {
            this(builder()
                    .itemsShown(itemsShown)
                    .similarityThreshold(similarityThreshold)
                    .trendingWindowDays(trendingWindowDays)
                    .weightBid(weightBid)
                    .weightWatchlist(weightWatchlist)
                    .weightBrowse(weightBrowse));
        }

        private Settings(Builder b) {
            this.itemsShown = b.itemsShown;
            this.similarityThreshold = b.similarityThreshold;
            this.trendingWindowDays = b.trendingWindowDays;
            this.weightBid = b.weightBid;
            this.weightWatchlist = b.weightWatchlist;
            this.weightBrowse = b.weightBrowse;
            this.recencyTauDays = b.recencyTauDays;
            this.contentWindowDays = b.contentWindowDays;
            this.weightCf = b.weightCf;
            this.weightUbcf = b.weightUbcf;
            this.weightContent = b.weightContent;
            this.weightPopularity = b.weightPopularity;
            this.weightRecency = b.weightRecency;
            this.diversityDivisor = b.diversityDivisor;
        }

        public static Builder builder() { return new Builder(); }

        /** A builder seeded from this snapshot, for changing only some of the values. */
        public Builder toBuilder() {
            return builder()
                    .itemsShown(itemsShown)
                    .similarityThreshold(similarityThreshold)
                    .trendingWindowDays(trendingWindowDays)
                    .weightBid(weightBid)
                    .weightWatchlist(weightWatchlist)
                    .weightBrowse(weightBrowse)
                    .recencyTauDays(recencyTauDays)
                    .contentWindowDays(contentWindowDays)
                    .weightCf(weightCf)
                    .weightUbcf(weightUbcf)
                    .weightContent(weightContent)
                    .weightPopularity(weightPopularity)
                    .weightRecency(weightRecency)
                    .diversityDivisor(diversityDivisor);
        }

        /** Every unset field falls back to the seeded default, never to zero. */
        public static final class Builder {
            private int itemsShown = DEFAULT_ITEMS_SHOWN;
            private double similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;
            private int trendingWindowDays = DEFAULT_TRENDING_WINDOW_DAYS;
            private double weightBid = UserBasedCollaborativeFilter.weightBid();
            private double weightWatchlist = UserBasedCollaborativeFilter.weightWatchlist();
            private double weightBrowse = UserBasedCollaborativeFilter.weightBrowse();
            private double recencyTauDays = DEFAULT_RECENCY_TAU_DAYS;
            private int contentWindowDays = DEFAULT_CONTENT_WINDOW_DAYS;
            private double weightCf = DEFAULT_W_CF;
            private double weightUbcf = DEFAULT_W_UBCF;
            private double weightContent = DEFAULT_W_CONTENT;
            private double weightPopularity = DEFAULT_W_POPULARITY;
            private double weightRecency = DEFAULT_W_RECENCY;
            private int diversityDivisor = DEFAULT_DIVERSITY_DIVISOR;

            public Builder itemsShown(int v)          { this.itemsShown = v; return this; }
            public Builder similarityThreshold(double v) { this.similarityThreshold = v; return this; }
            public Builder trendingWindowDays(int v)  { this.trendingWindowDays = v; return this; }
            public Builder weightBid(double v)        { this.weightBid = v; return this; }
            public Builder weightWatchlist(double v)  { this.weightWatchlist = v; return this; }
            public Builder weightBrowse(double v)     { this.weightBrowse = v; return this; }
            public Builder recencyTauDays(double v)   { this.recencyTauDays = v; return this; }
            public Builder contentWindowDays(int v)   { this.contentWindowDays = v; return this; }
            public Builder weightCf(double v)         { this.weightCf = v; return this; }
            public Builder weightUbcf(double v)       { this.weightUbcf = v; return this; }
            public Builder weightContent(double v)    { this.weightContent = v; return this; }
            public Builder weightPopularity(double v) { this.weightPopularity = v; return this; }
            public Builder weightRecency(double v)    { this.weightRecency = v; return this; }
            public Builder diversityDivisor(int v)    { this.diversityDivisor = v; return this; }

            public Settings build() { return new Settings(this); }
        }
    }

    static int clampItemsShown(int v)       { return Math.max(1, Math.min(24, v)); }
    static double clampThreshold(double v)  { return Math.max(0.0, Math.min(1.0, v)); }
    static int clampWindowDays(int v)       { return Math.max(1, Math.min(365, v)); }
    /** A negative weight would turn an interaction into evidence of dislike. */
    static double clampWeight(double v)     { return Math.max(0.0, Math.min(100.0, v)); }
    /** Zero is meaningful — it switches decay off — so the floor is zero, not one. */
    static double clampTauDays(double v)    { return Math.max(0.0, Math.min(3650.0, v)); }
    static int clampContentWindowDays(int v){ return Math.max(1, Math.min(3650, v)); }
    /** One means "a whole page of one category is fine", which switches the cap off. */
    static int clampDiversityDivisor(int v) { return Math.max(1, Math.min(24, v)); }

    // -------------------------------------------------------------------------
    // Settings cache
    //
    // getSettings() is read at least twice per recommendation request — once to resolve
    // the page size and again inside the ranking stages — and the values change only when
    // an admin saves the form. A short TTL keeps the extra round trips off the hot path
    // while staying well inside "the demo shows the change immediately"; saveSettings()
    // invalidates it outright so a save is visible on the very next request.
    // -------------------------------------------------------------------------

    private static final long SETTINGS_TTL_MILLIS = 30_000;
    private static volatile Settings cachedSettings;
    private static volatile long cachedSettingsAt;

    /** Drops the cached snapshot so the next read goes back to the database. */
    public static void invalidateSettingsCache() {
        cachedSettings = null;
        cachedSettingsAt = 0L;
    }

    /** Loads settings, falling back to defaults when unset or the table is missing. */
    public Settings getSettings() {
        Settings cached = cachedSettings;
        if (cached != null && System.currentTimeMillis() - cachedSettingsAt < SETTINGS_TTL_MILLIS) {
            return cached;
        }
        Settings loaded = loadSettings();
        cachedSettings = loaded;
        cachedSettingsAt = System.currentTimeMillis();
        return loaded;
    }

    private Settings loadSettings() {
        int items = DEFAULT_ITEMS_SHOWN;
        double threshold = DEFAULT_SIMILARITY_THRESHOLD;
        int windowDays = DEFAULT_TRENDING_WINDOW_DAYS;
        double wBid = UserBasedCollaborativeFilter.weightBid();
        double wWatchlist = UserBasedCollaborativeFilter.weightWatchlist();
        double wBrowse = UserBasedCollaborativeFilter.weightBrowse();
        double tauDays = DEFAULT_RECENCY_TAU_DAYS;
        int contentWindowDays = DEFAULT_CONTENT_WINDOW_DAYS;
        double wCf = DEFAULT_W_CF;
        double wUbcf = DEFAULT_W_UBCF;
        double wContent = DEFAULT_W_CONTENT;
        double wPopularity = DEFAULT_W_POPULARITY;
        double wRecency = DEFAULT_W_RECENCY;
        int diversityDivisor = DEFAULT_DIVERSITY_DIVISOR;

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
                    else if ("trending_window_days".equals(key)) windowDays = Integer.parseInt(value);
                    else if ("w_bid".equals(key)) wBid = Double.parseDouble(value);
                    else if ("w_watchlist".equals(key)) wWatchlist = Double.parseDouble(value);
                    else if ("w_browse".equals(key)) wBrowse = Double.parseDouble(value);
                    else if ("recency_tau_days".equals(key)) tauDays = Double.parseDouble(value);
                    else if ("content_window_days".equals(key)) contentWindowDays = Integer.parseInt(value);
                    else if ("rerank_w_cf".equals(key)) wCf = Double.parseDouble(value);
                    else if ("rerank_w_ubcf".equals(key)) wUbcf = Double.parseDouble(value);
                    else if ("rerank_w_content".equals(key)) wContent = Double.parseDouble(value);
                    else if ("rerank_w_pop".equals(key)) wPopularity = Double.parseDouble(value);
                    else if ("rerank_w_rec".equals(key)) wRecency = Double.parseDouble(value);
                    else if ("diversity_category_divisor".equals(key)) diversityDivisor = Integer.parseInt(value);
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) { }

        return Settings.builder()
                .itemsShown(clampItemsShown(items))
                .similarityThreshold(clampThreshold(threshold))
                .trendingWindowDays(clampWindowDays(windowDays))
                .weightBid(clampWeight(wBid))
                .weightWatchlist(clampWeight(wWatchlist))
                .weightBrowse(clampWeight(wBrowse))
                .recencyTauDays(clampTauDays(tauDays))
                .contentWindowDays(clampContentWindowDays(contentWindowDays))
                .weightCf(clampWeight(wCf))
                .weightUbcf(clampWeight(wUbcf))
                .weightContent(clampWeight(wContent))
                .weightPopularity(clampWeight(wPopularity))
                .weightRecency(clampWeight(wRecency))
                .diversityDivisor(clampDiversityDivisor(diversityDivisor))
                .build();
    }

    /** Persists the admin-tunable settings (upsert) and drops the cached snapshot. */
    public boolean saveSettings(Settings settings) {
        String sql = "INSERT INTO recommendation_settings (key, value, updated_at) VALUES (?, ?, NOW()) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            addSetting(ps, "items_shown", String.valueOf(clampItemsShown(settings.itemsShown)));
            addSetting(ps, "similarity_threshold", String.valueOf(clampThreshold(settings.similarityThreshold)));
            addSetting(ps, "trending_window_days", String.valueOf(clampWindowDays(settings.trendingWindowDays)));
            addSetting(ps, "w_bid", String.valueOf(clampWeight(settings.weightBid)));
            addSetting(ps, "w_watchlist", String.valueOf(clampWeight(settings.weightWatchlist)));
            addSetting(ps, "w_browse", String.valueOf(clampWeight(settings.weightBrowse)));
            addSetting(ps, "recency_tau_days", String.valueOf(clampTauDays(settings.recencyTauDays)));
            addSetting(ps, "content_window_days",
                    String.valueOf(clampContentWindowDays(settings.contentWindowDays)));
            addSetting(ps, "rerank_w_cf", String.valueOf(clampWeight(settings.weightCf)));
            addSetting(ps, "rerank_w_ubcf", String.valueOf(clampWeight(settings.weightUbcf)));
            addSetting(ps, "rerank_w_content", String.valueOf(clampWeight(settings.weightContent)));
            addSetting(ps, "rerank_w_pop", String.valueOf(clampWeight(settings.weightPopularity)));
            addSetting(ps, "rerank_w_rec", String.valueOf(clampWeight(settings.weightRecency)));
            addSetting(ps, "diversity_category_divisor",
                    String.valueOf(clampDiversityDivisor(settings.diversityDivisor)));
            ps.executeBatch();
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // Even a failed batch may have written some keys, so never leave a stale
            // snapshot behind for the next request to serve.
            invalidateSettingsCache();
        }
    }

    private static void addSetting(PreparedStatement ps, String key, String value) throws Exception {
        ps.setString(1, key);
        ps.setString(2, value);
        ps.addBatch();
    }

    /**
     * User-based CF with cosine similarity (FR4.1), using bids, watchlist, and browse history (FR4.3).
     */
    private List<SearchResultItem> userBasedCosineRecommendations(int userId, int limit, Set<Long> exclude) {
        if (limit <= 0) return List.of();

        Settings settings = getSettings();
        Map<Integer, Map<Long, Double>> vectors = loadInteractionVectors(settings);
        // Ranking is restricted to auctions that could actually be shown, because taking
        // the top `limit` first and filtering afterwards throws the arm away whenever the
        // neighbourhood's best-scoring items have closed. See recommendableAuctionIds().
        List<Long> rankedIds = UserBasedCollaborativeFilter.rankAuctionIds(
                userId, vectors, limit, exclude, settings.similarityThreshold,
                recommendableAuctionIds(userId));
        if (rankedIds.isEmpty()) return List.of();
        return fetchItemsByIds(rankedIds, userId, limit);
    }

    /**
     * Every auction that is currently recommendable to {@code viewerId} — open, moderated
     * active, not yet ended, and not the viewer's own listing.
     *
     * <p>Ranking used to run over the whole interaction history and hand the top
     * {@code limit} ids to {@link #fetchItemsByIds}, which applied these predicates only
     * then. Anything the ranking picked that had since closed was dropped without a
     * replacement, so a neighbourhood topped by ended auctions produced an empty
     * {@code SIMILAR_TASTE} arm rather than a shorter one. That was not hypothetical: for
     * one demo user the first eleven candidates had all ended and the first recommendable
     * one ranked fourteenth.</p>
     *
     * <p>The alternative — over-fetching some multiple of {@code limit} and filtering
     * afterwards — is one query cheaper but only moves the cliff: it still fails once more
     * than the multiple have closed, and the multiple has to be guessed. Materialising the
     * allow-set is exact for any history, at the cost of one extra query and a set of ids
     * bounded by the number of <em>open</em> auctions rather than by all of them.</p>
     *
     * <p>An empty result is a real answer, not an error: when a user's whole neighbourhood
     * has ended, the arm legitimately contributes nothing and the remaining pipeline stages
     * fill the slots instead.</p>
     */
    private Set<Long> recommendableAuctionIds(int viewerId) {
        Set<Long> out = new LinkedHashSet<>();
        String sql = "SELECT auction_id FROM auction "
                + "WHERE status_id = 1 AND moderation_state = 'active' AND date_end > now() "
                + "  AND seller_id <> ?";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, viewerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /**
     * Builds the per-user interaction vectors. The weights come from
     * {@code recommendation_settings}, so an admin can decide how much more a bid says
     * about someone's taste than a page view without a redeploy; the constants in
     * {@link UserBasedCollaborativeFilter} are only the seeded defaults.
     */
    private Map<Integer, Map<Long, Double>> loadInteractionVectors(Settings settings) {
        Map<Integer, Map<Long, Double>> vectors = new HashMap<>();
        // Ages come back from the database rather than being computed in Java so that the
        // comparison happens in one clock. bids.bid_time is a naive timestamp written from
        // CURRENT_TIMESTAMP, while added_at and viewed_at are timestamptz; the naive column
        // is pinned to UTC here exactly as CLICK_PRECEDES_BID does, rather than being left
        // to whatever TimeZone the session happens to carry.
        String sql =
            "SELECT user_id, auction_id, 'BID' AS src, "
          + "       EXTRACT(EPOCH FROM (now() - (bid_time AT TIME ZONE 'UTC'))) / 86400.0 AS age_days "
          + "  FROM bids "
          + "UNION ALL SELECT user_id, auction_id, 'WATCH', "
          + "       EXTRACT(EPOCH FROM (now() - added_at)) / 86400.0 FROM watchlist "
          + "UNION ALL SELECT user_id, auction_id, 'BROWSE', "
          + "       EXTRACT(EPOCH FROM (now() - viewed_at)) / 86400.0 FROM browse_history";
        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int uid = rs.getInt("user_id");
                long aid = rs.getLong("auction_id");
                double w;
                String src = rs.getString("src");
                if ("BID".equals(src)) {
                    w = settings.weightBid;
                } else if ("WATCH".equals(src)) {
                    w = settings.weightWatchlist;
                } else {
                    w = settings.weightBrowse;
                }
                UserBasedCollaborativeFilter.addInteraction(vectors, uid, aid,
                        w * recencyMultiplier(rs.getDouble("age_days"), settings.recencyTauDays));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return vectors;
    }

    /**
     * Exponential recency decay {@code exp(-Δdays / τ)}, in {@code (0, 1]}.
     *
     * <p>A τ of zero or less disables decay and returns 1, which is what lets an admin put
     * the recommender back on flat weights without a redeploy. Negative ages are clamped to
     * zero so a timestamp written slightly ahead of the database clock cannot inflate a
     * signal past its nominal weight.</p>
     */
    public static double recencyMultiplier(double ageDays, double tauDays) {
        if (tauDays <= 0) return 1.0;
        return Math.exp(-Math.max(0.0, ageDays) / tauDays);
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
          + DUTCH_CLOCK_COLUMNS
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
                    Timestamp end = rs.getTimestamp("date_end");
                    Timestamp start = rs.getTimestamp("date_created");
                    Instant endInstant = end != null ? end.toInstant() : null;
                    int typeId = rs.getInt("auction_type");
                    BigDecimal price = DutchClock.listedPrice(typeId,
                            rs.getBigDecimal("current_price"),
                            rs.getBigDecimal("starting_price"), rs.getBigDecimal("dutch_floor_price"),
                            start != null ? start.toInstant() : null,
                            endInstant, Instant.now());
                    byId.put(aid, new SearchResultItem(
                            aid,
                            rs.getString("title"),
                            rs.getString("category"),
                            price,
                            endInstant,
                            rs.getString("username"),
                            rs.getString("thumb"),
                            typeId));
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
          // One peer bidding twenty times is one endorsement, not twenty: counting rows
          // let a single determined bidder outrank a listing several peers agreed on.
          // Matches the scoring already used by similarByBidders().
          + "  SELECT auction_id, SUM(score) AS score FROM ( "
          + "     SELECT auction_id, COUNT(DISTINCT user_id) AS score FROM bids "
          + "       WHERE user_id IN (SELECT user_id FROM peers) GROUP BY auction_id "
          + "     UNION ALL "
          + "     SELECT auction_id, COUNT(DISTINCT user_id) AS score FROM watchlist "
          + "       WHERE user_id IN (SELECT user_id FROM peers) GROUP BY auction_id "
          + "  ) s GROUP BY auction_id "
          + ") "
          + "SELECT a.auction_id, d.title, d.category, a.auction_type, "
          // Blind auctions resolve to the entry price: recommended listings are
          // all still open, so their leading sealed bid must not reach the client.
          + "  CASE WHEN a.auction_type = 3 THEN d.starting_price "
          + "       ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) END AS current_price, "
          + DUTCH_CLOCK_COLUMNS
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

        int windowDays = getSettings().contentWindowDays;

        // The window is admin-tunable but defaults generously: set it shorter than the age
        // of the available history and this stage returns nothing at all, taking the
        // SAME_CATEGORY arm down with it. bid_time is naive and pinned to UTC; added_at and
        // viewed_at are already timestamptz.
        StringBuilder sql = new StringBuilder(
            "WITH my_signals AS ( "
          + "  SELECT auction_id FROM bids WHERE user_id = ? "
          + "    AND bid_time AT TIME ZONE 'UTC' > now() - (?::int * interval '1 day') "
          + "  UNION SELECT auction_id FROM watchlist WHERE user_id = ? "
          + "    AND added_at > now() - (?::int * interval '1 day') "
          + "  UNION SELECT auction_id FROM browse_history WHERE user_id = ? "
          + "    AND viewed_at > now() - (?::int * interval '1 day') "
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
          + DUTCH_CLOCK_COLUMNS
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
            for (int signal = 0; signal < 3; signal++) {
                ps.setInt(idx++, userId);
                ps.setInt(idx++, windowDays);
            }
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
     * Trending active auctions ordered by recent bid count, then soonest-ending. Used for
     * cold-start users and to top up sparse CF results.
     *
     * <p>{@code bid_count} is restricted to the admin-tunable
     * {@code trending_window_days} window. Counting bids over all time made a long-running
     * listing permanently "trending" and contradicted the wording on the card.</p>
     *
     * <p>When {@code viewerId} is given the viewer's own listings are dropped, and so are
     * auctions they already bid on or watch: as the pipeline's final filler this stage would
     * otherwise recommend people the items they are already bidding on. The public
     * {@code /trending} strip passes {@code null} and stays a genuine marketplace-wide list.</p>
     */
    public List<SearchResultItem> trending(int limit, Set<Long> excludeIds, Integer viewerId) {
        int windowDays = getSettings().trendingWindowDays;

        StringBuilder sql = new StringBuilder();
        if (viewerId != null) {
            sql.append("WITH my_items AS ( ")
               .append("  SELECT auction_id FROM bids WHERE user_id = ? ")
               .append("  UNION SELECT auction_id FROM watchlist WHERE user_id = ? ")
               .append(") ");
        }
        sql.append(
            "SELECT a.auction_id, d.title, d.category, a.auction_type, "
          // Blind auctions resolve to the entry price: recommended listings are
          // all still open, so their leading sealed bid must not reach the client.
          + "  CASE WHEN a.auction_type = 3 THEN d.starting_price "
          + "       ELSE COALESCE((SELECT MAX(b.bid_amount) FROM bids b WHERE b.auction_id = a.auction_id), d.starting_price) END AS current_price, "
          + DUTCH_CLOCK_COLUMNS
          + "  a.date_end, u.username, "
          + "  (SELECT image_url FROM auction_images i WHERE i.auction_id = a.auction_id ORDER BY id LIMIT 1) AS thumb, "
          + "  (SELECT COUNT(*) FROM bids b WHERE b.auction_id = a.auction_id "
          + "     AND b.bid_time AT TIME ZONE 'UTC' > now() - (?::int * interval '1 day')) AS bid_count "
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
        if (viewerId != null) {
            sql.append("AND a.auction_id NOT IN (SELECT auction_id FROM my_items) ");
            sql.append("AND a.seller_id <> ? ");
        }
        sql.append("ORDER BY bid_count DESC, a.date_end ASC LIMIT ?");

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (viewerId != null) {
                ps.setInt(idx++, viewerId);
                ps.setInt(idx++, viewerId);
            }
            ps.setInt(idx++, windowDays);
            for (Long id : excl) ps.setLong(idx++, id);
            if (viewerId != null) ps.setInt(idx++, viewerId);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return tag(mapRows(rs), Reason.TRENDING, trendingReason(windowDays));
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
        Timestamp start = rs.getTimestamp("date_created");
        Instant endInstant = end != null ? end.toInstant() : null;
        int typeId = rs.getInt("auction_type");
        return new SearchResultItem(
                rs.getLong("auction_id"),
                rs.getString("title"),
                rs.getString("category"),
                DutchClock.listedPrice(typeId, rs.getBigDecimal("current_price"),
                        rs.getBigDecimal("starting_price"), rs.getBigDecimal("dutch_floor_price"),
                        start != null ? start.toInstant() : null,
                        endInstant, Instant.now()),
                endInstant,
                rs.getString("username"),
                rs.getString("thumb"),
                typeId);
    }
}
