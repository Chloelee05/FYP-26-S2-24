package com.auction.servlet;

import com.auction.dao.AutoBidDAO;
import com.auction.dao.BidDAO;
import com.auction.model.AuctionDetail;
import com.auction.util.RbacUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.logging.Logger;

/**
 * Stores or cancels a buyer's maximum auto-bid for a specific auction (SCRUM-52).
 *
 * <p>Mapped to {@code /protected/auto-bid}; {@code AuthFilter} ({@code /protected/*})
 * guarantees the caller is authenticated. An inline BUYER-role check provides
 * defense-in-depth (SCRUM-271).</p>
 *
 * <h2>Actions</h2>
 * <ul>
 *   <li>{@code action=SET} — store or update the auto-bid max amount (+ optional note).</li>
 *   <li>{@code action=CANCEL} — remove the auto-bid for this auction.</li>
 * </ul>
 *
 * <h2>SCRUM-296 security controls</h2>
 * <ul>
 *   <li>{@code userId} always from session — never from a request parameter.</li>
 *   <li>{@code auctionId} parsed as {@code long} — non-numeric input returns 400.</li>
 *   <li>{@code maxAmount} validated server-side: must be positive and exceed the current bid floor.</li>
 *   <li>Auction must be ACTIVE and not expired; setting on ended/cancelled auctions is rejected
 *       (SCRUM-271).</li>
 *   <li>No client-trusted auto-bid logic: the auto-bid triggers fire inside
 *       {@link AutoBidDAO#processAutoBids} on the server.</li>
 * </ul>
 */
@WebServlet("/protected/auto-bid")
public class SetAutoBidServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(SetAutoBidServlet.class.getName());

    private AutoBidDAO autoBidDAO;
    private BidDAO bidDAO;

    public SetAutoBidServlet() {
        this.autoBidDAO = new AutoBidDAO();
        this.bidDAO = new BidDAO();
    }

    public SetAutoBidServlet(AutoBidDAO autoBidDAO, BidDAO bidDAO) {
        this.autoBidDAO = autoBidDAO;
        this.bidDAO = bidDAO;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);

        // SCRUM-271: only authenticated buyers
        if (!RbacUtil.isBuyer(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Only buyers may set auto-bids.");
            return;
        }

        // SCRUM-296: userId always from session
        int userId = ((Number) session.getAttribute("userId")).intValue();

        // SCRUM-296: auctionId parsed strictly (IDOR guard)
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

        String action = req.getParameter("action");
        if (action == null) action = "SET";

        if ("CANCEL".equalsIgnoreCase(action)) {
            autoBidDAO.delete(auctionId, userId);
            session.setAttribute("autoBidFlash", "Auto-bid cancelled.");
            resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
            return;
        }

        // --- SET action ---

        // SCRUM-296: validate maxAmount server-side
        String amountStr = req.getParameter("maxAmount");
        if (amountStr == null || amountStr.isBlank()) {
            storeErrorAndRedirect(session, req, resp, auctionId, "Max bid amount is required.");
            return;
        }
        BigDecimal maxAmount;
        try {
            maxAmount = new BigDecimal(amountStr.trim());
            if (maxAmount.compareTo(BigDecimal.ZERO) <= 0) {
                storeErrorAndRedirect(session, req, resp, auctionId,
                        "Max bid amount must be greater than zero.");
                return;
            }
        } catch (NumberFormatException e) {
            storeErrorAndRedirect(session, req, resp, auctionId,
                    "Max bid amount must be a valid number.");
            return;
        }

        // SCRUM-271: auction must be open (ACTIVE + not expired + moderation = active)
        AuctionDetail detail;
        try {
            detail = bidDAO.findByIdForDisplay(auctionId);
        } catch (RuntimeException e) {
            LOGGER.severe("SetAutoBidServlet DB error: " + e.getMessage());
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        if (detail == null) {
            storeErrorAndRedirect(session, req, resp, auctionId, "Auction not found.");
            return;
        }
        if (!detail.isOpen()) {
            storeErrorAndRedirect(session, req, resp, auctionId,
                    "Auto-bid can only be set on active, open auctions.");
            return;
        }
        // Cannot set auto-bid on own auction
        if (detail.getSellerId() == userId) {
            storeErrorAndRedirect(session, req, resp, auctionId,
                    "You cannot set an auto-bid on your own auction.");
            return;
        }
        // SCRUM-296: max must exceed current floor (server-side validation)
        if (maxAmount.compareTo(detail.getCurrentBid()) <= 0) {
            storeErrorAndRedirect(session, req, resp, auctionId,
                    "Your max auto-bid must be greater than the current bid of $"
                    + detail.getCurrentBid().toPlainString() + ".");
            return;
        }

        // Optional private note (SCRUM-296: encrypted at rest by AutoBidDAO)
        String note = req.getParameter("note");
        if (note != null && note.trim().length() > 500) {
            note = note.trim().substring(0, 500);
        }

        // Store auto-bid (encrypts max_amount + note inside the DAO)
        try {
            autoBidDAO.upsert(auctionId, userId, maxAmount, note);
        } catch (RuntimeException e) {
            LOGGER.severe("SetAutoBidServlet upsert error: " + e.getMessage());
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // SCRUM-296: no client-trusted auto-bid logic — fire proxy resolution server-side
        try {
            com.auction.util.DBUtil.runInTransaction(conn -> {
                autoBidDAO.processAutoBids(conn, auctionId);
                return null;
            });
        } catch (Exception e) {
            // Auto-bid stored; processing failed — log and continue (bid will fire next cycle)
            LOGGER.warning("SetAutoBidServlet processAutoBids failed: " + e.getMessage());
        }

        // SCRUM-295: log only IDs and amount — no PII
        LOGGER.info(String.format(
                "Auto-bid set [auctionId=%d, userId=%d, maxAmount=%s].",
                auctionId, userId, maxAmount.toPlainString()));

        session.setAttribute("autoBidFlash",
                "Auto-bid set! We'll bid on your behalf up to $" + maxAmount.toPlainString() + ".");
        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
    }

    private void storeErrorAndRedirect(HttpSession session, HttpServletRequest req,
                                       HttpServletResponse resp, long auctionId, String msg)
            throws IOException {
        session.setAttribute("autoBidFlashError", msg);
        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
    }
}
