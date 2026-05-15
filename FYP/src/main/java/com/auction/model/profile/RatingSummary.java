package com.auction.model.profile;

/** Aggregated ratings for the profile sidebar (star bars + average). */
public final class RatingSummary {
    private final double average;
    private final int reviewCount;
    /** Index 0 = 5-star count, index 4 = 1-star count. */
    private final int[] starCountsHighToLow;
    private final int[] barWidthsPercent;

    public RatingSummary(double average, int reviewCount, int[] starCountsHighToLow) {
        this.average = average;
        this.reviewCount = reviewCount;
        this.starCountsHighToLow = starCountsHighToLow;
        this.barWidthsPercent = computeBarWidths(starCountsHighToLow);
    }

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
