package com.auction.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenStore + AuthSession")
class TokenStoreTest {

    @Test
    @DisplayName("create → get returns session with same token")
    void createAndGet() {
        AuthSession created = TokenStore.getInstance().create();
        assertNotNull(created.getToken());
        AuthSession fetched = TokenStore.getInstance().get(created.getToken());
        assertSame(created, fetched);
    }

    @Test
    @DisplayName("remove invalidates token")
    void removeInvalidates() {
        AuthSession s = TokenStore.getInstance().create();
        String token = s.getToken();
        TokenStore.getInstance().remove(token);
        assertNull(TokenStore.getInstance().get(token));
    }

    @Test
    @DisplayName("get(null) and get(unknown) return null")
    void getMissing() {
        assertNull(TokenStore.getInstance().get(null));
        assertNull(TokenStore.getInstance().get("not-a-real-token"));
    }

    @Test
    @DisplayName("session attributes round-trip")
    void sessionAttributes() {
        AuthSession s = TokenStore.getInstance().create();
        s.setAttribute("userId", 42);
        s.setAttribute("userRole", "BUYER");
        assertEquals(42, s.getAttribute("userId"));
        assertEquals("BUYER", s.getAttribute("userRole"));
    }

    @Test
    @DisplayName("invalidate marks session invalid")
    void invalidate() {
        AuthSession s = TokenStore.getInstance().create();
        String token = s.getToken();
        s.invalidate();
        assertFalse(s.isValid());
        assertNull(TokenStore.getInstance().get(token));
    }
}
