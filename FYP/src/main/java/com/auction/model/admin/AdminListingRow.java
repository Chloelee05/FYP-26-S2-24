package com.auction.model.admin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Listing row for admin listing moderation table.
 *
 * <p>Built by {@code AdminManagementDAO} for the moderation queue. It joins the listing to
 * its seller and to a count of open reports, which is what the queue is ordered by, so it
 * is wider than what any single table holds.</p>
 *
 * <p>Two independent states travel together here and are easy to confuse.
 * {@link #getModerationState()} is the admin's own decision about the listing, and
 * {@link #getAuctionStatus()} is where the auction is in its normal lifecycle. A listing
 * can be live and flagged at the same time.</p>
 */
public final class AdminListingRow {
    private  long auctionId;
    private  String title;
    private  LocalDate listedDate;
    private  String sellerUsername;
    private String category;
    private  BigDecimal currentBid;
    /** Open reports against this listing. The moderation queue sorts on it. */
    private  int reportCount;
    /** Admin moderation decision: active, flagged or removed. */
    private  String moderationState;
    /** Whether an admin has pinned this listing to the landing page. */
    private boolean featured;
    /** Auction lifecycle status: ACTIVE, PENDING, FINISHED, CANCELLED */
    private String auctionStatus;

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

    public String getAuctionStatus() {
        return auctionStatus;
    }

    public void setAuctionStatus(String auctionStatus) {
        this.auctionStatus = auctionStatus;
    }
}
