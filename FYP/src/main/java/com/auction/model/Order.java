package com.auction.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A simulated checkout record (funds-vs-goods exchange) created when an auction is won.
 * Serialised for both buyer and seller views; {@code role} and {@code counterparty}
 * are set relative to the requesting user.
 */
public final class Order {

    private final long id;
    private final long auctionId;
    private final String auctionTitle;
    private final long buyerId;
    private final long sellerId;
    private final BigDecimal amount;
    private final String status;
    private final Instant createdAt;
    private final Instant paidAt;
    private final Instant completedAt;
    private final String role;          // "buyer" or "seller" for the requesting user
    private final String counterparty;  // the other party's username
    private final String shippingStatus;
    private final Instant shippingUpdatedAt;
    private final boolean hasRated;
    private final String refundStatus;
    private final String refundReason;
    private final Instant refundRequestedAt;

    public Order(long id, long auctionId, String auctionTitle, long buyerId, long sellerId,
                 BigDecimal amount, String status, Instant createdAt, Instant paidAt, Instant completedAt,
                 String role, String counterparty, String shippingStatus, Instant shippingUpdatedAt,
                 boolean hasRated, String refundStatus, String refundReason, Instant refundRequestedAt) {
        this.id = id;
        this.auctionId = auctionId;
        this.auctionTitle = auctionTitle;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
        this.completedAt = completedAt;
        this.role = role;
        this.counterparty = counterparty;
        this.shippingStatus = shippingStatus;
        this.shippingUpdatedAt = shippingUpdatedAt;
        this.hasRated = hasRated;
        this.refundStatus = refundStatus;
        this.refundReason = refundReason;
        this.refundRequestedAt = refundRequestedAt;
    }

    public long getId()            { return id; }
    public long getAuctionId()     { return auctionId; }
    public String getAuctionTitle(){ return auctionTitle; }
    public long getBuyerId()       { return buyerId; }
    public long getSellerId()      { return sellerId; }
    public BigDecimal getAmount()  { return amount; }
    public String getStatus()      { return status; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getPaidAt()     { return paidAt; }
    public Instant getCompletedAt(){ return completedAt; }
    public String getRole()        { return role; }
    public String getCounterparty(){ return counterparty; }
    public String getShippingStatus()    { return shippingStatus; }
    public Instant getShippingUpdatedAt(){ return shippingUpdatedAt; }
    public boolean isHasRated()           { return hasRated; }
    public String getRefundStatus()       { return refundStatus; }
    public String getRefundReason()       { return refundReason; }
    public Instant getRefundRequestedAt() { return refundRequestedAt; }
}
