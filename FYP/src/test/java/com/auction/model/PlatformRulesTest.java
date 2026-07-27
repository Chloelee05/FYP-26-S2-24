package com.auction.model;

import com.auction.model.admin.PlatformRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the platform auction rules (SCRUM-69).
 *
 * <p>Covers the pure rule arithmetic that {@code BidDAO}, {@code SellerApiServlet},
 * and {@code CreateAuctionServlet} all delegate to, plus the admin input validation
 * used by {@code POST /api/admin/rules}.</p>
 */
@DisplayName("PlatformRules")
class PlatformRulesTest {

    private static PlatformRules rules(String increment, int days) {
        return new PlatformRules(new BigDecimal(increment), days, null, null);
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("are $1.00 and 30 days")
        void defaultValues() {
            PlatformRules defaults = PlatformRules.defaults();
            assertEquals(0, new BigDecimal("1.00").compareTo(defaults.getMinBidIncrement()));
            assertEquals(30, defaults.getMaxAuctionDurationDays());
            assertNull(defaults.getUpdatedAt());
            assertNull(defaults.getUpdatedBy());
        }

        @Test
        @DisplayName("constructor clamps an increment below one cent up to one cent")
        void clampsIncrementFloor() {
            assertEquals(0, PlatformRules.MIN_ALLOWED_BID_INCREMENT
                    .compareTo(rules("0.001", 30).getMinBidIncrement()));
            assertEquals(0, PlatformRules.MIN_ALLOWED_BID_INCREMENT
                    .compareTo(new PlatformRules(null, 30, null, null).getMinBidIncrement()));
        }

        @Test
        @DisplayName("constructor clamps a duration below one day up to one day")
        void clampsDurationFloor() {
            assertEquals(1, rules("1.00", 0).getMaxAuctionDurationDays());
            assertEquals(1, rules("1.00", -5).getMaxAuctionDurationDays());
        }
    }

    @Nested
    @DisplayName("minimum bid increment")
    class MinIncrement {

        @Test
        @DisplayName("minimum acceptable bid is the current price plus the increment")
        void minimumAcceptableBid() {
            PlatformRules r = rules("5.00", 30);
            assertEquals(0, new BigDecimal("105.00").compareTo(r.minimumAcceptableBid(new BigDecimal("100.00"))));
        }

        @Test
        @DisplayName("a null current price is treated as zero")
        void nullCurrentPrice() {
            assertEquals(0, new BigDecimal("5.00").compareTo(rules("5.00", 30).minimumAcceptableBid(null)));
        }

        @Test
        @DisplayName("a bid exactly one increment above the price is accepted")
        void exactIncrementAccepted() {
            assertTrue(rules("5.00", 30)
                    .meetsMinBidIncrement(new BigDecimal("105.00"), new BigDecimal("100.00")));
        }

        @Test
        @DisplayName("a bid above the price but short of the increment is rejected")
        void shortOfIncrementRejected() {
            PlatformRules r = rules("5.00", 30);
            assertFalse(r.meetsMinBidIncrement(new BigDecimal("104.99"), new BigDecimal("100.00")));
            assertFalse(r.meetsMinBidIncrement(new BigDecimal("100.01"), new BigDecimal("100.00")));
        }

        @Test
        @DisplayName("an equal or lower bid is rejected")
        void equalOrLowerRejected() {
            PlatformRules r = rules("1.00", 30);
            assertFalse(r.meetsMinBidIncrement(new BigDecimal("100.00"), new BigDecimal("100.00")));
            assertFalse(r.meetsMinBidIncrement(new BigDecimal("99.00"), new BigDecimal("100.00")));
        }

        @Test
        @DisplayName("a null bid is rejected")
        void nullBidRejected() {
            assertFalse(rules("1.00", 30).meetsMinBidIncrement(null, new BigDecimal("100.00")));
        }

        @Test
        @DisplayName("trailing zeros do not change the comparison")
        void scaleIndependent() {
            assertTrue(rules("1.00", 30)
                    .meetsMinBidIncrement(new BigDecimal("101"), new BigDecimal("100.00")));
        }
    }

