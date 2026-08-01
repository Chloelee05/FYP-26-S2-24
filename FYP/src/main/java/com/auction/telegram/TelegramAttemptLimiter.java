package com.auction.telegram;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force guard for the manual 6-digit linking code, counted per Telegram chat.
 *
 * <p>This is the limit that actually protects the OTP. An attacker cannot mint codes —
 * that needs a logged-in session — but anyone can message the bot and guess, and a
 * six-digit space is small. Counting failures per chat caps a single Telegram account at
 * a handful of guesses per window regardless of which victim it is aiming at.</p>
 *
 * <p>In-memory and per-instance, following the {@code OtpStore} precedent: the counters
 * reset if the app restarts, and a multi-instance deployment would count separately.
 * Both are acceptable here because the codes expire in ten minutes anyway, which bounds
 * the total guesses available against any one code far below 10<sup>6</sup>.</p>
 */
public final class TelegramAttemptLimiter {

    /** Failures tolerated inside {@link #WINDOW} before the block applies. */
    public static final int MAX_FAILURES = 5;
    public static final Duration WINDOW = Duration.ofMinutes(10);
    public static final Duration BLOCK = Duration.ofHours(1);

    /** Stop tracking a quiet chat once its block and window have both lapsed. */
    private static final Duration EVICT_AFTER = Duration.ofHours(2);

    private static final TelegramAttemptLimiter INSTANCE = new TelegramAttemptLimiter();

    private final ConcurrentHashMap<String, Attempts> byChat = new ConcurrentHashMap<>();

    public TelegramAttemptLimiter() {
    }

    public static TelegramAttemptLimiter getInstance() {
        return INSTANCE;
    }

    /**
     * True when this chat has spent its guesses and is still inside the block.
     *
     * @param chatKey opaque per-chat key — pass a hash, never a raw chat id
     */
    public boolean isBlocked(String chatKey) {
        if (chatKey == null) {
            return false;
        }
        Attempts a = byChat.get(chatKey);
        return a != null && a.isBlocked(Instant.now());
    }

    /** Records one wrong code. Returns true if that failure triggered the block. */
    public boolean recordFailure(String chatKey) {
        if (chatKey == null) {
            return false;
        }
        sweep();
        Attempts a = byChat.computeIfAbsent(chatKey, k -> new Attempts());
        return a.fail(Instant.now());
    }

    /** Clears the chat's history after a code is accepted. */
    public void recordSuccess(String chatKey) {
        if (chatKey != null) {
            byChat.remove(chatKey);
        }
    }

    /** Seconds left on the block, or 0 when not blocked. Drives the bot's reply wording. */
    public long blockedSecondsRemaining(String chatKey) {
        if (chatKey == null) {
            return 0;
        }
        Attempts a = byChat.get(chatKey);
        return a == null ? 0 : a.remaining(Instant.now());
    }

    /** Forgets every recorded failure. */
    public void reset() {
        byChat.clear();
    }

    /** Drops entries that can no longer affect a decision, so the map cannot grow forever. */
    private void sweep() {
        Instant cutoff = Instant.now().minus(EVICT_AFTER);
        for (Iterator<Map.Entry<String, Attempts>> it = byChat.entrySet().iterator(); it.hasNext(); ) {
            Attempts a = it.next().getValue();
            if (a.lastSeenBefore(cutoff)) {
                it.remove();
            }
        }
    }

    private static final class Attempts {
        private final Deque<Instant> failures = new ArrayDeque<>();
        private Instant blockedUntil;
        private Instant lastSeen = Instant.now();

        synchronized boolean fail(Instant now) {
            lastSeen = now;
            Instant windowStart = now.minus(WINDOW);
            while (!failures.isEmpty() && failures.peekFirst().isBefore(windowStart)) {
                failures.pollFirst();
            }
            failures.addLast(now);
            if (failures.size() >= MAX_FAILURES) {
                blockedUntil = now.plus(BLOCK);
                failures.clear();
                return true;
            }
            return false;
        }

        synchronized boolean isBlocked(Instant now) {
            lastSeen = now;
            if (blockedUntil == null) {
                return false;
            }
            if (now.isAfter(blockedUntil)) {
                blockedUntil = null;
                return false;
            }
            return true;
        }

        synchronized long remaining(Instant now) {
            if (blockedUntil == null || now.isAfter(blockedUntil)) {
                return 0;
            }
            return Duration.between(now, blockedUntil).getSeconds();
        }

        synchronized boolean lastSeenBefore(Instant cutoff) {
            return lastSeen.isBefore(cutoff);
        }
    }
}
