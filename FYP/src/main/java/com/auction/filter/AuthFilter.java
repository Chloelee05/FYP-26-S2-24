package com.auction.filter;

import com.auction.util.RbacUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Blocks access to protected pages for unauthenticated users.
 * After a successful logout (session invalidated), any request to /protected/*
 * will be redirected to the login page.
 *
 * <p>This is the authentication gate for the legacy JSP flow. Everything the signed-in
 * user can reach through JSP pages lives under {@code /protected/*}: the account dashboard,
 * bidding, auto-bid, watchlist, ratings, reports and the seller auction list. The filter
 * only asks "is there a logged-in user"; the finer role questions (buyer, seller) are asked
 * again inside each servlet through {@link RbacUtil}, so a missing filter mapping cannot
 * silently expose a buyer-only action.</p>
 *
 * <p>The React SPA does not go through here. It calls {@code /api/*}, where each API servlet
 * performs its own session check, so this filter and the API guards are two separate paths
 * to the same rule.</p>
 */
@WebFilter("/protected/*")
public class AuthFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    /**
     * Lets the request through when the session carries a {@code userId}, otherwise sends a
     * redirect to the login page. A redirect rather than a 403 is deliberate: these URLs are
     * pages a person typed or bookmarked, so landing on the sign-in form is the useful result.
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

        filterChain.doFilter(req, resp);
    }

    @Override
    public void destroy() {}
}
