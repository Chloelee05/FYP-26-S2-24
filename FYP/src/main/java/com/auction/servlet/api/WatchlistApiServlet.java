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
 * Open to any signed-in non-admin account: selling does not remove the ability to watch.
 *
 * <p>The watchlist is a buyer's saved set of auctions. It also feeds the ending-soon
 * notification and is one of the signals the recommendation pipeline reads. Both methods take
 * the user id from the session, so a watchlist can only ever be read or changed by its owner.</p>
 */
@WebServlet("/api/watchlist")
public class WatchlistApiServlet extends ApiBase {

    private WatchlistDAO watchlistDAO;

    public WatchlistApiServlet() {
        this.watchlistDAO = new WatchlistDAO();
    }

    /** Test hook: lets a unit test supply a stub DAO. */
    public void setWatchlistDAO(WatchlistDAO watchlistDAO) { this.watchlistDAO = watchlistDAO; }

    /**
     * GET /api/watchlist. With no parameters it returns the caller's whole watchlist. With
     * {@code auctionId} it answers only {@code {"watching": true|false}} for that one listing.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (!canBuy(session)) { forbidden(resp); return; }
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

    /**
     * POST /api/watchlist with {@code auctionId} and {@code action} of add or remove; add is the
     * default. Both directions are idempotent: adding twice reports "already on watchlist" and
     * removing something that is not there still returns 200, so the star button can be clicked
     * repeatedly without producing errors.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (!canBuy(session)) { forbidden(resp); return; }
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
