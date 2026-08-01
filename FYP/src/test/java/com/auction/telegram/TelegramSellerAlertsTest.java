package com.auction.telegram;

import com.auction.dao.LandingContentDAO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The seller-side alert bodies: what the price feed and the two result messages say, what
 * they deliberately do not say, and the queueing metadata that makes coalescing work.
 */
@DisplayName("TelegramAlerts — seller alerts")
class TelegramSellerAlertsTest {

    private static final long AUCTION_ID = 42L;

    @AfterEach
    void resetCopy() {
        TelegramCopy.setDao(new LandingContentDAO());
    }

    private static void copy(Map<String, String> rows) {
        LandingContentDAO dao = mock(LandingContentDAO.class);
        when(dao.findAllValues()).thenReturn(rows);
        TelegramCopy.setDao(dao);
    }

    private static MockedStatic<TelegramConfig> withoutBaseUrl() {
        MockedStatic<TelegramConfig> config = mockStatic(TelegramConfig.class);
        config.when(TelegramConfig::publicBaseUrl).thenReturn(null);
        return config;
    }

    @Nested
    @DisplayName("Privacy")
    class Privacy {

        @Test
        @DisplayName("The price feed will not even accept the bidder's identity")
        void priceFeedTakesNoBidder() throws Exception {
            // Structural, like outbid and lost: what cannot be passed in cannot leak.
            var method = TelegramAlerts.class.getMethod("sellerPrice",
                    long.class, String.class, BigDecimal.class, int.class, int.class);
            assertEquals(5, method.getParameterCount());
        }

        @Test
        @DisplayName("The price message reports the figure and the contest, not who is bidding")
        void priceMessageNamesNobody() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                String body = TelegramAlerts.sellerPrice(
                        AUCTION_ID, "Leica M6", new BigDecimal("410"), 12, 120).body;

