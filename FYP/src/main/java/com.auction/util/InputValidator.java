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
            return "Please enter a valid email address.";
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
}
