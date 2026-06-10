package com.auction.servlet.api;

import com.auction.dao.BidDAO;
import com.auction.dao.BidDAO.BidResult;
import com.auction.model.AuctionType;
import com.auction.notification.NotificationService;
import com.auction.realtime.AuctionEventPublisher;
import com.auction.util.AuthSession;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * POST /api/bid  params: auctionId, bidAmount (bidAmount optional for Dutch "accept").
 * Dispatches by auction strategy:
 *   PRICE_UP → ascending bid + proxy auto-bids
 *   DUTCH    → accept current descending clock price (first acceptance wins)
 *   BLIND    → one sealed bid per buyer; revealed at close
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

        int typeId = bidDAO.getAuctionTypeId(auctionId);
        if (typeId < 0) { error(resp, 404, "Auction not found."); return; }

        AuctionType type;
        try { type = AuctionType.getAuctionType(typeId); }
        catch (IllegalArgumentException e) { type = AuctionType.PRICE_UP; }

        try {
            if (type == AuctionType.DUTCH_AUCTION) {
                handleDutch(resp, auctionId, buyerId);
            } else if (type == AuctionType.BLIND) {
                handleSealed(req, resp, auctionId, buyerId);
            } else {
                handleAscending(req, resp, auctionId, buyerId);
            }
        } catch (RuntimeException e) {
            getServletContext().log("bid error [auctionId=" + auctionId + ", buyerId=" + buyerId + "]", e);
            serverError(resp, "Could not place bid. Check server logs or run DB migrations.");
        }
    }

    private void handleAscending(HttpServletRequest req, HttpServletResponse resp, long auctionId, int buyerId)
            throws IOException {
        BigDecimal bidAmount = parseAmount(req, resp);
        if (bidAmount == null) return;

        BidResult result = bidDAO.placeBid(auctionId, buyerId, bidAmount);
        if (result == BidResult.SUCCESS) {
            AuctionEventPublisher.publishSnapshot(auctionId);
            NotificationService.notifyOutbid(auctionId, buyerId);
            okMsg(resp, "Bid of $" + bidAmount.toPlainString() + " placed successfully!");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    private void handleDutch(HttpServletResponse resp, long auctionId, int buyerId) throws IOException {
        BidResult result = bidDAO.acceptDutchBid(auctionId, buyerId);
        if (result == BidResult.SUCCESS) {
            AuctionEventPublisher.publishSnapshot(auctionId);
            NotificationService.notifyAuctionWon(auctionId, buyerId);
            okMsg(resp, "You accepted the current price and won this Dutch auction!");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    private void handleSealed(HttpServletRequest req, HttpServletResponse resp, long auctionId, int buyerId)
            throws IOException {
        BigDecimal bidAmount = parseAmount(req, resp);
        if (bidAmount == null) return;

        BidResult result = bidDAO.placeSealedBid(auctionId, buyerId, bidAmount);
        if (result == BidResult.SUCCESS) {
            AuctionEventPublisher.publishSnapshot(auctionId);
            okMsg(resp, "Your sealed bid was submitted. The winner is revealed when the auction ends.");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    private BigDecimal parseAmount(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String amountStr = param(req, "bidAmount");
        if (amountStr == null) { badRequest(resp, "bidAmount is required."); return null; }
        try {
            BigDecimal bidAmount = new BigDecimal(amountStr);
            if (bidAmount.compareTo(BigDecimal.ZERO) <= 0) {
                badRequest(resp, "Bid amount must be greater than zero."); return null;
            }
            return bidAmount;
        } catch (NumberFormatException e) {
            badRequest(resp, "bidAmount must be a valid number."); return null;
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
            case ALREADY_BID:        return "You have already submitted a sealed bid for this auction.";
            case WRONG_AUCTION_TYPE: return "That action is not valid for this auction type.";
            default:                 return "Could not place bid. Please try again.";
        }
    }
}
