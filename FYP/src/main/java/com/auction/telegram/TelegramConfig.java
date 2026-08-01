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
 *   <li>{@code TELEGRAM_PRICE_COOLDOWN_SECONDS} — how long seller price alerts coalesce for
 *       (default {@value #DEFAULT_PRICE_COOLDOWN_SECONDS})</li>
 *   <li>{@code TELEGRAM_PRICE_COOLDOWN_ENDGAME_SECONDS} — the tighter cooldown used near the
 *       close (default {@value #DEFAULT_PRICE_COOLDOWN_ENDGAME_SECONDS})</li>
 *   <li>{@code TELEGRAM_PRICE_ENDGAME_WINDOW_MINUTES} — how long before {@code date_end} the
 *       endgame cooldown takes over (default {@value #DEFAULT_ENDGAME_WINDOW_MINUTES})</li>
 * </ul>
 *
 * <p>Mirrors {@link com.auction.util.MailConfig#isSmtpConfigured()}: when
 * {@link #isConfigured()} is false the whole feature turns itself off — the UI reports
 * it as unavailable and nothing is sent — so local development needs no bot at all.
 * No value here is ever logged.</p>
 */
public final class TelegramConfig {

    /** Default seller price-alert cooldown: two minutes of bids arrive as one message. */
    public static final int DEFAULT_PRICE_COOLDOWN_SECONDS = 120;

    /** Default cooldown inside the endgame window, where staleness costs the seller more. */
    public static final int DEFAULT_PRICE_COOLDOWN_ENDGAME_SECONDS = 30;

    /** Default size of the endgame window before {@code date_end}, in minutes. */
    public static final int DEFAULT_ENDGAME_WINDOW_MINUTES = 10;

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

    /**
     * How long a seller price alert waits before it is sent, and therefore how long further
     * bids on the same auction coalesce into it.
     *
     * <p>Tunable rather than hardcoded because the right value depends on the traffic a
     * deployment actually sees: a quiet marketplace can afford to be chattier, and a busy
     * one has to be quieter, without either needing a rebuild.</p>
     */
    public static int priceCooldownSeconds() {
        return positiveIntEnv("TELEGRAM_PRICE_COOLDOWN_SECONDS", DEFAULT_PRICE_COOLDOWN_SECONDS);
    }

    /** The tighter cooldown that applies inside {@link #priceEndgameWindowMinutes()}. */
    public static int priceEndgameCooldownSeconds() {
        return positiveIntEnv("TELEGRAM_PRICE_COOLDOWN_ENDGAME_SECONDS",
                DEFAULT_PRICE_COOLDOWN_ENDGAME_SECONDS);
    }

    /** How long before {@code date_end} the endgame cooldown takes over, in minutes. */
    public static int priceEndgameWindowMinutes() {
        return positiveIntEnv("TELEGRAM_PRICE_ENDGAME_WINDOW_MINUTES",
                DEFAULT_ENDGAME_WINDOW_MINUTES);
    }

    /**
     * The cooldown that applies to an auction closing at {@code dateEnd}.
     *
     * <p>Two rates rather than one because the cost of a stale figure is not constant. With
     * an hour left, a two-minute-old price is harmless and a message per bid would be spam.
     * In the closing minutes the same delay can mean the seller learns their reserve was met
     * after the auction has already ended, so the feed tightens.</p>
     */
    public static int priceCooldownSecondsFor(java.time.Instant dateEnd) {
        return cooldownFor(dateEnd, java.time.Instant.now(), priceCooldownSeconds(),
                priceEndgameCooldownSeconds(), priceEndgameWindowMinutes());
    }

    /**
     * The rate decision itself, with the clock and all three settings passed in so it can be
     * tested without depending on the machine's environment or on wall-clock timing.
     *
     * <p>A missing {@code dateEnd} gets the normal cooldown: without knowing when the auction
     * closes we cannot claim to be in its endgame, and the quieter rate is the safer guess.
     * An auction whose end has already passed counts as endgame, so the last alert of a
     * closing auction is not held back by the slower rate.</p>
     */
    static int cooldownFor(java.time.Instant dateEnd, java.time.Instant now,
                           int normalSeconds, int endgameSeconds, int windowMinutes) {
        if (dateEnd == null) {
            return normalSeconds;
        }
        java.time.Instant endgameStart = dateEnd.minusSeconds(windowMinutes * 60L);
        return now.isBefore(endgameStart) ? normalSeconds : endgameSeconds;
    }

    private static int positiveIntEnv(String name, int fallback) {
        return positiveInt(firstNonBlank(System.getenv(name)), fallback);
    }

    /**
     * Parses a positive integer, falling back to {@code fallback} for anything missing,
     * unparseable or non-positive. A typo in a tuning variable must not take the feature
     * down or, worse, turn coalescing off silently by yielding a zero cooldown.
     */
    static int positiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
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
