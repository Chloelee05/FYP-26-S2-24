package com.auction.model.admin;

/**
 * Overview stat cards for the admin dashboard.
 */
public final class DashboardMetrics {
    private final int totalUsers;
    private final int activeUsers;
    private final int activeListings;
    private final int totalListings;
    private final int flaggedListings;
    private final long revenueDollars;
    private final String revenueGrowthLabel;

    public DashboardMetrics(int totalUsers, int activeUsers, int activeListings, int totalListings,
                            int flaggedListings, long revenueDollars, String revenueGrowthLabel) {
        this.totalUsers = totalUsers;
        this.activeUsers = activeUsers;
        this.activeListings = activeListings;
        this.totalListings = totalListings;
        this.flaggedListings = flaggedListings;
        this.revenueDollars = revenueDollars;
        this.revenueGrowthLabel = revenueGrowthLabel;
    }

    public int getTotalUsers() {
        return totalUsers;
    }

    public int getActiveUsers() {
        return activeUsers;
    }

    public int getActiveListings() {
        return activeListings;
    }

    public int getTotalListings() {
        return totalListings;
    }

    public int getFlaggedListings() {
        return flaggedListings;
    }

    public long getRevenueDollars() {
        return revenueDollars;
    }

    public String getRevenueGrowthLabel() {
        return revenueGrowthLabel;
    }
}
