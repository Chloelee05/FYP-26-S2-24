package com.auction.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RelativeTime – activity feed labels")
class RelativeTimeTest {

    private static final ZoneId SG = ZoneId.of("Asia/Singapore");

    @Test
    @DisplayName("null instant → empty string")
    void nullInstant() {
        assertEquals("", RelativeTime.format(null, Instant.now(), SG));
    }

    @Test
    @DisplayName("under 1 minute → just now")
    void justNow() {
        Instant now = Instant.parse("2026-06-11T12:00:00Z");
        assertEquals("just now", RelativeTime.format(now.minusSeconds(30), now, SG));
    }

    @Test
    @DisplayName("minutes ago")
    void minutesAgo() {
        Instant now = Instant.parse("2026-06-11T12:00:00Z");
        assertEquals("5 minutes ago", RelativeTime.format(now.minusSeconds(300), now, SG));
        assertEquals("1 minute ago", RelativeTime.format(now.minusSeconds(60), now, SG));
    }

    @Test
    @DisplayName("hours ago")
    void hoursAgo() {
        Instant now = Instant.parse("2026-06-11T12:00:00Z");
        assertEquals("2 hours ago", RelativeTime.format(now.minusSeconds(7200), now, SG));
    }

    @Test
    @DisplayName("days ago")
    void daysAgo() {
        Instant now = Instant.parse("2026-06-11T12:00:00Z");
        assertEquals("3 days ago", RelativeTime.format(now.minusSeconds(3 * 86400L), now, SG));
    }

    @Test
    @DisplayName("future instant clamped to just now")
    void futureClamped() {
        Instant now = Instant.parse("2026-06-11T12:00:00Z");
        assertEquals("just now", RelativeTime.format(now.plusSeconds(600), now, SG));
    }
}
