package com.auction.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

public class Bid implements Serializable {
    private static final long serialVersionUID = 1L;

    private int id;
    private int auctionId;
    private int buyerId;
    private BigDecimal amount;
    private Timestamp bidTime;

    // Joined fields
    private String buyerUsername;

    public Bid() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getAuctionId() { return auctionId; }
    public void setAuctionId(int auctionId) { this.auctionId = auctionId; }

    public int getBuyerId() { return buyerId; }
    public void setBuyerId(int buyerId) { this.buyerId = buyerId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Timestamp getBidTime() { return bidTime; }
    public void setBidTime(Timestamp bidTime) { this.bidTime = bidTime; }

    public String getBuyerUsername() { return buyerUsername; }
    public void setBuyerUsername(String buyerUsername) { this.buyerUsername = buyerUsername; }
}
