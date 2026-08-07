package com.auction.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Central security helpers for the Online Auction Platform: password hashing,
 * field-level encryption, PDPA-oriented masking, and input sanitization.
 *
 * <p>Hashing and encryption are separate tools here because they answer different
 * questions, and mixing them up is the usual mistake in this area. A password is
 * <em>hashed</em>: the transformation is one way, so even the server cannot recover it,
 * and a login works by hashing the attempt and comparing. A phone number or address is
 * <em>encrypted</em>: the application genuinely has to display it back to its owner and to
 * a seller arranging delivery, so the transformation has to be reversible. Encrypting
 * passwords would mean a stolen key exposes every account; hashing an address would make
 * it useless.</p>
 *
 * <p>Each password gets its own random salt, which is stored alongside the hash. Two users
 * who happen to pick the same password therefore end up with different stored values, so
 * an attacker cannot spot repeats or precompute a table of hashes once and reuse it across
 * the whole database.</p>
 *
 * <p>Encryption uses AES-256 in GCM mode, which authenticates as well as encrypts:
 * tampering with the stored ciphertext makes decryption fail rather than quietly returning
 * different text. The key is derived from the {@code AUCTION_AES_SECRET} environment
 * variable, so the secret is never in the repository.</p>
 *
 * <p>The masking methods exist for PDPA. Some screens have to identify a person without
 * disclosing who they are: public bid history, the seller's view of a buyer, an alert on a
 * phone's lock screen. Masking keeps just enough for the reader to recognise a name they
 * already know while giving a stranger nothing usable.</p>
 */
public final class SecurityUtil {

    private static final Logger LOGGER = Logger.getLogger(SecurityUtil.class.getName());

    private static final String HASH_FORMAT_PREFIX = "1";
    private static final int SALT_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String AES_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Environment variable holding the master secret for field-level encryption. */
    private static final String AES_SECRET_ENV = "AUCTION_AES_SECRET";

    /**
     * Development-only fallback used when {@value #AES_SECRET_ENV} is unset, so local
     * runs and unit tests work without provisioning a secret. Deployments must set the
     * environment variable; a warning is logged once when this fallback is used.
     */
    private static final String PLACEHOLDER_AES_SECRET =
            "CHANGE_ME_AUCTION_AES_SECRET_USE_ENV_OR_KEYSTORE";

    /** Keeps the fallback warning to one line per JVM rather than one per encrypt call. */
    private static final AtomicBoolean FALLBACK_KEY_WARNED = new AtomicBoolean(false);

    private SecurityUtil() {
    }

    /**
     * Hashes a password with a per-user random salt using SHA-256.
     * <p>
     * Requirement: NFR1 (enhanced password hashing; salted SHA-256).
     * </p>
     *
     * @param password the plaintext password; must not be {@code null}
     * @return stored form {@code 1$&lt;saltBase64&gt;$&lt;hashBase64&gt;} (UTF-8 bytes salted)
     * @throws SecurityOperationException if hashing fails
     * @throws NullPointerException     if {@code password} is {@code null}
     */
    public static String hashPassword(String password) {
        Objects.requireNonNull(password, "password");
        try {
            byte[] salt = new byte[SALT_BYTES];
            SECURE_RANDOM.nextBytes(salt);
            byte[] hash = sha256(salt, password.getBytes(StandardCharsets.UTF_8));
            return HASH_FORMAT_PREFIX
                    + "$"
                    + Base64.getEncoder().encodeToString(salt)
                    + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            LOGGER.log(Level.SEVERE, "SHA-256 not available for password hashing", e);
            throw new SecurityOperationException("Password hashing failed", e);
        }
    }

