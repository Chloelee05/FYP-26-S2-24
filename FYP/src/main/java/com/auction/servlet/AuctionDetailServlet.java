package com.auction.servlet;

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

/**
 * Serves the public auction detail page (SCRUM-51).
 *
 * <p>Mapped to {@code /auction/*} — the auction ID is extracted from the path info
 * (e.g., {@code /auction/42} → pathInfo {@code /42} → id {@code 42}).</p>
 *
 * <p>Access is public; no authentication is required to view auction details.
 * The bid form is displayed only to authenticated buyers who are not the seller
 * of this auction (canBid flag, evaluated server-side).</p>
 */
@WebServlet("/auction/*")
public class AuctionDetailServlet extends HttpServlet {

    private BidDAO bidDAO;

    public AuctionDetailServlet() {
        this.bidDAO = new BidDAO();
    }

    public AuctionDetailServlet(BidDAO bidDAO) {
        this.bidDAO = bidDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        // Parse auction ID from path: /auction/42
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Auction ID is required.");
            return;
        }

        long auctionId;
        try {
            // pathInfo starts with '/', strip it
            auctionId = Long.parseLong(pathInfo.substring(1).trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid auction ID.");
            return;
        }

        AuctionDetail auction;
        try {
            auction = bidDAO.findByIdForDisplay(auctionId);
        } catch (RuntimeException e) {
            getServletContext().log("AuctionDetailServlet: DB error for auction " + auctionId, e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (auction == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Auction not found.");
            return;
        }

        // Determine whether the current user can bid
        HttpSession session = req.getSession(false);
        boolean loggedIn = session != null && session.getAttribute("userId") != null;
        boolean isBuyer  = RbacUtil.isBuyer(session);
        boolean isSelf   = loggedIn
                && ((Number) session.getAttribute("userId")).intValue() == auction.getSellerId();
        // canBid: must be logged-in buyer, not the seller, and auction must be open
        boolean canBid = isBuyer && !isSelf && auction.isOpen();

        req.setAttribute("auction", auction);
        req.setAttribute("canBid",  canBid);
        req.setAttribute("isSelf",  isSelf);
        req.setAttribute("loggedIn", loggedIn);

        // Flash messages set by PlaceBidServlet after redirect
        if (session != null) {
            req.setAttribute("bidFlash",      session.getAttribute("bidFlash"));
            req.setAttribute("bidFlashError", session.getAttribute("bidFlashError"));
            session.removeAttribute("bidFlash");
            session.removeAttribute("bidFlashError");
        }

        req.getRequestDispatcher("/WEB-INF/views/auction-detail.jsp").forward(req, resp);
    }
}
