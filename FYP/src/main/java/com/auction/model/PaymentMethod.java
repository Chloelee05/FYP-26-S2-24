package com.auction.model;

import java.time.Instant;

/**
 * A stored payment card. The full PAN is held AES-GCM encrypted in the DB and is
 * never exposed here — only the brand and last 4 digits are shown to the user.
 */
public final class PaymentMethod {

    private final long id;
    private final String cardHolder;
    private final String cardBrand;
    private final String last4;
    private final int expMonth;
    private final int expYear;
    private final boolean isDefault;
    private final Instant createdAt;

    public PaymentMethod(long id, String cardHolder, String cardBrand, String last4,
                         int expMonth, int expYear, boolean isDefault, Instant createdAt) {
        this.id = id;
        this.cardHolder = cardHolder;
        this.cardBrand = cardBrand;
        this.last4 = last4;
        this.expMonth = expMonth;
        this.expYear = expYear;
        this.isDefault = isDefault;
        this.createdAt = createdAt;
    }

    public long getId()          { return id; }
    public String getCardHolder(){ return cardHolder; }
    public String getCardBrand() { return cardBrand; }
    public String getLast4()     { return last4; }
    public int getExpMonth()     { return expMonth; }
    public int getExpYear()      { return expYear; }
    public boolean isDefault()   { return isDefault; }
    public Instant getCreatedAt(){ return createdAt; }
}
