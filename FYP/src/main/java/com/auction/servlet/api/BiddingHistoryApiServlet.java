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
 *
 * <p>Backs the "My Bids" tab of the buyer profile. Behind AuthFilter, and the user id always
 * comes from the session rather than from a parameter, so one buyer cannot page through
 * another buyer's bids. Reads {@code bids} joined to {@code auctions} through
 * {@link ProfileActivityDAO}, which applies the BLIND confidentiality rule: on a sealed-bid
 * auction that is still open the buyer sees their own amount but no competing prices.</p>
 */
@WebServlet("/api/bidding-history")
public class BiddingHistoryApiServlet extends ApiBase {

    private final ProfileActivityDAO actDAO = new ProfileActivityDAO();

    /**
     * Serves GET /api/bidding-history for the logged-in user. Optional {@code page} (1-based)
     * and {@code size} (default 10, capped at 50 to bound the query) drive pagination.
     * Returns the page of bids plus {@code total}, {@code page} and {@code totalPages}.
     */
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

    /** Parses a paging parameter, forcing it to at least 1 so a negative page cannot produce a bad SQL offset. */
    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return def; }
    }
}
