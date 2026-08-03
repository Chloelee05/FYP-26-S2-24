package com.auction.util;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force guard for {@code POST /api/auth/login}, counted per account (the lowercase
 * email that was attempted).
 *
 * <p>Deliberately not per-IP: Render sits behind a proxy, so {@code getRemoteAddr()} would
 * need {@code X-Forwarded-For} parsing to mean anything, and a shared office/NAT IP would
 * then lock out unrelated accounts. Per-account is the simpler, safer choice for the
 * project's scope, matching {@code OtpStore}'s per-identifier keying rather than
 * {@code TelegramAttemptLimiter}'s per-chat keying.</p>
 *
 * <p>Follows the same in-memory, per-{@code ConcurrentHashMap}-entry shape as
 * {@code TelegramAttemptLimiter} (a small ad-hoc "attempt limiter" class per call site is
 * this codebase's established convention — see that class's javadoc — rather than a shared
 * abstraction), but is deliberately a standalone class in {@code com.auction.util} rather
 * than the {@code telegram} package, so auth logic never depends on it.</p>
 *
 * <p>Unlike {@code TelegramAttemptLimiter}, the failure threshold and the lockout cooldown
 * are not fixed constants: callers pass them in on every call, sourced from
 * {@code PlatformSettingsDAO} ({@code login_lockout_threshold} / {@code
 * login_lockout_cooldown_minutes}, defaults 5 / 15). That also makes the class trivially
 * testable with short durations, with no need for {@code Thread.sleep} or a mocked clock.</p>
 *
 * <p>While an account is locked out, further wrong passwords do not extend the lockout —
 * only its natural expiry clears it. Otherwise an attacker with no working password could
 * keep a victim locked out forever just by continuing to guess, which would turn a
 * time-bounded cooldown into a de-facto permanent one.</p>
 */
public final class LoginAttemptLimiter {

    public static final int DEFAULT_MAX_FAILURES = 5;
    public static final int DEFAULT_COOLDOWN_MINUTES = 15;

    /** Stop tracking a quiet account once it can no longer affect a decision. */
    private static final Duration EVICT_AFTER = Duration.ofHours(6);

    private static final LoginAttemptLimiter INSTANCE = new LoginAttemptLimiter();

    private final ConcurrentHashMap<String, Attempts> byAccount = new ConcurrentHashMap<>();

    /** Public for test isolation — production code should use {@link #getInstance()}. */
    public LoginAttemptLimiter() {
    }

    public static LoginAttemptLimiter getInstance() {
        return INSTANCE;
    }

    /** True while {@code accountKey} is inside an active lockout. */
    public boolean isLockedOut(String accountKey) {
        if (accountKey == null) return false;
        Attempts a = byAccount.get(normalize(accountKey));
        return a != null && a.isLocked(Instant.now());
    }

    /** Seconds left on the lockout, or 0 when not locked out. Drives the rejection message. */
    public long lockoutSecondsRemaining(String accountKey) {
        if (accountKey == null) return 0;
        Attempts a = byAccount.get(normalize(accountKey));
        return a == null ? 0 : a.remaining(Instant.now());
    }

    /**
     * Records one wrong password for {@code accountKey}.
     *
     * @param maxFailures consecutive failures tolerated before lockout applies
     * @param cooldown    how long the lockout lasts once triggered
     * @return {@code true} if the account is now locked out (whether this call just
     *         triggered it, or it was already locked from an earlier failure)
     */
    public boolean recordFailure(String accountKey, int maxFailures, Duration cooldown) {
        if (accountKey == null) return false;
        sweep();
        Attempts a = byAccount.computeIfAbsent(normalize(accountKey), k -> new Attempts());
        return a.fail(Instant.now(), maxFailures, cooldown);
    }

    /** Clears the account's failure history after a correct password. */
    public void recordSuccess(String accountKey) {
        if (accountKey != null) byAccount.remove(normalize(accountKey));
    }

    /** Forgets every recorded failure. Test hook. */
    public void reset() {
        byAccount.clear();
    }

    private static String normalize(String accountKey) {
        return accountKey.toLowerCase();
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(EVICT_AFTER);
        for (Iterator<Map.Entry<String, Attempts>> it = byAccount.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().lastSeenBefore(cutoff)) {
                it.remove();
            }
        }
    }

    private static final class Attempts {
        private int failures;
        private Instant lockedUntil;
        private Instant lastSeen = Instant.now();

        synchronized boolean fail(Instant now, int maxFailures, Duration cooldown) {
            lastSeen = now;
            if (lockedUntil != null) {
                if (now.isBefore(lockedUntil)) {
                    // Already locked: do not count this attempt or push the expiry back.
                    return true;
                }
                lockedUntil = null;
                failures = 0;
            }
            failures++;
            if (failures >= maxFailures) {
                lockedUntil = now.plus(cooldown);
                failures = 0;
                return true;
            }
            return false;
        }

        synchronized boolean isLocked(Instant now) {
            lastSeen = now;
            if (lockedUntil == null) return false;
            if (now.isAfter(lockedUntil)) {
                lockedUntil = null;
                return false;
            }
            return true;
        }

        synchronized long remaining(Instant now) {
            if (lockedUntil == null || now.isAfter(lockedUntil)) return 0;
            return Duration.between(now, lockedUntil).getSeconds();
        }

        synchronized boolean lastSeenBefore(Instant cutoff) {
            return lastSeen.isBefore(cutoff);
        }
    }
}
