package com.auction.model.profile;

import java.time.LocalDateTime;

/** One row in the buyer's watchlist view. */
public final class WatchlistRow {
    private final long auctionId;
    private final String title;
    private final int statusId;
    private final LocalDateTime addedAt;

    public WatchlistRow(long auctionId, String title, int statusId, LocalDateTime addedAt) {
        this.auctionId = auctionId;
        this.title     = title;
        this.statusId  = statusId;
        this.addedAt   = addedAt;
    }

    public long getAuctionId()        { return auctionId; }
    public String getTitle()          { return title; }
    public int getStatusId()          { return statusId; }
    public LocalDateTime getAddedAt() { return addedAt; }
}
