package com.auction.telegram;

import com.auction.dao.LandingContentDAO;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the user-facing Telegram wording from {@code landing_content} so an administrator
 * can reword the consent notice and the bot's replies without a redeploy.
 *
 * <p>Only explanatory copy is stored: the connect dialog's heading, body, event list and
 * PDPA notice, plus the bot's own messages. Button labels and validation errors stay in
 * code — they are UI mechanics rather than content.</p>
 *
 * <p>Every lookup carries a built-in default, so a database that has not been migrated
 * yet degrades to sensible English instead of blank messages.</p>
 */
public final class TelegramCopy {

    /** Prefix identifying the rows this class owns. */
    public static final String KEY_PREFIX = "telegram.";

    private static final long CACHE_TTL_MS = 60_000;

    private static volatile Map<String, String> cached;
    private static volatile long cachedAt;

    private static LandingContentDAO dao = new LandingContentDAO();

    private TelegramCopy() {
    }

    /** Test hook. */
    static void setDao(LandingContentDAO replacement) {
        dao = replacement;
        invalidate();
    }

    public static void invalidate() {
        cached = null;
        cachedAt = 0;
    }

    /** The configured text for {@code key}, or {@code fallback} when it is missing. */
    public static String get(String key, String fallback) {
        String value = all().get(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Every {@code telegram.*} row, for the connect dialog to render. */
    public static Map<String, String> dialogCopy() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : all().entrySet()) {
            if (e.getKey().startsWith(KEY_PREFIX) && !e.getKey().startsWith("telegram.bot.")) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static Map<String, String> all() {
        Map<String, String> snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt <= CACHE_TTL_MS) {
            return snapshot;
        }
        try {
            snapshot = dao.findAllValues();
        } catch (RuntimeException e) {
            // Fail soft: the caller's built-in defaults are better than an error.
            snapshot = Collections.emptyMap();
        }
        cached = snapshot;
        cachedAt = System.currentTimeMillis();
        return snapshot;
    }
}
