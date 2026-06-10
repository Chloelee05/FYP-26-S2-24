package com.auction.util;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store mapping opaque auth tokens to {@link AuthSession} instances.
 *
 * <p>Replaces cookie-based {@code HttpSession} lookup for {@code /api/*}: a token is
 * minted at login and handed to the client, which stores it in per-tab
 * {@code sessionStorage} and sends it back as an {@code Authorization: Bearer} header.
 * Each tab therefore resolves to its own session, enabling per-tab accounts.
 *
 * <p>Like the previous in-memory {@code HttpSession}, tokens do not survive a server
 * restart and are not shared across nodes — acceptable for a single Tomcat instance.
 */
public final class TokenStore {

    private static final TokenStore INSTANCE = new TokenStore();

    public static TokenStore getInstance() {
        return INSTANCE;
    }

    private final Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private TokenStore() {}

    /** Mints a new token and its (empty) session, and stores it. */
    public AuthSession create() {
        String token = newToken();
        AuthSession session = new AuthSession(token, this);
        sessions.put(token, session);
        return session;
    }

    /** Returns the live session for the token, or null if missing/expired. */
    public AuthSession get(String token) {
        if (token == null) return null;
        AuthSession session = sessions.get(token);
        if (session == null) return null;
        if (!session.isValid() || session.isExpired()) {
            sessions.remove(token);
            return null;
        }
        session.touch();
        return session;
    }

    public void remove(String token) {
        if (token != null) sessions.remove(token);
    }

    private String newToken() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
