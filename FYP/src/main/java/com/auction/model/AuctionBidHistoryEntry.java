package com.auction.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row in the public bid-history list for an auction (SCRUM-58).
 * Bidder username is pre-masked in the DAO — never the raw value from the database.
 */
public final class AuctionBidHistoryEntry {

    private final BigDecimal bidAmount;
    private final Instant bidTime;
    /** Masked bidder display name (partial for current leader, full mask for others). */
    private final String maskedBidderName;
    /** {@code true} when this bid was placed by the current highest bidder. */
    private final boolean currentLeader;
    /** {@code true} when this bid was placed by the currently authenticated viewer. */
    private final boolean self;

    /** Legacy constructor — {@code self} defaults to {@code false}. */
    public AuctionBidHistoryEntry(BigDecimal bidAmount, Instant bidTime,
                                  String maskedBidderName, boolean currentLeader) {
        this(bidAmount, bidTime, maskedBidderName, currentLeader, false);
    }

    public AuctionBidHistoryEntry(BigDecimal bidAmount, Instant bidTime,
                                  String maskedBidderName, boolean currentLeader, boolean self) {
        this.bidAmount = bidAmount;
        this.bidTime = bidTime;
        this.maskedBidderName = maskedBidderName;
        this.currentLeader = currentLeader;
        this.self = self;
    }

    public BigDecimal getBidAmount()      { return bidAmount; }
    public Instant getBidTime()           { return bidTime; }
    public String getMaskedBidderName()   { return maskedBidderName; }
    public boolean isCurrentLeader()      { return currentLeader; }
    /** Whether this bid was placed by the viewer who requested the history. */
    public boolean isSelf()               { return self; }

    /** JSP {@code fmt:formatDate} helper. */
    public java.util.Date getBidTimeDate() {
        return bidTime == null ? null : java.util.Date.from(bidTime);
    }
}
