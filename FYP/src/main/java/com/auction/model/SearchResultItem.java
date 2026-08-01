package com.auction.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only projection of a public auction listing returned by a keyword search (SCRUM-48).
 * Contains only the fields needed to render a search result card — no PII is exposed.
 */
public final class SearchResultItem {

    private final long auctionId;
    private final String title;
    private final String category;
    /** Current highest bid amount, or the starting price when no bids have been placed. */
    private final BigDecimal currentPrice;
    private final Instant endDate;
    /** Seller's display username (not masked — publicly visible on listings). */
    private final String sellerUsername;
    /** URL of the first uploaded image, or {@code null} if none. */
    private final String thumbnailUrl;
    /** {@link AuctionType} id; 0 when the query did not select it. */
    private final int auctionType;
    /** Set on recommendation endpoints only; omitted from search responses entirely. */
    private RecommendationProvenance why;

    public SearchResultItem(long auctionId, String title, String category,
                            BigDecimal currentPrice, Instant endDate,
                            String sellerUsername, String thumbnailUrl) {
        this(auctionId, title, category, currentPrice, endDate, sellerUsername, thumbnailUrl, 0);
    }

    public SearchResultItem(long auctionId, String title, String category,
                            BigDecimal currentPrice, Instant endDate,
                            String sellerUsername, String thumbnailUrl, int auctionType) {
        this.auctionId = auctionId;
        this.title = title;
        this.category = category;
        this.currentPrice = currentPrice;
        this.endDate = endDate;
        this.sellerUsername = sellerUsername;
        this.thumbnailUrl = thumbnailUrl;
        this.auctionType = auctionType;
    }

    public long getAuctionId() { return auctionId; }
    public String getTitle() { return title; }
    public String getCategory() { return category; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public Instant getEndDate() { return endDate; }
    public String getSellerUsername() { return sellerUsername; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public int getAuctionType() { return auctionType; }

    /**
     * {@code true} when the amount must not be shown to buyers. Every listing
     * these projections carry is still open, so a blind auction is by definition
     * one whose bids have not been revealed yet — {@link #getCurrentPrice()} is
     * the entry price in that case, never the leading sealed bid.
     */
    public boolean isSealed() { return auctionType == AuctionType.BLIND.getId(); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public RecommendationProvenance getWhy() { return why; }

    public void setWhy(RecommendationProvenance why) { this.why = why; }
}
