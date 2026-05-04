package com.auction.model.profile;

import java.time.LocalDate;

/** One review shown on the profile Reviews tab. */
public final class ProfileReviewRow {
    private final String reviewerMaskedName;
    private final int rating;
    private final String comment;
    private final LocalDate reviewDate;
    private final String auctionTitle;

    public ProfileReviewRow(String reviewerMaskedName, int rating, String comment,
                            LocalDate reviewDate, String auctionTitle) {
        this.reviewerMaskedName = reviewerMaskedName;
        this.rating = rating;
        this.comment = comment;
        this.reviewDate = reviewDate;
        this.auctionTitle = auctionTitle;
    }

    public String getReviewerMaskedName() {
        return reviewerMaskedName;
    }

    public int getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public LocalDate getReviewDate() {
        return reviewDate;
    }

    public String getAuctionTitle() {
        return auctionTitle;
    }
}
