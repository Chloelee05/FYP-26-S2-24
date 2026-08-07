package com.auction.model;

/**
 * The three bidding strategies a listing can run under. The numeric ids are the values
 * actually stored in {@code auction.auction_type_id}, so they are part of the database
 * contract and must not be renumbered.
 */
public enum AuctionType {
    /** Ascending auction: buyers bid upwards and the highest bid at close wins. */
    PRICE_UP(1),
    /**
     * Dutch auction: the price starts high and falls towards a floor as time passes.
     * The first buyer to accept the clock price ends the auction. See
     * {@link com.auction.util.DutchClock} for the price calculation.
     */
    DUTCH_AUCTION(2),
    /**
     * Blind (sealed bid) auction: competing amounts stay hidden until the auction closes.
     * Read paths guard on this id specifically, for example
     * {@link SearchResultItem#isSealed()}, so that a listing card never leaks the leading
     * bid while bidding is still open.
     */
    BLIND(3);

    private final int id;

    AuctionType(int id){
        this.id = id;
    }

    /** The value stored in the database, not the enum ordinal. */
    public int getId(){
        return this.id;
    }

    /**
     * Maps a stored {@code auction_type_id} back to its constant.
     *
     * @throws IllegalArgumentException when the id matches no constant, which means the
     *         row holds a value the application does not know how to run
     */
    public static AuctionType getAuctionType(int id)
    {
        for(AuctionType auctionType: values()) {
            if (auctionType.id == id) {
                return auctionType;
            }
        }
        throw new IllegalArgumentException("Invalid Auction Type");
    }
}
