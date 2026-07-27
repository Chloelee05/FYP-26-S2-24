package com.auction.model.admin;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Platform-wide auction rules set by admins (SCRUM-66 / SCRUM-67).
 *
 * <p>Immutable snapshot of the single {@code platform_rules} row:</p>
 * <ul>
 *   <li>{@code minBidIncrement} — the smallest amount a new bid must beat the current
 *       price by, so every auction steps up consistently.</li>
 *   <li>{@code maxAuctionDurationDays} — the longest span a seller may schedule between
 *       an auction's start and end.</li>
 * </ul>
 *
 * <p>All rule checks live here as pure functions so they can be unit-tested without a
 * database (SCRUM-69) and so the servlet, DAO, and legacy JSP paths all apply exactly
 * the same arithmetic.</p>
 */
public final class PlatformRules {

    /** Hard floor for the increment: one cent, matching {@code bids.bid_amount NUMERIC(10,2)}. */
    public static final BigDecimal MIN_ALLOWED_BID_INCREMENT = new BigDecimal("0.01");

    /** Hard ceiling for the increment; keeps a typo from freezing all bidding. */
    public static final BigDecimal MAX_ALLOWED_BID_INCREMENT = new BigDecimal("10000.00");

    /** Shortest maximum-duration an admin may configure. */
    public static final int MIN_ALLOWED_DURATION_DAYS = 1;

    /** Longest maximum-duration an admin may configure. */
    public static final int MAX_ALLOWED_DURATION_DAYS = 365;

    /** Default increment used when the rules row (or the whole table) is missing. */
    public static final BigDecimal DEFAULT_MIN_BID_INCREMENT = new BigDecimal("1.00");

    /** Default maximum duration used when the rules row (or the whole table) is missing. */
    public static final int DEFAULT_MAX_AUCTION_DURATION_DAYS = 30;

    private final BigDecimal minBidIncrement;
    private final int maxAuctionDurationDays;
    private final Instant updatedAt;
    private final Integer updatedBy;

    public PlatformRules(BigDecimal minBidIncrement, int maxAuctionDurationDays,
                         Instant updatedAt, Integer updatedBy) {
        this.minBidIncrement = (minBidIncrement != null && minBidIncrement.compareTo(MIN_ALLOWED_BID_INCREMENT) >= 0)
                ? minBidIncrement : MIN_ALLOWED_BID_INCREMENT;
        this.maxAuctionDurationDays = Math.max(MIN_ALLOWED_DURATION_DAYS, maxAuctionDurationDays);
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /** The platform defaults, used whenever the configuration row cannot be read. */
    public static PlatformRules defaults() {
        return new PlatformRules(DEFAULT_MIN_BID_INCREMENT, DEFAULT_MAX_AUCTION_DURATION_DAYS, null, null);
    }

    public BigDecimal getMinBidIncrement()   { return minBidIncrement; }
    public int getMaxAuctionDurationDays()   { return maxAuctionDurationDays; }
    public Instant getUpdatedAt()            { return updatedAt; }
    public Integer getUpdatedBy()            { return updatedBy; }

    // -------------------------------------------------------------------------
    // Minimum bid increment
    // -------------------------------------------------------------------------

    /**
     * The lowest bid that clears the increment rule for a given price floor.
     *
     * @param currentPrice the price to beat — the highest bid so far, or the starting
     *                     price while an auction has no bids
     * @return {@code currentPrice + minBidIncrement}
     */
    public BigDecimal minimumAcceptableBid(BigDecimal currentPrice) {
        BigDecimal floor = currentPrice != null ? currentPrice : BigDecimal.ZERO;
        return floor.add(minBidIncrement);
    }

    /**
     * {@code true} when {@code bidAmount} beats {@code currentPrice} by at least the
     * configured increment. Equal bids never pass.
     */
    public boolean meetsMinBidIncrement(BigDecimal bidAmount, BigDecimal currentPrice) {
        return bidAmount != null && bidAmount.compareTo(minimumAcceptableBid(currentPrice)) >= 0;
    }

    // -------------------------------------------------------------------------
    // Maximum auction duration
    // -------------------------------------------------------------------------

    /** The latest end instant a seller may schedule for an auction starting at {@code start}. */
    public Instant latestAllowedEnd(Instant start) {
        return start.plus(maxAuctionDurationDays, ChronoUnit.DAYS);
    }

    /**
     * {@code true} when the requested window is longer than the configured maximum.
     * A window that ends before it starts is not a duration violation — callers report
     * that separately.
     */
    public boolean exceedsMaxDuration(Instant start, Instant end) {
        if (start == null || end == null || end.isBefore(start)) return false;
        return Duration.between(start, end).compareTo(Duration.ofDays(maxAuctionDurationDays)) > 0;
    }

    // -------------------------------------------------------------------------
    // Admin input validation (SCRUM-67)
    // -------------------------------------------------------------------------

    /**
     * Validates an admin-supplied minimum bid increment.
     *
     * @return the violation message, or {@code null} when the value is acceptable
     */
    public static String violationForBidIncrement(BigDecimal increment) {
        if (increment == null) {
            return "Minimum bid increment is required.";
        }
        if (increment.compareTo(MIN_ALLOWED_BID_INCREMENT) < 0) {
            return "Minimum bid increment must be at least $" + MIN_ALLOWED_BID_INCREMENT.toPlainString() + ".";
        }
        if (increment.compareTo(MAX_ALLOWED_BID_INCREMENT) > 0) {
            return "Minimum bid increment cannot exceed $" + MAX_ALLOWED_BID_INCREMENT.toPlainString() + ".";
        }
        if (increment.stripTrailingZeros().scale() > 2) {
            return "Minimum bid increment cannot have more than 2 decimal places.";
        }
        return null;
    }

    /**
     * Validates an admin-supplied maximum auction duration in days.
     *
     * @return the violation message, or {@code null} when the value is acceptable
     */
    public static String violationForDurationDays(int days) {
        if (days < MIN_ALLOWED_DURATION_DAYS) {
            return "Maximum auction duration must be at least " + MIN_ALLOWED_DURATION_DAYS + " day.";
        }
        if (days > MAX_ALLOWED_DURATION_DAYS) {
            return "Maximum auction duration cannot exceed " + MAX_ALLOWED_DURATION_DAYS + " days.";
        }
        return null;
    }
}
