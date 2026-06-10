package com.auction.servlet.api;

import com.auction.dao.AutoBidDAO;
import com.auction.util.AuthSession;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * POST /api/auto-bid  params: auctionId, action (SET|CANCEL), maxAmount, note
 * Requires BUYER role.
 */
@WebServlet("/api/auto-bid")
public class AutoBidApiServlet extends ApiBase {

    private final AutoBidDAO autoBidDAO = new AutoBidDAO();

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

        String note = param(req, "note");
        autoBidDAO.upsert(auctionId, buyerId, maxAmount, note);
        okMsg(resp, "Auto-bid enabled up to $" + maxAmount.toPlainString() + ".");
    }
}
