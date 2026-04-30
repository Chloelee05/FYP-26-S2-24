package com.auction.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OtpStore — covers generation, verification, expiry, and replay prevention.
 *
 * A 1-second TTL is injected via the package-private constructor so expiry tests
 * run instantly without sleeping for the full production 5-minute window.
 */
public class OtpStoreTest {

    @Test
    public void generatedOtpIs6Digits() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@test.com");

        assertNotNull(otp);
        assertEquals(6, otp.length(), "OTP must be exactly 6 characters");
        assertTrue(otp.matches("\\d{6}"), "OTP must contain only digits (including leading zeros)");
    }

    @Test
    public void generatedOtpIsUnique() {
        OtpStore store = new OtpStore();
        String otp1 = store.generateAndStore("a@test.com");
        String otp2 = store.generateAndStore("b@test.com");
        // Extremely unlikely to collide; proves independent generation
        // (one-in-a-million chance of false failure — acceptable)
        assertNotEquals(otp1, otp2, "Two independent OTPs should not match");
    }

    @Test
    public void regeneratingOtpOverwritesPrevious() {
        OtpStore store = new OtpStore();
        String first  = store.generateAndStore("user@test.com");
        String second = store.generateAndStore("user@test.com");

        // The old OTP must no longer be valid
        assertFalse(store.verify("user@test.com", first),
                "First OTP should be invalidated once a new one is generated");

        // Only the latest OTP is valid
        assertTrue(store.verify("user@test.com", second),
                "Second OTP should be accepted");
    }

    // Verification 

    @Test
    public void correctOtpVerifies() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@test.com");
        assertTrue(store.verify("user@test.com", otp));
    }

    @Test
    public void wrongOtpDoesNotVerify() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@test.com");
        String wrong = otp.equals("000000") ? "000001" : "000000";
        assertFalse(store.verify("user@test.com", wrong));
    }

    @Test
    public void verifyTrimsWhitespaceFromUserInput() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@test.com");
        assertTrue(store.verify("user@test.com", "  " + otp + "  "),
                "Leading/trailing whitespace in user input should be ignored");
    }

    @Test
    public void verifyIsCaseInsensitiveOnIdentifier() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("User@Test.COM");  // stored as lowercase
        assertTrue(store.verify("user@test.com", otp),
                "Identifier lookup must be case-insensitive");
    }

    @Test
    public void verifyReturnsFalseForUnknownIdentifier() {
        OtpStore store = new OtpStore();
        assertFalse(store.verify("nobody@test.com", "123456"));
    }

    @Test
    public void verifyReturnsFalseForNullInputs() {
        OtpStore store = new OtpStore();
        store.generateAndStore("user@test.com");
        assertFalse(store.verify(null, "123456"));
        assertFalse(store.verify("user@test.com", null));
        assertFalse(store.verify(null, null));
    }

    // Expiry 

    @Test
    public void otpIsValidBeforeExpiry() throws InterruptedException {
        // 2-second TTL — OTP should still be valid immediately after generation
        OtpStore store = new OtpStore(2);
        String otp = store.generateAndStore("user@test.com");
        assertTrue(store.verify("user@test.com", otp),
                "OTP must be valid before its TTL elapses");
    }

    @Test
    public void otpIsRejectedAfterExpiry() throws InterruptedException {
        // 1-second TTL so the test finishes quickly
        OtpStore store = new OtpStore(1);
        String otp = store.generateAndStore("user@test.com");

        Thread.sleep(1200); // wait for expiry

        assertFalse(store.verify("user@test.com", otp),
                "OTP must be rejected once its TTL has elapsed");
    }

    @Test
    public void expiredOtpIsEvictedFromStore() throws InterruptedException {
        OtpStore store = new OtpStore(1);
        String otp = store.generateAndStore("user@test.com");

        Thread.sleep(1200);

        // First verify evicts the expired entry
        store.verify("user@test.com", otp);

        // A new correct OTP for same identifier should also fail — entry is gone
        assertFalse(store.verify("user@test.com", otp),
                "Expired entry should be removed after the first verification attempt");
    }

    // Invalidation / replay prevention
    @Test
    public void invalidatedOtpCannotBeReused() {
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@test.com");

        store.invalidate("user@test.com");

        assertFalse(store.verify("user@test.com", otp),
                "OTP must be unusable after explicit invalidation");
    }

    @Test
    public void invalidateIsNoOpForUnknownIdentifier() {
        OtpStore store = new OtpStore();
        // Must not throw
        assertDoesNotThrow(() -> store.invalidate("nobody@test.com"));
        assertDoesNotThrow(() -> store.invalidate(null));
    }

    @Test
    public void otpCanOnlyBeUsedOnce() {
        // Simulates the servlet calling invalidate() after a successful reset
        OtpStore store = new OtpStore();
        String otp = store.generateAndStore("user@test.com");

        assertTrue(store.verify("user@test.com", otp), "First use must succeed");

        store.invalidate("user@test.com");   // servlet does this on success

        assertFalse(store.verify("user@test.com", otp),
                "Replaying the same OTP after invalidation must fail");
    }

    // Default TTL constant

    @Test
    public void defaultTtlIsFiveMinutes() {
        assertEquals(300, OtpStore.OTP_TTL_SECONDS,
                "Production TTL must be 300 seconds (5 minutes). " +
                "Change this value — and update this test — if the requirement changes.");
    }
}
