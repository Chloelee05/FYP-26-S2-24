package com.auction.servlet.api;

import com.auction.dao.ReportDAO;
import com.auction.dao.ReportDAO.ReportResult;
import com.auction.dao.UserDAO;
import com.auction.model.User;
import com.auction.model.AccountReport;
import com.auction.notification.NotificationService;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;

/**
 * POST /api/report        params: auctionId, description (optional) — report a listing (buyer only)
 * POST /api/report/user   params: reportedId, reason                — report a user   (buyer only)
 */
@WebServlet("/api/report/*")
public class ReportApiServlet extends ApiBase {

    private final ReportDAO reportDAO = new ReportDAO();
    private final UserDAO userDAO = new UserDAO();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);

        String path = req.getPathInfo();
        if (path != null && path.startsWith("/user")) {
            handleReportUser(req, resp, session);
        } else {
            if (!isBuyer(session)) { forbidden(resp); return; }
            handleReportListing(req, resp, session);
        }
    }

    private void handleReportListing(HttpServletRequest req, HttpServletResponse resp, AuthSession session)
            throws IOException {
        int reporterId = ((Number) session.getAttribute("userId")).intValue();

        String auctionIdStr = param(req, "auctionId");
        if (auctionIdStr == null) { badRequest(resp, "auctionId is required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(auctionIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        String description = param(req, "description");
        if (description != null) description = SecurityUtil.sanitize(description.trim());

        ReportResult result = reportDAO.insertReport(auctionId, reporterId, description);
        if (result == ReportResult.SUCCESS) {
            NotificationService.notifyAdminsListingReport(auctionId);
            okMsg(resp, "Report submitted. Our team will review it shortly.");
        } else {
            error(resp, 400, toMessage(result));
        }
    }

    private void handleReportUser(HttpServletRequest req, HttpServletResponse resp, AuthSession session)
            throws IOException {
        int reporterId = ((Number) session.getAttribute("userId")).intValue();

        String reportedIdStr = param(req, "reportedId");
        if (reportedIdStr == null) { badRequest(resp, "reportedId is required."); return; }

        long reportedId;
        try { reportedId = Long.parseLong(reportedIdStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid user ID."); return; }

        if (reporterId == (int) reportedId) {
            error(resp, 400, "You cannot report yourself."); return;
        }

        String reason = param(req, "reason");
        if (reason == null) { badRequest(resp, "reason is required."); return; }
        reason = SecurityUtil.sanitize(reason.trim());

        try {
            AccountReport report = new AccountReport(
                    (long) reporterId, reportedId, reason, null, Instant.now());
            boolean ok = reportDAO.reportUser(report);
            if (ok) {
                User reporter = userDAO.getUserById(reporterId);
                NotificationService.notifyAdminsAccountReport(
                        reporter != null ? reporter.getUsername() : null, reason);
                okMsg(resp, "User report submitted. Our team will review it shortly.");
            } else {
                serverError(resp, "Could not submit report. Please try again.");
            }
        } catch (Exception e) {
            serverError(resp, "Could not submit report. Please try again.");
        }
    }

    private String toMessage(ReportResult r) {
        switch (r) {
            case AUCTION_NOT_FOUND: return "Auction not found.";
            case SELF_REPORT:       return "You cannot report your own listing.";
            case ALREADY_REPORTED:  return "You have already reported this listing.";
            default:                return "Could not submit report. Please try again.";
        }
    }
}
