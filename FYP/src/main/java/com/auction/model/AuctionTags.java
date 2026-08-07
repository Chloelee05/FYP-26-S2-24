package com.auction.model;

/**
 * A tag that can be attached to a listing, from the {@code tags} lookup table. Tags feed
 * the content arm of the recommendation pipeline as well as the listing form's tag picker.
 * {@link Auction} holds only the tag ids, so this pairs an id with its display name.
 */
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
