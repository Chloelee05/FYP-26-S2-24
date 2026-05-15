package com.auction.servlet.seller;

import com.auction.dao.SellerAuctionDAO;
import com.auction.util.RbacUtil;
import com.auction.util.SecurityUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.IOException;

/**
 * SCRUM-34 – Cancel an on-going auction.
 *
 * POST /seller/cancel-auction
 *   auction_id   (required) – ID of the auction to cancel
 *   cancel_reason (optional) – free-text reason; truncated to 1000 chars, stripped if blank
 *
 * Preconditions enforced in SellerAuctionDAO:
 *   - Auction must be ACTIVE or PENDING (not FINISHED or already CANCELLED)
 *   - Session user must be the owning seller
 *
 * Bids are intentionally preserved for audit; only the status changes.
 */
@WebServlet("/seller/cancel-auction")
public class CancelAuctionServlet extends HttpServlet {

    private SellerAuctionDAO dao = new SellerAuctionDAO();

    public void setDao(SellerAuctionDAO dao) { this.dao = dao; }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        if (!RbacUtil.isSeller(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        String idParam = req.getParameter("auction_id");
        if (idParam == null || idParam.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "auction_id is required");
            return;
        }

        long auctionId;
        try {
            auctionId = Long.parseLong(idParam.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid auction ID");
            return;
        }

        String reason = req.getParameter("cancel_reason");
        if (reason != null) {
            reason = reason.trim();
            if (reason.length() > 1000) reason = reason.substring(0, 1000);
            if (reason.isBlank()) {
                reason = null;
            } else {
                reason = SecurityUtil.sanitize(reason);
            }
        }

        try {
            boolean cancelled = dao.cancelAuction(auctionId, sellerId, reason);
            if (!cancelled) {
                // Not found, wrong owner, or auction is in a non-cancellable state
                resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "Auction cannot be cancelled");
                return;
            }
            resp.sendRedirect(req.getContextPath() + "/protected/seller/auctions");
        } catch (Exception e) {
            getServletContext().log("CancelAuctionServlet error", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
