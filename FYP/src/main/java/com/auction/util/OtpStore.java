package com.auction.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, thread-safe store for one-time passwords used in the password-reset flow.
 * Each OTP is a zero-padded 6-digit code tied to a lowercase identifier (email or phone)
 * and expires after {@link #OTP_TTL_SECONDS} seconds.
 *
 * Simulated delivery: callers receive the generated OTP string and are responsible for
 * forwarding it to the user (email API, SMS gateway, etc.).
 *
 * <p>A wrong code counts against {@link #MAX_ATTEMPTS}; spending them all destroys the OTP,
 * so the caller must request a fresh one. Without that cap a 6-digit code sitting in a
 * 5-minute window is enumerable, and the reset endpoint is unauthenticated. This mirrors
 * {@code TelegramAttemptLimiter}, which guards the equivalent 6-digit linking code — the
 * difference is that this counter lives on the OTP itself rather than per caller, because
 * password reset has no chat id or session to key a limiter on.</p>
 */
public class OtpStore {

    static final int OTP_TTL_SECONDS = 300; // 5-minute window
    /** Wrong guesses tolerated before the OTP is destroyed. */
    public static final int MAX_ATTEMPTS = 5;
    private static final int OTP_RANGE = 1_000_000; // produces 000000–999999
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, OtpEntry> store = new ConcurrentHashMap<>();
    private final int ttlSeconds;

    public OtpStore() {
        this.ttlSeconds = OTP_TTL_SECONDS;
    }

    /** Package-private: allows tests to inject a short TTL without touching production code. */
    OtpStore(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Generates a cryptographically random 6-digit OTP, stores it against {@code identifier},
     * and returns the OTP string for simulated delivery.
     * Any previous OTP for the same identifier is overwritten.
     *
     * @param identifier lowercase email address or phone number
     * @return the generated OTP (e.g. {@code "048321"})
     */
    public String generateAndStore(String identifier) {
        String otp = String.format("%06d", SECURE_RANDOM.nextInt(OTP_RANGE));
        store.put(identifier.toLowerCase(),
                new OtpEntry(otp, Instant.now().plusSeconds(ttlSeconds)));
        return otp;
    }

    /**
     * Verifies that {@code otp} matches the stored value for {@code identifier} and has
     * not expired. An expired entry is removed on first access (lazy eviction), and the
     * entry is also removed once {@link #MAX_ATTEMPTS} wrong codes have been supplied.
     *
     * @param identifier lowercase email or phone
     * @param otp        the code entered by the user; leading/trailing whitespace is stripped
     * @return {@code true} only if the OTP matches, is still within its TTL, and the
     *         attempt allowance has not been spent
     */
    public boolean verify(String identifier, String otp) {
        if (identifier == null || otp == null) return false;
        String key = identifier.toLowerCase();
        OtpEntry entry = store.get(key);
        if (entry == null) return false;
        if (Instant.now().isAfter(entry.expiry)) {
            store.remove(key);
            return false;
        }
        if (entry.otp.equals(otp.trim())) {
            return true;
        }
        // Burn the code rather than the caller: there is no session or chat id to rate-limit
        // on here, so the allowance has to travel with the OTP.
        if (entry.recordFailure() >= MAX_ATTEMPTS) {
            store.remove(key);
        }
        return false;
    }

    /**
     * Removes the OTP for {@code identifier} so it cannot be reused after a successful reset.
     *
     * @param identifier lowercase email or phone
     */
    public void invalidate(String identifier) {
        if (identifier != null) store.remove(identifier.toLowerCase());
    }

    private static final class OtpEntry {
        final String otp;
        final Instant expiry;
        private int failures;

        OtpEntry(String otp, Instant expiry) {
            this.otp = otp;
            this.expiry = expiry;
        }

        /** Records one wrong code and returns the running total. */
        synchronized int recordFailure() {
            return ++failures;
        }
    }
}
