package com.auction.servlet.admin;

import com.auction.dao.AuctionDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

@WebServlet("/admin/listings")
public class AdminListingsServlet extends HttpServlet {

    private final AuctionDAO auctionDAO;

    public AdminListingsServlet() {
        this.auctionDAO = new AuctionDAO();
    }

    AdminListingsServlet(AuctionDAO auctionDAO) {
        this.auctionDAO = auctionDAO;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession();
        copyFlash(session, req, "adminFlash");
        copyFlash(session, req, "adminFlashError");

        req.setAttribute("listings", auctionDAO.listListingsForModeration());
        req.setAttribute("adminActiveNav", "listings");
        req.getRequestDispatcher("/WEB-INF/views/admin/listings.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession();
        String action = req.getParameter("action");
        String idStr = req.getParameter("auctionId");
        if (action == null || action.isBlank() || idStr == null || idStr.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        long auctionId;
        try {
            auctionId = Long.parseLong(idStr.trim());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        action = action.trim().toUpperCase();
        boolean ok;
        switch (action) {
            case "FLAG":
                ok = auctionDAO.incrementReports(auctionId) && auctionDAO.updateModerationState(auctionId, "flagged");
                setFlash(session, ok, "Listing flagged for review.", "Could not flag listing.");
                break;
            case "REMOVE":
                ok = auctionDAO.updateModerationState(auctionId, "removed");
                setFlash(session, ok, "Listing removed from public view.", "Could not remove listing.");
                break;
            case "RESTORE":
                ok = auctionDAO.updateModerationState(auctionId, "active");
                setFlash(session, ok, "Listing restored.", "Could not restore listing.");
                break;
            default:
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
        }
        resp.sendRedirect(req.getContextPath() + "/admin/listings");
    }

    private static void setFlash(HttpSession session, boolean ok, String success, String err) {
        session.setAttribute(ok ? "adminFlash" : "adminFlashError", ok ? success : err);
    }

    private static void copyFlash(HttpSession session, HttpServletRequest req, String key) {
        Object v = session.getAttribute(key);
        if (v != null) {
            req.setAttribute(key, v);
            session.removeAttribute(key);
        }
    }
}
