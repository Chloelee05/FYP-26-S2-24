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
    /** First listing image, so the row can show the item instead of a placeholder. */
    private final String thumbnailUrl;
    /** {@code true} once this user has left a review on the auction. */
    private final boolean rated;

    public BidHistoryRow(long auctionId, String itemTitle, BigDecimal bidAmount,
                         LocalDateTime bidTime, String auctionStatus, boolean won) {
        this(auctionId, itemTitle, bidAmount, bidTime, auctionStatus, won, null, false);
    }

    public BidHistoryRow(long auctionId, String itemTitle, BigDecimal bidAmount,
                         LocalDateTime bidTime, String auctionStatus, boolean won,
                         String thumbnailUrl, boolean rated) {
        this.auctionId = auctionId;
        this.itemTitle = itemTitle;
        this.bidAmount = bidAmount;
        this.bidTime = bidTime;
        this.auctionStatus = auctionStatus;
        this.won = won;
        this.thumbnailUrl = thumbnailUrl;
        this.rated = rated;
    }

    public long getAuctionId() { return auctionId; }
    public String getItemTitle() { return itemTitle; }
    public BigDecimal getBidAmount() { return bidAmount; }
    public LocalDateTime getBidTime() { return bidTime; }
    public String getAuctionStatus() { return auctionStatus; }
    public boolean isWon() { return won; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public boolean isRated() { return rated; }
}
