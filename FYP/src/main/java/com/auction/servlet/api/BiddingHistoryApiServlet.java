package com.auction.servlet.api;

import com.auction.dao.ProfileActivityDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/bidding-history  params: page, size
 * Requires authentication.
 */
@WebServlet("/api/bidding-history")
public class BiddingHistoryApiServlet extends ApiBase {

    private final ProfileActivityDAO actDAO = new ProfileActivityDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        int page  = parseInt(param(req, "page"), 1);
        int size  = Math.min(parseInt(param(req, "size"), 10), 50);
        int total = actDAO.countBidHistory(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bids",       actDAO.getBidHistory(userId, page, size));
        body.put("total",      total);
        body.put("page",       page);
        body.put("totalPages", (int) Math.ceil((double) total / size));
        ok(resp, body);
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return def; }
    }
}
