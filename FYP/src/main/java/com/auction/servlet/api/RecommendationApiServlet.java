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
 * GET  /api/recommendations/attribution        — ADMIN only: per-user provenance detail
 * POST /api/recommendations/dismiss  auctionId — hide a recommendation (auth required)
 * POST /api/recommendations/events   type=impression|click, auctionId (or auctionIds CSV),
 *                                    optional keyword the card was attributed to and
 *                                    optional reasonCode naming the arm that produced it
 * POST /api/recommendations/search-keyword q=  — records a searched keyword
 *
 * <p>Returns personalised recommendations (item-based collaborative filtering) for the
 * logged-in buyer, or trending active auctions for anonymous / cold-start users.
 * Response shape mirrors {@code /api/search} results so the same card renders both,
 * plus a {@code why} block explaining the placement.</p>
 *
 * <p><b>Privacy split:</b> the public routes expose only aggregates (click counts,
 * keywords) and masked usernames. Which specific user clicked or searched what is
 * available exclusively on {@code /attribution}, which requires the ADMIN role.</p>
 */
@WebServlet({"/api/recommendations", "/api/recommendations/*"})
public class RecommendationApiServlet extends ApiBase {

    /** Upper bound on any caller-supplied limit, so one request cannot make the pipeline rank the whole catalogue. */
    private static final int MAX_LIMIT = 24;

    private RecommendationDAO recommendationDAO;

    public RecommendationApiServlet() {
        this.recommendationDAO = new RecommendationDAO();
    }

    /** Test hook: lets a unit test supply a stub DAO. */
    public void setRecommendationDAO(RecommendationDAO recommendationDAO) {
        this.recommendationDAO = recommendationDAO;
    }

