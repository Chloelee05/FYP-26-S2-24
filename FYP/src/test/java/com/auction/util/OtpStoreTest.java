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
}
