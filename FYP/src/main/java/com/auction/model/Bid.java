package com.auction.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * One row of the {@code bids} table: who bid how much on what, and when.
 *
 * <p>There is no primary key field because a bid is identified by the whole tuple. Bid
 * time breaks ties on equal amounts, which is how the earliest of two identical bids wins
 * in {@link com.auction.util.AuctionFinalizer}.</p>
 */
public class Bid implements Serializable {
    private Long auction_id;
    private Long user_id;
    private float bid_amount;
    private Instant bid_time;

    public Bid()
    {
    }

    public Bid(Long auction_id, Long user_id, float bid_amount, Instant bid_time)
    {
        this.auction_id = auction_id;
        this.user_id = user_id;
        this.bid_amount = bid_amount;
        this.bid_time = bid_time;
    }

    public Long getAuction_id() {
        return this.auction_id;
    }

    public void setAuction_id(Long auction_id)
    {
        this.auction_id = auction_id;
    }

    public Long getUser_id() {
        return this.user_id;
    }

    public void setUser_id(Long user_id){
        this.user_id = user_id;
    }

    public float getBid_amount() {
        return this.bid_amount;
    }

    public void setBid_amount(float bid_amount)
    {
        this.bid_amount = bid_amount;
    }

    public Instant getBid_time() {
        return this.bid_time;
    }

    public void setBid_time(Instant bid_time)
    {
        this.bid_time = bid_time;
    }
}
