package com.auction.servlet;

import com.auction.dao.BidDAO;
import com.auction.model.AuctionBidHistoryEntry;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Public paginated bid-history endpoint for an auction (SCRUM-58).
 *
 * <p>{@code GET /auction-bids?auctionId=&amp;page=&amp;size=} — no authentication required.
 * Bidder usernames are masked in {@link BidDAO#getBidHistory(long, int, int)} before they
 * reach the view layer.</p>
 *
 * <p><b>Security (SCRUM-361):</b>
 * <ul>
 *   <li>Public access — no session check.</li>
 *   <li>{@code auctionId} parsed as {@code long}; invalid → 400.</li>
 *   <li>{@code page} clamped to ≥ 1; {@code size} clamped to [1, {@link BidDAO#MAX_BID_HISTORY_PAGE_SIZE}].</li>
 *   <li>Non-existent auction → 404.</li>
 *   <li>Current leader partially masked; all others fully masked (DAO).</li>
 * </ul>
 * </p>
 */
@WebServlet("/auction-bids")
public class AuctionBidHistoryServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(AuctionBidHistoryServlet.class.getName());

    public static final int DEFAULT_PAGE_SIZE = 10;

    private BidDAO bidDAO;

    public AuctionBidHistoryServlet() {
        this.bidDAO = new BidDAO();
    }

    public AuctionBidHistoryServlet(BidDAO bidDAO) {
        this.bidDAO = bidDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        long auctionId = parseAuctionId(req, resp);
        if (auctionId < 0) return;

        int page = parsePage(req);
        int pageSize = parsePageSize(req);

        try {
            if (!bidDAO.auctionExists(auctionId)) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Auction not found.");
                return;
            }
        } catch (RuntimeException e) {
            LOGGER.severe("auctionExists error [auctionId=" + auctionId + "]: " + e.getMessage());
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        List<AuctionBidHistoryEntry> bids;
        int totalBids;
        try {
            totalBids = bidDAO.countBidHistory(auctionId);
            bids = bidDAO.getBidHistory(auctionId, page, pageSize);
        } catch (RuntimeException e) {
            LOGGER.severe("getBidHistory error [auctionId=" + auctionId + "]: " + e.getMessage());
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        int totalPages = totalBids == 0 ? 1 : (int) Math.ceil((double) totalBids / pageSize);
        if (page > totalPages) {
            page = totalPages;
            bids = bidDAO.getBidHistory(auctionId, page, pageSize);
        }

        req.setAttribute("auctionId", auctionId);
        req.setAttribute("bidHistory", bids);
        req.setAttribute("bidPage", page);
        req.setAttribute("bidTotalPages", totalPages);
        req.setAttribute("bidPageSize", pageSize);
        req.setAttribute("bidTotalCount", totalBids);
        req.setAttribute("bidHistoryEmpty", bids.isEmpty());

        req.getRequestDispatcher("/WEB-INF/views/auction-bid-history.jsp").forward(req, resp);
    }

    // =========================================================================
    // Parsing helpers (public for unit tests — SCRUM-361)
    // =========================================================================

    public static long parseAuctionId(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String raw = req.getParameter("auctionId");
        if (raw == null || raw.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "auctionId is required.");
            return -1;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid auction ID.");
            return -1;
        }
    }

    public static int parsePage(HttpServletRequest req) {
        try {
            String p = req.getParameter("page");
            if (p == null || p.isBlank()) p = req.getParameter("bidPage");
            if (p != null && !p.isBlank()) return Math.max(1, Integer.parseInt(p.trim()));
        } catch (NumberFormatException ignored) { }
        return 1;
    }

    public static int parsePageSize(HttpServletRequest req) {
        try {
            String s = req.getParameter("size");
            if (s == null || s.isBlank()) s = req.getParameter("bidSize");
            if (s != null && !s.isBlank()) {
                return Math.min(BidDAO.MAX_BID_HISTORY_PAGE_SIZE,
                        Math.max(1, Integer.parseInt(s.trim())));
            }
        } catch (NumberFormatException ignored) { }
        return DEFAULT_PAGE_SIZE;
    }
}
