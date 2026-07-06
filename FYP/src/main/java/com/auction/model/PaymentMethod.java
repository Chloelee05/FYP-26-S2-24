package com.auction.model;

import java.time.Instant;

/**
 * A stored payment method. Supports three types (see {@code methodType}):
 * <ul>
 *   <li>{@code CARD} — a credit/debit card. The full PAN is held AES-GCM encrypted
 *       in the DB and never exposed here; only the brand and last 4 digits are shown.</li>
 *   <li>{@code PAYPAL} — a linked PayPal account, identified by {@code accountRef} (email).</li>
 *   <li>{@code BANK_TRANSFER} — a bank account; {@code accountRef} holds the bank name and
 *       {@code last4} the last 4 digits of the account number (full number encrypted).</li>
 * </ul>
 */
public final class PaymentMethod {

    private final long id;
    private final String methodType;   // CARD | PAYPAL | BANK_TRANSFER
    private final String cardHolder;
    private final String cardBrand;
    private final String last4;
    private final int expMonth;
    private final int expYear;
    private final String accountRef;   // PayPal email or bank name (null for cards)
    private final boolean isDefault;
    private final Instant createdAt;

    public PaymentMethod(long id, String methodType, String cardHolder, String cardBrand, String last4,
                         int expMonth, int expYear, String accountRef, boolean isDefault, Instant createdAt) {
        this.id = id;
        this.methodType = methodType == null ? "CARD" : methodType;
        this.cardHolder = cardHolder;
        this.cardBrand = cardBrand;
        this.last4 = last4;
        this.expMonth = expMonth;
        this.expYear = expYear;
        this.accountRef = accountRef;
        this.isDefault = isDefault;
        this.createdAt = createdAt;
    }

    /** Backwards-compatible constructor for card-only rows. */
    public PaymentMethod(long id, String cardHolder, String cardBrand, String last4,
                         int expMonth, int expYear, boolean isDefault, Instant createdAt) {
        this(id, "CARD", cardHolder, cardBrand, last4, expMonth, expYear, null, isDefault, createdAt);
    }

    public long getId()          { return id; }
    public String getMethodType(){ return methodType; }
    public String getCardHolder(){ return cardHolder; }
    public String getCardBrand() { return cardBrand; }
    public String getLast4()     { return last4; }
    public int getExpMonth()     { return expMonth; }
    public int getExpYear()      { return expYear; }
    public String getAccountRef(){ return accountRef; }
    public boolean isDefault()   { return isDefault; }
    public Instant getCreatedAt(){ return createdAt; }

    /** Human-readable one-line label for receipts and the account UI. */
    public String getDisplayLabel() { return label(methodType, cardBrand, last4, accountRef); }

    /** Builds a display label from the raw column values (shared with the DAO). */
    public static String label(String methodType, String cardBrand, String last4, String accountRef) {
        String type = methodType == null ? "CARD" : methodType;
        switch (type) {
            case "PAYPAL":
                return accountRef != null && !accountRef.isBlank()
                        ? "PayPal (" + accountRef + ")" : "PayPal";
            case "BANK_TRANSFER":
                String bank = accountRef != null && !accountRef.isBlank() ? accountRef : "Bank";
                return last4 != null && !last4.isBlank()
                        ? bank + " account ****" + last4 : bank + " transfer";
            case "CARD":
            default:
                String brand = cardBrand != null && !cardBrand.isBlank() ? cardBrand : "Card";
                return last4 != null && !last4.isBlank() ? brand + " ****" + last4 : brand;
        }
    }
}
