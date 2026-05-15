package com.auction.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCRUM-192 — parameterized input validation for profile fields (display name, phone, contact).
 */
@DisplayName("InputValidator profile fields (SCRUM-192)")
class InputValidatorProfileFieldsTest {

    @ParameterizedTest(name = "valid display name: {0}")
    @ValueSource(strings = {"Ab", "John Doe", "Mary-Jane", "Lee O'Malley", "User 99", "José"})
    void displayName_acceptsValid(String name) {
        assertNull(InputValidator.getDisplayNameViolation(name), () -> name);
    }

    @ParameterizedTest(name = "invalid display name: {0}")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "a", "x", "bad@name", "no_underscore"})
    void displayName_rejectsInvalid(String name) {
        assertNotNull(InputValidator.getDisplayNameViolation(name));
    }

    @Test
    @DisplayName("display name too long")
    void displayName_tooLong() {
        assertNotNull(InputValidator.getDisplayNameViolation("x".repeat(65)));
    }

    @ParameterizedTest(name = "optional phone blank ok: {0}")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    void optionalPhone_blankOk(String phone) {
        assertNull(InputValidator.getOptionalPhoneFormatViolation(phone));
    }

    @ParameterizedTest(name = "optional phone valid: {0}")
    @ValueSource(strings = {"+6512345678", "12345678", "+44123456789012"})
    void optionalPhone_valid(String phone) {
        assertNull(InputValidator.getOptionalPhoneFormatViolation(phone));
    }

    @ParameterizedTest(name = "optional phone invalid: {0}")
    @ValueSource(strings = {"123", "abc", "+6512345"})
    void optionalPhone_invalid(String phone) {
        assertNotNull(InputValidator.getOptionalPhoneFormatViolation(phone));
    }

    @ParameterizedTest(name = "optional address blank ok")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void optionalAddress_blankOk(String address) {
        assertNull(InputValidator.getOptionalAddressViolation(address));
    }

    @Test
    @DisplayName("optional address over max length")
    void optionalAddress_tooLong() {
        assertNotNull(InputValidator.getOptionalAddressViolation("x".repeat(InputValidator.ADDRESS_MAX_LENGTH + 1)));
    }

    @ParameterizedTest(name = "optional image URL: {0}")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "https://cdn.example.com/pic.png"})
    void optionalImageUrl_ok(String url) {
        assertNull(InputValidator.getOptionalProfileImageUrlViolation(url));
    }

    @ParameterizedTest(name = "image URL bad: {0}")
    @ValueSource(strings = {"http://insecure.com/x.png", "ftp://x", "not-a-url"})
    void optionalImageUrl_bad(String url) {
        assertNotNull(InputValidator.getOptionalProfileImageUrlViolation(url));
    }
}
