package com.auction.servlet;

import com.auction.dao.ProfileActivityDAO;
import com.auction.model.profile.BidHistoryRow;
import com.auction.util.RbacUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Returns the authenticated user's own bidding history (SCRUM-78).
 *
 * <p><b>Auth:</b> Mapped to {@code /protected/bidding-history} — AuthFilter guarantees
 * the caller is logged in. The servlet also checks explicitly so tests can verify the
 * redirect path.</p>
 *
 * <p><b>No IDOR:</b> {@code userId} is read exclusively from
 * {@code session.getAttribute("userId")} — never from any request parameter.</p>
 *
 * <p><b>Access:</b> Any authenticated role (BUYER or SELLER) may view their own bid
 * history; the feature is not restricted to BUYER only.</p>
 *
 * <p><b>Pagination:</b> {@code page} (1-based, default 1) and {@code size}
 * (default {@value #DEFAULT_PAGE_SIZE}, max {@value #MAX_PAGE_SIZE}) are clamped
 * to valid ranges.</p>
 */
@WebServlet("/protected/bidding-history")
public class BiddingHistoryServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(BiddingHistoryServlet.class.getName());

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;

    private ProfileActivityDAO dao;

    public BiddingHistoryServlet() {
        this.dao = new ProfileActivityDAO();
    }

    public BiddingHistoryServlet(ProfileActivityDAO dao) {
        this.dao = dao;
    }

    public void setDao(ProfileActivityDAO dao) {
        this.dao = dao;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        if (!RbacUtil.isAuthenticated(session)) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }

        // No IDOR: userId always from session, never from request
        int userId = ((Number) session.getAttribute("userId")).intValue();

        int page = parsePage(req);
        int size = parseSize(req);

        List<BidHistoryRow> rows = dao.getBidHistory(userId, page, size);
        int total = dao.countBidHistory(userId);
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / size);

        if (page > totalPages) {
            page = totalPages;
            rows = dao.getBidHistory(userId, page, size);
        }

        LOGGER.fine(String.format("Bidding history [userId=%d] page %d/%d, total=%d.",
                userId, page, totalPages, total));

        req.setAttribute("bids", rows);
        req.setAttribute("total", total);
        req.setAttribute("currentPage", page);
        req.setAttribute("totalPages", totalPages);
        req.setAttribute("pageSize", size);

        req.getRequestDispatcher("/WEB-INF/views/bidding-history.jsp").forward(req, resp);
    }

    // -------------------------------------------------------------------------
    // Pagination helpers
    // -------------------------------------------------------------------------

    private static int parsePage(HttpServletRequest req) {
        try {
            String p = req.getParameter("page");
            if (p != null && !p.isBlank()) {
                return Math.max(1, Integer.parseInt(p.trim()));
            }
        } catch (NumberFormatException ignored) { }
        return 1;
    }

    private static int parseSize(HttpServletRequest req) {
        try {
            String s = req.getParameter("size");
            if (s != null && !s.isBlank()) {
                return Math.min(MAX_PAGE_SIZE, Math.max(1, Integer.parseInt(s.trim())));
            }
        } catch (NumberFormatException ignored) { }
        return DEFAULT_PAGE_SIZE;
    }
}
