package com.auction.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OtpStore – password-reset OTP")
class OtpStoreTest {

    @Test
    @DisplayName("generateAndStore + verify succeeds for matching OTP")
    void verifySuccess() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@email.com");
        assertEquals(6, otp.length());
        assertTrue(store.verify("user@email.com", otp));
    }

    @Test
    @DisplayName("verify fails for wrong OTP")
    void verifyWrongOtp() {
        OtpStore store = new OtpStore();
        store.generateAndStore("user@email.com");
        assertFalse(store.verify("user@email.com", "000000"));
    }

    @Test
    @DisplayName("invalidate prevents reuse")
    void invalidate() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@email.com");
        store.invalidate("user@email.com");
        assertFalse(store.verify("user@email.com", otp));
    }

    @Test
    @DisplayName("expired OTP is rejected")
    void expiredOtp() throws InterruptedException {
        OtpStore store = new OtpStore(1);
        String otp = store.generateAndStore("user@email.com");
        Thread.sleep(1100);
        assertFalse(store.verify("user@email.com", otp));
    }

    @Test
    @DisplayName("verify is case-insensitive on identifier")
    void identifierCaseInsensitive() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("User@Email.com");
        assertTrue(store.verify("user@email.com", otp));
    }

    @Test
    @DisplayName("the correct OTP still works while attempts remain")
    void survivesFailuresBelowLimit() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@email.com");
        for (int i = 0; i < OtpStore.MAX_ATTEMPTS - 1; i++) {
            assertFalse(store.verify("user@email.com", "000000"));
        }
        assertTrue(store.verify("user@email.com", otp),
                "OTP should survive fewer than MAX_ATTEMPTS wrong guesses");
    }

    @Test
    @DisplayName("MAX_ATTEMPTS wrong guesses invalidate the OTP")
    void attemptLimitInvalidatesOtp() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@email.com");
        for (int i = 0; i < OtpStore.MAX_ATTEMPTS; i++) {
            assertFalse(store.verify("user@email.com", "000000"));
        }
        assertFalse(store.verify("user@email.com", otp),
                "the real OTP must be rejected once the attempt allowance is spent");
    }

    @Test
    @DisplayName("attempt counters are per identifier")
    void attemptLimitIsPerIdentifier() {
        OtpStore store = new OtpStore();
        String victim = store.generateAndStore("victim@email.com");
        store.generateAndStore("attacker@email.com");
        for (int i = 0; i < OtpStore.MAX_ATTEMPTS; i++) {
            store.verify("attacker@email.com", "000000");
        }
        assertTrue(store.verify("victim@email.com", victim),
                "burning one identifier's attempts must not lock out another");
    }

    @Test
    @DisplayName("a fresh OTP restores the full attempt allowance")
    void regenerateResetsAttempts() {
        OtpStore store = new OtpStore();
        store.generateAndStore("user@email.com");
        for (int i = 0; i < OtpStore.MAX_ATTEMPTS; i++) {
            store.verify("user@email.com", "000000");
        }
        String fresh = store.generateAndStore("user@email.com");
        assertTrue(store.verify("user@email.com", fresh));
    }
}
