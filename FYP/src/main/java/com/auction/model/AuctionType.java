package com.auction.model;

public enum AuctionType {
    PRICE_UP(1),
    DUTCH_AUCTION(2),
    BLIND(3);

    private final int id;

    AuctionType(int id){
        this.id = id;
    }

    public int getId(){
        return this.id;
    }

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
