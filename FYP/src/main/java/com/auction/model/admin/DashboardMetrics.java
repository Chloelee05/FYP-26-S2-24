package com.auction.model.admin;

/**
 * Overview stat cards for the admin dashboard.
 *
 * <p>Every field is an aggregate computed by {@code AdminDashboardDAO}, so nothing here
 * maps to a column. Each count is paired with its total because the cards show them
 * together, for example live listings out of all listings ever created.</p>
 */
public final class DashboardMetrics {
    private final int totalUsers;
    /** Accounts in {@code Status.ACTIVE}, so excluding pending, suspended and closed ones. */
    private final int activeUsers;
    private final int activeListings;
    private final int totalListings;
    /** Listings awaiting a moderation decision, which is the admin's work queue depth. */
    private final int flaggedListings;
    /** Completed sales value, already rounded to whole dollars for display. */
    private final long revenueDollars;
    /** Pre-formatted change against the previous period, for example {@code "+12%"}. */
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
