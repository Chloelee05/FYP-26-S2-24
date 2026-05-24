package com.auction.servlet;

import com.auction.dao.ReportDAO;
import com.auction.dao.ReportDAO.ReportResult;
import com.auction.util.InputValidator;
import com.auction.util.RbacUtil;
import com.auction.util.SecurityUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Handles buyer reports against sellers (SCRUM-52).
 *
 * <p><b>Auth:</b> Mapped to {@code /protected/report}, so {@code AuthFilter}
 * guarantees a logged-in user. The servlet additionally enforces the BUYER role.</p>
 *
 * <p><b>No IDOR:</b>
 * <ul>
 *   <li>{@code buyerId} is read exclusively from the session — never from a request parameter.</li>
 *   <li>{@code auctionId} is parsed as {@code long}; non-numeric input returns 400.</li>
 *   <li>The seller's identity ({@code reportedUserId}) is resolved inside {@link ReportDAO}
 *       from the DB, not from the request.</li>
 * </ul>
 * </p>
 *
 * <p><b>Self-report guard:</b> {@link ReportDAO} rejects reports where the buyer is
 * the auction's seller.</p>
 *
 * <p><b>No PII in logs:</b> Only {@code auctionId} and {@code buyerId} are logged.</p>
 */
@WebServlet("/protected/report")
public class BuyerReportServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(BuyerReportServlet.class.getName());

    private ReportDAO reportDAO;

    public BuyerReportServlet() {
        this.reportDAO = new ReportDAO();
    }

    public BuyerReportServlet(ReportDAO reportDAO) {
        this.reportDAO = reportDAO;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);

        if (!RbacUtil.isBuyer(session)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Only buyers may report sellers.");
            return;
        }

        // buyerId always from session (never from request)
        int buyerId = ((Number) session.getAttribute("userId")).intValue();

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

        // description is optional; sanitize and enforce max length when provided
        String rawDescription = req.getParameter("description");
        String description = null;
        if (rawDescription != null && !rawDescription.isBlank()) {
            if (rawDescription.trim().length() > InputValidator.REPORT_DESCRIPTION_MAX_LENGTH) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "Description must be at most "
                        + InputValidator.REPORT_DESCRIPTION_MAX_LENGTH + " characters.");
                return;
            }
            description = SecurityUtil.sanitize(rawDescription);
        }

        ReportResult result;
        try {
            result = reportDAO.insertReport(auctionId, buyerId, description);
        } catch (RuntimeException e) {
            LOGGER.severe(String.format("insertReport error [auctionId=%d, buyerId=%d]: %s",
                    auctionId, buyerId, e.getMessage()));
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (result == ReportResult.SUCCESS) {
            LOGGER.info(String.format("Report submitted [auctionId=%d, buyerId=%d].",
                    auctionId, buyerId));
            session.setAttribute("reportFlash", "Your report has been submitted.");
        } else {
            session.setAttribute("reportFlashError", toMessage(result));
        }

        resp.sendRedirect(req.getContextPath() + "/auction/" + auctionId);
    }

    /** Translates a {@link ReportResult} failure to a user-facing message. */
    public static String toMessage(ReportResult result) {
        switch (result) {
            case AUCTION_NOT_FOUND:  return "Auction not found.";
            case SELF_REPORT:        return "You cannot report your own auction.";
            case ALREADY_REPORTED:   return "You have already reported this auction.";
            default:                 return "Could not submit report. Please try again.";
        }
    }
}
