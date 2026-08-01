package com.auction.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tuning of the seller price feed: the two cooldown rates, the window that switches
 * between them, and the parsing that keeps a mistyped environment variable from silently
 * turning coalescing off.
 */
@DisplayName("TelegramConfig — seller price cooldown")
class TelegramPriceCooldownTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Nested
    @DisplayName("Which rate applies")
    class Rate {

        @Test
        @DisplayName("Outside the endgame window, bids coalesce at the normal rate")
        void normalRateWellBeforeTheClose() {
            Instant endsInAnHour = NOW.plusSeconds(3600);
            assertEquals(120, TelegramConfig.cooldownFor(endsInAnHour, NOW, 120, 30, 10));
        }

        @Test
        @DisplayName("Inside the endgame window, the feed tightens")
        void endgameRateNearTheClose() {
            Instant endsInFiveMinutes = NOW.plusSeconds(5 * 60);
            assertEquals(30, TelegramConfig.cooldownFor(endsInFiveMinutes, NOW, 120, 30, 10));
        }

        @Test
        @DisplayName("The boundary is the window itself, not a moment either side of it")
        void boundaryIsExact() {
            Instant endsInTenMinutes = NOW.plusSeconds(10 * 60);
            // Exactly on the boundary is already the endgame: now is not *before* it.
            assertEquals(30, TelegramConfig.cooldownFor(endsInTenMinutes, NOW, 120, 30, 10));
            assertEquals(120, TelegramConfig.cooldownFor(
                    endsInTenMinutes.plusSeconds(1), NOW, 120, 30, 10));
        }

        @Test
        @DisplayName("An auction already past its end is treated as endgame, not slowed down")
        void pastEndIsEndgame() {
            assertEquals(30, TelegramConfig.cooldownFor(NOW.minusSeconds(30), NOW, 120, 30, 10));
        }

        @Test
        @DisplayName("An unknown end date gets the quieter rate rather than a guess")
        void unknownEndFallsBackToNormal() {
            assertEquals(120, TelegramConfig.cooldownFor(null, NOW, 120, 30, 10));
        }

        @Test
        @DisplayName("A wider configured window moves the boundary with it")
        void windowIsHonoured() {
            Instant endsInThirtyMinutes = NOW.plusSeconds(30 * 60);
            assertEquals(120, TelegramConfig.cooldownFor(endsInThirtyMinutes, NOW, 120, 30, 10));
            assertEquals(30, TelegramConfig.cooldownFor(endsInThirtyMinutes, NOW, 120, 30, 60));
        }
    }

    @Nested
    @DisplayName("Reading the environment")
    class Parsing {

        @Test
        @DisplayName("The documented defaults are what an unconfigured deployment gets")
        void defaultsAreTheApprovedValues() {
            assertEquals(120, TelegramConfig.DEFAULT_PRICE_COOLDOWN_SECONDS);
            assertEquals(30, TelegramConfig.DEFAULT_PRICE_COOLDOWN_ENDGAME_SECONDS);
            assertEquals(10, TelegramConfig.DEFAULT_ENDGAME_WINDOW_MINUTES);
        }

        @Test
        @DisplayName("A configured value overrides the default")
        void explicitValueWins() {
            assertEquals(45, TelegramConfig.positiveInt("45", 120));
            assertEquals(45, TelegramConfig.positiveInt("  45  ", 120));
        }

        @Test
        @DisplayName("Unset, blank, unparseable and non-positive all fall back")
        void badValuesFallBackRatherThanBreakingTheFeature() {
            assertEquals(120, TelegramConfig.positiveInt(null, 120));
            assertEquals(120, TelegramConfig.positiveInt("   ", 120));
            assertEquals(120, TelegramConfig.positiveInt("two minutes", 120));
            // Zero is the dangerous one: it would send a message per bid without erroring.
            assertEquals(120, TelegramConfig.positiveInt("0", 120));
            assertEquals(120, TelegramConfig.positiveInt("-30", 120));
        }
    }
}
