package com.auction.model;

import java.math.BigDecimal;

/**
 * Immutable value object grouping all optional filter parameters for the buyer search
 * (SCRUM-59: price range, item condition, location, end-time window).
 *
 * <p>All fields are nullable — {@code null} means "no filter on this dimension".
 * Use {@link Builder} to construct instances.</p>
 *
 * <p><b>Security (SCRUM-345):</b> Every field is validated before being placed in this
 * object (negative prices dropped, condition validated against the {@link ItemCondition}
 * whitelist, location trimmed and length-capped). The values are passed to the DAO as
 * {@code PreparedStatement} parameters — never concatenated into SQL.</p>
 */
public final class SearchFilter {

    /** Minimum current price (inclusive); {@code null} = no lower bound. */
    private final BigDecimal minPrice;

    /** Maximum current price (inclusive); {@code null} = no upper bound. */
    private final BigDecimal maxPrice;

    /**
     * {@link ItemCondition#getId()} value to match; {@code null} = any condition.
     * Always derived from the {@link ItemCondition} enum, never from a raw client string.
     */
    private final Integer itemConditionId;

    /**
     * Free-text location hint; searched against title and description via {@code ILIKE}.
     * {@code null} = no location filter. Already trimmed and length-validated by the servlet.
     */
    private final String location;

    /**
     * Auction must end within this many hours from now; {@code null} = any end time.
     * Must be a positive integer when set.
     */
    private final Integer endWithinHours;

    private SearchFilter(Builder b) {
        this.minPrice       = b.minPrice;
        this.maxPrice       = b.maxPrice;
        this.itemConditionId = b.itemConditionId;
        this.location       = b.location;
        this.endWithinHours = b.endWithinHours;
    }

    public BigDecimal getMinPrice()       { return minPrice; }
    public BigDecimal getMaxPrice()       { return maxPrice; }
    public Integer    getItemConditionId(){ return itemConditionId; }
    public String     getLocation()       { return location; }
    public Integer    getEndWithinHours() { return endWithinHours; }

    /** Returns {@code true} when no filter dimension is active. */
    public boolean isEmpty() {
        return minPrice == null && maxPrice == null
                && itemConditionId == null && location == null && endWithinHours == null;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        private Integer    itemConditionId;
        private String     location;
        private Integer    endWithinHours;

        public Builder minPrice(BigDecimal v)       { this.minPrice        = v; return this; }
        public Builder maxPrice(BigDecimal v)       { this.maxPrice        = v; return this; }
        public Builder itemConditionId(Integer v)   { this.itemConditionId = v; return this; }
        public Builder location(String v)           { this.location        = v; return this; }
        public Builder endWithinHours(Integer v)    { this.endWithinHours  = v; return this; }
        public SearchFilter build()                 { return new SearchFilter(this); }
    }
}
