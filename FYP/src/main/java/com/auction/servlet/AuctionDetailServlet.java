package com.auction.servlet;

import com.auction.dao.AutoBidDAO;
import com.auction.dao.BidDAO;
import com.auction.dao.QuestionDAO;
import com.auction.model.AuctionBidHistoryEntry;
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
 *
 * <p>Legacy JSP page. The SPA reads the same auction from {@code /api/auction/*} in
 * {@code AuctionApiServlet} and renders it client-side. This servlet assembles everything the
 * page needs in one request: the auction itself from {@link BidDAO}, the Q&amp;A thread from
 * {@link QuestionDAO}, the first page of public bid history, and the viewer's existing auto-bid
 * ceiling from {@link AutoBidDAO}.</p>
 *
 * <p>The secondary loads are individually wrapped in try/catch: a failure in the Q&amp;A or the
 * bid history degrades to an empty section rather than losing the whole listing page.</p>
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

    /**
     * Builds the auction detail page for the id in the path. Works for a guest and for a signed-in
     * user; what changes is the set of permission flags handed to the JSP. Accepts optional
     * {@code page} and {@code size} parameters for the embedded bid history.
     */
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

        // Public bid history (SCRUM-58) — first page embedded on detail page
        int bidPage = AuctionBidHistoryServlet.parsePage(req);
        int bidPageSize = AuctionBidHistoryServlet.parsePageSize(req);
        List<AuctionBidHistoryEntry> bidHistory;
        int bidTotalCount;
        try {
            bidTotalCount = bidDAO.countBidHistory(auctionId);
            bidHistory = bidDAO.getBidHistory(auctionId, bidPage, bidPageSize);
        } catch (RuntimeException e) {
            getServletContext().log("AuctionDetailServlet: bid history error for auction " + auctionId, e);
            bidHistory = List.of();
            bidTotalCount = 0;
        }
        int bidTotalPages = bidTotalCount == 0 ? 1
                : (int) Math.ceil((double) bidTotalCount / bidPageSize);
        // If someone asks for page 9 of a 3-page history, clamp and reload rather than showing
        // an empty table with pagination links that go nowhere.
        if (bidPage > bidTotalPages && bidTotalCount > 0) {
            bidPage = bidTotalPages;
            try {
                bidHistory = bidDAO.getBidHistory(auctionId, bidPage, bidPageSize);
            } catch (RuntimeException e) {
                bidHistory = List.of();
            }
        }

        // Determine whether the current user can bid / ask / answer
        // These flags only decide what the page draws. Every one of them is re-checked inside
        // the servlet that performs the action, so hiding a button is presentation, not security.
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
        req.setAttribute("bidHistory", bidHistory);
        req.setAttribute("bidPage", bidPage);
        req.setAttribute("bidTotalPages", bidTotalPages);
        req.setAttribute("bidPageSize", bidPageSize);
        req.setAttribute("bidTotalCount", bidTotalCount);
        req.setAttribute("bidHistoryEmpty", bidHistory.isEmpty());
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
        // Those servlets finish with a redirect back here, so the outcome message has to survive
        // one request in the session. It is copied onto the request and removed immediately,
        // which is what stops "your bid was placed" reappearing on every later page view.
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
