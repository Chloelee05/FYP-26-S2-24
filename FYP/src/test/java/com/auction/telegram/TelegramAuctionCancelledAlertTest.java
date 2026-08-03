package com.auction.telegram;

import com.auction.dao.LandingContentDAO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Telegram half of "tell the bidders the auction was cancelled".
 *
 * <p>Nothing announced a cancellation in any channel before this, which was the worse half of
 * minimum requirement Seller (d): the platform could cancel, but the people with money
 * committed were never told.</p>
 */
@DisplayName("TelegramAlerts.auctionCancelled")
class TelegramAuctionCancelledAlertTest {

    private LandingContentDAO copyDao;

    @BeforeEach
    void useStubbedCopy() {
        copyDao = mock(LandingContentDAO.class);
        when(copyDao.findAllValues()).thenReturn(Collections.emptyMap());
        TelegramCopy.setDao(copyDao);
    }

    @AfterEach
    void restoreCopy() {
        TelegramCopy.setDao(new LandingContentDAO());
        TelegramCopy.invalidate();
    }

    @Test
    @DisplayName("built as its own event type, so it is queued and gated as one")
    void hasItsOwnEventType() {
        TelegramAlerts.Alert a = TelegramAlerts.auctionCancelled(30L, 5, "Butterfly Sapphire");

        assertEquals("AUCTION_CANCELLED", a.eventType);
        assertEquals(Long.valueOf(30L), a.auctionId);
    }

    @Test
    @DisplayName("falls back to built-in copy when the admin row is absent")
    void fallsBackToBuiltInCopy() {
        TelegramAlerts.Alert a = TelegramAlerts.auctionCancelled(30L, 5, "Butterfly Sapphire");

        assertTrue(a.body.contains("cancelled"), a.body);
        assertTrue(a.body.contains("Butterfly Sapphire"), a.body);
        assertTrue(a.body.contains("nothing is owed"), a.body);
    }

    @Test
    @DisplayName("admin-edited copy is used when present")
    void adminCopyWins() {
        when(copyDao.findAllValues()).thenReturn(
                Map.of("telegram.alert.auctionCancelled", "Withdrawn: {title}."));
        TelegramCopy.invalidate();

        TelegramAlerts.Alert a = TelegramAlerts.auctionCancelled(30L, 5, "Butterfly Sapphire");

        assertTrue(a.body.startsWith("Withdrawn:"), a.body);
        assertTrue(a.body.contains("Butterfly Sapphire"), a.body);
    }

    @Test
    @DisplayName("deduplicated per recipient per auction, so cancel and last-unit removal cannot double-send")
    void dedupeKeyIsPerRecipientPerAuction() {
        TelegramAlerts.Alert first  = TelegramAlerts.auctionCancelled(30L, 5, "Thing");
        TelegramAlerts.Alert second = TelegramAlerts.auctionCancelled(30L, 5, "Thing");
        TelegramAlerts.Alert other  = TelegramAlerts.auctionCancelled(30L, 6, "Thing");

        assertEquals(first.dedupeKey, second.dedupeKey);
        assertNotEquals(first.dedupeKey, other.dedupeKey);
        assertEquals("AUCTION_CANCELLED:30:5", first.dedupeKey);
    }

    @Test
    @DisplayName("a title with markup in it is escaped, not interpolated raw")
    void titleIsEscaped() {
        TelegramAlerts.Alert a = TelegramAlerts.auctionCancelled(30L, 5, "<b>Rolex</b>");

        assertFalse(a.body.contains("<b>Rolex</b>"), a.body);
        assertTrue(a.body.contains("&lt;b&gt;Rolex&lt;/b&gt;"), a.body);
    }

    @Test
    @DisplayName("a missing title still produces a sendable message")
    void blankTitleFallsBack() {
        TelegramAlerts.Alert a = TelegramAlerts.auctionCancelled(30L, 5, "  ");

        assertFalse(a.body.isBlank());
        assertTrue(a.body.contains("your item"), a.body);
    }

    @Test
    @DisplayName("sent immediately – the recipient is not waiting on a coalescing window")
    void noDelay() {
        assertEquals(0, TelegramAlerts.auctionCancelled(30L, 5, "Thing").initialDelaySeconds);
    }
}
