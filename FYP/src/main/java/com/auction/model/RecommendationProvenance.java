package com.auction.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Why a particular auction was recommended, and how that recommendation has performed.
 *
 * <p>Attached to a {@link SearchResultItem} on the recommendation endpoints only, so the
 * card can explain itself instead of appearing without justification.</p>
 *
 * <p><b>PDPA:</b> everything here is safe for an unauthenticated visitor to read. Click
 * figures are aggregates, {@code clickedByMasked} is produced by
 * {@code SecurityUtil.maskUsername} and is only populated once enough distinct people
 * have clicked, and keywords are search terms with no user attached. The per-user rows
 * behind these numbers are only served by the ADMIN attribution endpoint.</p>
 */
public final class RecommendationProvenance {

    /** Stable identifier for the pipeline stage that produced the item. */
    public enum Reason {
        SEARCH_KEYWORD,
        PEER_BIDS,
        SIMILAR_TASTE,
        SAME_CATEGORY,
        TRENDING
    }

    /**
     * The signals the hybrid re-ranker scores a candidate on.
     *
     * <p>Distinct from {@link Reason}, which names the single stage that first produced the
     * card and stays the label the CTR breakdown is grouped by. A card can carry several
     * components at once — that is the point of re-ranking — so the dominant one is
     * reported separately rather than overwriting the arm.</p>
     */
    public enum Component {
        /** Peer co-occurrence on bids and watchlist. */
        CF,
        /** User-based cosine similarity over bids, watchlist and browse history. */
        UBCF,
        /** Shared category or tag with the viewer's own history. */
        CONTENT,
        /** Recent bid count — the popularity signal behind trending. */
        POPULARITY,
        /** How soon the auction closes. */
        RECENCY
    }

    private final Reason reasonCode;
    private final String reason;
    private long clickCount;
    private long distinctClickers;
    private String clickedByMasked;
    private List<String> keywords = new ArrayList<>();
    private double score;
    private Component dominantComponent;

    public RecommendationProvenance(Reason reasonCode, String reason) {
        this.reasonCode = reasonCode;
        this.reason = reason;
    }

    public String getReasonCode() { return reasonCode.name(); }
    public String getReason() { return reason; }
    public long getClickCount() { return clickCount; }
    public long getDistinctClickers() { return distinctClickers; }
    public String getClickedByMasked() { return clickedByMasked; }
    public List<String> getKeywords() { return keywords; }

    /**
     * The hybrid re-ranker's blended score for this card, in {@code [0, 1]}.
     *
     * <p>Weighted mean of the min-max normalised components, divided by the weight of the
     * components that carried any signal at all, so the figure stays comparable when a
     * component is missing. Zero when the ranking fell back to stage order.</p>
     */
    public double getScore() { return score; }

    /** Which component contributed most to {@link #getScore()}, or null before ranking. */
    public String getDominantComponent() {
        return dominantComponent == null ? null : dominantComponent.name();
    }

    public Reason reason() { return reasonCode; }
    public Component dominantComponentCode() { return dominantComponent; }

    public void setClickCount(long clickCount) { this.clickCount = clickCount; }
    public void setDistinctClickers(long distinctClickers) { this.distinctClickers = distinctClickers; }
    public void setClickedByMasked(String clickedByMasked) { this.clickedByMasked = clickedByMasked; }
    public void setScore(double score) { this.score = score; }
    public void setDominantComponent(Component dominantComponent) {
        this.dominantComponent = dominantComponent;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = (keywords == null) ? new ArrayList<>() : new ArrayList<>(keywords);
    }
}
