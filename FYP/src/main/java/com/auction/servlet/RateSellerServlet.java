package com.auction.servlet;

import com.auction.dao.RatingDAO;
import com.auction.dao.RatingDAO.RatingResult;
import com.auction.util.RbacUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Handles seller ratings submitted by winning buyers (SCRUM-49).
 *
 * <p><b>Auth:</b> Mapped to {@code /protected/rate-seller}, so {@code AuthFilter}
 * guarantees a logged-in user. The servlet additionally enforces the BUYER role.</p>
 *
 * <p><b>No IDOR:</b>
 * <ul>
 *   <li>{@code buyerId} is read exclusively from the session — never from a request parameter.</li>
 *   <li>{@code auctionId} is parsed as {@code long}; non-numeric input returns 400.</li>
 *   <li>The seller's identity is resolved inside {@link RatingDAO} from the DB, not from
 *       the request, so a buyer cannot forge a rating against an arbitrary user.</li>
 * </ul>
 * </p>
 *
 * <p><b>No PII in logs:</b> Only {@code auctionId}, {@code buyerId}, and {@code score}
 * are logged.</p>
 */
@WebServlet("/protected/rate-seller")
public class RateSellerServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(RateSellerServlet.class.getName());

    private RatingDAO ratingDAO;

    public RateSellerServlet() {
        this.ratingDAO = new RatingDAO();
    }

    public RateSellerServlet(RatingDAO ratingDAO) {
        this.ratingDAO = ratingDAO;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);

        if (!RbacUtil.isBuyer(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Only buyers may rate sellers.");
            return;
        }

        // buyerId always from session (never from request)
        int buyerId = ((Number) session.getAttribute("userId")).intValue();

        // auctionId parsed as long (rejects non-numeric IDOR attempts)
        String auctionIdStr = req.getParameter("auctionId");
        if (auctionIdStr == null || auctionIdStr.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "auctionId is required.");
            return;
        }
        long auctionId;
        try {
            auctionId = Long.parseLong(auctionIdStr.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid auction ID.");
            return;
        }

        // score must be an integer in [1, 5]
        String scoreStr = req.getParameter("score");
        if (scoreStr == null || scoreStr.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Score is required.");
            return;
        }
        int score;
        try {
            score = Integer.parseInt(scoreStr.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Score must be a number.");
            return;
        }
        if (score < 1 || score > 5) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Score must be between 1 and 5.");
            return;
        }

        RatingResult result;
        try {
            result = ratingDAO.insertRating(auctionId, buyerId, score, null);
        } catch (RuntimeException e) {
            LOGGER.severe(String.format("insertRating error [auctionId=%d, buyerId=%d]: %s",
                    auctionId, buyerId, e.getMessage()));
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (result == RatingResult.SUCCESS) {
            LOGGER.info(String.format("Rating submitted [auctionId=%d, buyerId=%d, score=%d].",
                    auctionId, buyerId, score));
            session.setAttribute("ratingFlash", "Your rating was submitted successfully!");
        } else {
            session.setAttribute("ratingFlashError", toMessage(result));
        }

        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
    }

    /** Translates a {@link RatingResult} failure to a user-facing message. */
    public static String toMessage(RatingResult result) {
        switch (result) {
            case AUCTION_NOT_FOUND:    return "Auction not found.";
            case AUCTION_NOT_FINISHED: return "You can only rate sellers after the auction has ended.";
            case NOT_WINNER:           return "Only the auction winner may rate the seller.";
            case ALREADY_RATED:        return "You have already rated the seller for this auction.";
            default:                   return "Could not submit rating. Please try again.";
        }
    }
}
