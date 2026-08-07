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
 * GET  /api/report/mine   — the caller's submitted reports with status + admin reply
 *
 * <p>The member-facing half of moderation: this is where a report is raised. Admins triage them
 * in {@code AdminApiServlet}. Every route needs a session, because an anonymous report cannot be
 * followed up and would make the queue easy to flood.</p>
 *
 * <p>Self-reporting is blocked on both routes, a listing can only be reported once by the same
 * member, and the free-text reason is sanitised before it reaches the admin console.
 * {@link NotificationService} alerts the admins once a report lands.</p>
 */
@WebServlet("/api/report/*")
public class ReportApiServlet extends ApiBase {

    private ReportDAO reportDAO;
    private UserDAO userDAO;

    public ReportApiServlet() {
        this.reportDAO = new ReportDAO();
        this.userDAO    = new UserDAO();
    }

    /** Test hooks: let a unit test supply stub DAOs. */
    public void setReportDAO(ReportDAO reportDAO) { this.reportDAO = reportDAO; }
    public void setUserDAO(UserDAO userDAO)       { this.userDAO    = userDAO; }

    /**
     * GET /api/report/mine. Lists the caller's own reports with their current status and any
     * admin reply, so a member can see their report was acted on. Any other path gives 404.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        String path = req.getPathInfo();
        if (path == null || !path.startsWith("/mine")) { error(resp, 404, "Not found."); return; }

        int userId = sessionUserId(req);
        try {
            ok(resp, reportDAO.listForReporter(userId));
        } catch (Exception e) {
            serverError(resp, "Could not load your reports.");
        }
    }

    /**
     * Routes the two report types. /user reports a member and is open to any signed-in account,
     * including sellers reporting a buyer. Anything else reports a listing and is buyer-side only.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        AuthSession session = authSession(req);

        String path = req.getPathInfo();
        if (path != null && path.startsWith("/user")) {
            handleReportUser(req, resp, session);
        } else {
            if (!canBuy(session)) { forbidden(resp); return; }
            handleReportListing(req, resp, session);
        }
    }

    /**
     * POST /api/report with {@code auctionId} and an optional {@code description}. The DAO
     * refuses a report on the caller's own listing and a duplicate report of the same listing,
     * so the moderation queue is not filled by one member clicking repeatedly.
     */
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

    /**
     * POST /api/report/user with {@code reportedId} and {@code reason}. Reports a member rather
     * than a listing, which is how a seller reports a non-paying buyer. Reporting yourself is
     * rejected here, since there is no DAO-level rule for it.
     */
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

    /** Wording for each reason a listing report can be refused. */
    private String toMessage(ReportResult r) {
        switch (r) {
            case AUCTION_NOT_FOUND: return "Auction not found.";
            case SELF_REPORT:       return "You cannot report your own listing.";
            case ALREADY_REPORTED:  return "You have already reported this listing.";
            default:                return "Could not submit report. Please try again.";
        }
    }
}
