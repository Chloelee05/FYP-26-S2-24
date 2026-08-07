package com.auction.util;

/**
 * SMTP settings for transactional mail (password-reset OTP).
 * <p>
 * Configure Tomcat (or your OS) environment variables before expecting real delivery:
 * </p>
 * <ul>
 *   <li>{@code AUCTION_SMTP_HOST} — SMTP server hostname (required for sending mail)</li>
 *   <li>{@code AUCTION_SMTP_PORT} — port (default {@code 587})</li>
 *   <li>{@code AUCTION_SMTP_USER} — login user if the server requires AUTH</li>
 *   <li>{@code AUCTION_SMTP_PASSWORD} — password or app password</li>
 *   <li>{@code AUCTION_MAIL_FROM} — From address (default {@code noreply@auctionhub.local})</li>
 *   <li>{@code AUCTION_SMTP_AUTH} — {@code false} to skip authenticator (default {@code true})</li>
 *   <li>{@code AUCTION_SMTP_STARTTLS} — {@code false} to disable STARTTLS (default {@code true})</li>
 *   <li>{@code AUCTION_SMTP_SSL} — {@code true} for implicit SSL (e.g. port 465)</li>
 * </ul>
 * If {@code AUCTION_SMTP_HOST} is unset, the app keeps the FYP behaviour: OTP is shown in-page as
 * {@code simulatedOtp} and logged server-side.
 *
 * <p>Nothing is cached: each getter reads the environment when it is called, so there is
 * no state to go stale and no credentials held in a field. The password getter is the one
 * exception to the blank-is-null convention, returning an empty string, because the mail
 * authenticator wants a string rather than a null.</p>
 *
 * <p>Whether mail can be delivered is a separate question from whether an OTP may be shown
 * in a response. {@link com.auction.util.DevMode} answers the second one, and deliberately
 * does not consult this class.</p>
 */
public final class MailConfig {

    private MailConfig() {
    }

    /**
     * Whether the deployment can send mail at all, which the host alone decides. Callers
     * use this to skip the mail leg entirely rather than attempting a send that must fail.
     */
    public static boolean isSmtpConfigured() {
        String h = firstNonBlank(System.getenv("AUCTION_SMTP_HOST"));
        return h != null && !h.isBlank();
    }

    public static String smtpHost() {
        return firstNonBlank(System.getenv("AUCTION_SMTP_HOST"));
    }

    /** Defaults to 587, the usual submission port for SMTP with STARTTLS. */
    public static int smtpPort() {
        String p = firstNonBlank(System.getenv("AUCTION_SMTP_PORT"));
        if (p == null) {
            return 587;
        }
        try {
            return Integer.parseInt(p);
        } catch (NumberFormatException e) {
            return 587;
        }
    }

    public static String smtpUser() {
        return firstNonBlank(System.getenv("AUCTION_SMTP_USER"));
    }

    /** Empty rather than null when unset, since the authenticator expects a string. */
    public static String smtpPassword() {
        String v = System.getenv("AUCTION_SMTP_PASSWORD");
        return v == null ? "" : v;
    }

    public static String mailFrom() {
        String f = firstNonBlank(System.getenv("AUCTION_MAIL_FROM"));
        return f != null ? f : "noreply@auctionhub.local";
    }

    public static String mailSubject() {
        String s = firstNonBlank(System.getenv("AUCTION_MAIL_SUBJECT"));
        return s != null ? s : "AuctionHub password reset code";
    }

    public static boolean smtpAuth() {
        String v = System.getenv("AUCTION_SMTP_AUTH");
        if (v == null || v.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(v.trim());
    }

    /**
     * STARTTLS upgrades a plain connection to an encrypted one after connecting, which is
     * what port 587 expects. On by default, so a misconfigured variable cannot silently
     * send credentials in the clear.
     */
    public static boolean startTls() {
        String v = System.getenv("AUCTION_SMTP_STARTTLS");
        if (v == null || v.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(v.trim());
    }

    /**
     * Implicit SSL encrypts from the first byte instead of upgrading later, which is what
     * port 465 expects. Off by default and mutually exclusive with STARTTLS.
     */
    public static boolean implicitSsl() {
        String v = System.getenv("AUCTION_SMTP_SSL");
        return "true".equalsIgnoreCase(v != null ? v.trim() : "");
    }

    private static String firstNonBlank(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
