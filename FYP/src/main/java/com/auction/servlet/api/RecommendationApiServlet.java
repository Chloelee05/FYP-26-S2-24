package com.auction.servlet.api;

import com.auction.dao.RecommendationDAO;
import com.auction.model.SearchResultItem;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET  /api/recommendations?limit=8            — personalised recommendations
 * GET  /api/recommendations/trending?limit=8   — trending auctions, never personalised
 * GET  /api/recommendations/similar?auctionId= — "buyers who bid on this also bid on…"
 * POST /api/recommendations/dismiss  auctionId — hide a recommendation (auth required)
 * POST /api/recommendations/events   type=impression|click, auctionId (or auctionIds CSV)
 *
 * <p>Returns personalised recommendations (item-based collaborative filtering) for the
 * logged-in buyer, or trending active auctions for anonymous / cold-start users.
 * Response shape mirrors {@code /api/search} results so the same card renders both.</p>
 */
@WebServlet({"/api/recommendations", "/api/recommendations/*"})
public class RecommendationApiServlet extends ApiBase {

    private static final int MAX_LIMIT = 24;

    private RecommendationDAO recommendationDAO;

    public RecommendationApiServlet() {
        this.recommendationDAO = new RecommendationDAO();
    }

    /** Test hook */
    public void setRecommendationDAO(RecommendationDAO recommendationDAO) {
        this.recommendationDAO = recommendationDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path != null && path.startsWith("/similar")) {
            handleSimilar(req, resp);
            return;
        }

        int limit = resolveLimit(req);

        // /trending is never personalised, even for a signed-in user — the home page
        // needs a genuine "what's hot right now" list alongside personalised picks.
        boolean trendingOnly = path != null && path.startsWith("/trending");

        Integer userId = trendingOnly ? null : sessionUserId(req);
        boolean personalised = userId != null;

        List<SearchResultItem> results;
        try {
            results = personalised
                    ? recommendationDAO.recommendForUser(userId, limit)
                    : recommendationDAO.trending(limit, Collections.emptySet(), null);
        } catch (RuntimeException e) {
            getServletContext().log("recommendations error", e);
            // Fail soft: an empty list keeps the home page working.
            results = Collections.emptyList();
            personalised = false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", results);
        body.put("personalised", personalised);
        ok(resp, body);
    }

    /** Caller-supplied {@code limit}, clamped to {@link #MAX_LIMIT}; admin setting by default. */
    private int resolveLimit(HttpServletRequest req) {
        int limit = recommendationDAO.getSettings().itemsShown;
        String limitStr = param(req, "limit");
        if (limitStr != null) {
            try { limit = Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(limitStr))); }
            catch (NumberFormatException ignored) { }
        }
        return limit;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path != null && path.startsWith("/dismiss")) {
            handleDismiss(req, resp);
        } else if (path != null && path.startsWith("/events")) {
            handleEvents(req, resp);
        } else {
            error(resp, 404, "Not found.");
        }
    }

    /** GET /api/recommendations/similar?auctionId=&limit= */
    private void handleSimilar(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long auctionId = parseLong(param(req, "auctionId"));
        if (auctionId == null) { badRequest(resp, "auctionId is required."); return; }

        int limit = 4;
        String limitStr = param(req, "limit");
        if (limitStr != null) {
            try { limit = Math.max(1, Math.min(12, Integer.parseInt(limitStr))); }
            catch (NumberFormatException ignored) { }
        }

        List<SearchResultItem> results;
        try {
            results = recommendationDAO.similarByBidders(auctionId, sessionUserId(req), limit);
        } catch (RuntimeException e) {
            getServletContext().log("similar recommendations error", e);
            results = Collections.emptyList();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", results);
        ok(resp, body);
    }

    /** POST /api/recommendations/dismiss  auctionId — requires login. */
    private void handleDismiss(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        Long auctionId = parseLong(param(req, "auctionId"));
        if (auctionId == null) { badRequest(resp, "auctionId is required."); return; }
        try {
            recommendationDAO.dismiss(sessionUserId(req), auctionId);
            okMsg(resp, "We won't recommend this item again.");
        } catch (RuntimeException e) {
            serverError(resp, "Could not dismiss this recommendation.");
        }
    }

    /**
     * POST /api/recommendations/events  type=impression|click,
     * auctionId (single) or auctionIds (comma-separated, for batched impressions).
     */
    private void handleEvents(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String type = param(req, "type");
        String eventType = "click".equalsIgnoreCase(type) ? "CLICK"
                : "impression".equalsIgnoreCase(type) ? "IMPRESSION" : null;
        if (eventType == null) { badRequest(resp, "type must be impression or click."); return; }

        Integer userId = sessionUserId(req);
        List<Long> ids = new java.util.ArrayList<>();
        Long single = parseLong(param(req, "auctionId"));
        if (single != null) ids.add(single);
        String csv = param(req, "auctionIds");
        if (csv != null) {
            for (String part : csv.split(",")) {
                Long id = parseLong(part.trim());
                if (id != null) ids.add(id);
            }
        }
        if (ids.isEmpty()) { badRequest(resp, "auctionId or auctionIds is required."); return; }

        for (Long id : ids) recommendationDAO.recordEvent(userId, id, eventType);
        okMsg(resp, "Recorded.");
    }

    private Long parseLong(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return null; }
    }
}
