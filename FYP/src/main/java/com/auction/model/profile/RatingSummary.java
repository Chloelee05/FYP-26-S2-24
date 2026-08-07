package com.auction.model.profile;

/** Aggregated ratings for the profile sidebar (star bars + average). */
public final class RatingSummary {
    private final double average;
    private final int reviewCount;
    /** Index 0 = 5-star count, index 4 = 1-star count. */
    private final int[] starCountsHighToLow;
    /** Derived from the counts at construction so the view does no arithmetic. */
    private final int[] barWidthsPercent;

    public RatingSummary(double average, int reviewCount, int[] starCountsHighToLow) {
        this.average = average;
        this.reviewCount = reviewCount;
        this.starCountsHighToLow = starCountsHighToLow;
        this.barWidthsPercent = computeBarWidths(starCountsHighToLow);
    }

    /**
     * Scales each star count against the largest one, so the most common rating fills its
     * bar completely and the rest are drawn relative to it. Scaling against the total
     * instead would leave every bar short on a profile where opinion is split.
     *
     * <p>All zeroes when there are no reviews at all, which avoids dividing by zero and
     * renders as five empty bars.</p>
     */
    private static int[] computeBarWidths(int[] counts) {
        int m = 0;
        for (int c : counts) {
            if (c > m) m = c;
        }
        int[] w = new int[5];
        if (m == 0) {
            return w;
        }
        for (int i = 0; i < 5; i++) {
            w[i] = (100 * counts[i]) / m;
        }
        return w;
    }

    public int[] getBarWidthsPercent() {
        return barWidthsPercent;
    }

    public double getAverage() {
        return average;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public int[] getStarCountsHighToLow() {
        return starCountsHighToLow;
    }

    public int getMaxStarBarCount() {
        int m = 0;
        for (int c : starCountsHighToLow) {
            if (c > m) m = c;
        }
        return m;
    }
}
