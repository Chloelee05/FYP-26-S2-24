package com.auction.notification;

import com.auction.dao.NotificationDAO.TelegramPreferences;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the new AUCTION_CANCELLED push sits in the preference model.
 *
 * <p>It is deliberately not behind a per-type switch. A cancellation is a material change to
 * something the recipient has money committed to — the same reasoning that leaves
 * {@code LOST} ungated — so the only thing that silences it is the member turning Telegram off
 * altogether.</p>
 */
@DisplayName("AUCTION_CANCELLED preference gating")
class AuctionCancelledPreferenceTest {

    /** Everything off except the master switch. */
    private static TelegramPreferences onlyMasterSwitch() {
        return new TelegramPreferences(true, false, false, false, false, false, false);
    }

    @Test
    @DisplayName("delivered even when every optional category is switched off")
    void notGatedByOptionalCategories() {
        assertTrue(TelegramNotifier.allowed(onlyMasterSwitch(), "AUCTION_CANCELLED"));
    }

    @Test
    @DisplayName("delivered under the default preferences")
    void allowedByDefault() {
        assertTrue(TelegramNotifier.allowed(TelegramPreferences.defaults(), "AUCTION_CANCELLED"));
    }

    @Test
    @DisplayName("the master switch still silences it")
    void masterSwitchWins() {
        TelegramPreferences off = new TelegramPreferences(false, true, true, true, true, true, true);
        assertFalse(TelegramNotifier.allowed(off, "AUCTION_CANCELLED"));
    }

    @Test
    @DisplayName("it is not accidentally folded into the order-updates switch")
    void notAnOrderUpdate() {
        TelegramPreferences noOrders =
                new TelegramPreferences(true, true, true, true, true, true, false);

        assertTrue(TelegramNotifier.allowed(noOrders, "AUCTION_CANCELLED"));
        assertFalse(TelegramNotifier.allowed(noOrders, "ORDER_CANCELLED"));
    }
}
