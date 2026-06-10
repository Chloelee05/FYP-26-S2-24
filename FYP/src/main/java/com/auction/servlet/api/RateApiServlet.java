package com.auction.servlet.api;

import com.auction.dao.RatingDAO;
import com.auction.dao.RatingDAO.RatingResult;
import com.auction.util.AuthSession;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET  /api/rate/check  params: auctionId — whether the buyer already rated
 * POST /api/rate        params: auctionId, score (1-5), comment?
 */
@WebServlet(urlPatterns = {"/api/rate", "/api/rate/*"})
public class RateApiServlet extends ApiBase {

    private final RatingDAO ratingDAO = new RatingDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        String path = req.getPathInfo();
        if (path != null && (path.equals("/check") || path.endsWith("/check"))) {
            handleCheck(req, resp);
        } else {
            error(resp, 404, "Not found.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);
        if (!isBuyer(session)) { forbidden(resp); return; }
        int buyerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        String scoreStr     = param(req, "score");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        if (scoreStr     == null) { badRequest(resp, "score is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        int score;
        try {
            score = Integer.parseInt(scoreStr);
            if (score < 1 || score > 5) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            badRequest(resp, "Score must be a number between 1 and 5."); return;
        }

        String comment = param(req, "comment");
        if (comment != null) comment = com.auction.util.SecurityUtil.sanitize(comment.trim());

        RatingResult result = ratingDAO.insertRating(auctionId, buyerId, score, comment);
        if (result == RatingResult.SUCCESS) {
            okMsg(resp, "Rating submitted. Thank you!");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    private void handleCheck(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isBuyer(authSession(req))) { forbidden(resp); return; }
        int buyerId = sessionUserId(req);
        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rated", ratingDAO.existsByBuyerAndAuction(buyerId, auctionId));
        ok(resp, body);
    }

    private String toMessage(RatingResult r) {
        switch (r) {
            case AUCTION_NOT_FOUND:     return "Auction not found.";
            case AUCTION_NOT_FINISHED:  return "You can only rate a seller after the auction ends.";
            case NOT_WINNER:            return "Only the winning buyer can rate the seller.";
            case ALREADY_RATED:         return "You have already rated this seller for this auction.";
            case ORDER_NOT_COMPLETED:   return "You can rate the seller after the order is marked complete.";
            default:                    return "Could not submit rating. Please try again.";
        }
    }
}
