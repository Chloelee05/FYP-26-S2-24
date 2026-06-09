package com.auction.servlet;

import com.auction.dao.ReviewDAO;
import com.auction.dao.ReviewDAO.BuyerRatingResult;
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
 * Handles seller ratings submitted by buyers after a completed auction.
 *
 * <p><b>Auth:</b> Mapped to {@code /protected/buyer/rate-seller}, so {@code AuthFilter}
 * guarantees a logged-in user. The servlet additionally enforces the BUYER role.</p>
 *
 * <p><b>No IDOR:</b>
 * <ul>
 *   <li>{@code buyerId} is read exclusively from the session — never from a request parameter.</li>
 *   <li>{@code auctionId} is parsed as {@code long}; non-numeric input returns 400.</li>
 *   <li>The buyer's identity is resolved inside {@link ReviewDAO} from
 *       {@code auction_details.winner_id}, not from the request.</li>
 *   <li>Auction ownership (buyer_id = session.userId) is enforced inside {@link ReviewDAO};
 *       a mismatch returns 403.</li>
 * </ul>
 * </p>
 *
 * <p><b>No PII in logs:</b> Only {@code auctionId}, {@code buyerId}, and {@code score}
 * are logged.</p>
 */
@WebServlet("/protected/buyer/rate-seller")
public class BuyerRateSellerServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(BuyerRateSellerServlet.class.getName());

    private ReviewDAO reviewDAO;

    public BuyerRateSellerServlet() {
        this.reviewDAO = new ReviewDAO();
    }

    public BuyerRateSellerServlet(ReviewDAO reviewDAO) {
        this.reviewDAO = reviewDAO;
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

        BuyerRatingResult result;
        try {
            result = reviewDAO.insertBuyerRating(auctionId, buyerId, score);
        } catch (RuntimeException e) {
            LOGGER.severe(String.format("insertSellerRating error [auctionId=%d, buyerId=%d]: %s",
                    auctionId, buyerId, e.getMessage()));
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // Auction ownership violation → hard 403 (not a flash message)
        if (result == BuyerRatingResult.NOT_AUCTION_OWNER) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "You do not own this auction.");
            return;
        }

        if (result == BuyerRatingResult.SUCCESS) {
            LOGGER.info(String.format("Seller rating submitted [auctionId=%d, buyerId=%d, score=%d].",
                    auctionId, buyerId, score));
            session.setAttribute("sellerRatingFlash", "Your rating was submitted successfully!");
        } else {
            session.setAttribute("sellerRatingFlashError", toMessage(result));
        }

        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
    }

    /** Translates a {@link BuyerRatingResult} failure to a user-facing message. */
    public static String toMessage(BuyerRatingResult result) {
        switch (result) {
            case AUCTION_NOT_FOUND:    return "Auction not found.";
            case AUCTION_NOT_FINISHED: return "You can only rate buyers after the auction has ended.";
            case NOT_AUCTION_OWNER:    return "You do not own this auction.";
            case NO_WINNER:            return "This auction has no winner yet.";
            case ALREADY_RATED:        return "You have already rated the buyer for this auction.";
            default:                   return "Could not submit rating. Please try again.";
        }
    }
}
