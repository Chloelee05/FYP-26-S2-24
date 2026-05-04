package com.auction.model.profile;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row in the profile transaction history table. */
public final class ProfileTransactionRow {
    private final String displayId;
    private final LocalDate transactionDate;
    private final String itemTitle;
    /** {@code purchase} or {@code sale} */
    private final String transactionType;
    private final BigDecimal amount;
    /** {@code Completed} or {@code Pending} */
    private final String status;

    public ProfileTransactionRow(String displayId, LocalDate transactionDate, String itemTitle,
                                 String transactionType, BigDecimal amount, String status) {
        this.displayId = displayId;
        this.transactionDate = transactionDate;
        this.itemTitle = itemTitle;
        this.transactionType = transactionType;
        this.amount = amount;
        this.status = status;
    }

    public String getDisplayId() {
        return displayId;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public String getItemTitle() {
        return itemTitle;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }
}
