package com.auction.util;

import java.util.regex.Pattern;

/**
 * Central input validation for registration and account flows: email format and password policy.
 * <p>
 * Complements {@link SecurityUtil#sanitize(String)} — validate structure here, then sanitize or
 * hash as appropriate in servlets/DAOs.
 * </p>
 */
public final class InputValidator {

    /**
     * Pragmatic email pattern (ASCII local-part and domain); not a full RFC 5322 implementation.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}$");


    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{8,15}$");

    private static final Pattern DISPLAY_NAME_PATTERN = Pattern.compile(
            "^[\\p{L}0-9][\\p{L}0-9 '\\-.]{1,63}$");

    /** Max length for street / contact address text. */
    public static final int ADDRESS_MAX_LENGTH = 500;

    public static final int DISPLAY_NAME_MAX_LENGTH = 64;

    public static final int PROFILE_IMAGE_URL_MAX_LENGTH = 512;
    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_LENGTH = 128;

    private InputValidator() {
    }

    /**
     * Returns {@code true} if the value is a non-blank, well-formed email address.
     * <p>
     * Requirement: SCRUM-94 (input validation — email).
     * </p>
     *
     * @param email raw email; {@code null} or blank yields {@code false}
     * @return {@code true} if the trimmed value matches the supported email pattern
     */
    public static boolean isValidEmail(String email) {
        return getEmailFormatViolation(email) == null;
    }

    /**
     * Human-readable reason when {@link #isValidEmail(String)} would return {@code false};
     * otherwise {@code null}.
     * <p>
     * Requirement: SCRUM-94.
     * </p>
     *
     * @param email user-supplied email
     * @return {@code null} if valid; otherwise a short English message for UI or request attributes
     */
    public static String getEmailFormatViolation(String email) {
        if (email == null || email.isBlank()) {
            return "Email is required.";
        }
        String trimmed = email.trim();
        if (trimmed.length() > 254) {
            return "Email is too long.";
        }
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            return "Please enter a valid Email address.";
        }
        return null;
    }

    /**
     * Password policy: length between {@link #PASSWORD_MIN_LENGTH} and {@link #PASSWORD_MAX_LENGTH},
     * at least one uppercase letter, one lowercase letter, one digit, and one special character
     * from {@code !@#$%^&*()_+-=[]{}|;:,.?}.
     * <p>
     * Requirement: SCRUM-94 (password strength).
     * </p>
     *
     * @param password plaintext password; {@code null} or blank yields {@code false}
     * @return {@code true} if all policy checks pass
     */
    public static boolean meetsPasswordPolicy(String password) {
        return getPasswordPolicyViolation(password) == null;
    }

    /**
     * Human-readable reason when {@link #meetsPasswordPolicy(String)} would return {@code false};
     * otherwise {@code null}.
     * <p>
     * Requirement: SCRUM-94.
     * </p>
     *
     * @param password user-supplied password
     * @return {@code null} if valid; otherwise a short English message
     */
    public static String getPasswordPolicyViolation(String password) {
        if (password == null || password.isBlank()) {
            return "Password is required.";
        }
        if (password.length() < PASSWORD_MIN_LENGTH) {
            return "Password must be at least " + PASSWORD_MIN_LENGTH + " characters.";
        }
        if (password.length() > PASSWORD_MAX_LENGTH) {
            return "Password must be at most " + PASSWORD_MAX_LENGTH + " characters.";
        }
        if (!password.matches(".*[A-Z].*")) {
            return "Password must include at least one uppercase letter.";
        }
        if (!password.matches(".*[a-z].*")) {
            return "Password must include at least one lowercase letter.";
        }
        if (!password.matches(".*[0-9].*")) {
            return "Password must include at least one digit.";
        }
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.?].*")) {
            return "Password must include at least one special character (!@#$%^&* etc.).";
        }
        return null;
    }

    /**
     * Short summary for help text on registration or reset-password pages.
     * <p>
     * Requirement: SCRUM-94.
     * </p>
     *
     * @return English description of the password rules
     */
    public static String getPasswordPolicySummary() {
        return String.format(
                "%d–%d characters, with uppercase, lowercase, a number, and a special character.",
                PASSWORD_MIN_LENGTH,
                PASSWORD_MAX_LENGTH);
    }

    /**
     * Human-readable reason when a phone number is invalid; {@code null} if valid.
     * Accepts an optional leading {@code +} followed by 8–15 digits (E.164-style).
     *
     * @param phone user-supplied phone number; {@code null} or blank yields an error message
     * @return {@code null} if valid; otherwise a short English message
     */
    public static String getPhoneFormatViolation(String phone) {
        if (phone == null || phone.isBlank()) {
            return "Phone number is required.";
        }
        if (!PHONE_PATTERN.matcher(phone.trim()).matches()) {
            return "Please enter a valid phone number (8–15 digits, optional leading +).";
        }
        return null;
    }

    /**
     * Optional phone for profile updates: blank is valid; non-blank must match {@link #getPhoneFormatViolation(String)} rules.
     */
    public static String getOptionalPhoneFormatViolation(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        if (!PHONE_PATTERN.matcher(phone.trim()).matches()) {
            return "Please enter a valid phone number (8–15 digits, optional leading +).";
        }
        return null;
    }

    /**
     * Display name (username) for profile: 2–{@link #DISPLAY_NAME_MAX_LENGTH} chars, letters / digits / spaces / hyphen / dot / apostrophe.
     */
    public static String getDisplayNameViolation(String name) {
        if (name == null || name.isBlank()) {
            return "Display name is required.";
        }
        String t = name.trim();
        if (t.length() < 2) {
            return "Display name must be at least 2 characters.";
        }
        if (t.length() > DISPLAY_NAME_MAX_LENGTH) {
            return "Display name must be at most " + DISPLAY_NAME_MAX_LENGTH + " characters.";
        }
        if (!DISPLAY_NAME_PATTERN.matcher(t).matches()) {
            return "Display name contains invalid characters.";
        }
        return null;
    }

    /**
     * Optional address: blank allowed; otherwise max {@link #ADDRESS_MAX_LENGTH} chars after trim.
     */
    public static String getOptionalAddressViolation(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        if (address.trim().length() > ADDRESS_MAX_LENGTH) {
            return "Address must be at most " + ADDRESS_MAX_LENGTH + " characters.";
        }
        return null;
    }

    /**
     * Optional profile image URL: blank allowed; otherwise must be https and within max length.
     */
    public static String getOptionalProfileImageUrlViolation(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String t = url.trim();
        if (t.length() > PROFILE_IMAGE_URL_MAX_LENGTH) {
            return "Image URL is too long.";
        }
        if (!t.startsWith("https://")) {
            return "Image URL must use https://";
        }
        try {
            java.net.URI.create(t);
        } catch (IllegalArgumentException e) {
            return "Image URL is not a valid URL.";
        }
        return null;
    }
}