    /**
     * Routes the reads and serves the main recommendation strip. Runs the sequential top-up
     * pipeline in {@link RecommendationDAO}: PEER_BIDS, then SIMILAR_TASTE, then SAME_CATEGORY,
     * then TRENDING as filler, followed by hybrid re-ranking with a category diversity cap and
     * recency decay. Optional {@code limit} overrides the admin-configured card count.
     *
     * <p>Returns {@code results}, a {@code personalised} flag and {@code trendingWindowDays}.
     * No authentication is needed; a guest simply gets the trending arm.</p>
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path != null && path.startsWith("/similar")) {
            handleSimilar(req, resp);
            return;
        }
        if (path != null && path.startsWith("/attribution")) {
            handleAttribution(req, resp);
            return;
        }

        RecommendationDAO.Settings settings = recommendationDAO.getSettings();
        int limit = resolveLimit(req, settings.itemsShown);

        // /trending is never personalised, even for a signed-in user — the home page
        // needs a genuine "what's hot right now" list alongside personalised picks.
        boolean trendingOnly = path != null && path.startsWith("/trending");

        Integer userId = trendingOnly ? null : sessionUserId(req);

        List<SearchResultItem> results;
        try {
            results = userId != null
                    ? recommendationDAO.recommendForUser(userId, limit)
                    : recommendationDAO.trending(limit, Collections.emptySet(), null);
        } catch (RuntimeException e) {
            getServletContext().log("recommendations error", e);
            // Fail soft: an empty list keeps the home page working.
            results = Collections.emptyList();
        }

        try {
            // Aggregate + masked provenance only; the per-user rows stay on /attribution.
            recommendationDAO.attachProvenance(results, userId);
        } catch (RuntimeException e) {
            getServletContext().log("recommendation provenance error", e);
        }

        // Claimed from what the pipeline actually returned, not from being signed in: a
        // new account with no history gets trending filler, and saying otherwise would
        // contradict the reasons printed on the cards.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", results);
        body.put("personalised", RecommendationDAO.isPersonalised(results));
        // The landing strip prints the window in its subtitle, so it has to come from the
        // same setting the ranking query used rather than a number hardcoded in React.
        body.put("trendingWindowDays", settings.trendingWindowDays);
        ok(resp, body);
    }

    /** Caller-supplied {@code limit}, clamped to {@link #MAX_LIMIT}; admin setting by default. */
    private int resolveLimit(HttpServletRequest req, int configuredLimit) {
        int limit = configuredLimit;
        String limitStr = param(req, "limit");
        if (limitStr != null) {
            try { limit = Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(limitStr))); }
            catch (NumberFormatException ignored) { }
        }
        return limit;
    }

    /**
     * Routes the writes: /dismiss suppresses an item, /events logs impressions and clicks that
     * feed the click-through reporting, and /search-keyword records a search term for the
     * "matches your search" explanation. Only /dismiss requires a session.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path != null && path.startsWith("/dismiss")) {
            handleDismiss(req, resp);
        } else if (path != null && path.startsWith("/events")) {
            handleEvents(req, resp);
        } else if (path != null && path.startsWith("/search-keyword")) {
            handleSearchKeyword(req, resp);
        } else {
            error(resp, 404, "Not found.");
        }
    }

    /**
     * GET /api/recommendations/attribution[?auctionId=&limit=] — ADMIN only.
     * Without an auctionId this returns the click/keyword leaderboard; with one it
     * returns the individual users behind that auction's recommendations.
     */
    private void handleAttribution(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // The only route in this servlet with a role check. Everything else is aggregate or
        // masked; this one names individual users, so it is admin only.
        if (!requireRole(req, resp, "ADMIN")) return;

        int limit = 25;
        String limitStr = param(req, "limit");
        if (limitStr != null) {
            try { limit = Math.max(1, Math.min(200, Integer.parseInt(limitStr))); }
            catch (NumberFormatException ignored) { }
        }

        Long auctionId = parseLong(param(req, "auctionId"));
        try {
            ok(resp, auctionId == null
                    ? recommendationDAO.attributionOverview(limit)
                    : recommendationDAO.attributionDetail(auctionId, limit));
        } catch (RuntimeException e) {
            getServletContext().log("recommendation attribution error", e);
            serverError(resp, "Could not load recommendation attribution.");
        }
    }

    /**
     * POST /api/recommendations/search-keyword  q=&lt;keyword&gt;
     *
     * <p>Records what a visitor searched for so recommendations can later explain
     * themselves with "matches your search for …". Fire-and-forget: the search itself is
     * served by {@code /api/search} and never blocks on this.</p>
     */
    private void handleSearchKeyword(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String keyword = param(req, "q");
        if (keyword == null) { badRequest(resp, "q is required."); return; }
        // Very short terms carry no signal and would just add noise to the keyword table.
        if (keyword.trim().length() < RecommendationDAO.MIN_KEYWORD_LENGTH) {
            badRequest(resp, "q must be at least " + RecommendationDAO.MIN_KEYWORD_LENGTH + " characters.");
            return;
        }
        recommendationDAO.recordSearchKeyword(sessionUserId(req), keyword);
        okMsg(resp, "Recorded.");
    }

    /**
     * GET /api/recommendations/similar?auctionId=&limit=
     *
     * <p>The "buyers who bid on this also bid on" strip at the bottom of a listing page. This is
     * the PEER_BIDS collaborative filtering arm run against one auction rather than against a
     * whole user profile, so it works for guests too. Default 4 cards, capped at 12.</p>
     */
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
        try {
            recommendationDAO.attachProvenance(results, sessionUserId(req));
        } catch (RuntimeException e) {
            getServletContext().log("recommendation provenance error", e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", results);
        ok(resp, body);
    }

    /**
     * POST /api/recommendations/dismiss with auctionId, login required.
     * Records negative feedback against the caller's own profile so the item stops appearing.
     * Login is required because a dismissal has to be attached to a user to mean anything.
     */
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
     *
     * <p>Batching exists because a single strip render produces one impression per visible card,
     * and sending them as one request keeps that off the page's critical path. A guest's events
     * are stored with a null user id, which still counts towards aggregate click-through.</p>
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

        // The keyword the card was surfaced under, so the click can be attributed to it.
        String keyword = param(req, "keyword");
        // Which arm produced the card, so CTR can be reported per stage rather than as one
        // pooled number. Unrecognised labels are discarded by the DAO.
        String reasonCode = param(req, "reasonCode");
        for (Long id : ids) recommendationDAO.recordEvent(userId, id, eventType, keyword, reasonCode);
        okMsg(resp, "Recorded.");
    }

    /** Parses an id, returning null rather than throwing so a malformed entry in a CSV is just skipped. */
    private Long parseLong(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return null; }
    }
}
