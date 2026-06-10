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
    private final String condition;
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

    // ── Strategy / extended fields (set post-construction to keep the core
    //    constructor stable for existing callers and tests) ──
    private int auctionTypeId = AuctionType.PRICE_UP.getId();
    private BigDecimal dutchFloorPrice; // Dutch clock floor; null = unset
    private Instant dateCreated;        // auction open time (for Dutch clock)
    private int quantity = 1;
    private BigDecimal costPrice;       // seller-private; never exposed to buyers

    public AuctionDetail(long auctionId, String title, String description, String category,
                         String condition, BigDecimal startingPrice, BigDecimal currentBid,
                         int bidCount, BigDecimal maxPrice, Instant endDate, int sellerId,
                         String sellerUsername, List<String> imageUrls, boolean open) {
        this.auctionId = auctionId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.condition = condition;
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
    public String getCondition()       { return condition; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public BigDecimal getCurrentBid()  { return currentBid; }
    public int getBidCount()           { return bidCount; }
    public BigDecimal getMaxPrice()    { return maxPrice; }
    public Instant getEndDate()        { return endDate; }
    public int getSellerId()           { return sellerId; }
    public String getSellerUsername()  { return sellerUsername; }
    public List<String> getImageUrls() { return imageUrls; }
    public boolean isOpen()            { return open; }

    public int getAuctionTypeId()              { return auctionTypeId; }
    public void setAuctionTypeId(int id)       { this.auctionTypeId = id; }
    public BigDecimal getDutchFloorPrice()     { return dutchFloorPrice; }
    public void setDutchFloorPrice(BigDecimal p) { this.dutchFloorPrice = p; }
    public Instant getDateCreated()            { return dateCreated; }
    public void setDateCreated(Instant t)      { this.dateCreated = t; }
    public int getQuantity()                   { return quantity; }
    public void setQuantity(int q)             { this.quantity = q; }
    public BigDecimal getCostPrice()           { return costPrice; }
    public void setCostPrice(BigDecimal c)     { this.costPrice = c; }
}
