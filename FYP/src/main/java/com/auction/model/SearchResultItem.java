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
    /** Set on recommendation endpoints only; omitted from search responses entirely. */
    private RecommendationProvenance why;

    public SearchResultItem(long auctionId, String title, String category,
                            BigDecimal currentPrice, Instant endDate,
                            String sellerUsername, String thumbnailUrl) {
        this.auctionId = auctionId;
        this.title = title;
        this.category = category;
        this.currentPrice = currentPrice;
        this.endDate = endDate;
        this.sellerUsername = sellerUsername;
        this.thumbnailUrl = thumbnailUrl;
    }

    public long getAuctionId() { return auctionId; }
    public String getTitle() { return title; }
    public String getCategory() { return category; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public Instant getEndDate() { return endDate; }
    public String getSellerUsername() { return sellerUsername; }
    public String getThumbnailUrl() { return thumbnailUrl; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public RecommendationProvenance getWhy() { return why; }

    public void setWhy(RecommendationProvenance why) { this.why = why; }
}
