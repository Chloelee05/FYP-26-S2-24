package com.auction.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

@DisplayName("DutchClock – descending price math")
class DutchClockTest {

    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant end   = Instant.parse("2026-01-01T10:00:00Z");
    private final BigDecimal high = new BigDecimal("1000");
    private final BigDecimal floor = new BigDecimal("200");

    @Test
    @DisplayName("at or before start → starting price")
    void atStart() {
        assertEquals(new BigDecimal("1000.00"), DutchClock.currentPrice(high, floor, start, end, start));
        Instant before = start.minusSeconds(60);
        assertEquals(new BigDecimal("1000.00"), DutchClock.currentPrice(high, floor, start, end, before));
    }

    @Test
    @DisplayName("at or after end → floor price")
    void atEnd() {
        assertEquals(new BigDecimal("200.00"), DutchClock.currentPrice(high, floor, start, end, end));
        Instant after = end.plusSeconds(60);
        assertEquals(new BigDecimal("200.00"), DutchClock.currentPrice(high, floor, start, end, after));
    }

    @Test
    @DisplayName("halfway → midpoint between start and floor")
    void halfway() {
        Instant mid = Instant.parse("2026-01-01T05:00:00Z");
        // 1000 - (1000-200)*0.5 = 600
        assertEquals(new BigDecimal("600.00"), DutchClock.currentPrice(high, floor, start, end, mid));
    }

    @Test
    @DisplayName("quarter elapsed → drops 25% of the span")
    void quarter() {
        Instant q = Instant.parse("2026-01-01T02:30:00Z");
        // 1000 - 800*0.25 = 800
        assertEquals(new BigDecimal("800.00"), DutchClock.currentPrice(high, floor, start, end, q));
    }

    @Test
    @DisplayName("null floor treated as zero")
    void nullFloor() {
        Instant mid = Instant.parse("2026-01-01T05:00:00Z");
        // 1000 - 1000*0.5 = 500
        assertEquals(new BigDecimal("500.00"), DutchClock.currentPrice(high, null, start, end, mid));
    }

    @Test
    @DisplayName("invalid window (end<=start) → starting price")
    void invalidWindow() {
        assertEquals(new BigDecimal("1000.00"),
                DutchClock.currentPrice(high, floor, end, start, Instant.now()));
    }

    @Test
    @DisplayName("floor above start is clamped to start (never negative drop)")
    void floorAboveStart() {
        Instant mid = Instant.parse("2026-01-01T05:00:00Z");
        BigDecimal result = DutchClock.currentPrice(high, new BigDecimal("5000"), start, end, mid);
        assertEquals(new BigDecimal("1000.00"), result);
    }
}
