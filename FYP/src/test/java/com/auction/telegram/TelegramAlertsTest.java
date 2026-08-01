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
 * The alert bodies: admin-editable wording, escaped user input, and the privacy guarantees the
 * copy is written to keep.
 */
@DisplayName("TelegramAlerts — message bodies")
class TelegramAlertsTest {

    private static final long AUCTION_ID = 42L;
    private static final int RECIPIENT = 3;

    @AfterEach
    void resetCopy() {
        TelegramCopy.setDao(new LandingContentDAO());
    }

    /** Points {@link TelegramCopy} at the given rows instead of the database. */
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
        @DisplayName("Neither builder will even accept the other party's identity")
        void noBuilderTakesAnOpponent() throws Exception {
            // The guarantee is structural: if the identity cannot be passed in, it cannot leak.
            for (String name : new String[] { "outbid", "lost" }) {
                var method = TelegramAlerts.class.getMethod(
                        name, long.class, int.class, String.class, BigDecimal.class);
                assertEquals(4, method.getParameterCount(), name);
            }
        }

        @Test
        @DisplayName("The outbid message says a higher bid exists, not who placed it")
        void outbidDoesNotNameTheOpponent() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                String body = TelegramAlerts.outbid(
                        AUCTION_ID, RECIPIENT, "Leica M6", new BigDecimal("1899")).body;

                assertTrue(body.contains("outbid"), body);
                assertTrue(body.contains("$1899.00"), body);
                assertFalse(body.toLowerCase().contains("by "),
                        "an opponent's identity is personal data and tells the loser how far "
                                + "they are willing to go: " + body);
            }
        }

        @Test
        @DisplayName("The lost message carries the title and final price only")
        void lostDoesNotNameTheWinner() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                String body = TelegramAlerts.lost(
                        AUCTION_ID, RECIPIENT, "Leica M6", new BigDecimal("1899")).body;

                assertTrue(body.contains("Leica M6"), body);
                assertTrue(body.contains("$1899.00"), body);
                assertFalse(body.toLowerCase().contains("winner"), body);
                assertFalse(body.toLowerCase().contains("won by"), body);
            }
        }
    }

    @Nested
    @DisplayName("Escaping")
    class Escaping {

        @Test
        @DisplayName("A title containing markup cannot break — or forge — the message")
        void titlesAreEscaped() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                String body = TelegramAlerts.outbid(AUCTION_ID, RECIPIENT,
                        "<b>Free</b> & <a href=\"http://evil\">click</a>", new BigDecimal("10")).body;

                assertFalse(body.contains("<a href"), "an injected link must not survive: " + body);
                assertTrue(body.contains("&lt;b&gt;Free&lt;/b&gt;"), body);
                assertTrue(body.contains("&amp;"), body);
            }
        }

        @Test
        @DisplayName("The title is emboldened by the code, so admin copy never has to contain markup")
        void titleIsEmboldenedOutsideTheCopy() {
            assertEquals("<b>Camera</b> sold for $9.00",
                    TelegramAlerts.render("{title} sold for {price}", "Camera", new BigDecimal("9")));
        }

        @Test
        @DisplayName("A missing title falls back rather than leaving a hole in the message")
        void blankTitleFallsBack() {
            assertTrue(TelegramAlerts.render("{title}", "   ", BigDecimal.ONE).contains("your item"));
            assertTrue(TelegramAlerts.render("{title}", null, BigDecimal.ONE).contains("your item"));
        }

        @Test
        @DisplayName("A missing price reads as prose rather than as a broken amount")
        void missingPriceReadsAsProse() {
            assertEquals("the closing price", TelegramAlerts.formatMoney(null));
            assertEquals("$1899.00", TelegramAlerts.formatMoney(new BigDecimal("1899")));
            assertEquals("$0.50", TelegramAlerts.formatMoney(new BigDecimal("0.5")));
        }
    }

    @Nested
    @DisplayName("Admin-editable copy")
    class Copy {

        @Test
        @DisplayName("Reworded copy is used, with placeholders still substituted")
        void adminCopyWins() {
            copy(Map.of("telegram.alert.won", "Nice one — {title} is yours for {price}."));
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertEquals("Nice one — <b>Leica M6</b> is yours for $1899.00.",
                        TelegramAlerts.won(AUCTION_ID, RECIPIENT, "Leica M6", new BigDecimal("1899")).body);
            }
        }

        @Test
        @DisplayName("An un-migrated database degrades to the built-in wording, not a blank message")
        void missingRowsFallBackToDefaults() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertFalse(TelegramAlerts.won(
                        AUCTION_ID, RECIPIENT, "Leica M6", new BigDecimal("1899")).body.isBlank());
            }
        }
    }

    @Nested
    @DisplayName("Queueing metadata")
    class Metadata {

        @Test
        @DisplayName("The dedupe key is per event, auction and recipient")
        void dedupeKeyShape() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertEquals("OUTBID:42:3",
                        TelegramAlerts.outbid(AUCTION_ID, RECIPIENT, "x", BigDecimal.ONE).dedupeKey);
                assertEquals("WON:42:3",
                        TelegramAlerts.won(AUCTION_ID, RECIPIENT, "x", BigDecimal.ONE).dedupeKey);
                assertEquals("LOST:42:3",
                        TelegramAlerts.lost(AUCTION_ID, RECIPIENT, "x", BigDecimal.ONE).dedupeKey);
            }
        }

        @Test
        @DisplayName("The event type doubles as the preference selector")
        void eventTypes() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertEquals("OUTBID",
                        TelegramAlerts.outbid(AUCTION_ID, RECIPIENT, "x", BigDecimal.ONE).eventType);
                assertEquals("LOST",
                        TelegramAlerts.lost(AUCTION_ID, RECIPIENT, "x", BigDecimal.ONE).eventType);
            }
        }
    }

    @Nested
    @DisplayName("The optional View auction link")
    class Link {

        @Test
        @DisplayName("Present when the deployment's public address is configured")
        void appendedWhenConfigured() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> config = mockStatic(TelegramConfig.class)) {
                config.when(TelegramConfig::publicBaseUrl).thenReturn("https://example.test/app");

                String body = TelegramAlerts.won(AUCTION_ID, RECIPIENT, "x", BigDecimal.ONE).body;
                assertTrue(body.contains("https://example.test/app/auction/42"), body);
            }
        }

        @Test
        @DisplayName("Omitted rather than guessed when it is not, so no message carries a dead link")
        void omittedWhenUnknown() {
            copy(Map.of());
            try (MockedStatic<TelegramConfig> ignored = withoutBaseUrl()) {
                assertFalse(TelegramAlerts.won(AUCTION_ID, RECIPIENT, "x", BigDecimal.ONE)
                        .body.contains("<a href"));
            }
        }
    }
}
