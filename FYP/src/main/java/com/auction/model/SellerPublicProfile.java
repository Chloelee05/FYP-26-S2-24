package com.auction.model;

import java.time.Instant;

/**
 * Public seller profile projection for buyer trust assessment (SCRUM-63).
 *
 * <p>Contains only fields safe for unauthenticated public display — no password,
 * phone, address, 2FA secret, or raw email. Contact email is pre-masked via
 * {@link com.auction.util.SecurityUtil#maskEmail(String)}.</p>
 */
public final class SellerPublicProfile {

    private final long sellerId;
    private final String username;
    /** Masked contact email — never the raw value from the database. */
    private final String maskedEmail;
    private final Instant memberSince;
    private final String profileImageUrl;
    private final int activeListingCount;

    public SellerPublicProfile(long sellerId, String username, String maskedEmail,
                               Instant memberSince, String profileImageUrl,
                               int activeListingCount) {
        this.sellerId = sellerId;
        this.username = username;
        this.maskedEmail = maskedEmail;
        this.memberSince = memberSince;
        this.profileImageUrl = profileImageUrl;
        this.activeListingCount = activeListingCount;
    }

    public long getSellerId() { return sellerId; }
    public String getUsername() { return username; }
    public String getMaskedEmail() { return maskedEmail; }
    public Instant getMemberSince() { return memberSince; }
    public String getProfileImageUrl() { return profileImageUrl; }
    public int getActiveListingCount() { return activeListingCount; }

    /** JSP-friendly date for {@code fmt:formatDate}. */
    public java.util.Date getMemberSinceDate() {
        return memberSince == null ? null : java.util.Date.from(memberSince);
    }
}
