package com.auction.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Human-readable relative labels for activity feeds (English, coarse granularity). */
public final class RelativeTime {

    private RelativeTime() {}

    /**
     * Describes how long ago {@code at} was, in the coarsest unit that still reads
     * sensibly: minutes within the hour, hours within the day, days within the week, and
     * an absolute date beyond that. Something a fortnight old is more usefully given as a
     * date than as "14 days ago".
     *
     * <p>The comparison instant is passed in rather than read from the system clock, which
     * makes the formatting testable. The zone only affects the day boundary and the final
     * date, so a timestamp late last night reads as one day ago rather than a few hours.</p>
     */
    public static String format(Instant at, Instant now, ZoneId zone) {
        if (at == null) {
            return "";
        }
        Duration d = Duration.between(at, now);
        // A timestamp slightly in the future, from clock skew between the app and the
        // database, reads as "just now" rather than as a negative age.
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
