package com.auction.model.seller;

import com.auction.model.ListingKind;

import java.math.BigDecimal;
import java.time.Instant;

public class SellerAuctionRow {
    private final long auctionId;
    private final String title;
    private final BigDecimal startingPrice;
    private final BigDecimal maxPrice;      // null when no cap set
    private final BigDecimal currentBid;    // 0 when no bids placed
    private final int bidCount;
    private final Instant startDate;
    private final Instant endDate;
    private final String statusName;
    private final int quantity;
    /** First uploaded image for the listing, or null when it has none. */
    private final String thumbnailUrl;
    /** How many buyers have this listing on their watchlist ("likes"). */
    private final int watchCount;
    /**
     * PRODUCT or SERVICE. Present so a seller's own list says which of their listings are
     * services — the field is theirs to set on create, so it has to read back to them here.
     */
    private final String listingKind;

    public SellerAuctionRow(long auctionId, String title, BigDecimal startingPrice,
                            BigDecimal maxPrice, BigDecimal currentBid, int bidCount,
                            Instant startDate, Instant endDate, String statusName, int quantity,
                            String thumbnailUrl, int watchCount) {
        this(auctionId, title, startingPrice, maxPrice, currentBid, bidCount,
                startDate, endDate, statusName, quantity, thumbnailUrl, watchCount, null);
    }

    public SellerAuctionRow(long auctionId, String title, BigDecimal startingPrice,
                            BigDecimal maxPrice, BigDecimal currentBid, int bidCount,
                            Instant startDate, Instant endDate, String statusName, int quantity,
                            String thumbnailUrl, int watchCount, String listingKind) {
        this.auctionId = auctionId;
        this.title = title;
        this.startingPrice = startingPrice;
        this.maxPrice = maxPrice;
        this.currentBid = currentBid;
        this.bidCount = bidCount;
        this.startDate = startDate;
        this.endDate = endDate;
        this.statusName = statusName;
        this.quantity = quantity;
        this.thumbnailUrl = thumbnailUrl;
        this.watchCount = watchCount;
        ListingKind parsed = ListingKind.parse(listingKind);
        this.listingKind = (parsed != null ? parsed : ListingKind.DEFAULT).name();
    }

    public long getAuctionId()      { return auctionId; }
    public String getTitle()         { return title; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public BigDecimal getMaxPrice()  { return maxPrice; }
    public BigDecimal getCurrentBid(){ return currentBid; }
    public int getBidCount()         { return bidCount; }
    public Instant getStartDate()    { return startDate; }
    public Instant getEndDate()      { return endDate; }
    public String getStatusName()    { return statusName; }
    public int getQuantity()         { return quantity; }
    public String getThumbnailUrl()  { return thumbnailUrl; }
    public int getWatchCount()       { return watchCount; }
    public String getListingKind()   { return listingKind; }
}
