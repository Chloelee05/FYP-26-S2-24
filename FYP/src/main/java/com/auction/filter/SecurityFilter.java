package com.auction.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.annotation.WebFilter;
import java.io.IOException;

@WebFilter("/*")
public class SecurityFilter implements Filter{
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletresponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletresponse;
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", "DENY");
        resp.setHeader("X-XSS-Protection", "1; mode=block");
        // Allow Bootstrap (and similar) from jsDelivr; default-src 'self' blocks external CSS/JS.
        resp.setHeader("Content-Security-Policy",
                "default-src 'self'; "
                        + "style-src 'self' https://cdn.jsdelivr.net; "
                        + "script-src 'self' https://cdn.jsdelivr.net; "
                        + "font-src 'self' https://cdn.jsdelivr.net data:");

        filterChain.doFilter(req, resp);
    }
}
