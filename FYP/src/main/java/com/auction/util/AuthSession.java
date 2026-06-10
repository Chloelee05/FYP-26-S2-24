package com.auction.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * A per-tab authentication session, identified by an opaque token instead of the
 * shared {@code JSESSIONID} cookie. Mirrors the small subset of
 * {@link jakarta.servlet.http.HttpSession} used by the API servlets so that
 * existing {@code getAttribute}/{@code setAttribute}/{@code invalidate} call sites
 * keep working unchanged.
 *
 * <p>Because the token lives in the browser's per-tab {@code sessionStorage} (not a
 * cookie), each tab carries its own token and therefore its own {@code AuthSession},
 * allowing different accounts to be logged in across tabs of the same window.
 */
public class AuthSession {

    private final String token;
    private final TokenStore store;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private volatile long lastAccess = System.currentTimeMillis();
    private volatile int  maxInactiveSeconds = 60 * 30; // matches the old HttpSession default
    private volatile boolean valid = true;

    AuthSession(String token, TokenStore store) {
        this.token = token;
        this.store = store;
    }

    public String getToken() {
        return token;
    }

    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    /** Drops this session from the store; subsequent lookups return null. */
    public void invalidate() {
        valid = false;
        attributes.clear();
        store.remove(token);
    }

    public void setMaxInactiveInterval(int seconds) {
        this.maxInactiveSeconds = seconds;
    }

    boolean isValid() {
        return valid;
    }

    boolean isExpired() {
        return (System.currentTimeMillis() - lastAccess) > (maxInactiveSeconds * 1000L);
    }

    void touch() {
        this.lastAccess = System.currentTimeMillis();
    }
}
