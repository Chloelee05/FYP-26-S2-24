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
 */
@WebFilter("/protected/*")
public class AuthFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

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
