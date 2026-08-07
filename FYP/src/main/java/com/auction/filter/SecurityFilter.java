package com.auction.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.annotation.WebFilter;
import java.io.IOException;

/**
 * Attaches the browser-side security headers to every response leaving the application.
 * Mapped to {@code /*}, so it covers the React SPA, the legacy JSP pages, the {@code /api/*}
 * JSON endpoints and static assets alike. It never rejects a request: it only sets headers
 * and passes control on, which is why it can safely sit in front of everything.
 * The Content Security Policy is the important part, and each directive below is there for
 * a concrete reason rather than copied from a template.
 *
 * <p>Chain position: all five filters in this package are declared with {@code @WebFilter} and
 * none appear in {@code web.xml}, so the container picks the relative order of the ones whose
 * patterns match a given URL (Tomcat orders annotated filters by class name). That is workable
 * here only because no filter depends on another having run first. This one and
 * {@link SpaFallbackFilter} match every URL; {@link AuthFilter}, {@link AdminFilter} and
 * {@link CorsFilter} join the chain only for {@code /protected/*}, {@code /admin*} and
 * {@code /api/*} respectively. Headers set here survive a forward to a JSP or to the SPA shell,
 * so the CSP still reaches the browser on those paths.</p>
 */
@WebFilter("/*")
public class SecurityFilter implements Filter{
    /**
     * Sets four response headers, then continues the chain.
     * X-Content-Type-Options stops the browser guessing a MIME type different from the one we
     * declared, X-Frame-Options DENY blocks the site being framed for clickjacking, and
     * X-XSS-Protection is a legacy header kept for older browsers. The Content-Security-Policy
     * that follows is the actual defence against injected scripts and styles.
     */
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
        // img-src adds blob: because listing/profile photo pickers preview the chosen file via
        // URL.createObjectURL before it has finished uploading; without it here, default-src's
        // 'self' does not cover blob: and the browser silently refuses to paint the preview.
        // script-src is the tightest of the four: only our own bundle plus jsDelivr, and no
        // 'unsafe-inline', so an attacker who manages to inject a <script> tag or an onclick
        // attribute into a listing title or a Q&A answer still gets nothing executed.
        // The trailing data: in img-src and font-src covers inlined base64 assets that
        // Bootstrap ships (icon sprites and the icon webfont), which would otherwise be blocked.
        resp.setHeader("Content-Security-Policy",
                "default-src 'self'; "
                        + "style-src 'self' https://cdn.jsdelivr.net https://fonts.googleapis.com; "
                        + "script-src 'self' https://cdn.jsdelivr.net; "
                        + "img-src 'self' blob: data:; "
                        + "font-src 'self' https://cdn.jsdelivr.net https://fonts.gstatic.com data:");

        filterChain.doFilter(req, resp);
    }
}
