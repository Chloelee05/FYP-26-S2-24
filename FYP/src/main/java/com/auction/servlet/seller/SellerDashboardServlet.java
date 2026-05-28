package com.auction.servlet.seller;

import com.auction.dao.SellerAuctionDAO;
import com.auction.model.AuctionStatus;
import com.auction.model.Bid;
import com.auction.model.Role;
import com.auction.model.User;
import com.auction.model.seller.SellerAuctionRow;
import com.auction.util.RbacUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SCRUM-38 – Seller auction dashboard.
 *
 * GET /protected/seller/auctions
 *   status (optional) – one of: active, pending, finished, cancelled (case-insensitive)
 *   page   (optional) – 1-based; default 1
 *   size   (optional) – rows per page; default 10, max 50
 *
 * Only the currently authenticated seller's auctions are ever returned.
 * Forwards to /WEB-INF/views/seller/auctions.jsp with:
 *   auctions    – List<SellerAuctionRow>
 *   currentPage – int
 *   totalPages  – int
 *   total       – int
 *   statusFilter – Integer (null = all)
 *
 * Protected by AuthFilter (/protected/*). Role check is re-enforced inline.
 */
@WebServlet("/protected/seller/auctions")
public class SellerDashboardServlet extends HttpServlet {

    private SellerAuctionDAO dao = new SellerAuctionDAO();

    public void setDao(SellerAuctionDAO dao) { this.dao = dao; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        if (!RbacUtil.isSeller(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        int sellerId = ((Number) session.getAttribute("userId")).intValue();

        Integer statusId = resolveStatusFilter(req, resp);
        if (Integer.valueOf(Integer.MIN_VALUE).equals(statusId)) return; // invalid — error already sent

        int[] pagination = resolvePagination(req, resp);
        if (pagination == null) return;
        int page     = pagination[0];
        int pageSize = pagination[1];

        try {
            List<SellerAuctionRow> auctions = dao.listSellerAuctions(sellerId, statusId, page, pageSize);
            int total      = dao.countSellerAuctions(sellerId, statusId);
            int totalPages = (total == 0) ? 1 : (int) Math.ceil((double) total / pageSize);

            req.setAttribute("auctions",     auctions);
            req.setAttribute("currentPage",  page);
            req.setAttribute("totalPages",   totalPages);
            req.setAttribute("total",        total);
            req.setAttribute("statusFilter", statusId);
            req.getRequestDispatcher("/WEB-INF/views/seller/auctions.jsp").forward(req, resp);
        } catch (Exception e) {
            getServletContext().log("SellerDashboardServlet error", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String temp = req.getParameter("auction_id");
        temp = (temp == null) ? null : temp.trim();
        if (temp == null || temp.isBlank()) {
            errorHandler(req, resp, "Invalid auction_id");
            return;
        }
        Long auctionId;
        try {
            auctionId = Long.parseLong(temp);
        } catch (NumberFormatException e) {
            errorHandler(req, resp, "Invalid auction id");
            return;
        }

        List<Bid> bids = getBidHistory(req, resp, auctionId);
        if (bids == null) return;
        req.setAttribute("bids", bids);
        // req.getRequestDispatcher("???").forward(req, resp);
    }

    /**
     * Maps the "status" query parameter to an AuctionStatus id.
     * Returns null for "all", Integer.MIN_VALUE (boxed) when the value is invalid
     * (caller checks via .equals to avoid NPE on null — sendError already called).
     */
    private Integer resolveStatusFilter(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String param = req.getParameter("status");
        if (param == null || param.isBlank()) return null;
        switch (param.trim().toLowerCase()) {
            case "active":    return AuctionStatus.ACTIVE.getId();
            case "finished":  return AuctionStatus.FINISHED.getId();
            case "cancelled": return AuctionStatus.CANCELLED.getId();
            case "pending":   return AuctionStatus.PENDING.getId();
            default:
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "status must be active, pending, finished, or cancelled");
                return Integer.MIN_VALUE;
        }
    }

    /** Returns [page, pageSize] or null on invalid input (error already sent). */
    private int[] resolvePagination(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        int page     = 1;
        int pageSize = 10;
        try {
            String p = req.getParameter("page");
            if (p != null && !p.isBlank()) page = Math.max(1, Integer.parseInt(p.trim()));
            String s = req.getParameter("size");
            if (s != null && !s.isBlank()) pageSize = Math.min(50, Math.max(1, Integer.parseInt(s.trim())));
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid pagination parameter");
            return null;
        }
        return new int[]{page, pageSize};
    }

    private List<Bid> getBidHistory(HttpServletRequest req, HttpServletResponse resp, Long auctionId) throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        if (session == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }

        User user = (User) session.getAttribute("user");
        if (user == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        if (!RbacUtil.isSeller(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }
        try {
            List<Bid> result = dao.getBidHistory(auctionId, (long) user.getId());
            if (result.isEmpty()) {
                req.setAttribute("info", "No bids found for this auction");
            }
            return result;
        } catch (Exception e) {
            getServletContext().log("Get bid history error", e);
            errorHandler(req, resp, "Could not reach the database");
            return null;
        }
    }

    private void errorHandler(HttpServletRequest req, HttpServletResponse resp, String message) throws ServletException, IOException {
        req.setAttribute("Error", message);
        // req.getRequestDispatcher("???").forward(req, resp);
    }
}
