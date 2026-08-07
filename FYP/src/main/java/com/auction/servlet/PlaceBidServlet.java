package com.auction.servlet;

import com.auction.dao.BidDAO;
import com.auction.dao.BidDAO.BidResult;
import com.auction.notification.NotificationService;
import com.auction.util.RbacUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.logging.Logger;

/**
 * Handles bid placement for authenticated buyers (SCRUM-51).
 *
 * <p><b>Auth (SCRUM-295):</b> Mapped to {@code /protected/bid}, so {@code AuthFilter}
 * ({@code /protected/*}) guarantees the caller is logged in before this servlet runs.
 * The servlet additionally enforces the BUYER role inline.</p>
 *
 * <p><b>No IDOR (SCRUM-295):</b>
 * <ul>
 *   <li>{@code buyerId} is read exclusively from {@code session.getAttribute("userId")} —
 *       never from any request parameter.</li>
 *   <li>{@code auctionId} is parsed as {@code long}; non-numeric input returns 400.</li>
 *   <li>All business-rule checks (auction open, seller ownership, bid cap) are enforced
 *       inside the {@link BidDAO} transaction against live DB data.</li>
 * </ul>
 * </p>
 *
 * <p><b>No PII in logs (SCRUM-295):</b> Only {@code auctionId}, {@code buyerId},
 * and the bid amount are logged. Session tokens, emails, and passwords are never
 * passed to {@link Logger}.</p>
 *
 * <p>Legacy JSP flow. The SPA posts to {@code /api/bid} in {@code BidApiServlet}, and both
 * routes end up in the same {@link BidDAO#placeBid} transaction, so the auction rules are
 * enforced in one place regardless of which front end the bid came from.</p>
 *
 * <p>This servlet is deliberately thin. It only checks that the caller is a buyer and that the
 * numbers parse; whether the auction is still open, whether the bid beats the current price,
 * and whether the bidder is the seller are all decided inside the DAO transaction against live
 * rows, because those answers can change between the page load and the submit.</p>
 */
@WebServlet("/protected/bid")
public class PlaceBidServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(PlaceBidServlet.class.getName());

    private BidDAO bidDAO;

    public PlaceBidServlet() {
        this.bidDAO = new BidDAO();
    }

    public PlaceBidServlet(BidDAO bidDAO) {
        this.bidDAO = bidDAO;
    }

    public void setBidDAO(BidDAO bidDAO) {
        this.bidDAO = bidDAO;
    }

    /**
     * Places one bid. Expects {@code auctionId} and {@code bidAmount} from the form.
     * Malformed ids get a 400 because they indicate a broken or hostile client, while a rejected
     * amount gets a flash message and a redirect back to the auction, because that is an ordinary
     * mistake a person makes. Ends by redirecting to the listing either way, which also means a
     * browser refresh cannot resubmit the bid.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);

        // SCRUM-266/295: only authenticated BUYERS may place bids
        if (!RbacUtil.isBuyer(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Only buyers may place bids.");
            return;
        }

        // SCRUM-295: buyerId always from session (never from request)
        int buyerId = ((Number) session.getAttribute("userId")).intValue();

        // SCRUM-295: auctionId parsed as long (rejects non-numeric IDOR attempts)
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

        // Bid amount — must be a positive decimal
        String amountStr = req.getParameter("bidAmount");
        if (amountStr == null || amountStr.isBlank()) {
            storeErrorAndRedirect(session, req, resp, auctionId, "Bid amount is required.");
            return;
        }
        BigDecimal bidAmount;
        try {
            bidAmount = new BigDecimal(amountStr.trim());
            if (bidAmount.compareTo(BigDecimal.ZERO) <= 0) {
                storeErrorAndRedirect(session, req, resp, auctionId, "Bid amount must be greater than zero.");
                return;
            }
        } catch (NumberFormatException e) {
            storeErrorAndRedirect(session, req, resp, auctionId, "Bid amount must be a valid number.");
            return;
        }

        BidDAO.BidOutcome outcome;
        try {
            outcome = bidDAO.placeBid(auctionId, buyerId, bidAmount);
        } catch (RuntimeException e) {
            LOGGER.severe(String.format("placeBid error [auctionId=%d, buyerId=%d]: %s",
                    auctionId, buyerId, e.getMessage()));
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (outcome.isSuccess()) {
            // SCRUM-295: log only IDs and amount — no PII (no session token, no email)
            LOGGER.info(String.format("Bid placed [auctionId=%d, buyerId=%d, amount=%s].",
                    auctionId, buyerId, bidAmount.toPlainString()));
            // This path never told the displaced bidder anything, so a bid placed from the
            // JSP page silently produced no notification at all. Same call as the API path.
            Integer displaced = outcome.displacedBidderId();
            if (displaced != null) {
                NotificationService.notifyOutbid(auctionId, displaced);
            }
            NotificationService.notifySellerNewBid(auctionId, bidAmount);
            session.setAttribute("bidFlash", "Your bid of $" + bidAmount.toPlainString() + " was placed successfully!");
        } else {
            session.setAttribute("bidFlashError", toMessage(outcome.result));
        }

        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
    }

    /**
     * Parks an error message in the session and redirects to the auction page, which reads and
     * clears it. Used instead of an error status for problems the bidder can simply correct.
     */
    private void storeErrorAndRedirect(HttpSession session, HttpServletRequest req,
                                       HttpServletResponse resp, long auctionId, String msg)
            throws IOException {
        session.setAttribute("bidFlashError", msg);
        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
    }

    /** Translates a {@link BidResult} failure to a user-facing message. */
    public static String toMessage(BidResult result) {
        switch (result) {
            case AUCTION_NOT_FOUND: return "Auction not found.";
            case AUCTION_CLOSED:   return "This auction has ended or is not accepting bids.";
            case AUCTION_REMOVED:  return "This auction has been removed from the platform.";
            case SELF_BID:         return "You cannot bid on your own auction.";
            case BID_TOO_LOW:      return "Your bid must be higher than the current bid.";
            case BID_TOO_FAST:     return "You're bidding too fast — please wait a few seconds before bidding again.";
            case EXCEEDS_MAX_PRICE:return "Your bid exceeds the maximum allowed price for this auction.";
            case WRONG_AUCTION_TYPE:return "That action is not valid for this auction type.";
            default:               return "Could not place bid. Please try again.";
        }
    }
}