    /**
     * Verifies a plaintext password against a value produced by {@link #hashPassword(String)}.
     * <p>
     * Requirement: NFR1.
     * </p>
     *
     * @param plainText     the password supplied at login; may be {@code null} (treated as non-match)
     * @param hashedResult  the stored salted hash string; may be {@code null} (treated as non-match)
     * @return {@code true} if the password matches; {@code false} otherwise
     */
    public static boolean verifyPassword(String plainText, String hashedResult) {
        if (plainText == null || hashedResult == null) {
            return false;
        }
        String[] parts = hashedResult.split("\\$", 3);
        if (parts.length != 3 || !HASH_FORMAT_PREFIX.equals(parts[0])) {
            LOGGER.warning("verifyPassword: unrecognized hash format");
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = sha256(salt, plainText.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "verifyPassword: invalid Base64 in stored hash", e);
            return false;
        } catch (NoSuchAlgorithmException e) {
            LOGGER.log(Level.SEVERE, "SHA-256 not available for password verification", e);
            throw new SecurityOperationException("Password verification failed", e);
        }
    }

    /**
     * Encrypts sensitive PII (e.g. phone, address) with AES-256 in GCM mode.
     * <p>
     * Requirement: NFR1 (secure data encryption). The key is derived from the
     * {@value #AES_SECRET_ENV} environment variable, falling back to a development
     * placeholder when it is unset.
     * </p>
     *
     * @param data plaintext; {@code null} yields {@code null}
     * @return Base64 URL-safe string containing IV + ciphertext + tag, or {@code null} if {@code data} is {@code null}
     * @throws SecurityOperationException on encryption failure
     */
    public static String encrypt(String data) {
        if (data == null) {
            return null;
        }
        try {
            SecretKey key = resolveAes256Key();
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            AlgorithmParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            byte[] cipherBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AES encryption failed", e);
            throw new SecurityOperationException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a string produced by {@link #encrypt(String)}.
     * <p>
     * Requirement: NFR1.
     * </p>
     *
     * @param encryptedData Base64 payload from {@link #encrypt(String)}; {@code null} yields {@code null}
     * @return plaintext, or {@code null} if {@code encryptedData} is {@code null}
     * @throws SecurityOperationException if the value cannot be decrypted (wrong key, corrupt data, etc.)
     */
    public static String decrypt(String encryptedData) {
        if (encryptedData == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedData);
            if (combined.length < GCM_IV_LENGTH + 1) {
                throw new IllegalArgumentException("Ciphertext too short");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] cipherOnly = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherOnly, 0, cipherOnly.length);
            SecretKey key = resolveAes256Key();
            Cipher cipher = Cipher.getInstance(AES_TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherOnly);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AES decryption failed", e);
            throw new SecurityOperationException("Decryption failed", e);
        }
    }

    /**
     * Masks an email for PDPA-aligned display (e.g. {@code lee170@mymail.sim.edu.sg}
     * {@literal ->} {@code l***0@mymail.sim.edu.sg}).
     * <p>
     * Requirements: FR1.5, NFR4.
     * </p>
     *
     * @param email raw email; {@code null} or blank returns {@code null} or unchanged empty
     * @return masked email, or {@code null} if {@code email} is {@code null}
     */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        int at = trimmed.indexOf('@');
        if (at <= 0) {
            return maskLocalToken(trimmed);
        }
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at);
        return maskLocalToken(local) + domain;
    }

