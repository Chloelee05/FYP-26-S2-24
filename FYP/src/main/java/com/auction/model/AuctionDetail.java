package com.auction.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read-only projection of a single auction for the public detail page (SCRUM-51).
 * Includes enough information to render the bid form and enforce business rules.
 */
public final class AuctionDetail {

    private final long auctionId;
    private final String title;
    private final String description;
    private final String category;
    private final BigDecimal startingPrice;
    /** Current highest bid, or {@link #startingPrice} when no bids have been placed. */
    private final BigDecimal currentBid;
    private final int bidCount;
    /** Hard ceiling set by the seller; {@code null} means no cap. */
    private final BigDecimal maxPrice;
    private final Instant endDate;
    /** ID of the seller — used server-side for self-bid detection; not exposed in JSP. */
    private final int sellerId;
    private final String sellerUsername;
    private final List<String> imageUrls;
    /**
     * {@code true} when the auction has {@code status_id = ACTIVE},
     * {@code moderation_state = 'active'}, and {@code date_end > now()}.
     */
    private final boolean open;

    public AuctionDetail(long auctionId, String title, String description, String category,
                         BigDecimal startingPrice, BigDecimal currentBid, int bidCount,
                         BigDecimal maxPrice, Instant endDate, int sellerId,
                         String sellerUsername, List<String> imageUrls, boolean open) {
        this.auctionId = auctionId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.startingPrice = startingPrice;
        this.currentBid = currentBid;
        this.bidCount = bidCount;
        this.maxPrice = maxPrice;
        this.endDate = endDate;
        this.sellerId = sellerId;
        this.sellerUsername = sellerUsername;
        this.imageUrls = imageUrls;
        this.open = open;
    }

    public long getAuctionId()        { return auctionId; }
    public String getTitle()           { return title; }
    public String getDescription()     { return description; }
    public String getCategory()        { return category; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public BigDecimal getCurrentBid()  { return currentBid; }
    public int getBidCount()           { return bidCount; }
    public BigDecimal getMaxPrice()    { return maxPrice; }
    public Instant getEndDate()        { return endDate; }
    public int getSellerId()           { return sellerId; }
    public String getSellerUsername()  { return sellerUsername; }
    public List<String> getImageUrls() { return imageUrls; }
    public boolean isOpen()            { return open; }
}
