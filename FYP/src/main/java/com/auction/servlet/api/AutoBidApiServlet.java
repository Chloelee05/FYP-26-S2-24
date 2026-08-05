package com.auction.servlet.api;

import com.auction.dao.AutoBidDAO;
import com.auction.dao.BidDAO;
import com.auction.notification.NotificationService;
import com.auction.realtime.AuctionEventPublisher;
import com.auction.util.AuthSession;
import com.auction.util.DBUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * POST /api/auto-bid  params: auctionId, action (SET|CANCEL), maxAmount, note, bidIncrement
 * GET  /api/auto-bid?auctionId=X  — returns the authenticated buyer's current auto-bid (or 404)
 * Open to any signed-in non-admin account; auto-bidding on your own listing is rejected.
 */
@WebServlet("/api/auto-bid")
public class AutoBidApiServlet extends ApiBase {

    private static final Logger LOGGER = Logger.getLogger(AutoBidApiServlet.class.getName());

    private AutoBidDAO autoBidDAO;

    public AutoBidApiServlet() {
        this.autoBidDAO = new AutoBidDAO();
    }

    /** Test hook */
    public void setAutoBidDAO(AutoBidDAO autoBidDAO) { this.autoBidDAO = autoBidDAO; }

    /** GET /api/auto-bid?auctionId=X — return the buyer's active auto-bid row, or 404. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (!canBuy(session)) { forbidden(resp); return; }

        int buyerId = ((Number) session.getAttribute("userId")).intValue();
        String auctionIdStr = req.getParameter("auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        // Proxy bidding does not apply to a sealed auction, so answer as though no auto-bid
        // exists rather than handing back a row (possibly one predating this guard) that
        // nothing will ever act on.
        if (autoBidDAO.isBlindAuction(auctionId)) {
            error(resp, 404, "No auto-bid set.");
            return;
        }

        AutoBidDAO.AutoBidRow row = autoBidDAO.getAutoBidForUser(auctionId, buyerId);
        if (row == null) {
            error(resp, 404, "No auto-bid set.");
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled",      true);
        body.put("maxAmount",    row.getMaxAmount());
        body.put("bidIncrement", row.getIncrement());
        ok(resp, body);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        if (!canBuy(session)) { forbidden(resp); return; }

        int buyerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        // A seller may not auto-bid on their own listing. Checked before the CANCEL
        // branch so an owner can still clear a row created before this guard existed.
        String action = param(req, "action");
        if (!"CANCEL".equalsIgnoreCase(action) && autoBidDAO.isOwnAuction(auctionId, buyerId)) {
            error(resp, 403, "You cannot set an auto-bid on your own auction.");
            return;
        }

        if ("CANCEL".equalsIgnoreCase(action)) {
            autoBidDAO.delete(auctionId, buyerId);
            okMsg(resp, "Auto-bid cancelled.");
            return;
        }

        // Proxy bidding counter-bids one increment above the visible leader, which a sealed
        // auction has by definition neither of; placeSealedBid never invokes it. Rejected
        // rather than accepted-and-ignored, so a buyer is never left believing an auto-bid
        // is defending them when nothing is. Checked after CANCEL so a row created before
        // this guard existed can still be cleared.
        if (autoBidDAO.isBlindAuction(auctionId)) {
            badRequest(resp, "Auto-bid does not apply to sealed-bid auctions. "
                    + "Submit one hidden bid instead — it stands on its own until the auction closes.");
            return;
        }

        String maxStr = param(req, "maxAmount");
        if (maxStr == null) { badRequest(resp, "maxAmount is required."); return; }

        BigDecimal maxAmount;
        try {
            maxAmount = new BigDecimal(maxStr);
            if (maxAmount.compareTo(BigDecimal.ZERO) <= 0) {
                badRequest(resp, "maxAmount must be greater than zero."); return;
            }
        } catch (NumberFormatException e) {
            badRequest(resp, "maxAmount must be a valid number."); return;
        }

        // bidIncrement is optional; falls back to AutoBidDAO.MIN_INCREMENT (0.01)
        BigDecimal bidIncrement = AutoBidDAO.MIN_INCREMENT;
        String incStr = param(req, "bidIncrement");
        if (incStr != null && !incStr.isBlank()) {
            try {
                bidIncrement = new BigDecimal(incStr);
                if (bidIncrement.compareTo(AutoBidDAO.MIN_INCREMENT) < 0) {
                    bidIncrement = AutoBidDAO.MIN_INCREMENT;
                }
            } catch (NumberFormatException e) {
                badRequest(resp, "bidIncrement must be a valid number."); return;
            }
        }

        String note = param(req, "note");
        autoBidDAO.upsert(auctionId, buyerId, maxAmount, note, bidIncrement);

        // Fire proxy resolution immediately so the new auto-bid activates without
        // requiring another manual bid — mirrors SetAutoBidServlet (legacy JSP path).
        final long auctionIdFinal = auctionId;
        try {
            Integer displaced = DBUtil.runInTransaction(conn -> {
                Integer before = BidDAO.topBidderId(conn, auctionIdFinal);
                autoBidDAO.processAutoBids(conn, auctionIdFinal);
                Integer after = BidDAO.topBidderId(conn, auctionIdFinal);
                // Setting an auto-bid can take the lead off someone, and this path never told
                // them. Only report it when the lead genuinely changed hands.
                return (before != null && !before.equals(after)) ? before : null;
            });
            // Notify before publishing: the snapshot push is the more failure-prone of the two,
            // and a realtime hiccup must not cost the displaced bidder their notification.
            if (displaced != null) {
                NotificationService.notifyOutbid(auctionIdFinal, displaced);
            }
            AuctionEventPublisher.publishSnapshot(auctionIdFinal);
        } catch (Exception e) {
            // Auto-bid stored; processing failed — log and continue.
            LOGGER.warning("AutoBidApiServlet processAutoBids failed: " + e.getMessage());
        }

        okMsg(resp, "Auto-bid enabled up to $" + maxAmount.toPlainString()
                + " (step $" + bidIncrement.toPlainString() + ").");
    }
}
