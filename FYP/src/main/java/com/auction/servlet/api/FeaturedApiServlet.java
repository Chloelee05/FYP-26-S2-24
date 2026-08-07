package com.auction.servlet.api;

import com.auction.dao.FeaturedListingDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/featured returns the active promoted listings.
 *
 * <p>Public endpoint feeding the featured carousel on the landing page, so guests can call it.
 * A featured slot is a paid promotion a seller buys for one of their auctions; the DAO returns
 * only slots whose promotion window is still open and whose auction is still live. Reads
 * {@code featured_listings} joined to {@code auctions} through {@link FeaturedListingDAO}.</p>
 */
@WebServlet("/api/featured")
public class FeaturedApiServlet extends ApiBase {

    private final FeaturedListingDAO featuredDAO = new FeaturedListingDAO();

    /**
     * Serves GET /api/featured. Optional {@code limit} parameter caps how many slots come back,
     * defaulting to 8 and clamped to 1..20 so a hand-crafted request cannot pull the whole table.
     * Returns {@code {"results": [...]}}.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int limit = Math.min(20, Math.max(1, parseInt(param(req, "limit"), 8)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", featuredDAO.listActiveFeatured(limit));
        ok(resp, body);
    }

    /** Parses a query parameter as an int, falling back to {@code def} when it is missing or not a number. */
    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
