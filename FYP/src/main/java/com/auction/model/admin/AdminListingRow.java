package com.auction.model.admin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Listing row for admin listing moderation table.
 */
public final class AdminListingRow {
    private final long auctionId;
    private final String title;
    private final LocalDate listedDate;
    private final String sellerUsername;
    private final String category;
    private final BigDecimal currentBid;
    private final int reportCount;
    private final String moderationState;

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

    public String getTitle() {
        return title;
    }

    public LocalDate getListedDate() {
        return listedDate;
    }

    public String getSellerUsername() {
        return sellerUsername;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getCurrentBid() {
        return currentBid;
    }

    public int getReportCount() {
        return reportCount;
    }

    public String getModerationState() {
        return moderationState;
    }
}
