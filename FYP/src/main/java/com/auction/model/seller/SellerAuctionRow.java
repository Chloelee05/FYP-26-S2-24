package com.auction.model.seller;

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

    public SellerAuctionRow(long auctionId, String title, BigDecimal startingPrice,
                            BigDecimal maxPrice, BigDecimal currentBid, int bidCount,
                            Instant startDate, Instant endDate, String statusName, int quantity) {
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
}