    @Nested
    @DisplayName("maximum auction duration")
    class MaxDuration {

        private final Instant start = Instant.parse("2026-01-01T00:00:00Z");

        @Test
        @DisplayName("a window shorter than the maximum is allowed")
        void withinLimit() {
            assertFalse(rules("1.00", 7).exceedsMaxDuration(start, start.plus(6, ChronoUnit.DAYS)));
        }

        @Test
        @DisplayName("a window exactly at the maximum is allowed")
        void exactlyAtLimit() {
            assertFalse(rules("1.00", 7).exceedsMaxDuration(start, start.plus(7, ChronoUnit.DAYS)));
        }

        @Test
        @DisplayName("one second past the maximum is rejected")
        void justOverLimit() {
            assertTrue(rules("1.00", 7)
                    .exceedsMaxDuration(start, start.plus(7, ChronoUnit.DAYS).plusSeconds(1)));
        }

        @Test
        @DisplayName("latestAllowedEnd is the start plus the configured days")
        void latestAllowedEnd() {
            assertEquals(start.plus(14, ChronoUnit.DAYS), rules("1.00", 14).latestAllowedEnd(start));
        }

        @Test
        @DisplayName("an end before the start is not reported as a duration violation")
        void invertedWindow() {
            assertFalse(rules("1.00", 7).exceedsMaxDuration(start, start.minusSeconds(1)));
        }

        @Test
        @DisplayName("null bounds are not reported as a duration violation")
        void nullBounds() {
            PlatformRules r = rules("1.00", 7);
            assertFalse(r.exceedsMaxDuration(null, start));
            assertFalse(r.exceedsMaxDuration(start, null));
        }
    }

    @Nested
    @DisplayName("admin input validation")
    class AdminValidation {

        @Test
        @DisplayName("accepts an increment inside the allowed range")
        void incrementAccepted() {
            assertNull(PlatformRules.violationForBidIncrement(new BigDecimal("0.01")));
            assertNull(PlatformRules.violationForBidIncrement(new BigDecimal("2.50")));
            assertNull(PlatformRules.violationForBidIncrement(PlatformRules.MAX_ALLOWED_BID_INCREMENT));
        }

        @Test
        @DisplayName("rejects a null, zero, negative, or oversized increment")
        void incrementRejected() {
            assertNotNull(PlatformRules.violationForBidIncrement(null));
            assertNotNull(PlatformRules.violationForBidIncrement(BigDecimal.ZERO));
            assertNotNull(PlatformRules.violationForBidIncrement(new BigDecimal("-1.00")));
            assertNotNull(PlatformRules.violationForBidIncrement(
                    PlatformRules.MAX_ALLOWED_BID_INCREMENT.add(BigDecimal.ONE)));
        }

        @Test
        @DisplayName("rejects an increment with sub-cent precision")
        void incrementPrecisionRejected() {
            assertNotNull(PlatformRules.violationForBidIncrement(new BigDecimal("1.005")));
            assertNull(PlatformRules.violationForBidIncrement(new BigDecimal("1.5000")));
        }

        @Test
        @DisplayName("accepts a duration inside the allowed range")
        void durationAccepted() {
            assertNull(PlatformRules.violationForDurationDays(PlatformRules.MIN_ALLOWED_DURATION_DAYS));
            assertNull(PlatformRules.violationForDurationDays(30));
            assertNull(PlatformRules.violationForDurationDays(PlatformRules.MAX_ALLOWED_DURATION_DAYS));
        }

        @Test
        @DisplayName("rejects a duration outside the allowed range")
        void durationRejected() {
            assertNotNull(PlatformRules.violationForDurationDays(0));
            assertNotNull(PlatformRules.violationForDurationDays(-1));
            assertNotNull(PlatformRules.violationForDurationDays(
                    PlatformRules.MAX_ALLOWED_DURATION_DAYS + 1));
        }
    }
}
