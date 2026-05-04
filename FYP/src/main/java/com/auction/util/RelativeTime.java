package com.auction.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Human-readable relative labels for activity feeds (English, coarse granularity). */
public final class RelativeTime {

    private RelativeTime() {}

    public static String format(Instant at, Instant now, ZoneId zone) {
        if (at == null) {
            return "";
        }
        Duration d = Duration.between(at, now);
        if (d.isNegative()) {
            d = Duration.ZERO;
        }
        long minutes = d.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " minute" + (minutes == 1 ? "" : "s") + " ago";
        }
        long hours = d.toHours();
        if (hours < 24) {
            return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        }
        long days = ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate());
        if (days < 7) {
            return days + " day" + (days == 1 ? "" : "s") + " ago";
        }
        return DateTimeFormatter.ISO_LOCAL_DATE.withZone(zone).format(at);
    }
}
