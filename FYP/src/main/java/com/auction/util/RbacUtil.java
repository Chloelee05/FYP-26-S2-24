package com.auction.util;

import com.auction.model.Role;
import jakarta.servlet.http.HttpSession;

/**
 * Role-Based Access Control helper.
 * All checks are stateless — they read the {@code userRole} and {@code userId}
 * attributes written into the session by {@code LoginServlet} on successful login.
 *
 * <p>The access model has two parts. A <em>role</em> is what an account is, and every
 * account has exactly one, which for anything created since the merge is administrator or
 * buyer. A <em>capability</em> is something an account may additionally do, and selling is
 * the only one of those at present. The two are kept separate
 * because buying and selling were merged onto a single login: a member browses and bids
 * from the same account they list items with, so there is no seller role to switch into.
 * Selling is granted by the {@code users.can_sell} column, copied into the session as
 * {@code canSell} at login, and {@link #isSeller} reads that rather than the role.</p>
 *
 * <p>Accounts predating the merge still carry the legacy {@link Role#SELLER} role, so the
 * seller check accepts either signal. Servlets are expected to call these before acting,
 * since nothing here is enforced by the session itself.</p>
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

    /**
     * Convenience: {@code true} when the session user may list items for sale.
     *
     * <p>Buying and selling share one account, so this reads the {@code canSell}
     * capability written at login. Sessions created before the merge (or against an
     * un-migrated database) still pass via the legacy SELLER role.</p>
     */
    public static boolean isSeller(HttpSession session) {
        if (!isAuthenticated(session)) return false;
        if (Boolean.TRUE.equals(session.getAttribute("canSell"))) return true;
        return hasRole(session, Role.SELLER);
    }

    /** Convenience: {@code true} only when the session user holds the BUYER role. */
    public static boolean isBuyer(HttpSession session) {
        return hasRole(session, Role.BUYER);
    }
}
