package com.auction.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SCRUM-173: JUnit 5 coverage for salted SHA-256 password hashing and verification.
 */
@DisplayName("SecurityUtil — salted SHA-256 (SCRUM-173)")
class SecurityUtilHashingTest {

    private static final Pattern HASH_PATTERN = Pattern.compile("^1\\$[A-Za-z0-9+/]+=*\\$[A-Za-z0-9+/]+=*$");

    @Test
    @DisplayName("hashPassword returns versioned three-part string with decodable Base64 segments")
    void hashPassword_formatAndDecodableSegments() {
        String stored = SecurityUtil.hashPassword("CorrectHorseBatteryStaple!9");
        assertTrue(HASH_PATTERN.matcher(stored).matches(), () -> "unexpected format: " + stored);

        String[] parts = stored.split("\\$", 3);
        byte[] salt = Base64.getDecoder().decode(parts[1]);
        byte[] hash = Base64.getDecoder().decode(parts[2]);
        assertEquals(16, salt.length);
        assertEquals(32, hash.length);
    }

    @Test
    @DisplayName("same plaintext yields different stored strings (unique salt per hash)")
    void hashPassword_saltsDiffer() {
        String a = SecurityUtil.hashPassword("same-password");
        String b = SecurityUtil.hashPassword("same-password");
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("verifyPassword succeeds for matching plaintext")
    void verifyPassword_acceptsCorrectPassword() {
        String stored = SecurityUtil.hashPassword("MyS3cure#Pass");
        assertTrue(SecurityUtil.verifyPassword("MyS3cure#Pass", stored));
    }

    @Test
    @DisplayName("verifyPassword rejects wrong password")
    void verifyPassword_rejectsWrongPassword() {
        String stored = SecurityUtil.hashPassword("Secret!1");
        assertFalse(SecurityUtil.verifyPassword("Secret!2", stored));
    }

    @Test
    @DisplayName("verifyPassword rejects tampered hash segment")
    void verifyPassword_rejectsTamperedHash() {
        String stored = SecurityUtil.hashPassword("abc");
        String[] parts = stored.split("\\$", 3);
        byte[] hashBytes = Base64.getDecoder().decode(parts[2]);
        hashBytes[0] ^= 0x01;
        String tampered = parts[0] + "$" + parts[1] + "$" + Base64.getEncoder().encodeToString(hashBytes);
        assertFalse(SecurityUtil.verifyPassword("abc", tampered));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "unicode-密码-🔐", "\tnewline\n"})
    @DisplayName("verifyPassword round-trip for varied UTF-8 passwords")
    void verifyPassword_utf8RoundTrip(String password) {
        String stored = SecurityUtil.hashPassword(password);
        assertTrue(SecurityUtil.verifyPassword(password, stored));
    }

    @Test
    @DisplayName("verifyPassword returns false for malformed stored strings")
    void verifyPassword_badFormat() {
        assertFalse(SecurityUtil.verifyPassword("x", "not-three-parts"));
        assertFalse(SecurityUtil.verifyPassword("x", "0$abc$def"));
        assertFalse(SecurityUtil.verifyPassword("x", "1$!!!$!!!"));
    }

    @Test
    @DisplayName("verifyPassword returns false when arguments are null")
    void verifyPassword_nullArguments() {
        assertFalse(SecurityUtil.verifyPassword(null, "1$a$b"));
        assertFalse(SecurityUtil.verifyPassword("pw", null));
    }

    @Test
    @DisplayName("hashPassword rejects null password")
    void hashPassword_nullThrows() {
        assertThrows(NullPointerException.class, () -> SecurityUtil.hashPassword(null));
    }

    @Test
    @DisplayName("hashPassword completes without exception for typical credentials")
    void hashPassword_typicalPasswordNoThrow() {
        assertDoesNotThrow(() -> SecurityUtil.hashPassword("Str0ng!Pass"));
    }

    @Test
    @DisplayName("verifyPassword does not throw SecurityOperationException on bad password")
    void verifyPassword_invalidPasswordNoCryptoException() {
        String stored = SecurityUtil.hashPassword("ok");
        assertDoesNotThrow(() -> assertFalse(SecurityUtil.verifyPassword("no", stored)));
    }
}
