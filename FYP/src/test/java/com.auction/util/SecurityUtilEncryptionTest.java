package com.auction.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.auction.util.SecurityUtil.SecurityOperationException;

/**
 * SCRUM-174: AES-256-GCM encrypt/decrypt consistency across plaintext lengths and content.
 */
@DisplayName("SecurityUtil — AES-256-GCM (SCRUM-174)")
class SecurityUtilEncryptionTest {

    static Stream<Arguments> plaintextCases() {
        return Stream.of(
                Arguments.of("", "empty string"),
                Arguments.of("A", "single ASCII"),
                Arguments.of("+65 9123 4567", "phone-like"),
                Arguments.of("Blk 123 #12-345, Singapore 123456", "address-like"),
                Arguments.of("日本語とEnglish mixed テスト", "Unicode"),
                Arguments.of("x".repeat(4096), "4k ASCII"),
                Arguments.of("β".repeat(2000), "2k repeated Unicode BMP"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("plaintextCases")
    @DisplayName("round-trip preserves exact plaintext")
    void encryptDecrypt_roundTrip(String plaintext, String label) {
        String cipher = SecurityUtil.encrypt(plaintext);
        assertNotNull(cipher);
        assertNotEquals(plaintext, cipher);
        String roundTrip = SecurityUtil.decrypt(cipher);
        assertTrue(plaintext.contentEquals(roundTrip),
                () -> "mismatch for case: " + label);
    }

    @Test
    @DisplayName("encrypt(null) and decrypt(null) return null")
    void nullInNullOut() {
        assertNull(SecurityUtil.encrypt(null));
        assertNull(SecurityUtil.decrypt(null));
    }

    @Test
    @DisplayName("same plaintext produces different ciphertext (random IV per encryption)")
    void encrypt_nonDeterministicIv() {
        String plain = "static-plaintext";
        String a = SecurityUtil.encrypt(plain);
        String b = SecurityUtil.encrypt(plain);
        assertNotEquals(a, b);
        assertTrue(plain.contentEquals(SecurityUtil.decrypt(a)));
        assertTrue(plain.contentEquals(SecurityUtil.decrypt(b)));
    }

    @Test
    @DisplayName("UTF-8 bytes are preserved for supplementary characters")
    void roundTrip_utf8Supplementary() {
        String plain = new String(Character.toChars(0x1F600)) + " emoji suffix";
        byte[] expectedUtf8 = plain.getBytes(StandardCharsets.UTF_8);
        String cipher = SecurityUtil.encrypt(plain);
        String back = SecurityUtil.decrypt(cipher);
        assertArrayEquals(expectedUtf8, back.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("large payload (100k chars) round-trips")
    void roundTrip_largePayload() {
        String plain = "L".repeat(100_000);
        String cipher = SecurityUtil.encrypt(plain);
        String back = SecurityUtil.decrypt(cipher);
        org.junit.jupiter.api.Assertions.assertEquals(100_000, back.length());
        assertTrue(plain.contentEquals(back));
    }

    @Test
    @DisplayName("decrypt rejects invalid Base64 / truncated ciphertext")
    void decrypt_invalidPayloadThrows() {
        assertThrows(SecurityOperationException.class, () -> SecurityUtil.decrypt("not-valid-base64!!!"));
        assertThrows(SecurityOperationException.class, () -> SecurityUtil.decrypt("YQ=="));
    }

    @Test
    @DisplayName("encrypt/decrypt complete for typical PII without throwing")
    void encryptDecrypt_noThrowTypicalPii() {
        assertDoesNotThrow(() -> {
            String c = SecurityUtil.encrypt("65-8123-4411");
            SecurityUtil.decrypt(c);
        });
    }
}
