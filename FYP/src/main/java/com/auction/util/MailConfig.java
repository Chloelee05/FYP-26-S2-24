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
 */
public final class MailConfig {

    private MailConfig() {
    }

    public static boolean isSmtpConfigured() {
        String h = firstNonBlank(System.getenv("AUCTION_SMTP_HOST"));
        return h != null && !h.isBlank();
    }

    public static String smtpHost() {
        return firstNonBlank(System.getenv("AUCTION_SMTP_HOST"));
    }

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

    public static boolean startTls() {
        String v = System.getenv("AUCTION_SMTP_STARTTLS");
        if (v == null || v.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(v.trim());
    }

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
