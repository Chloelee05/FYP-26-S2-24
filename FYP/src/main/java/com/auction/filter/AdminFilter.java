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

@WebFilter(urlPatterns = {"/admin", "/admin/*"})
public class AdminFilter implements Filter {

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
