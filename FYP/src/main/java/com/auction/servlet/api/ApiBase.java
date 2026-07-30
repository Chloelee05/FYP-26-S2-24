package com.auction.servlet.api;

import com.auction.model.Role;
import com.auction.util.AuthSession;
import com.auction.util.TokenStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Collections;

/**
 * Base class for all /api/* JSON servlets.
 * Provides a shared ObjectMapper and helper methods for writing JSON responses
 * and enforcing authentication/role checks.
 */
public abstract class ApiBase extends HttpServlet {

    protected static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    protected void json(HttpServletResponse resp, int status, Object data) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        resp.setStatus(status);
        MAPPER.writeValue(resp.getWriter(), data);
    }

    protected void ok(HttpServletResponse resp, Object data) throws IOException {
        json(resp, 200, data);
    }

    protected void okMsg(HttpServletResponse resp, String message) throws IOException {
        json(resp, 200, Collections.singletonMap("message", message));
    }

    protected void error(HttpServletResponse resp, int status, String message) throws IOException {
        json(resp, status, Collections.singletonMap("error", message));
    }

    protected void badRequest(HttpServletResponse resp, String message) throws IOException {
        error(resp, 400, message);
    }

    protected void unauthorized(HttpServletResponse resp) throws IOException {
        error(resp, 401, "Authentication required");
    }

    protected void forbidden(HttpServletResponse resp) throws IOException {
        error(resp, 403, "Access denied");
    }

    protected void serverError(HttpServletResponse resp, String message) throws IOException {
        error(resp, 500, message);
    }

    /**
     * Resolves the per-tab {@link AuthSession} from the request's bearer token,
     * or null if absent/expired. The token is read from the
     * {@code Authorization: Bearer <token>} header (falling back to {@code X-Auth-Token}).
     */
    protected AuthSession authSession(HttpServletRequest req) {
        return TokenStore.getInstance().get(bearerToken(req));
    }

    /** Extracts the raw token from the Authorization/X-Auth-Token headers, or null. */
    protected String bearerToken(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = auth.substring(7).trim();
            if (!t.isEmpty()) return t;
        }
        String x = req.getHeader("X-Auth-Token");
        return (x == null || x.isBlank()) ? null : x.trim();
    }

    /** Returns the session userId, or null if not logged in. */
    protected Integer sessionUserId(HttpServletRequest req) {
        AuthSession s = authSession(req);
        if (s == null) return null;
        Object id = s.getAttribute("userId");
        if (id instanceof Integer) return (Integer) id;
        if (id instanceof Number)  return ((Number) id).intValue();
        return null;
    }

    /** Returns the session userRole string (e.g. "BUYER"), or null. */
    protected String sessionRole(HttpServletRequest req) {
        AuthSession s = authSession(req);
        return s == null ? null : (String) s.getAttribute("userRole");
    }

    // ── Role checks (token-based, mirror RbacUtil for the legacy HttpSession path) ──

    /** True if the session is authenticated and its role matches one of {@code allowedRoles}. */
    protected boolean hasRole(AuthSession session, Role... allowedRoles) {
        if (session == null || session.getAttribute("userId") == null || allowedRoles == null) return false;
        String roleStr = (String) session.getAttribute("userRole");
        if (roleStr == null) return false;
        try {
            Role userRole = Role.valueOf(roleStr);
            for (Role allowed : allowedRoles) {
                if (userRole == allowed) return true;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        return false;
    }

    protected boolean isAdmin(AuthSession session)  { return hasRole(session, Role.ADMIN);  }

    /** Exact role match. Prefer {@link #canBuy(AuthSession)} for buyer-side authorisation. */
    protected boolean isBuyer(AuthSession session)  { return hasRole(session, Role.BUYER);  }

    /**
     * True when the session may take buyer-side actions — bidding, watchlisting,
     * asking questions, rating and reporting.
     *
     * <p>Buying and selling share one account, so this is not an exclusive role check:
     * a seller-capable account (and a legacy SELLER-role account) buys on the same
     * login it sells from. Only admins are excluded, to keep moderation impartial.
     * Per-listing limits such as "not your own auction" are enforced by the DAOs.</p>
     */
    protected boolean canBuy(AuthSession session) {
        if (session == null || session.getAttribute("userId") == null) return false;
        return !isAdmin(session);
    }

    /** Request-scoped form of {@link #canBuy(AuthSession)}. */
    protected boolean canBuy(HttpServletRequest req) {
        return canBuy(authSession(req));
    }

    /**
     * True when the session may list items for sale.
     *
     * <p>Buying and selling share one account, so this reads the {@code canSell}
     * capability written at login rather than a SELLER role. Sessions created before
     * the merge (or against an un-migrated database) still pass via the legacy role.</p>
     */
    protected boolean isSeller(AuthSession session) {
        if (session == null || session.getAttribute("userId") == null) return false;
        if (Boolean.TRUE.equals(session.getAttribute("canSell"))) return true;
        return hasRole(session, Role.SELLER);
    }

    /** Request-scoped form of {@link #isSeller(AuthSession)}. */
    protected boolean canSell(HttpServletRequest req) {
        return isSeller(authSession(req));
    }

    /**
     * Writes 403 and returns false when the caller cannot sell.
     * Use as a guard: {@code if (!requireSeller(req, resp)) return;}
     */
    protected boolean requireSeller(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return false;
        if (!canSell(req)) {
            forbidden(resp);
            return false;
        }
        return true;
    }

    /**
     * Writes 401 and returns false if the caller is not logged in.
     * Use as a guard: {@code if (!requireAuth(req, resp)) return;}
     */
    protected boolean requireAuth(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (sessionUserId(req) == null) {
            unauthorized(resp);
            return false;
        }
        return true;
    }

    /**
     * Writes 403 and returns false if the caller does not hold the required role.
     * Implies authentication check.
     */
    protected boolean requireRole(HttpServletRequest req, HttpServletResponse resp, String role)
            throws IOException {
        if (!requireAuth(req, resp)) return false;
        if (!role.equalsIgnoreCase(sessionRole(req))) {
            forbidden(resp);
            return false;
        }
        return true;
    }

    /** Extracts a non-blank parameter value, or null. */
    protected String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
