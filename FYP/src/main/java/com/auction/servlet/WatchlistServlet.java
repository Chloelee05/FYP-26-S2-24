package com.auction.servlet;

import com.auction.dao.WatchlistDAO;
import com.auction.dao.WatchlistDAO.WatchlistResult;
import com.auction.model.profile.WatchlistRow;
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
 * Manages a buyer's auction watchlist (SCRUM-54).
 *
 * <p><b>Auth:</b> Mapped to {@code /protected/watchlist}, so {@code AuthFilter}
 * guarantees a logged-in user. The servlet additionally enforces the BUYER role.</p>
 *
 * <p><b>GET /protected/watchlist</b> — returns the buyer's watchlist items,
 * forwarded to {@code /WEB-INF/views/watchlist.jsp}.</p>
 *
 * <p><b>POST /protected/watchlist</b>
 * <ul>
 *   <li>{@code action=add} — add an auction to the watchlist.</li>
 *   <li>{@code action=remove} — remove an auction from the watchlist.</li>
 * </ul>
 * Both actions redirect to the auction page with a flash message.</p>
 *
 * <p><b>No IDOR:</b>
 * <ul>
 *   <li>{@code userId} is read exclusively from the session.</li>
 *   <li>{@code auctionId} is parsed as {@code long}; non-numeric input returns 400.</li>
 *   <li>The seller's identity is resolved inside {@link WatchlistDAO} from the DB.</li>
 * </ul>
 * </p>
 *
 * <p><b>No PII in logs:</b> Only {@code auctionId} and {@code userId} are logged.</p>
 */
@WebServlet("/protected/watchlist")
public class WatchlistServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(WatchlistServlet.class.getName());

    private WatchlistDAO watchlistDAO;

    public WatchlistServlet() {
        this.watchlistDAO = new WatchlistDAO();
    }

    public WatchlistServlet(WatchlistDAO watchlistDAO) {
        this.watchlistDAO = watchlistDAO;
    }

    // -------------------------------------------------------------------------
    // GET — list watchlist
    // -------------------------------------------------------------------------

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        if (!RbacUtil.isBuyer(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Only buyers may access the watchlist.");
            return;
        }

        // userId always from session (never from request)
        int userId = ((Number) session.getAttribute("userId")).intValue();

        List<WatchlistRow> watchlist;
        try {
            watchlist = watchlistDAO.listByUser(userId);
        } catch (RuntimeException e) {
            LOGGER.severe(String.format("listByUser error [userId=%d]: %s", userId, e.getMessage()));
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        req.setAttribute("watchlist", watchlist);
        req.getRequestDispatcher("/WEB-INF/views/watchlist.jsp").forward(req, resp);
    }

    // -------------------------------------------------------------------------
    // POST — add / remove
    // -------------------------------------------------------------------------

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        if (!RbacUtil.isBuyer(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Only buyers may modify the watchlist.");
            return;
        }

        // userId always from session (never from request)
        int userId = ((Number) session.getAttribute("userId")).intValue();

        // auctionId parsed as long (rejects non-numeric IDOR attempts)
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
        if (action == null || action.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "action is required.");
            return;
        }

        switch (action.trim()) {
            case "add":
                handleAdd(req, resp, session, auctionId, userId);
                break;
            case "remove":
                handleRemove(req, resp, session, auctionId, userId);
                break;
            default:
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "action must be 'add' or 'remove'.");
        }
    }

    private void handleAdd(HttpServletRequest req, HttpServletResponse resp,
                           HttpSession session, long auctionId, int userId)
            throws IOException {

        WatchlistResult result;
        try {
            result = watchlistDAO.add(auctionId, userId);
        } catch (RuntimeException e) {
            LOGGER.severe(String.format("watchlist add error [auctionId=%d, userId=%d]: %s",
                    auctionId, userId, e.getMessage()));
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (result == WatchlistResult.SUCCESS) {
            LOGGER.info(String.format("Watchlist add [auctionId=%d, userId=%d].", auctionId, userId));
            session.setAttribute("watchlistFlash", "Auction added to your watchlist.");
        } else {
            session.setAttribute("watchlistFlashError", toMessage(result));
        }

        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
    }

    private void handleRemove(HttpServletRequest req, HttpServletResponse resp,
                              HttpSession session, long auctionId, int userId)
            throws IOException {

        boolean removed;
        try {
            removed = watchlistDAO.remove(auctionId, userId);
        } catch (RuntimeException e) {
            LOGGER.severe(String.format("watchlist remove error [auctionId=%d, userId=%d]: %s",
                    auctionId, userId, e.getMessage()));
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (removed) {
            LOGGER.info(String.format("Watchlist remove [auctionId=%d, userId=%d].", auctionId, userId));
            session.setAttribute("watchlistFlash", "Auction removed from your watchlist.");
        }
        // Non-existent entry is silently accepted — no error flash

        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
    }

    /** Translates a {@link WatchlistResult} failure to a user-facing message. */
    public static String toMessage(WatchlistResult result) {
        switch (result) {
            case AUCTION_NOT_FOUND: return "Auction not found.";
            case OWN_AUCTION:       return "You cannot watch your own auction.";
            case ALREADY_WATCHING:  return "This auction is already in your watchlist.";
            default:                return "Could not update watchlist. Please try again.";
        }
    }
}
