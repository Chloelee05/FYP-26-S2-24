package com.auction.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Allows the React dev-server (http://localhost:3000) to call /api/* endpoints
 * with session cookies (credentials: true).
 *
 * <p>Only {@code /api/*} is matched, so the JSP pages and static assets are untouched.
 * The allowed origin is a single hard-coded value rather than a reflection of whatever
 * {@code Origin} header arrives, which is the point: with
 * {@code Access-Control-Allow-Credentials: true} a wildcard or a reflected origin would let
 * any site on the internet make authenticated calls using the visitor's JSESSIONID.</p>
 *
 * <p>In production the SPA is served from the same origin as the API, so the browser never
 * issues a cross-origin request and these headers are simply ignored.</p>
 */
@WebFilter("/api/*")
public class CorsFilter implements Filter {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    /**
     * Writes the CORS headers, then either answers a preflight or continues the chain.
     * A browser sends {@code OPTIONS} before any request carrying a JSON content type or a
     * custom header, and that preflight must be answered here with 204 and no body: passing it
     * down the chain would reach a servlet that has no {@code doOptions} handling for it.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest)  request;
        HttpServletResponse resp = (HttpServletResponse) response;

        resp.setHeader("Access-Control-Allow-Origin",      ALLOWED_ORIGIN);
        resp.setHeader("Access-Control-Allow-Credentials", "true");
        resp.setHeader("Access-Control-Allow-Methods",     "GET, POST, PUT, DELETE, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers",     "Content-Type, Accept, X-Requested-With, Authorization, X-Auth-Token");
        resp.setHeader("Access-Control-Max-Age",           "3600");

        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {}
}
