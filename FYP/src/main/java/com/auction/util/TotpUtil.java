package com.auction.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;

/**
 * Time-based One-Time Password (TOTP) utility implementing RFC 6238 / RFC 4226.
 *
 * Secrets are Base32-encoded (RFC 4648) so they are compatible with standard
 * authenticator apps (Google Authenticator, Authy, etc.).
 * Codes are 6-digit HMAC-SHA1 values with a 30-second time step and a ±1 step
 * verification window to tolerate small clock skew.
 */
public final class TotpUtil {

    private static final int    SECRET_BYTES = 20;       // 160-bit key
    private static final int    CODE_DIGITS  = 6;
    private static final long   TIME_STEP    = 30L;      // seconds
    private static final int    WINDOW       = 1;        // ±1 step tolerance
    private static final String ALGORITHM    = "HmacSHA1";
    private static final String BASE32       = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TotpUtil() {}

    /**
     * Generates a cryptographically random Base32-encoded TOTP secret key.
     * Store this (encrypted) in the user record after the user confirms setup.
     *
     * @return 32-character Base32 secret (no padding)
     */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * Computes the TOTP code for {@code base32Secret} at the current instant.
     * Intended for testing and simulated delivery — production should not expose codes.
     *
     * @param base32Secret Base32-encoded secret (case-insensitive, padding stripped)
     * @return zero-padded 6-digit code string, e.g. {@code "048321"}
     * @throws SecurityUtil.SecurityOperationException on HMAC failure
     */
    public static String generateCode(String base32Secret) {
        try {
            long counter = Instant.now().getEpochSecond() / TIME_STEP;
            int code = hotp(base32Decode(normalize(base32Secret)), counter);
            return String.format("%0" + CODE_DIGITS + "d", code);
        } catch (Exception e) {
            throw new SecurityUtil.SecurityOperationException("TOTP code generation failed", e);
        }
    }

    /**
     * Verifies a user-supplied OTP against {@code base32Secret} for the current time.
     * Accepts codes from the previous, current, and next 30-second window (±1 step).
     *
     * @param base32Secret Base32-encoded secret
     * @param code         the 6-digit code entered by the user; leading/trailing whitespace stripped
     * @return {@code true} only if the code matches any window within the tolerance
     */
    public static boolean verifyCode(String base32Secret, String code) {
        if (base32Secret == null || code == null) return false;
        String trimmed = code.trim();
        if (trimmed.length() != CODE_DIGITS) return false;
        try {
            byte[] key     = base32Decode(normalize(base32Secret));
            long   counter = Instant.now().getEpochSecond() / TIME_STEP;
            for (int delta = -WINDOW; delta <= WINDOW; delta++) {
                String expected = String.format("%0" + CODE_DIGITS + "d", hotp(key, counter + delta));
                if (expected.equals(trimmed)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Builds an {@code otpauth://totp/} URI for QR-code generation.
     * The URI can be fed to a QR library and scanned by any standard authenticator app.
     *
     * @param secret  Base32 secret from {@link #generateSecret()}
     * @param account user's email address or display name
     * @param issuer  application name shown in the authenticator app
     * @return fully-formed otpauth URI string
     */
    public static String generateTotpUri(String secret, String account, String issuer) {
        return "otpauth://totp/" + issuer + ":" + account
                + "?secret=" + secret
                + "&issuer=" + issuer
                + "&algorithm=SHA1"
                + "&digits=" + CODE_DIGITS
                + "&period=" + TIME_STEP;
    }

    // internals 

    /** Strips padding and uppercases a Base32 string. */
    private static String normalize(String s) {
        return s.toUpperCase().replaceAll("[^A-Z2-7]", "");
    }

    /** RFC 4226 HOTP: HMAC-SHA1 truncation of an 8-byte big-endian counter. */
    private static int hotp(byte[] key, long counter)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(key, ALGORITHM));
        byte[] msg  = ByteBuffer.allocate(8).putLong(counter).array();
        byte[] hash = mac.doFinal(msg);
        int offset  = hash[hash.length - 1] & 0x0F;
        int otp = ((hash[offset]     & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                |  (hash[offset + 3] & 0xFF);
        return otp % (int) Math.pow(10, CODE_DIGITS);
    }

    /** RFC 4648 Base32 encoder — no padding characters appended. */
    private static String base32Encode(byte[] data) {
        StringBuilder sb   = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte b : data) {
            buf   = (buf << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buf >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buf << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    /** RFC 4648 Base32 decoder — skips unknown characters (tolerates whitespace/padding). */
    private static byte[] base32Decode(String s) {
        byte[] out  = new byte[s.length() * 5 / 8];
        int buf = 0, bits = 0, idx = 0;
        for (char c : s.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) continue;
            buf   = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) (buf >> (bits - 8));
                bits -= 8;
            }
        }
        return idx < out.length ? Arrays.copyOf(out, idx) : out;
    }
}
