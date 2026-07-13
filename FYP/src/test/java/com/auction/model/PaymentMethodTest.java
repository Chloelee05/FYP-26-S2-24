package com.auction.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PaymentMethod display label")
class PaymentMethodTest {

    @Test
    @DisplayName("card shows brand + last 4")
    void cardLabel() {
        assertEquals("Visa ****4242", PaymentMethod.label("CARD", "Visa", "4242", null));
    }

    @Test
    @DisplayName("card falls back to brand when last4 is missing")
    void cardLabelNoLast4() {
        assertEquals("Mastercard", PaymentMethod.label("CARD", "Mastercard", null, null));
    }

    @Test
    @DisplayName("PayPal shows the linked email")
    void paypalLabel() {
        assertEquals("PayPal (a@b.com)", PaymentMethod.label("PAYPAL", null, null, "a@b.com"));
    }

    @Test
    @DisplayName("PayPal falls back to plain label without an email")
    void paypalLabelNoEmail() {
        assertEquals("PayPal", PaymentMethod.label("PAYPAL", null, null, null));
    }

    @Test
    @DisplayName("bank transfer shows bank name + last 4")
    void bankLabel() {
        assertEquals("DBS account ****6789", PaymentMethod.label("BANK_TRANSFER", "Bank", "6789", "DBS"));
    }

    @Test
    @DisplayName("null method type defaults to card formatting")
    void nullTypeDefaultsToCard() {
        assertEquals("Visa ****1111", PaymentMethod.label(null, "Visa", "1111", null));
    }

    @Test
    @DisplayName("getDisplayLabel reflects the constructed type")
    void getDisplayLabel() {
        PaymentMethod pm = new PaymentMethod(1, "PAYPAL", null, "PayPal", null, 0, 0, "a@b.com", true, null);
        assertEquals("PayPal (a@b.com)", pm.getDisplayLabel());
    }
}
