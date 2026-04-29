package com.auction.util;

import com.auction.model.Role;
import jakarta.servlet.http.HttpSession;

/**
 * Role-Based Access Control helper.
 * All checks are stateless — they read the {@code userRole} and {@code userId}
 * attributes written into the session by {@code LoginServlet} on successful login.
 */
public final class RbacUtil {

    private RbacUtil() {}

    /**
     * Returns {@code true} if the session belongs to a fully authenticated user
     * (i.e. a non-null {@code userId} attribute is present).
     *
     * @param session current HTTP session; {@code null} returns {@code false}
     */
    public static boolean isAuthenticated(HttpSession session) {
        return session != null && session.getAttribute("userId") != null;
    }

    /**
     * Returns {@code true} if the authenticated user's role matches at least one
     * of {@code allowedRoles}.
     *
     * @param session      current HTTP session
     * @param allowedRoles one or more roles that are permitted
     * @return {@code false} when the session is unauthenticated, the role is absent,
     *         the role string is unrecognised, or none of {@code allowedRoles} match
     */
    public static boolean hasRole(HttpSession session, Role... allowedRoles) {
        if (!isAuthenticated(session) || allowedRoles == null) return false;
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

    /** Convenience: {@code true} only when the session user holds the ADMIN role. */
    public static boolean isAdmin(HttpSession session) {
        return hasRole(session, Role.ADMIN);
    }

    /** Convenience: {@code true} only when the session user holds the SELLER role. */
    public static boolean isSeller(HttpSession session) {
        return hasRole(session, Role.SELLER);
    }

    /** Convenience: {@code true} only when the session user holds the BUYER role. */
    public static boolean isBuyer(HttpSession session) {
        return hasRole(session, Role.BUYER);
    }
}
