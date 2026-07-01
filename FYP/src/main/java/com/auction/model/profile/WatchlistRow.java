package com.auction.model.profile;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/** One row in the buyer's watchlist view. */
public final class WatchlistRow implements Serializable {
    private long auctionId;
    private String title;
    private int statusId;
    private Instant addedAt;
    private BigDecimal currentBid;
    private Instant endDate;
    private int bidCount;
    private int userId;

    public WatchlistRow()
    {
    }

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

    public void setAuctionId(long auctionId)
    {
        this.auctionId = auctionId;
    }
    public String getTitle()          { return title; }

    public void setTitle(String title)
    {
        this.title = title;
    }
    public int getStatusId()          { return statusId; }

    public void setStatusId (int statusId)
    {
        this.statusId = statusId;
    }
    public Instant getAddedAt() { return addedAt; }

    public void setAddedAt(Instant addedAt)
    {
        this.addedAt = addedAt;
    }
    public BigDecimal getCurrentBid() { return currentBid; }

    public void setCurrentBid(BigDecimal currentBid)
    {
        this.currentBid = currentBid;
    }
    public Instant getEndDate()       { return endDate; }

    public void setEndDate(Instant endDate)
    {
        this.endDate = endDate;
    }

    public int getBidCount()          { return bidCount; }

    public void setBidCount(int bidCount)
    {
        this.bidCount = bidCount;
    }

    public int getUserId()
    {
        return this.userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }
}
