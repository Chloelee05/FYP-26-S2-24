package com.auction.model;

public enum AuctionStatus {
    ACTIVE(1),
    FINISHED(2),
    CANCELLED(3),
    PENDING(4);

    private final int id;

    AuctionStatus(int id){
        this.id = id;
    }

    public int getId(){
        return this.id;
    }

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
