package com.auction.model;

public class AuctionTags {
    private final long id;
    private final String name;

    public AuctionTags(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public long getId() { return id; }
    public String getName() { return name; }
}
