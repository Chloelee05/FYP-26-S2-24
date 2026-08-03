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
        // default-src 'self' blocks every external asset, so each origin the app actually
        // loads from has to be named. Google Fonts serves the stylesheet from
        // fonts.googleapis.com and the font files themselves from fonts.gstatic.com, which is
        // why the two origins land in different directives. jsDelivr stays because the legacy
        // JSP pages (still mapped, e.g. POST /forgot-password) pull Bootstrap from it.
        resp.setHeader("Content-Security-Policy",
                "default-src 'self'; "
                        + "style-src 'self' https://cdn.jsdelivr.net https://fonts.googleapis.com; "
                        + "script-src 'self' https://cdn.jsdelivr.net; "
                        + "font-src 'self' https://cdn.jsdelivr.net https://fonts.gstatic.com data:");

        filterChain.doFilter(req, resp);
    }
}
