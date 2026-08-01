package com.auction.telegram;

/**
 * Telegram Bot API settings, read from the environment at call time so a rotated
 * secret takes effect on restart without a rebuild.
 *
 * <ul>
 *   <li>{@code TELEGRAM_BOT_TOKEN} — bot token from BotFather (required to send)</li>
 *   <li>{@code TELEGRAM_BOT_USERNAME} — bot handle without {@code @}, used to build the deep link</li>
 *   <li>{@code TELEGRAM_WEBHOOK_SECRET} — value Telegram echoes in
 *       {@code X-Telegram-Bot-Api-Secret-Token}; the webhook rejects anything else</li>
 *   <li>{@code AUCTION_TELEGRAM_PEPPER} — server-side pepper mixed into the chat-id hash so a
 *       stolen database alone cannot be brute-forced back to chat ids</li>
 *   <li>{@code AUCTION_PUBLIC_BASE_URL} — optional public address of the deployment, used to
 *       link alerts back to the auction</li>
 * </ul>
 *
 * <p>Mirrors {@link com.auction.util.MailConfig#isSmtpConfigured()}: when
 * {@link #isConfigured()} is false the whole feature turns itself off — the UI reports
 * it as unavailable and nothing is sent — so local development needs no bot at all.
 * No value here is ever logged.</p>
 */
public final class TelegramConfig {

    private TelegramConfig() {
    }

    /**
     * True when enough is configured to actually run the linking flow: a bot token to
     * call the API with, a username to build the deep link from, and a webhook secret
     * to authenticate inbound updates.
     */
    public static boolean isConfigured() {
        return botToken() != null && botUsername() != null && webhookSecret() != null;
    }

    public static String botToken() {
        return firstNonBlank(System.getenv("TELEGRAM_BOT_TOKEN"));
    }

    /** Bot handle without the leading {@code @}, e.g. {@code AuctionHubAlertsBot}. */
    public static String botUsername() {
        String raw = firstNonBlank(System.getenv("TELEGRAM_BOT_USERNAME"));
        if (raw == null) {
            return null;
        }
        return raw.startsWith("@") ? raw.substring(1) : raw;
    }

    public static String webhookSecret() {
        return firstNonBlank(System.getenv("TELEGRAM_WEBHOOK_SECRET"));
    }

    /**
     * Pepper for the chat-id hash. Falls back to a fixed development value so local runs
     * work; a deployment that changes this invalidates existing links, which is the
     * intended behaviour for a compromised pepper.
     */
    public static String pepper() {
        String env = firstNonBlank(System.getenv("AUCTION_TELEGRAM_PEPPER"));
        return env != null ? env : "auctionhub-dev-telegram-pepper";
    }

    /**
     * Public address of this deployment, e.g.
     * {@code https://fyp-26-s2-24.onrender.com/online-auction}, used to put a "View auction"
     * link in an alert. Optional: unset means alerts simply carry no link, which is better
     * than guessing a host and shipping a dead one. Any trailing slash is dropped so callers
     * can always append {@code /path}.
     */
    public static String publicBaseUrl() {
        String raw = firstNonBlank(System.getenv("AUCTION_PUBLIC_BASE_URL"));
        if (raw == null) {
            return null;
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    /** Deep link that opens the bot and hands it {@code token} as the {@code /start} payload. */
    public static String deepLink(String token) {
        String user = botUsername();
        if (user == null || token == null) {
            return null;
        }
        return "https://t.me/" + user + "?start=" + token;
    }

    private static String firstNonBlank(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
