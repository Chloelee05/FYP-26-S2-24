package com.auction.servlet;

import com.auction.dao.AutoBidDAO;
import com.auction.dao.BidDAO;
import com.auction.dao.QuestionDAO;
import com.auction.model.AuctionDetail;
import com.auction.model.AuctionQuestion;
import com.auction.util.RbacUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.List;

/**
 * Serves the public auction detail page (SCRUM-51 / SCRUM-62).
 *
 * <p>Mapped to {@code /auction/*} — the auction ID is extracted from the path info
 * (e.g., {@code /auction/42} → pathInfo {@code /42} → id {@code 42}).</p>
 *
 * <p>Access is public; no authentication is required to view auction details or Q&A.
 * The bid form is displayed only to authenticated buyers who are not the seller
 * of this auction (canBid flag, evaluated server-side).</p>
 */
@WebServlet("/auction/*")
public class AuctionDetailServlet extends HttpServlet {

    private BidDAO bidDAO;
    private AutoBidDAO autoBidDAO;
    private QuestionDAO questionDAO;

    public AuctionDetailServlet() {
        this.bidDAO = new BidDAO();
        this.autoBidDAO = new AutoBidDAO();
        this.questionDAO = new QuestionDAO();
    }

    public AuctionDetailServlet(BidDAO bidDAO, AutoBidDAO autoBidDAO, QuestionDAO questionDAO) {
        this.bidDAO = bidDAO;
        this.autoBidDAO = autoBidDAO;
        this.questionDAO = questionDAO;
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

        List<AuctionQuestion> questions;
        try {
            questions = questionDAO.listByAuction(auctionId);
        } catch (RuntimeException e) {
            getServletContext().log("AuctionDetailServlet: Q&A load error for auction " + auctionId, e);
            questions = List.of();
        }

        // Determine whether the current user can bid / ask / answer
        HttpSession session = req.getSession(false);
        boolean loggedIn = session != null && session.getAttribute("userId") != null;
        boolean isBuyer  = RbacUtil.isBuyer(session);
        boolean isSeller = RbacUtil.isSeller(session);
        boolean isSelf   = loggedIn
                && ((Number) session.getAttribute("userId")).intValue() == auction.getSellerId();
        // canBid: must be logged-in buyer, not the seller, and auction must be open
        boolean canBid = isBuyer && !isSelf && auction.isOpen();
        // canAsk: buyer on someone else's open auction (SCRUM-62)
        boolean canAsk = canBid;
        // canAnswer: seller viewing their own open auction (SCRUM-62)
        boolean canAnswer = isSeller && isSelf && auction.isOpen();

        req.setAttribute("auction", auction);
        req.setAttribute("questions", questions);
        req.setAttribute("canBid",  canBid);
        req.setAttribute("canAsk",  canAsk);
        req.setAttribute("canAnswer", canAnswer);
        req.setAttribute("isSelf",  isSelf);
        req.setAttribute("loggedIn", loggedIn);

        // Load buyer's existing auto-bid max (decrypted) for display
        if (loggedIn && isBuyer && !isSelf) {
            int userId = ((Number) session.getAttribute("userId")).intValue();
            try {
                java.math.BigDecimal existingMax = autoBidDAO.getMaxAmountForUser(auctionId, userId);
                req.setAttribute("existingAutoBidMax", existingMax);
            } catch (RuntimeException ignored) {
                // non-critical — just don't show the existing value
            }
        }

        // Flash messages set by PlaceBidServlet / SetAutoBidServlet / AuctionQuestionServlet
        if (session != null) {
            req.setAttribute("bidFlash",          session.getAttribute("bidFlash"));
            req.setAttribute("bidFlashError",     session.getAttribute("bidFlashError"));
            req.setAttribute("autoBidFlash",      session.getAttribute("autoBidFlash"));
            req.setAttribute("autoBidFlashError", session.getAttribute("autoBidFlashError"));
            req.setAttribute("questionFlash",     session.getAttribute("questionFlash"));
            req.setAttribute("questionFlashError",session.getAttribute("questionFlashError"));
            session.removeAttribute("bidFlash");
            session.removeAttribute("bidFlashError");
            session.removeAttribute("autoBidFlash");
            session.removeAttribute("autoBidFlashError");
            session.removeAttribute("questionFlash");
            session.removeAttribute("questionFlashError");
        }

        req.getRequestDispatcher("/WEB-INF/views/auction-detail.jsp").forward(req, resp);
    }
}
