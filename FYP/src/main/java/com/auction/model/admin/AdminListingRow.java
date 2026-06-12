package com.auction.model.admin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Listing row for admin listing moderation table.
 */
public final class AdminListingRow {
    private  long auctionId;
    private  String title;
    private  LocalDate listedDate;
    private  String sellerUsername;
    private String category;
    private  BigDecimal currentBid;
    private  int reportCount;
    private  String moderationState;
    private boolean featured;

    public AdminListingRow()
    {}

    public AdminListingRow(long auctionId, String title, LocalDate listedDate, String sellerUsername,
                           String category, BigDecimal currentBid, int reportCount, String moderationState) {
        this.auctionId = auctionId;
        this.title = title;
        this.listedDate = listedDate;
        this.sellerUsername = sellerUsername;
        this.category = category;
        this.currentBid = currentBid;
        this.reportCount = reportCount;
        this.moderationState = moderationState;
    }

    public long getAuctionId() {
        return auctionId;
    }

    public void setAuctionId(Long auctionId)
    {
        this.auctionId = auctionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getListedDate() {
        return listedDate;
    }

    public void setListedDate(LocalDate listedDate) {
        this.listedDate = listedDate;
    }

    public String getSellerUsername() {
        return sellerUsername;
    }

    public void setSellerUsername(String sellerUsername) {
        this.sellerUsername = sellerUsername;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getCurrentBid() {
        return currentBid;
    }

    public void setCurrentBid(BigDecimal currentBid) {
        this.currentBid = currentBid;
    }

    public int getReportCount() {
        return reportCount;
    }

    public void setReportCount(int reportCount) {
        this.reportCount = reportCount;
    }

    public String getModerationState() {
        return moderationState;
    }

    public void setModerationState(String moderationState) {
        this.moderationState = moderationState;
    }

    public boolean isFeatured() {
        return featured;
    }

    public void setFeatured(boolean featured) {
        this.featured = featured;
    }
}
