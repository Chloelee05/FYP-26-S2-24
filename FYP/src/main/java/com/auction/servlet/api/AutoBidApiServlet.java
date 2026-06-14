package com.auction.servlet.api;

import com.auction.dao.AutoBidDAO;
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
 * Requires BUYER role.
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
        if (!isBuyer(session)) { forbidden(resp); return; }

        int buyerId = ((Number) session.getAttribute("userId")).intValue();
        String auctionIdStr = req.getParameter("auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

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
        if (!isBuyer(session)) { forbidden(resp); return; }

        int buyerId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        String action = param(req, "action");
        if ("CANCEL".equalsIgnoreCase(action)) {
            autoBidDAO.delete(auctionId, buyerId);
            okMsg(resp, "Auto-bid cancelled.");
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
            DBUtil.runInTransaction(conn -> {
                autoBidDAO.processAutoBids(conn, auctionIdFinal);
                return null;
            });
            AuctionEventPublisher.publishSnapshot(auctionIdFinal);
        } catch (Exception e) {
            // Auto-bid stored; processing failed — log and continue.
            LOGGER.warning("AutoBidApiServlet processAutoBids failed: " + e.getMessage());
        }

        okMsg(resp, "Auto-bid enabled up to $" + maxAmount.toPlainString()
                + " (step $" + bidIncrement.toPlainString() + ").");
    }
}
