package com.auction.servlet.api;

import com.auction.dao.BidDAO;
import com.auction.dao.BidDAO.BidResult;
import com.auction.util.AuthSession;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * POST /api/bid  params: auctionId, bidAmount
 * Requires BUYER role.
 */
@WebServlet("/api/bid")
public class BidApiServlet extends ApiBase {

    private final BidDAO bidDAO = new BidDAO();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (!isBuyer(session)) {
            forbidden(resp); return;
        }

        int buyerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        String amountStr = param(req, "bidAmount");
        if (amountStr == null) { badRequest(resp, "bidAmount is required."); return; }

        BigDecimal bidAmount;
        try {
            bidAmount = new BigDecimal(amountStr);
            if (bidAmount.compareTo(BigDecimal.ZERO) <= 0) {
                badRequest(resp, "Bid amount must be greater than zero."); return;
            }
        } catch (NumberFormatException e) {
            badRequest(resp, "bidAmount must be a valid number."); return;
        }

        BidResult result = bidDAO.placeBid(auctionId, buyerId, bidAmount);

        if (result == BidResult.SUCCESS) {
            okMsg(resp, "Bid of $" + bidAmount.toPlainString() + " placed successfully!");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    private String toMessage(BidResult r) {
        switch (r) {
            case AUCTION_NOT_FOUND:  return "Auction not found.";
            case AUCTION_CLOSED:     return "This auction has ended or is not accepting bids.";
            case AUCTION_REMOVED:    return "This auction has been removed from the platform.";
            case SELF_BID:           return "You cannot bid on your own auction.";
            case BID_TOO_LOW:        return "Your bid must be higher than the current bid.";
            case EXCEEDS_MAX_PRICE:  return "Your bid exceeds the maximum allowed price.";
            default:                 return "Could not place bid. Please try again.";
        }
    }
}
