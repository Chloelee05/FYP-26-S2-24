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
 * GET /api/recommendations?limit=8
 *
 * <p>Returns personalised recommendations (item-based collaborative filtering) for the
 * logged-in buyer, or trending active auctions for anonymous / cold-start users.
 * Response shape mirrors {@code /api/search} results so the same card renders both.</p>
 */
@WebServlet("/api/recommendations")
public class RecommendationApiServlet extends ApiBase {

    private static final int DEFAULT_LIMIT = 8;
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
        int limit = DEFAULT_LIMIT;
        String limitStr = param(req, "limit");
        if (limitStr != null) {
            try { limit = Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(limitStr))); }
            catch (NumberFormatException ignored) { }
        }

        Integer userId = sessionUserId(req);
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
}
