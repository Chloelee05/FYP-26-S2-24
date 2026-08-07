package com.auction.filter;

import com.auction.util.RbacUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Guards the whole admin area of the legacy JSP flow. It matches both {@code /admin} and
 * {@code /admin/*}, which covers the dashboard, the user table, listings moderation,
 * categories and analytics.
 * Because this filter runs first, the admin servlets behind it do not repeat the role check;
 * that is why the mapping has to include the bare {@code /admin} path as well as the subtree,
 * otherwise {@link com.auction.servlet.admin.AdminRootServlet} would be reachable unguarded.
 * The equivalent check for the React SPA lives inside {@code AdminApiServlet} on {@code /api/admin/*}.
 */
@WebFilter(urlPatterns = {"/admin", "/admin/*"})
public class AdminFilter implements Filter {

    /**
     * Applies two checks in order, and the order matters. An anonymous visitor is redirected to
     * the login page because they may simply need to sign in. A signed-in non-admin gets a 403
     * instead: bouncing them to a login form they have already passed would be misleading, and
     * the response says nothing about whether the admin page exists.
     */
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletResponse;

        HttpSession session = req.getSession(false);
        if (!RbacUtil.isAuthenticated(session)) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }
        if (!RbacUtil.isAdmin(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Admin access required.");
            return;
        }
        filterChain.doFilter(req, resp);
    }
}
