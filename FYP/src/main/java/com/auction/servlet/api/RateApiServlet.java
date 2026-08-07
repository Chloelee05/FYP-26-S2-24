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
 * GET  /api/rate/check   params: auctionId — whether the buyer already rated
 * GET  /api/rate/mine    — reviews written by the caller (with editable flag)
 * POST /api/rate         params: auctionId, score (1-5), comment?
 * POST /api/rate/update  params: reviewId, score (1-5), comment? — edit own review (24h window)
 * POST /api/rate/delete  params: reviewId — delete own review (24h window)
 *
 * <p>Seller reputation. A review can only be written by the buyer who won the auction and only
 * after the order is marked complete, which is what stops anyone from posting reviews about a
 * seller they never dealt with. One review per auction. {@link RatingDAO} enforces all of that
 * in SQL rather than relying on the servlet.</p>
 *
 * <p>Edits and deletions are limited to {@link RatingDAO#EDIT_WINDOW_HOURS} after posting, so a
 * seller cannot pressure a buyer into rewriting an old review. Comment text is sanitised because
 * it appears on the seller's public profile.</p>
 */
@WebServlet(urlPatterns = {"/api/rate", "/api/rate/*"})
public class RateApiServlet extends ApiBase {

    private RatingDAO ratingDAO;

    public RateApiServlet() {
        this.ratingDAO = new RatingDAO();
    }

    /** Test hook: lets a unit test supply a stub DAO. */
    public void setRatingDAO(RatingDAO ratingDAO) { this.ratingDAO = ratingDAO; }

    /**
     * Routes the reads: /check answers whether the caller has already rated one auction, and
     * /mine lists the reviews they have written along with whether each is still editable.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        String path = req.getPathInfo();
        if (path != null && (path.equals("/check") || path.endsWith("/check"))) {
            handleCheck(req, resp);
        } else if (path != null && path.startsWith("/mine")) {
            ok(resp, ratingDAO.listWrittenBy(sessionUserId(req)));
        } else {
            error(resp, 404, "Not found.");
        }
    }

    /**
     * Routes the writes. /update and /delete edit the caller's own review; anything else is a new
     * rating, taking {@code auctionId}, {@code score} of 1 to 5 and an optional {@code comment}.
     * The rater id always comes from the session.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;

        // Edit/delete own review — any authenticated user (ownership enforced in SQL).
        String path = req.getPathInfo();
        if (path != null && path.startsWith("/update")) { handleUpdateOwn(req, resp); return; }
        if (path != null && path.startsWith("/delete")) { handleDeleteOwn(req, resp); return; }

        AuthSession session = authSession(req);
        if (!canBuy(session)) { forbidden(resp); return; }
        int buyerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        String scoreStr     = param(req, "score");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }
        if (scoreStr     == null) { badRequest(resp, "score is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        // Out-of-range scores are thrown into the same catch as unparsable ones, so both give the
        // one message. The bound matters because the average rating is shown on the seller profile.
        int score;
        try {
            score = Integer.parseInt(scoreStr);
            if (score < 1 || score > 5) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            badRequest(resp, "Score must be a number between 1 and 5."); return;
        }

        // Sanitised because the comment is rendered on the seller's public profile.
        String comment = param(req, "comment");
        if (comment != null) comment = com.auction.util.SecurityUtil.sanitize(comment.trim());

        RatingResult result = ratingDAO.insertRating(auctionId, buyerId, score, comment);
        if (result == RatingResult.SUCCESS) {
            okMsg(resp, "Rating submitted. Thank you!");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    /**
     * POST /api/rate/update with {@code reviewId}, {@code score} and optional {@code comment}.
     * The UPDATE is scoped by both review id and user id, so somebody else's review id matches
     * nothing and comes back as 404. Refused once the edit window has passed.
     */
    private void handleUpdateOwn(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int userId = sessionUserId(req);
        Long reviewId = parseLongParam(req, "reviewId");
        if (reviewId == null) { badRequest(resp, "reviewId is required."); return; }

        String scoreStr = param(req, "score");
        int score;
        try {
            score = Integer.parseInt(scoreStr);
            if (score < 1 || score > 5) throw new NumberFormatException();
        } catch (Exception e) {
            badRequest(resp, "Score must be a number between 1 and 5."); return;
        }
        String comment = param(req, "comment");
        if (comment != null) comment = com.auction.util.SecurityUtil.sanitize(comment.trim());

        switch (ratingDAO.updateOwnReview(reviewId, userId, score, comment)) {
            case SUCCESS:        okMsg(resp, "Review updated."); break;
            case WINDOW_EXPIRED: error(resp, 400, "Reviews can only be edited within "
                    + RatingDAO.EDIT_WINDOW_HOURS + " hours of posting."); break;
            default:             error(resp, 404, "Review not found."); break;
        }
    }

    /**
     * POST /api/rate/delete with {@code reviewId}. Same ownership and time-window rules as the
     * update path, so an old review cannot be withdrawn later.
     */
    private void handleDeleteOwn(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int userId = sessionUserId(req);
        Long reviewId = parseLongParam(req, "reviewId");
        if (reviewId == null) { badRequest(resp, "reviewId is required."); return; }

        switch (ratingDAO.deleteOwnReview(reviewId, userId)) {
            case SUCCESS:        okMsg(resp, "Review deleted."); break;
            case WINDOW_EXPIRED: error(resp, 400, "Reviews can only be deleted within "
                    + RatingDAO.EDIT_WINDOW_HOURS + " hours of posting."); break;
            default:             error(resp, 404, "Review not found."); break;
        }
    }

    /** Parses an id parameter, returning null rather than throwing when absent or malformed. */
    private Long parseLongParam(HttpServletRequest req, String name) {
        String v = param(req, name);
        if (v == null) return null;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return null; }
    }

    /**
     * GET /api/rate/check?auctionId=X. Returns {@code {"rated": true|false}} so the order page
     * can show either the rating form or the review the buyer already left.
     */
    private void handleCheck(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!canBuy(authSession(req))) { forbidden(resp); return; }
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

    /** Wording for each reason a rating can be refused, spelling out the condition the buyer missed. */
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
