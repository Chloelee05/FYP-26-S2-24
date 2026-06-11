package com.auction.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Serves the React SPA {@code index.html} for client-side routes in production.
 * Skips API, uploads, legacy protected endpoints, and static assets.
 */
@WebFilter("/*")
public class SpaFallbackFilter implements Filter {

    private ServletContext servletContext;

    @Override
    public void init(FilterConfig filterConfig) {
        servletContext = filterConfig.getServletContext();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if (shouldServeSpa(req)) {
            RequestDispatcher dispatcher = servletContext.getRequestDispatcher("/index.html");
            if (dispatcher != null) {
                dispatcher.forward(req, resp);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean shouldServeSpa(HttpServletRequest req) {
        if (!"GET".equalsIgnoreCase(req.getMethod()) && !"HEAD".equalsIgnoreCase(req.getMethod())) {
            return false;
        }

        if (!resourceExists("/index.html")) {
            return false;
        }

        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        String path = uri.substring(contextPath.length());

        if (path.isEmpty()) {
            path = "/";
        }

        if (path.startsWith("/api/")
                || path.startsWith("/uploads/")
                || path.startsWith("/protected/")
                || path.startsWith("/WEB-INF/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/assets/")) {
            return false;
        }

        if (hasStaticExtension(path)) {
            return false;
        }

        if (resourceExists(path)) {
            return false;
        }

        return true;
    }

    private boolean resourceExists(String path) {
        try {
            URL url = servletContext.getResource(path);
            return url != null;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private static boolean hasStaticExtension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot <= path.lastIndexOf('/')) {
            return false;
        }
        String ext = path.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "js":
            case "css":
            case "map":
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "webp":
            case "svg":
            case "ico":
            case "woff":
            case "woff2":
            case "ttf":
            case "json":
            case "txt":
            case "xml":
                return true;
            default:
                return false;
        }
    }

    @Override
    public void destroy() {}
}
