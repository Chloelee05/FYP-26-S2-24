package com.auction.model.profile;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One row in the buyer bidding-history view. */
public final class BidHistoryRow {
    private final long auctionId;
    private final String itemTitle;
    private final BigDecimal bidAmount;
    private final LocalDateTime bidTime;
    /** {@code "Live"} or {@code "Ended"} */
    private final String auctionStatus;
    /** {@code true} when this user won the auction. */
    private final boolean won;

    public BidHistoryRow(long auctionId, String itemTitle, BigDecimal bidAmount,
                         LocalDateTime bidTime, String auctionStatus, boolean won) {
        this.auctionId = auctionId;
        this.itemTitle = itemTitle;
        this.bidAmount = bidAmount;
        this.bidTime = bidTime;
        this.auctionStatus = auctionStatus;
        this.won = won;
    }

    public long getAuctionId() { return auctionId; }
    public String getItemTitle() { return itemTitle; }
    public BigDecimal getBidAmount() { return bidAmount; }
    public LocalDateTime getBidTime() { return bidTime; }
    public String getAuctionStatus() { return auctionStatus; }
    public boolean isWon() { return won; }
}
