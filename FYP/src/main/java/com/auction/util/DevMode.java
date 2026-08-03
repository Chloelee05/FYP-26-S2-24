package com.auction.util;

/**
 * Explicit opt-in switch for development-only conveniences that must never reach a
 * deployed environment — currently the password-reset and 2FA one-time passwords being
 * echoed back to the caller instead of being emailed.
 *
 * <p>Enabled only when {@code AUCTION_DEV_MODE} is set to {@code true}, {@code 1}, {@code yes}
 * or {@code on}. Anything else — including the variable being absent, blank or misspelt —
 * leaves it off, so a deployment gets the safe behaviour by default rather than by
 * remembering to switch something off.</p>
 *
 * <p>This deliberately does <em>not</em> key off {@link MailConfig#isSmtpConfigured()}.
 * Inferring "this must be a developer's machine" from an unrelated setting is what put a
 * working password-reset OTP in a production API response: {@code AUCTION_SMTP_HOST} is
 * unset on the deployment, so the debug branch was permanently live. Whether the OTP can be
 * delivered and whether it may be disclosed are separate questions and need separate flags.</p>
 */
public final class DevMode {

    /** Environment variable that turns development-only OTP disclosure on. */
    private static final String DEV_MODE_ENV = "AUCTION_DEV_MODE";

    private DevMode() {
    }

    /** True only when {@code AUCTION_DEV_MODE} is explicitly set to an affirmative value. */
    public static boolean isEnabled() {
        String v = System.getenv(DEV_MODE_ENV);
        if (v == null) {
            return false;
        }
        String t = v.trim();
        return "true".equalsIgnoreCase(t)
                || "1".equals(t)
                || "yes".equalsIgnoreCase(t)
                || "on".equalsIgnoreCase(t);
    }
}
