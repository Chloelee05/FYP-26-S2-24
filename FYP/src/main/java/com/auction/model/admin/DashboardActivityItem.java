package com.auction.model.admin;

/**
 * Single line in the recent activity feed ({@code severity}: success | warning | danger).
 */
public final class DashboardActivityItem {
    private final String severity;
    private final String message;
    private final String timeLabel;

    public DashboardActivityItem(String severity, String message, String timeLabel) {
        this.severity = severity;
        this.message = message;
        this.timeLabel = timeLabel;
    }

    public String getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public String getTimeLabel() {
        return timeLabel;
    }
}
