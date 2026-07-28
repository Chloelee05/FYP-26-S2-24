package com.auction.servlet.api;

import com.auction.dao.WatchlistDAO;
import com.auction.dao.WatchlistDAO.WatchlistResult;
import com.auction.util.AuthSession;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Collections;

/**
 * GET  /api/watchlist              — list buyer's watchlist
 * GET  /api/watchlist?auctionId=X  — {@code {"watching": true|false}} for one auction
 * POST /api/watchlist              — params: auctionId, action (add|remove)
 * Requires BUYER role.
 */
@WebServlet("/api/watchlist")
public class WatchlistApiServlet extends ApiBase {

    private WatchlistDAO watchlistDAO;

    public WatchlistApiServlet() {
        this.watchlistDAO = new WatchlistDAO();
    }

    /** Test hook */
    public void setWatchlistDAO(WatchlistDAO watchlistDAO) { this.watchlistDAO = watchlistDAO; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (!isBuyer(session)) { forbidden(resp); return; }
        int userId = ((Number) session.getAttribute("userId")).intValue();

        // Single-auction check: the detail page only needs to know whether one item is
        // watched, so answer that directly instead of making it download the whole list.
        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr != null) {
            long auctionId;
            try { auctionId = Long.parseLong(auctionIdStr); }
            catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }
            ok(resp, Collections.singletonMap(
                    "watching", watchlistDAO.existsByUserAndAuction(userId, auctionId)));
            return;
        }

        ok(resp, watchlistDAO.listByUser(userId));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (!isBuyer(session)) { forbidden(resp); return; }
        int userId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        String action = param(req, "action");
        if ("remove".equalsIgnoreCase(action)) {
            watchlistDAO.remove(auctionId, userId);
            okMsg(resp, "Removed from watchlist.");
        } else {
            WatchlistResult result = watchlistDAO.add(auctionId, userId);
            if (result == WatchlistResult.ALREADY_WATCHING) {
                okMsg(resp, "Already on watchlist.");
            } else {
                okMsg(resp, "Added to watchlist.");
            }
        }
    }
}
