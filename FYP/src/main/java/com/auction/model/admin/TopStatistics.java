package com.auction.model.admin;

import com.auction.model.User;

import java.io.Serializable;

public class TopStatistics implements Serializable {
    private User user;
    private int auction_count;
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
