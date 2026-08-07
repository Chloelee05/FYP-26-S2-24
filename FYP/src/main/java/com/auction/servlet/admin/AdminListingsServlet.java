package com.auction.servlet.admin;

import com.auction.dao.AuctionDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Listing moderation screen. GET renders the table of auctions with their moderation state;
 * POST applies one of three transitions to a listing: FLAG, REMOVE or RESTORE.
 *
 * <p>Legacy JSP admin console behind {@code AdminFilter}; the SPA moderates through
 * {@code /api/admin/*} in {@code AdminApiServlet}.</p>
 *
 * <p>Moderation state is separate from auction status. A removed listing keeps running as far
 * as the auction lifecycle is concerned but stops being visible to buyers, which is why the
 * change goes through {@code updateModerationState} rather than touching {@code status_id}.
 * RESTORE is the reverse of both FLAG and REMOVE, setting the state back to active.</p>
 */
@WebServlet("/admin/listings")
public class AdminListingsServlet extends HttpServlet {

    private AuctionDAO auctionDAO;

    public AdminListingsServlet() {
        this.auctionDAO = new AuctionDAO();
    }

    /** Test hook */
    public void setAuctionDAO(AuctionDAO auctionDAO) {
        this.auctionDAO = auctionDAO;
    }

    /** Loads every listing with its moderation state and shows any pending flash message. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession();
        copyFlash(session, req, "adminFlash");
        copyFlash(session, req, "adminFlashError");

        req.setAttribute("listings", auctionDAO.listListingsForModeration());
        req.setAttribute("adminActiveNav", "listings");
        req.getRequestDispatcher("/WEB-INF/views/admin/listings.jsp").forward(req, resp);
    }

    /**
     * Applies one moderation action. Expects {@code action} (FLAG, REMOVE or RESTORE) and
     * {@code auctionId}. FLAG does two things, bumping the report counter and setting the state,
     * so the table can show how often a listing has been reported.
     */
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

    /** Stores the outcome message under the success or the error key so it survives the redirect. */
    private static void setFlash(HttpSession session, boolean ok, String success, String err) {
        session.setAttribute(ok ? "adminFlash" : "adminFlashError", ok ? success : err);
    }

    /** Moves a one-shot message from the session onto the request and clears it. */
    private static void copyFlash(HttpSession session, HttpServletRequest req, String key) {
        Object v = session.getAttribute(key);
        if (v != null) {
            req.setAttribute(key, v);
            session.removeAttribute(key);
        }
    }
}
