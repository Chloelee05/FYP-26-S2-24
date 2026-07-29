package com.auction.servlet.api;

import com.auction.dao.LandingContentDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * GET /api/landing-content — public, unauthenticated landing page copy as a flat
 * {@code key -> text} map, so the marketing wording is administrator-editable instead
 * of hardcoded in the React bundle.
 *
 * <p>Kept separate from {@code /api/stats} because the two have different lifecycles:
 * stats are recomputed constantly and may legitimately be empty, whereas copy changes
 * only when an admin saves and must appear immediately afterwards. A sibling endpoint
 * lets {@link #invalidateCache()} drop the copy cache on save without discarding the
 * stats cache, and keeps a failure in one from emptying the other.</p>
 *
 * <p>Fail-soft: any error yields an empty map, and the landing page falls back to its
 * built-in defaults rather than rendering blank.</p>
 */
@WebServlet("/api/landing-content")
public class LandingContentApiServlet extends ApiBase {

    private static final long CACHE_TTL_MS = 60_000;

    /** Shared with the admin write servlet, which invalidates it on save. */
    private static volatile Map<String, String> cached;
    private static volatile long cachedAt;

    private LandingContentDAO landingContentDAO = new LandingContentDAO();

    /** Test hook */
    public void setLandingContentDAO(LandingContentDAO dao) { this.landingContentDAO = dao; }

    /** Called after an admin edit so the next request re-reads the database. */
    public static void invalidateCache() {
        cached = null;
        cachedAt = 0;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, String> body = cached;
        if (body == null || System.currentTimeMillis() - cachedAt > CACHE_TTL_MS) {
            try {
                body = landingContentDAO.findAllValues();
                cached = body;
                cachedAt = System.currentTimeMillis();
            } catch (RuntimeException e) {
                getServletContext().log("landing content error", e);
                // Fail soft: the landing page should render even without editable copy.
                body = Collections.emptyMap();
            }
        }
        ok(resp, body);
    }
}
