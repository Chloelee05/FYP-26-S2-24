package com.auction.model.admin;

import com.auction.model.User;

import java.io.Serializable;

/**
 * One entry in the admin dashboard's top sellers ranking: an account paired with the two
 * aggregates it is ranked on. Built by the reporting query rather than read from a table.
 */
public class TopStatistics implements Serializable {
    private User user;
    /** How many listings this seller has created. */
    private int auction_count;
    /** Summed value of their completed sales. */
    private float total_revenue;

    public TopStatistics(){

    }

    public User getUser() {
        return this.user;
    }

    public void setUser(User user)
    {
        this.user = user;
    }

    public int getAuction_count() {
        return this.auction_count;
    }

    public void setAuction_count(int auction_count)
    {
        this.auction_count = auction_count;
    }

    public float getTotal_revenue() {
        return this.total_revenue;
    }

    public void setTotal_revenue(float total_revenue)
    {
        this.total_revenue = total_revenue;
    }
}
