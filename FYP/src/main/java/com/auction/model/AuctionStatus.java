package com.auction.model;

/**
 * Lifecycle state of an auction, mirroring {@code auction.status_id}. The numeric ids are
 * the stored values and are not in lifecycle order, so compare with the constants rather
 * than with the numbers. {@link com.auction.util.AuctionStateUtil} wraps the common checks.
 */
public enum AuctionStatus {
    /** Open and accepting bids. */
    ACTIVE(1),
    /** Ended normally, either by the clock running out or by a winner being declared. */
    FINISHED(2),
    /** Withdrawn by the seller before it could conclude. */
    CANCELLED(3),
    /** Scheduled but not yet open, because the start date is still in the future. */
    PENDING(4);

    private final int id;

    AuctionStatus(int id){
        this.id = id;
    }

    /** The value stored in {@code auction.status_id}, not the enum ordinal. */
    public int getId(){
        return this.id;
    }

    /**
     * Maps a stored {@code status_id} back to its constant.
     *
     * @throws IllegalArgumentException when no constant carries that id
     */
    public static AuctionStatus getAuctionStatus(int id)
    {
        for(AuctionStatus auctionStatus: values()) {
            if (auctionStatus.id == id) {
                return auctionStatus;
            }
        }
        throw new IllegalArgumentException("Invalid Auction Status");
    }
}
