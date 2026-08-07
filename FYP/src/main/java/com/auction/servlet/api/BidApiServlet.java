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
 * Optional {@code action=BUY_NOW} purchases at the seller's Buy It Now price (ascending only).
 * Dispatches by auction strategy:
 *   PRICE_UP → ascending bid + proxy auto-bids
 *   DUTCH    → accept current descending clock price (first acceptance wins)
 *   BLIND    → one sealed bid per buyer; revealed at close
 * Open to any signed-in non-admin account; bidding on your own listing is rejected
 * downstream with {@link BidResult#SELF_BID}.
 *
 * <p>The most safety-critical endpoint in the system, so the servlet stays thin: it parses and
 * authorises, then hands off to {@link BidDAO}, which does the price comparison, the rate limit
 * and the write inside one database transaction. Doing the checks here instead would let two
 * bids arriving at the same moment both pass and both win.</p>
 *
 * <p>After a successful write it pushes a fresh snapshot to
 * {@link AuctionEventPublisher} so every watcher's price updates live, then queues outbid,
 * won and lost messages through {@link NotificationService}.</p>
 */
@WebServlet("/api/bid")
public class BidApiServlet extends ApiBase {

    private BidDAO bidDAO;

    public BidApiServlet() {
        this.bidDAO = new BidDAO();
    }

    /** Test hook: lets a unit test drive the strategy branches without a live database. */
    public void setBidDAO(BidDAO bidDAO) { this.bidDAO = bidDAO; }

    /**
     * Serves POST /api/bid. Requires a signed-in non-admin session; the buyer id is taken from
     * the session and never from a parameter. Reads {@code auctionId}, optional {@code action}
     * and, for the ascending and sealed paths, {@code bidAmount}.
     *
     * <p>The auction's own type decides which handler runs, rather than anything the client
     * sends, so a buyer cannot post a Dutch acceptance at an ascending listing to skip the
     * minimum-increment rule.</p>
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // canBuy covers both checks at once: signed in, and not an admin. Admins are excluded so
        // that moderation stays impartial; a seller-capable account may still bid, because buying
        // and selling share one login.
        AuthSession session = authSession(req);
        if (!canBuy(session)) {
            forbidden(resp); return;
        }

        int buyerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        String action = param(req, "action");
        if (action != null && "BUY_NOW".equalsIgnoreCase(action.trim())) {
            try {
                handleBuyNow(resp, auctionId, buyerId);
            } catch (RuntimeException e) {
                getServletContext().log("buyNow error [auctionId=" + auctionId + ", buyerId=" + buyerId + "]", e);
                serverError(resp, "Could not complete Buy It Now. Check server logs or run DB migrations.");
            }
            return;
        }

        int typeId = bidDAO.getAuctionTypeId(auctionId);
        if (typeId < 0) { error(resp, 404, "Auction not found."); return; }

        AuctionType type;
        try { type = AuctionType.getAuctionType(typeId); }
        catch (IllegalArgumentException e) { type = AuctionType.PRICE_UP; }
        // An unrecognised type id falls back to ascending, the most restrictive of the three:
        // it never reveals a sealed price and never closes the auction on a single action.

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

    /**
     * Buy It Now: pays the seller's fixed price and closes the auction on the spot. Ascending
     * only, which {@link BidDAO#buyItNow} enforces. Everyone who was still bidding gets a lost
     * notice because the listing disappeared from under them.
     */
    private void handleBuyNow(HttpServletResponse resp, long auctionId, int buyerId) throws IOException {
        BidResult result = bidDAO.buyItNow(auctionId, buyerId);
        if (result == BidResult.SUCCESS) {
            AuctionEventPublisher.publishSnapshot(auctionId);
            NotificationService.notifyAuctionWon(auctionId, buyerId);
            // A Buy It Now ends the auction under everyone else's feet — they need telling.
            NotificationService.notifyAuctionLost(auctionId, buyerId);
            okMsg(resp, "Buy It Now successful — you won this auction!");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    /**
     * PRICE_UP: the ordinary ascending bid. The DAO checks the amount beats the current high,
     * applies the per-buyer rate limit, writes the row and then resolves any proxy auto-bids,
     * all in one transaction.
     */
    private void handleAscending(HttpServletRequest req, HttpServletResponse resp, long auctionId, int buyerId)
            throws IOException {
        BigDecimal bidAmount = parseAmount(req, resp);
        if (bidAmount == null) return;

        BidDAO.BidOutcome outcome = bidDAO.placeBid(auctionId, buyerId, bidAmount);
        if (outcome.isSuccess()) {
            AuctionEventPublisher.publishSnapshot(auctionId);
            // The DAO reports who actually lost the lead, which is not always the previous
            // leader: a proxy auto-bid may have outbid this buyer inside the same transaction.
            Integer displaced = outcome.displacedBidderId();
            if (displaced != null) {
                NotificationService.notifyOutbid(auctionId, displaced);
            }
            NotificationService.notifySellerNewBid(auctionId, bidAmount);
            okMsg(resp, "Bid of $" + bidAmount.toPlainString() + " placed successfully!");
        } else {
            error(resp, 400, toMessage(outcome.result));
        }
    }

    /**
     * DUTCH_AUCTION: no amount is sent. The buyer is accepting whatever the declining clock reads
     * right now, and the DAO recomputes that price server side so a stale or edited client figure
     * cannot be used to buy below the current clock.
     */
    private void handleDutch(HttpServletResponse resp, long auctionId, int buyerId) throws IOException {
        BidResult result = bidDAO.acceptDutchBid(auctionId, buyerId);
        if (result == BidResult.SUCCESS) {
            AuctionEventPublisher.publishSnapshot(auctionId);
            NotificationService.notifyAuctionWon(auctionId, buyerId);
            // First acceptance closes the clock for everyone still waiting for it to fall.
            NotificationService.notifyAuctionLost(auctionId, buyerId);
            okMsg(resp, "You accepted the current price and won this Dutch auction!");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    /**
     * BLIND: one sealed bid per buyer, rejected with {@link BidResult#ALREADY_BID} on a second
     * attempt. The confirmation text says nothing about the standing or amount of any other bid,
     * and the snapshot pushed afterwards withholds the price while the auction is open.
     */
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

    /**
     * Parses {@code bidAmount} into a BigDecimal, writing the 400 itself and returning null when
     * it is missing, unparsable or not positive. BigDecimal rather than double because money must
     * not pick up binary rounding error.
     */
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

    /**
     * Turns a {@link BidResult} into wording for the bidder. Kept in one place so the same
     * rejection always reads the same way whichever auction type produced it.
     */
    private String toMessage(BidResult r) {
        switch (r) {
            case AUCTION_NOT_FOUND:  return "Auction not found.";
            case AUCTION_CLOSED:     return "This auction has ended or is not accepting bids.";
            case AUCTION_REMOVED:    return "This auction has been removed from the platform.";
            case SELF_BID:           return "You cannot bid on your own auction.";
            case BID_TOO_LOW:        return "Your bid must be higher than the current bid.";
            case BID_TOO_FAST:       return "You're bidding too fast — please wait a few seconds before bidding again.";
            case EXCEEDS_MAX_PRICE:  return "Your bid exceeds the maximum allowed price.";
            case ALREADY_BID:        return "You have already submitted a sealed bid for this auction.";
            case WRONG_AUCTION_TYPE: return "That action is not valid for this auction type.";
            default:                 return "Could not place bid. Please try again.";
        }
    }
}
