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

    private final Reason reasonCode;
    private final String reason;
    private long clickCount;
    private long distinctClickers;
    private String clickedByMasked;
    private List<String> keywords = new ArrayList<>();

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

    public Reason reason() { return reasonCode; }

    public void setClickCount(long clickCount) { this.clickCount = clickCount; }
    public void setDistinctClickers(long distinctClickers) { this.distinctClickers = distinctClickers; }
    public void setClickedByMasked(String clickedByMasked) { this.clickedByMasked = clickedByMasked; }

    public void setKeywords(List<String> keywords) {
        this.keywords = (keywords == null) ? new ArrayList<>() : new ArrayList<>(keywords);
    }
}