                assertTrue(body.contains("Leica M6"), body);
                assertTrue(body.contains("$410.00"), body);
                assertTrue(body.contains("12 bids"), body);
                assertFalse(body.toLowerCase().contains("bidder is"), body);
                assertFalse(body.toLowerCase().contains(" by "), body);
            }
        }

        @Test
        @DisplayName("The sold message masks the winner rather than naming them")
        void soldMessageMasksTheWinner() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                String body = TelegramAlerts.sellerSold(
                        AUCTION_ID, "Leica M6", new BigDecimal("1899"), "chloelee").body;

                assertFalse(body.contains("chloelee"),
                        "the buyer's full identity belongs on the order page: " + body);
                assertTrue(body.contains("c***e"), body);
                assertTrue(body.contains("$1899.00"), body);
            }
        }

        @Test
        @DisplayName("Masking is applied inside the builder, so no call site can skip it")
        void maskingIsNotTheCallersJob() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                // The caller hands over the raw username; the mask is the builder's guarantee.
                String body = TelegramAlerts.sellerSold(
                        AUCTION_ID, "x", BigDecimal.ONE, "alexanderhamilton").body;

                assertFalse(body.contains("alexanderhamilton"), body);
                assertTrue(body.contains("a***n"), body);
            }
        }

        @Test
        @DisplayName("A winner with no usable name is still referred to as somebody")
        void blankWinnerFallsBack() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertTrue(TelegramAlerts.sellerSold(AUCTION_ID, "x", BigDecimal.ONE, null)
                        .body.contains("a verified buyer"));
                assertTrue(TelegramAlerts.sellerSold(AUCTION_ID, "x", BigDecimal.ONE, "  ")
                        .body.contains("a verified buyer"));
            }
        }
    }

    @Nested
    @DisplayName("Outcome content")
    class Outcome {

        @Test
        @DisplayName("Sold says what it made and hints at who bought it")
        void soldCarriesPriceAndBuyer() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                String body = TelegramAlerts.sellerSold(
                        AUCTION_ID, "Leica M6", new BigDecimal("1899"), "chloelee").body;

                assertTrue(body.contains("sold for"), body);
                assertTrue(body.contains("$1899.00"), body);
                assertTrue(body.contains("c***e"), body);
                assertTrue(body.toLowerCase().contains("order"),
                        "the seller needs pointing at the order: " + body);
            }
        }

        @Test
        @DisplayName("Unsold says it can be relisted, and names no price or buyer")
        void unsoldOffersARelist() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                String body = TelegramAlerts.sellerUnsold(AUCTION_ID, "Leica M6").body;

                assertTrue(body.contains("Leica M6"), body);
                assertTrue(body.toLowerCase().contains("relist"), body);
                assertFalse(body.contains("$"), "nothing was bid, so there is no figure: " + body);
                assertFalse(body.contains("***"), body);
            }
        }

        @Test
        @DisplayName("The bid count reads as a phrase, singular included")
        void bidCountIsAPhrase() {
            assertEquals("1 bid", TelegramAlerts.formatBidCount(1));
            assertEquals("12 bids", TelegramAlerts.formatBidCount(12));
            assertEquals("0 bids", TelegramAlerts.formatBidCount(0));
            // Negative means "this message has no {bids} placeholder to fill".
            assertEquals("", TelegramAlerts.formatBidCount(-1));
        }
    }

    @Nested
    @DisplayName("Escaping")
    class Escaping {

        @Test
        @DisplayName("A title containing markup cannot forge a seller alert either")
        void titlesAreEscaped() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                String body = TelegramAlerts.sellerPrice(AUCTION_ID,
                        "<a href=\"http://evil\">win</a> & more", new BigDecimal("10"), 2, 120).body;

                assertFalse(body.contains("<a href"), body);
                assertTrue(body.contains("&amp;"), body);
            }
        }

        @Test
        @DisplayName("A title containing a placeholder is not itself substituted into")
        void substitutionIsSinglePass() {
            copy(Map.of("telegram.alert.sellerPrice", "{title} is now at {price}."));
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                String body = TelegramAlerts.sellerPrice(
                        AUCTION_ID, "Vintage {price} lens", new BigDecimal("99"), 1, 120).body;

                // The listing keeps the text its seller typed; only one price appears.
                assertTrue(body.contains("Vintage {price} lens"), body);
                assertEquals(1, body.split("\\$99\\.00", -1).length - 1, body);
            }
        }

        @Test
        @DisplayName("Brace text that is not a placeholder is sent as written")
        void unknownPlaceholdersSurvive() {
            assertEquals("a {nope} b", TelegramAlerts.render("a {nope} b", "t", BigDecimal.ONE, -1, null));
            assertEquals("unclosed {", TelegramAlerts.render("unclosed {", "t", BigDecimal.ONE, -1, null));
        }
    }

    @Nested
    @DisplayName("Queueing metadata")
    class Metadata {

        @Test
        @DisplayName("The price key is per auction only, so a bidding war collapses into one row")
        void priceKeyIsPerAuction() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                var alert = TelegramAlerts.sellerPrice(
                        AUCTION_ID, "x", BigDecimal.ONE, 3, 120);

                assertEquals("PRICE:42", alert.dedupeKey);
                assertEquals("SELLER_PRICE", alert.eventType);
            }
        }

        @Test
        @DisplayName("The cooldown becomes the queued row's initial delay — the window and the wait are one thing")
        void cooldownIsTheInitialDelay() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertEquals(120, TelegramAlerts.sellerPrice(
                        AUCTION_ID, "x", BigDecimal.ONE, 1, 120).initialDelaySeconds);
                assertEquals(30, TelegramAlerts.sellerPrice(
                        AUCTION_ID, "x", BigDecimal.ONE, 1, 30).initialDelaySeconds);
            }
        }

        @Test
        @DisplayName("Both outcomes share RESULT:{auctionId}, since an auction has exactly one")
        void bothOutcomesShareOneKey() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                var sold = TelegramAlerts.sellerSold(AUCTION_ID, "x", BigDecimal.ONE, "abc");
                var unsold = TelegramAlerts.sellerUnsold(AUCTION_ID, "x");

                assertEquals("RESULT:42", sold.dedupeKey);
                assertEquals("RESULT:42", unsold.dedupeKey);
                assertEquals("SELLER_RESULT", sold.eventType);
                assertEquals("SELLER_RESULT", unsold.eventType);
            }
        }

        @Test
        @DisplayName("Result alerts are due immediately — only the price feed waits")
        void resultsAreNotDelayed() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertEquals(0, TelegramAlerts.sellerSold(
                        AUCTION_ID, "x", BigDecimal.ONE, "abc").initialDelaySeconds);
                assertEquals(0, TelegramAlerts.sellerUnsold(AUCTION_ID, "x").initialDelaySeconds);
            }
        }
    }

    @Nested
    @DisplayName("Admin-editable copy")
    class Copy {

        @Test
        @DisplayName("Reworded copy is used, with all four placeholders substituted")
        void adminCopyWins() {
            copy(Map.of("telegram.alert.sellerPrice", "{title}: {price} after {bids}.",
                    "telegram.alert.sellerSold", "{title} went for {price} to {winner}."));
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertEquals("<b>Leica M6</b>: $410.00 after 12 bids.",
                        TelegramAlerts.sellerPrice(
                                AUCTION_ID, "Leica M6", new BigDecimal("410"), 12, 120).body);
                assertEquals("<b>Leica M6</b> went for $410.00 to <b>c***e</b>.",
                        TelegramAlerts.sellerSold(
                                AUCTION_ID, "Leica M6", new BigDecimal("410"), "chloelee").body);
            }
        }

        @Test
        @DisplayName("An un-migrated database degrades to the built-in wording, not a blank message")
        void missingRowsFallBackToDefaults() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertFalse(TelegramAlerts.sellerPrice(
                        AUCTION_ID, "x", BigDecimal.ONE, 1, 120).body.isBlank());
                assertFalse(TelegramAlerts.sellerSold(
                        AUCTION_ID, "x", BigDecimal.ONE, "abc").body.isBlank());
                assertFalse(TelegramAlerts.sellerUnsold(AUCTION_ID, "x").body.isBlank());
            }
        }
    }
}
