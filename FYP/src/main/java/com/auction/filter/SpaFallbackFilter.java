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
 *
 * <p>The SPA uses client-side routing, so a URL such as {@code /auctions/42} exists only in
 * the browser's router and has no matching file or servlet on the server. Without this filter
 * a page refresh or a pasted link would return 404. It sits on {@code /*} and, for requests
 * that look like a page view and match nothing else, forwards to the built {@code index.html}
 * so React can resolve the route itself.</p>
 *
 * <p>The whole filter is inert when {@code index.html} is absent, which is the case during
 * development before {@code npm run build} has produced a bundle.</p>
 */
@WebFilter("/*")
public class SpaFallbackFilter implements Filter {

    private ServletContext servletContext;

    @Override
    public void init(FilterConfig filterConfig) {
        servletContext = filterConfig.getServletContext();
    }

    /**
     * Forwards to {@code /index.html} when the request is an unmatched SPA route, and otherwise
     * hands the request on untouched. The forward keeps the original URL in the address bar,
     * which a redirect would not, so the React router still sees the path the user asked for.
     */
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

    /**
     * Decides whether this request is an SPA route. It has to say no in five separate cases,
     * each of which would otherwise break something: non-GET requests (a form POST must reach
     * its servlet), a missing bundle, the reserved prefixes below, anything that looks like a
     * static file, and any path the container can already resolve to a real resource.
     */
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

        // Reserved prefixes. /api/ must return JSON (an HTML page here would make the SPA's
        // fetch calls fail with a parse error), /uploads/ streams listing photos, /protected/
        // is the legacy JSP flow behind AuthFilter, and the rest are asset roots.
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

    /** True when the deployed web application actually contains a file at {@code path}. */
    private boolean resourceExists(String path) {
        try {
            URL url = servletContext.getResource(path);
            return url != null;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    /**
     * True when the last path segment ends in a known asset extension. A missing image should
     * come back as a 404 rather than as the HTML shell, because returning HTML for a broken
     * {@code .js} or {@code .css} request produces confusing MIME-type errors in the console.
     */
    private static boolean hasStaticExtension(String path) {
        int dot = path.lastIndexOf('.');
        // A dot that sits before the final slash belongs to a directory name, not to a file
        // extension, so a route like /user/j.doe/profile is not mistaken for an asset.
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
