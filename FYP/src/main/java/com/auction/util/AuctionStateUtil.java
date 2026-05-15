package com.auction.util;

import com.auction.model.AuctionStatus;

import java.time.Instant;

/**
 * SCRUM-36 – Helpers for the auction state machine.
 *
 * State transitions (status-based):
 *   PENDING  (4)  → scheduled / not yet started
 *   ACTIVE   (1)  → live / accepting bids
 *   FINISHED (2)  → ended normally
 *   CANCELLED(3)  → ended early by seller
 *
 * Time-based variants are provided for when the DB status may be stale
 * (i.e. before an optional status-update scheduler has run).
 * All Instant comparisons use millisecond granularity — no sub-ms clock skew risk.
 */
public final class AuctionStateUtil {

    private AuctionStateUtil() {}

    // ------------------------------------------------------------------ status-based

    /** True when the auction has not yet opened for bidding. */
    public static boolean isScheduled(AuctionStatus status) {
        return status == AuctionStatus.PENDING;
    }

    /** True when the auction is open and accepting bids. */
    public static boolean isLive(AuctionStatus status) {
        return status == AuctionStatus.ACTIVE;
    }

    /** True when the auction has concluded (normally finished or seller-cancelled). */
    public static boolean isEnded(AuctionStatus status) {
        return status == AuctionStatus.FINISHED || status == AuctionStatus.CANCELLED;
    }

    // ------------------------------------------------------------------ time-based (wall-clock)

    /**
     * True when {@code now} is before the auction's {@code startDate}.
     * Use this when the persisted status_id may not yet reflect the real-time state.
     */
    public static boolean isScheduledByTime(Instant now, Instant startDate) {
        return now.isBefore(startDate);
    }

    /**
     * True when {@code now} is within [startDate, endDate).
     */
    public static boolean isLiveByTime(Instant now, Instant startDate, Instant endDate) {
        return !now.isBefore(startDate) && now.isBefore(endDate);
    }

    /**
     * True when {@code now} is at or after {@code endDate}.
     */
    public static boolean isEndedByTime(Instant now, Instant endDate) {
        return !now.isBefore(endDate);
    }
}