    /**
     * Masks a username or display name for public bidding history (first visible, middle hidden,
     * last character visible when the token is long enough).
     * <p>
     * Requirements: FR1.5, NFR4.
     * </p>
     *
     * @param username display name; {@code null} returns {@code null}
     * @return masked name (whitespace between words preserved); {@code null} if input {@code null}
     */
    public static String maskUsername(String username) {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        StringBuilder out = new StringBuilder();
        String[] parts = trimmed.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(maskLocalToken(parts[i]));
        }
        return out.toString();
    }

    /**
     * Fully masks a username for non-leading bidders in public bid history (SCRUM-58).
     * No characters from the original name are revealed — unlike {@link #maskUsername}
     * which keeps the first and last character visible for the current leader.
     *
     * @param username display name; {@code null} or blank returns {@code "****"}
     */
    public static String maskUsernameFully(String username) {
        if (username == null || username.isBlank()) {
            return "****";
        }
        return "****";
    }

    /**
     * Masks a phone number for public listings / PDPA (keeps only the last 4 digits, normalised digits only).
     *
     * @param phone plaintext phone; {@code null} or blank returns {@code null}
     * @return masked form e.g. {@code ****5678}, or {@code null}
     */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() <= 2) {
            return "****";
        }
        String tail = digits.substring(digits.length() - Math.min(4, digits.length()));
        return "****" + tail;
    }

    /**
     * Masks a street address for public preview (first/last chars only).
     */
    public static String maskAddress(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= 4) {
            return "****";
        }
        int keep = Math.min(3, trimmed.length() / 4);
        return trimmed.substring(0, keep) + "***" + trimmed.substring(trimmed.length() - 2);
    }

    /**
     * Trims input, performs HTML entity escaping for XSS mitigation, and doubles single quotes
     * for ANSI SQL string-literal escaping as a secondary control. Primary defenses must remain
     * parameterized queries / {@code PreparedStatement} and contextual output encoding.
     * Prefer storing raw text in the database with {@code PreparedStatement} and escaping on
     * output; use this helper where a single normalized “safe string” is required by convention.
     * <p>
     * Requirement: NFR3.
     * </p>
     *
     * @param input user-controlled text; {@code null} returns {@code null}
     * @return sanitized text, or {@code null} if {@code input} was {@code null}
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        try {
            String trimmed = input.trim();
            String sqlEscaped = escapeSqlStringLiteral(trimmed);
            return escapeHtml(sqlEscaped);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "sanitize: unexpected failure, returning empty string", e);
            return "";
        }
    }

    /** Digests the salt followed by the value, which is what makes the salt take effect. */
    private static byte[] sha256(byte[] salt, byte[] value) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(salt);
        md.update(value);
        return md.digest();
    }

    /**
     * Turns the configured secret into a key of the exact length AES-256 requires. The
     * secret is an arbitrary string, so it is run through SHA-256 to get 32 bytes rather
     * than being truncated or padded to fit.
     */
    private static SecretKey resolveAes256Key() throws NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha256.digest(aesSecret().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * The master secret behind {@link #encrypt(String)} / {@link #decrypt(String)}: the
     * {@value #AES_SECRET_ENV} environment variable, or the development placeholder.
     * The secret itself is never logged.
     */
    private static String aesSecret() {
        String env = System.getenv(AES_SECRET_ENV);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        if (FALLBACK_KEY_WARNED.compareAndSet(false, true)) {
            LOGGER.warning(AES_SECRET_ENV + " is not set — falling back to the built-in development "
                    + "encryption key. Set " + AES_SECRET_ENV + " in every deployed environment.");
        }
        return PLACEHOLDER_AES_SECRET;
    }

    /**
     * The masking rule the email and username helpers share: keep the first and last
     * character and hide the middle. Short tokens reveal less, because keeping both ends of
     * a two-character name would not hide anything at all.
     */
    private static String maskLocalToken(String token) {
        int len = token.length();
        if (len <= 1) {
            return "*";
        }
        if (len == 2) {
            return token.charAt(0) + "*";
        }
        return token.charAt(0) + "***" + token.charAt(len - 1);
    }

    /** Replaces the five characters that would otherwise be read as HTML markup. */
    private static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Doubles {@code '} per SQL string literal rules (defense in depth only).
     */
    private static String escapeSqlStringLiteral(String s) {
        return s.replace("'", "''");
    }

    /**
     * Unchecked exception for cryptographic or hashing failures after logging.
     */
    public static final class SecurityOperationException extends RuntimeException {
        public SecurityOperationException(String message) {
            super(message);
        }

        public SecurityOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
