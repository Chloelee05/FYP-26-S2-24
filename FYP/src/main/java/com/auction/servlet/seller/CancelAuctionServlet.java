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
 *
 * <p>Legacy JSP flow; the SPA cancels through {@code /api/seller/*} in
 * {@code SellerApiServlet}. Keeping the bids matters: a seller who cancels an auction that
 * already had bidders leaves a record of who bid and how much, which an admin can look at if
 * the cancellation is disputed.</p>
 *
 * <p>Once cancelled, the auction is skipped by {@code AuctionExpiryListener}, since that sweep
 * only finalises auctions still in ACTIVE.</p>
 */
@WebServlet("/seller/cancel-auction")
public class CancelAuctionServlet extends HttpServlet {

    private SellerAuctionDAO dao = new SellerAuctionDAO();

    /** Injection point for a stub DAO in unit tests. */
    public void setDao(SellerAuctionDAO dao) { this.dao = dao; }

    /**
     * Cancels one auction. Expects {@code auction_id} and an optional {@code cancel_reason},
     * which is capped and sanitized because an admin may read it later. Ownership and the
     * cancellable-status rule are both decided in the DAO, and either failing comes back as the
     * same 403, so the response does not reveal whether the auction exists.
     */
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
