package com.auction.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SecurityUtil – hashing, encryption, masking, sanitization")
class SecurityUtilTest {

    @Test
    @DisplayName("hashPassword produces salted format and verifyPassword round-trips")
    void passwordRoundTrip() {
        String hash = SecurityUtil.hashPassword("Password1!");
        assertTrue(hash.startsWith("1$"));
        assertTrue(SecurityUtil.verifyPassword("Password1!", hash));
        assertFalse(SecurityUtil.verifyPassword("WrongPassword1!", hash));
    }

    @Test
    @DisplayName("encrypt/decrypt round-trip for PII")
    void encryptRoundTrip() {
        String plain = "+65 9123 4567";
        String enc = SecurityUtil.encrypt(plain);
        assertNotEquals(plain, enc);
        assertEquals(plain, SecurityUtil.decrypt(enc));
    }

    @Test
    @DisplayName("decrypt(null) returns null")
    void decryptNull() {
        assertNull(SecurityUtil.decrypt(null));
    }

    @Test
    @DisplayName("maskEmail hides local part")
    void maskEmail() {
        assertEquals("j***n@email.com", SecurityUtil.maskEmail("john@email.com"));
        assertNull(SecurityUtil.maskEmail(null));
    }

    @Test
    @DisplayName("maskUsername shows first and last char")
    void maskUsername() {
        assertEquals("a***e", SecurityUtil.maskUsername("alice"));
        assertEquals("a*", SecurityUtil.maskUsername("ab"));
        assertEquals("****", SecurityUtil.maskUsernameFully("alice"));
    }

    @Test
    @DisplayName("maskPhone keeps last 4 digits")
    void maskPhone() {
        assertTrue(SecurityUtil.maskPhone("+6591234567").endsWith("4567"));
        assertNull(SecurityUtil.maskPhone(null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("verifyPassword rejects null/blank inputs")
    void verifyPasswordRejectsBlank(String input) {
        assertFalse(SecurityUtil.verifyPassword(input, SecurityUtil.hashPassword("Password1!")));
    }

    @Test
    @DisplayName("sanitize strips script tags")
    void sanitizeXss() {
        String dirty = "<script>alert(1)</script>Hello";
        String clean = SecurityUtil.sanitize(dirty);
        assertFalse(clean.toLowerCase().contains("<script>"));
        assertTrue(clean.contains("Hello"));
    }
}
