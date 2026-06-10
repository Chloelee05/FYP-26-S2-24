package com.auction.model.profile;

import java.math.BigDecimal;
import java.time.Instant;

/** One row in the buyer's watchlist view. */
public final class WatchlistRow {
    private final long auctionId;
    private final String title;
    private final int statusId;
    private final Instant addedAt;
    private final BigDecimal currentBid;
    private final Instant endDate;
    private final int bidCount;

    public WatchlistRow(long auctionId, String title, int statusId, Instant addedAt,
                        BigDecimal currentBid, Instant endDate, int bidCount) {
        this.auctionId  = auctionId;
        this.title      = title;
        this.statusId   = statusId;
        this.addedAt    = addedAt;
        this.currentBid = currentBid;
        this.endDate    = endDate;
        this.bidCount   = bidCount;
    }

    public long getAuctionId()        { return auctionId; }
    public String getTitle()          { return title; }
    public int getStatusId()          { return statusId; }
    public Instant getAddedAt() { return addedAt; }
    public BigDecimal getCurrentBid() { return currentBid; }
    public Instant getEndDate()       { return endDate; }
    public int getBidCount()          { return bidCount; }
}
