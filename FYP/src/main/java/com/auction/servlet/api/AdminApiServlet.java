package com.auction.servlet.api;

import com.auction.dao.AdminManagementDAO;
import com.auction.dao.AdminReportDAO;
import com.auction.dao.AuctionDAO;
// NEW for the "platform-wide auction rules" admin story: reads the shared default constant so
// GET /auction-rules reports the exact same fallback BidDAO itself uses.
import com.auction.dao.BidDAO;
import com.auction.dao.CategoryDAO;
import com.auction.dao.FeaturedListingDAO;
import com.auction.dao.OrderDAO;
import com.auction.dao.PlatformRevenueDAO;
// NEW for the "platform-wide auction rules" admin story: read/write the settings this
// endpoint exposes, the same DAO BidDAO and the seller listing endpoints read from.
import com.auction.dao.PlatformSettingsDAO;
import com.auction.dao.ReportDAO;
import com.auction.dao.SellerAnalyticsDAO;
import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.User;
import com.auction.model.admin.AdminUserSummary;
import com.auction.model.admin.DashboardMetrics;
import com.auction.util.DatabaseBackupUtil;
import com.auction.util.InputValidator;
import com.auction.util.MailConfig;
import com.auction.util.OtpMailer;
import com.auction.util.RelativeTime;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GET  /api/admin/dashboard
 * GET  /api/admin/users
 * POST /api/admin/users           (action: suspend|active|approve|reject|deactivate, userid)
 * GET  /api/admin/listings
 * POST /api/admin/listings        (action: FLAG|REMOVE|RESTORE|FEATURE|UNFEATURE|EDIT|SET_KIND, auctionId)
 * GET  /api/admin/categories
 * POST /api/admin/categories      (action: CREATE|EDIT|DELETE|RESTORE)
 * GET  /api/admin/orders
 * POST /api/admin/orders          (action: refund-approve|refund-decline|correct-status, orderId)
 * GET  /api/admin/analytics
 * GET  /api/admin/analytics/report?type=user-activity|revenue|moderation
 * GET  /api/admin/audit-log       (management actions, newest first)
 * GET  /api/admin/database/status
 * GET  /api/admin/database/backup
 * POST /api/admin/database/restore
 * GET  /api/admin/sellers/analytics?sellerId=N   (read the report inline)
 * POST /api/admin/sellers/analytics-email
 * All require ADMIN role.
 *
 * <p>The whole admin console behind one servlet. Every request starts with
 * {@code requireRole(req, resp, "ADMIN")}, checked here rather than by a filter, because
 * {@code AdminFilter} only guards the legacy JSP paths under {@code /admin/*} and not
 * {@code /api/*}. Miss that call in a new handler and the endpoint is open to any logged-in
 * member, so it is the first line of both doGet and doPost.</p>
 *
 * <p>It touches most of the DAO layer: user approval and banning, listing moderation,
 * categories, order and refund correction, reviews, the reporting queue, revenue analytics,
 * recommendation tuning, and database backup and restore. Actions that change another
 * member's account are written to the audit log through {@link AdminManagementDAO}, and an
 * admin cannot act on their own account or on another admin.</p>
 */
@WebServlet("/api/admin/*")
public class AdminApiServlet extends ApiBase {

    private final UserDAO     userDAO    = new UserDAO();
    private final AuctionDAO  auctionDAO = new AuctionDAO();
    private final CategoryDAO catDAO     = new CategoryDAO();
    private final ReportDAO   reportDAO  = new ReportDAO();
    private final OrderDAO    orderDAO   = new OrderDAO();
    private final AdminReportDAO adminReportDAO = new AdminReportDAO();
    private final AdminManagementDAO adminManagementDAO = new AdminManagementDAO();
    private final SellerAnalyticsDAO sellerAnalyticsDAO = new SellerAnalyticsDAO();
    private final FeaturedListingDAO featuredListingDAO = new FeaturedListingDAO();
    private final PlatformRevenueDAO platformRevenueDAO = new PlatformRevenueDAO();
    private final com.auction.dao.RatingDAO ratingDAO = new com.auction.dao.RatingDAO();
    private final com.auction.dao.RecommendationDAO recommendationDAO = new com.auction.dao.RecommendationDAO();
    // NEW for the "platform-wide auction rules" admin story.
    private final PlatformSettingsDAO platformSettingsDAO = new PlatformSettingsDAO();

    /**
     * Routes every admin read. Multi-segment paths such as /database/status are matched first,
     * then the single-segment ones fall through to the switch on the first path segment. The
     * ADMIN check happens once here so no individual handler can be reached without it.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;
        String path = req.getPathInfo();
        if (path != null && path.startsWith("/analytics/report")) {
            handleAnalyticsReport(req, resp);
            return;
        }
        if (path != null && path.equals("/database/status")) {
            handleDatabaseStatus(resp);
            return;
        }
        if (path != null && path.equals("/database/backup")) {
            handleDatabaseBackup(resp);
            return;
        }
        if (path != null && path.equals("/recommendations")) {
            handleGetRecommendationConfig(resp);
            return;
        }
        if (path != null && path.equals("/sellers/analytics")) {
            handleSellerAnalyticsView(req, resp);
            return;
        }
        if (path != null && path.equals("/audit-log")) {
            handleGetAuditLog(req, resp);
            return;
        }
        if (path != null && path.equals("/listings/content")) {
            handleGetListingContent(req, resp);
            return;
        }
        // NEW for the "platform-wide auction rules" admin story: its own top-level path check,
        // matching how /recommendations and /sellers/analytics above are routed.
        if (path != null && path.equals("/auction-rules")) {
            handleGetAuctionRules(resp);
            return;
        }
        switch (sub(req)) {
            case "dashboard":   handleDashboard(resp);         break;
            case "users":       ok(resp, userDAO.listUsersForAdminTable()); break;
            case "listings":    handleGetListings(resp);        break;
            case "categories":  ok(resp, catDAO.listAll());    break;
            case "analytics":   handleAnalytics(resp);         break;
            case "reports":     handleGetReports(resp);        break;
            case "reviews":     ok(resp, ratingDAO.listAllForAdmin(200)); break;
            case "orders":      handleGetOrders(resp);         break;
            default: error(resp, 404, "Not found.");            break;
        }
    }

    /**
     * Routes every admin write, with the same ADMIN gate as the read side. Each handler then
     * reads its own {@code action} parameter to decide what to do to the named record.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;
        String path = req.getPathInfo();
        if (path != null && path.equals("/database/restore")) {
            handleDatabaseRestore(req, resp);
            return;
        }
        if (path != null && path.equals("/sellers/analytics-email")) {
            handleSellerAnalyticsEmail(req, resp);
            return;
        }
        if (path != null && path.equals("/recommendations")) {
            handleSaveRecommendationConfig(req, resp);
            return;
        }
        // NEW for the "system-wide announcement" admin story: its own top-level path check,
        // exactly like /database/restore, /sellers/analytics-email and /recommendations
        // above, so it cannot collide with a case added to the switch below by anyone else
        // working in this file at the same time.
        if (path != null && path.equals("/announcements")) {
            handleBroadcastAnnouncement(req, resp);
            return;
        }
        // NEW for the "platform-wide auction rules" admin story: same isolated top-level path
        // check as /announcements just above.
        if (path != null && path.equals("/auction-rules")) {
            handleSaveAuctionRules(req, resp);
            return;
        }
        switch (sub(req)) {
            case "users":      handleUserAction(req, resp);      break;
            case "listings":   handleListingAction(req, resp);   break;
            case "categories": handleCategoryAction(req, resp);  break;
            case "reports":    handleReportAction(req, resp);    break;
            case "reviews":    handleReviewAction(req, resp);    break;
            case "orders":     handleOrderAction(req, resp);     break;
            default: error(resp, 404, "Not found.");             break;
        }
    }

    /** GET /api/admin/recommendations — performance metrics + tunable parameters. */
    private void handleGetRecommendationConfig(HttpServletResponse resp) throws IOException {
        try {
            com.auction.dao.RecommendationDAO.Settings s = recommendationDAO.getSettings();
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("itemsShown", s.itemsShown);
            settings.put("similarityThreshold", s.similarityThreshold);
            settings.put("trendingWindowDays", s.trendingWindowDays);
            settings.put("weightBid", s.weightBid);
            settings.put("weightWatchlist", s.weightWatchlist);
            settings.put("weightBrowse", s.weightBrowse);
            settings.put("recencyTauDays", s.recencyTauDays);
            settings.put("contentWindowDays", s.contentWindowDays);
            settings.put("weightCf", s.weightCf);
            settings.put("weightUbcf", s.weightUbcf);
            settings.put("weightContent", s.weightContent);
            settings.put("weightPopularity", s.weightPopularity);
            settings.put("weightRecency", s.weightRecency);
            settings.put("diversityDivisor", s.diversityDivisor);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("metrics", recommendationDAO.metrics());
            // Per-arm breakdown, including the non-personalised TRENDING_CONTROL strip.
            body.put("metricsByReason", recommendationDAO.metricsByReason());
            body.put("settings", settings);
            ok(resp, body);
        } catch (Exception e) {
            serverError(resp, "Could not load recommendation configuration.");
        }
    }

    /**
     * POST /api/admin/recommendations  itemsShown, similarityThreshold, and optionally
     * trendingWindowDays, weightBid, weightWatchlist, weightBrowse.
     *
     * <p>Every field added after the original two is optional, so an older client that
     * posts only itemsShown and similarityThreshold keeps working — an absent value leaves
     * the stored setting exactly as it was rather than resetting it to a default.</p>
     */
    private void handleSaveRecommendationConfig(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        com.auction.dao.RecommendationDAO.Settings current = recommendationDAO.getSettings();

        int itemsShown;
        double threshold;
        try {
            itemsShown = Integer.parseInt(param(req, "itemsShown"));
            threshold  = Double.parseDouble(param(req, "similarityThreshold"));
        } catch (Exception e) {
            badRequest(resp, "itemsShown and similarityThreshold are required numbers."); return;
        }
        if (itemsShown < 1 || itemsShown > 24) {
            badRequest(resp, "itemsShown must be between 1 and 24."); return;
        }
        if (threshold < 0 || threshold > 1) {
            badRequest(resp, "similarityThreshold must be between 0 and 1."); return;
        }

        int windowDays;
        double weightBid, weightWatchlist, weightBrowse, recencyTauDays;
        int contentWindowDays;
        double weightCf, weightUbcf, weightContent, weightPopularity, weightRecency;
        int diversityDivisor;
        try {
            windowDays        = optionalInt(req, "trendingWindowDays", current.trendingWindowDays);
            weightBid         = optionalDouble(req, "weightBid", current.weightBid);
            weightWatchlist   = optionalDouble(req, "weightWatchlist", current.weightWatchlist);
            weightBrowse      = optionalDouble(req, "weightBrowse", current.weightBrowse);
            recencyTauDays    = optionalDouble(req, "recencyTauDays", current.recencyTauDays);
            contentWindowDays = optionalInt(req, "contentWindowDays", current.contentWindowDays);
            weightCf          = optionalDouble(req, "weightCf", current.weightCf);
            weightUbcf        = optionalDouble(req, "weightUbcf", current.weightUbcf);
            weightContent     = optionalDouble(req, "weightContent", current.weightContent);
            weightPopularity  = optionalDouble(req, "weightPopularity", current.weightPopularity);
            weightRecency     = optionalDouble(req, "weightRecency", current.weightRecency);
            diversityDivisor  = optionalInt(req, "diversityDivisor", current.diversityDivisor);
        } catch (NumberFormatException e) {
            badRequest(resp, "trendingWindowDays and the interaction weights must be numbers."); return;
        }

        if (windowDays < 1 || windowDays > 365) {
            badRequest(resp, "trendingWindowDays must be between 1 and 365."); return;
        }
        for (double weight : new double[]{weightBid, weightWatchlist, weightBrowse}) {
            // A negative weight would make an interaction count as evidence of dislike.
            if (weight < 0 || weight > 100) {
                badRequest(resp, "Interaction weights must be between 0 and 100."); return;
            }
        }
        // Zero is allowed and meaningful: it turns recency decay off.
        if (recencyTauDays < 0 || recencyTauDays > 3650) {
            badRequest(resp, "recencyTauDays must be between 0 and 3650."); return;
        }
        if (contentWindowDays < 1 || contentWindowDays > 3650) {
            badRequest(resp, "contentWindowDays must be between 1 and 3650."); return;
        }
        // Zero is allowed per weight — that is how an admin demonstrates a signal being
        // switched off — but all five at once leaves the ranking with nothing to sort by.
        for (double weight : new double[]{weightCf, weightUbcf, weightContent,
                                          weightPopularity, weightRecency}) {
            if (weight < 0 || weight > 100) {
                badRequest(resp, "Re-ranking weights must be between 0 and 100."); return;
            }
        }
        if (diversityDivisor < 1 || diversityDivisor > 24) {
            badRequest(resp, "diversityDivisor must be between 1 and 24."); return;
        }

        try {
            recommendationDAO.saveSettings(com.auction.dao.RecommendationDAO.Settings.builder()
                    .itemsShown(itemsShown)
                    .similarityThreshold(threshold)
                    .trendingWindowDays(windowDays)
                    .weightBid(weightBid)
                    .weightWatchlist(weightWatchlist)
                    .weightBrowse(weightBrowse)
                    .recencyTauDays(recencyTauDays)
                    .contentWindowDays(contentWindowDays)
                    .weightCf(weightCf)
                    .weightUbcf(weightUbcf)
                    .weightContent(weightContent)
                    .weightPopularity(weightPopularity)
                    .weightRecency(weightRecency)
                    .diversityDivisor(diversityDivisor)
                    .build());
            okMsg(resp, "Recommendation settings saved.");
        } catch (Exception e) {
            serverError(resp, "Could not save settings. Run DB migrations and try again.");
        }
    }

    /** Parses an optional numeric parameter, keeping {@code fallback} when it is absent. */
    private int optionalInt(HttpServletRequest req, String name, int fallback) {
        String raw = param(req, name);
        return (raw == null || raw.isBlank()) ? fallback : Integer.parseInt(raw.trim());
    }

    /** Parses an optional decimal parameter, keeping {@code fallback} when it is absent. */
    private double optionalDouble(HttpServletRequest req, String name, double fallback) {
        String raw = param(req, name);
        return (raw == null || raw.isBlank()) ? fallback : Double.parseDouble(raw.trim());
    }

    // ── GET/POST: platform-wide auction rules (NEW) ─────────────────────────────
    //
    // The whole story: platform-wide minimum bid increment and maximum auction duration,
    // admin-tunable the same way the recommendation parameters above are. Read/written through
    // PlatformSettingsDAO, the same generic key/value store BidDAO already reads for the bid
    // rate limit. Whitelisted the same way AdminLandingContentApiServlet.handleUpdate
    // whitelists its own content keys, so an unrecognised key is a 400, not a silently-ignored
    // no-op or a row inserted for a key nothing ever reads.

    /**
     * The only two keys this endpoint may read or write. {@link BidDAO} and
     * {@link com.auction.servlet.api.SellerApiServlet} read the same two keys directly from
     * {@link PlatformSettingsDAO}, so this whitelist exists purely to stop a POST here from
     * writing an arbitrary {@code platform_settings} row — it is not the only reader.
     */
    private static final Set<String> AUCTION_RULE_KEYS =
            Set.of("min_bid_increment", "max_auction_duration_days");

    /** Sane upper bound on an admin-set minimum bid increment; well above any real listing price. */
    private static final BigDecimal MAX_MIN_BID_INCREMENT = new BigDecimal("1000");
    /** Sane upper bound on an admin-set maximum auction duration, in days (100 years). */
    private static final int MAX_AUCTION_DURATION_DAYS_CEILING = 36500;

    /**
     * GET /api/admin/auction-rules — the two platform-wide auction rules and their current
     * effective values (NEW for the "platform-wide auction rules" admin story).
     */
    private void handleGetAuctionRules(HttpServletResponse resp) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("minBidIncrement",
                platformSettingsDAO.getBigDecimal("min_bid_increment", BidDAO.DEFAULT_MIN_BID_INCREMENT));
        out.put("maxAuctionDurationDays",
                platformSettingsDAO.getInt("max_auction_duration_days",
                        SellerApiServlet.DEFAULT_MAX_AUCTION_DURATION_DAYS));
        ok(resp, out);
    }

    /**
     * POST /api/admin/auction-rules — updates one or both settings (NEW for the
     * "platform-wide auction rules" admin story). Either {@code minBidIncrement} or
     * {@code maxAuctionDurationDays} (or both) may be supplied; whichever is omitted is left
     * untouched. Each changed value is written to the admin audit log via
     * {@link AdminManagementDAO#recordAuctionRulesChange}.
     */
    private void handleSaveAuctionRules(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Reject any request parameter that is not one of the two whitelisted keys, mirroring
        // AdminLandingContentApiServlet.handleUpdate's own whitelist check.
        for (String key : req.getParameterMap().keySet()) {
            if (!AUCTION_RULE_KEYS.contains(key)) {
                badRequest(resp, "Unknown auction rule key: " + key);
                return;
            }
        }

        String minIncStr = param(req, "min_bid_increment");
        String maxDaysStr = param(req, "max_auction_duration_days");
        if (minIncStr == null && maxDaysStr == null) {
            badRequest(resp, "min_bid_increment or max_auction_duration_days is required.");
            return;
        }

        BigDecimal newMinInc = null;
        if (minIncStr != null) {
            try {
                newMinInc = new BigDecimal(minIncStr.trim());
            } catch (NumberFormatException e) {
                badRequest(resp, "Invalid min_bid_increment."); return;
            }
            if (newMinInc.compareTo(BigDecimal.ZERO) <= 0
                    || newMinInc.compareTo(MAX_MIN_BID_INCREMENT) > 0) {
                badRequest(resp, "min_bid_increment must be greater than 0 and at most "
                        + MAX_MIN_BID_INCREMENT + "."); return;
            }
        }

        Integer newMaxDays = null;
        if (maxDaysStr != null) {
            try {
                newMaxDays = Integer.parseInt(maxDaysStr.trim());
            } catch (NumberFormatException e) {
                badRequest(resp, "Invalid max_auction_duration_days."); return;
            }
            if (newMaxDays <= 0 || newMaxDays > MAX_AUCTION_DURATION_DAYS_CEILING) {
                badRequest(resp, "max_auction_duration_days must be greater than 0 and at most "
                        + MAX_AUCTION_DURATION_DAYS_CEILING + "."); return;
            }
        }

        try {
            int admin = adminId(req);
            if (newMinInc != null) {
                String oldValue = platformSettingsDAO
                        .getBigDecimal("min_bid_increment", BidDAO.DEFAULT_MIN_BID_INCREMENT).toPlainString();
                platformSettingsDAO.setValue("min_bid_increment", newMinInc.toPlainString());
                adminManagementDAO.recordAuctionRulesChange(admin, "min_bid_increment",
                        oldValue, newMinInc.toPlainString(), "Admin auction rules update");
            }
            if (newMaxDays != null) {
                String oldValue = String.valueOf(platformSettingsDAO.getInt(
                        "max_auction_duration_days", SellerApiServlet.DEFAULT_MAX_AUCTION_DURATION_DAYS));
                platformSettingsDAO.setValue("max_auction_duration_days", String.valueOf(newMaxDays));
                adminManagementDAO.recordAuctionRulesChange(admin, "max_auction_duration_days",
                        oldValue, String.valueOf(newMaxDays), "Admin auction rules update");
            }
            handleGetAuctionRules(resp);
        } catch (Exception e) {
            serverError(resp, "Could not save auction rules.");
        }
    }

    // ── POST: system-wide announcement (NEW) ────────────────────────────────────
    //
    // The whole story: "As an Admin, I want to send system-wide announcements or
    // notifications to all users, so that I can send maintenance or policy updates."
    // A single fire-and-forget compose form — no scheduling, no recipient segmentation, no
    // channel picker (the existing per-user email/Telegram funnel handles that already).

    /** Bounds for the announcement form, mirroring AdminLandingContentApiServlet.MAX_VALUE_LENGTH's
     *  shape (a title-sized cap and a longer body cap) rather than inventing new validation. */
    private static final int ANNOUNCEMENT_TITLE_MAX_LENGTH = 200;
    private static final int ANNOUNCEMENT_BODY_MAX_LENGTH = 2000;

    /**
     * POST /api/admin/announcements  title, body — broadcasts one admin-authored message to
     * every active user (NEW for the system-wide announcement admin story).
     *
     * <p>Delegates the fan-out to {@code NotificationService.broadcastAnnouncement}, which
     * reuses the existing per-user email/Telegram funnel, so this handler carries no channel
     * logic of its own — only request validation, dispatch and the audit-log entry. The ADMIN
     * gate already ran in {@link #doPost} before {@code sub(req)}/path routing reached here,
     * the same as every other write in this servlet.</p>
     */
    private void handleBroadcastAnnouncement(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String title = SecurityUtil.sanitizeText(param(req, "title"));
        String body = SecurityUtil.sanitizeText(param(req, "body"));
        if (title == null || title.isBlank()) { badRequest(resp, "title is required."); return; }
        if (body == null || body.isBlank()) { badRequest(resp, "body is required."); return; }
        if (title.length() > ANNOUNCEMENT_TITLE_MAX_LENGTH) {
            badRequest(resp, "title must be at most " + ANNOUNCEMENT_TITLE_MAX_LENGTH + " characters.");
            return;
        }
        if (body.length() > ANNOUNCEMENT_BODY_MAX_LENGTH) {
            badRequest(resp, "body must be at most " + ANNOUNCEMENT_BODY_MAX_LENGTH + " characters.");
            return;
        }
        try {
            int recipients = com.auction.notification.NotificationService
                    .broadcastAnnouncement(title, body);
            // Recorded the same way every other admin management action here is: one row in
            // the existing admin_audit_log, through AdminManagementDAO.
            adminManagementDAO.recordAnnouncementBroadcast(adminId(req), title, body, recipients);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("recipients", recipients);
            // The single source of truth for whether email actually went out is the server's
            // own MailConfig check, read fresh right here — never assumed from the message
            // text below, which is the exact "email not configured" self-contradiction bug
            // that was previously found and fixed on the seller-analytics admin page.
            out.put("emailConfigured", MailConfig.isSmtpConfigured());
            out.put("message", "Announcement sent to " + recipients + " user(s).");
            ok(resp, out);
        } catch (Exception e) {
            serverError(resp, "Could not send the announcement.");
        }
    }

    /** POST /api/admin/reviews  action=delete, reviewId — remove an inappropriate review. */
    private void handleReviewAction(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = param(req, "action");
        String idStr  = param(req, "reviewId");
        if (!"delete".equalsIgnoreCase(action)) { badRequest(resp, "Unknown action."); return; }
        if (idStr == null) { badRequest(resp, "reviewId is required."); return; }
        long reviewId;
        try { reviewId = Long.parseLong(idStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid reviewId."); return; }

        if (ratingDAO.adminDeleteReview(reviewId)) {
            okMsg(resp, "Review removed.");
        } else {
            error(resp, 404, "Review not found.");
        }
    }

    // ── GET: dashboard ───────────────────────────────────────────────────────

    /**
     * GET /api/admin/dashboard. The headline counts, a recent activity feed and short previews of
     * the user and listing tables, all in one call so the admin home page loads in a single round
     * trip.
     */
    private void handleDashboard(HttpServletResponse resp) throws IOException {
        Instant now  = Instant.now();
        ZoneId  zone = ZoneId.systemDefault();

        DashboardMetrics metrics = new DashboardMetrics(
                userDAO.countNonDeletedUsers(),
                userDAO.countActiveUsers(),
                auctionDAO.countListingsModerationActive(),
                auctionDAO.countListingsTotal(),
                auctionDAO.countListingsFlagged(),
                auctionDAO.sumWinningBidDollars(),
                // Was the hard-coded string "+ 12.5% this month", rendered under the Revenue
                // card whatever the data said. A fabricated metric reads worse than a bug.
                adminReportDAO.revenueGrowthLabel());

        List<Map<String, Object>> activities = buildActivity(now, zone);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics",    metrics);
        body.put("activities", activities);
        body.put("previewUsers",    slice(userDAO.listUsersForAdminTable(),    5));
        body.put("previewListings", slice(auctionDAO.listListingsForModeration(), 5));
        ok(resp, body);
    }

    /**
     * Merges three separate event feeds, registrations, flagged listings and suspensions, into
     * one list ordered newest first and trimmed to 12. Each source is queried separately because
     * they live in different tables, so the sort has to happen in Java. Returns a single
     * placeholder row on a fresh install rather than an empty list, so the panel is never blank.
     */
    private List<Map<String, Object>> buildActivity(Instant now, ZoneId zone) {
        List<Map<String, Object>> buf = new ArrayList<>();
        for (UserDAO.NamedInstantEvent e : userDAO.recentRegistrations(6)) {
            buf.add(actItem("success", "New user " + e.getName() + " registered",
                    RelativeTime.format(e.getAt(), now, zone), e.getAt()));
        }
        for (AuctionDAO.FlaggedTitleEvent e : auctionDAO.recentFlaggedListings(6)) {
            buf.add(actItem("warning", "Listing '" + e.getTitle() + "' was flagged",
                    RelativeTime.format(e.getAt(), now, zone), e.getAt()));
        }
        for (UserDAO.NamedInstantEvent e : userDAO.recentSuspensions(6)) {
            buf.add(actItem("danger", "User " + e.getName() + " was banned",
                    RelativeTime.format(e.getAt(), now, zone), e.getAt()));
        }
        buf.sort(Comparator.comparing(m -> ((Instant) m.get("_at"))));
        java.util.Collections.reverse(buf);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(12, buf.size()); i++) {
            Map<String, Object> item = buf.get(i);
            // Copy out only the display fields, which drops the raw timestamp used for sorting.
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("severity",  item.get("severity"));
            clean.put("message",   item.get("message"));
            clean.put("timeLabel", item.get("timeLabel"));
            out.add(clean);
        }
        if (out.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("severity",  "secondary");
            empty.put("message",   "No recent activity yet.");
            empty.put("timeLabel", "");
            out.add(empty);
        }
        return out;
    }

    /**
     * One activity row. {@code sev} is the badge colour the panel renders, and {@code _at} is the
     * sort key that {@link #buildActivity} strips before the response is written.
     */
    private static Map<String, Object> actItem(String sev, String msg, String time, Instant at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity",  sev);
        m.put("message",   msg);
        m.put("timeLabel", time);
        m.put("_at",       at);   // removed before serializing
        return m;
    }

    // ── GET: analytics ────────────────────────────────────────────────────────

    /**
     * GET /api/admin/analytics. Platform totals for the analytics page. Gross auction value and
     * the platform's own revenue are reported separately, because commission and featured listing
     * fees are what the platform actually earns.
     */
    private void handleAnalytics(HttpServletResponse resp) throws IOException {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("totalUsers",    userDAO.countNonDeletedUsers());
            body.put("activeUsers",   userDAO.countActiveUsers());
            body.put("totalListings", auctionDAO.countListingsTotal());
            body.put("activeListings",auctionDAO.countListingsModerationActive());
            body.put("flagged",       auctionDAO.countListingsFlagged());
            body.put("revenue",       auctionDAO.sumWinningBidDollars());
            body.put("platformCommissionRevenue", platformRevenueDAO.sumByType("COMMISSION"));
            body.put("featuredListingRevenue", platformRevenueDAO.sumByType("FEATURED_LISTING"));
            body.put("topCreators",   auctionDAO.getTopAuctionCreator());
            body.put("topRevenue",    auctionDAO.getTopSellerRevenue());
            ok(resp, body);
        } catch (Exception e) {
            serverError(resp, "Could not load analytics.");
        }
    }

    /**
     * GET /api/admin/analytics/report?type=user-activity|revenue|moderation. Unlike everything
     * else here this writes plain text with a Content-Disposition header, so the browser saves it
     * as a file. An unknown type falls back to the user activity report.
     */
    /**
     * NEW for the "report filters by date range, category and seller" admin story: the largest
     * date range a report may be asked to cover. An "absurd range" (per the story) is rejected
     * as a 400 from this new validation rather than silently run — a query spanning centuries is
     * almost certainly a typo, not a real request.
     */
    private static final long MAX_REPORT_RANGE_DAYS = 3650; // 10 years
    /** Earliest plausible {@code dateFrom}/{@code dateTo}; anything before this is an "absurd" date. */
    private static final LocalDate MIN_REPORT_DATE = LocalDate.of(2000, 1, 1);

    private void handleAnalyticsReport(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String type = param(req, "type");
        if (type == null) type = "user-activity";

        // NEW for the "report filters by date range, category and seller" admin story: dateFrom,
        // dateTo and category are all optional. Absent means "no filter", so a request with none
        // of the three falls straight through to the exact unfiltered report generators below —
        // the original behaviour for every existing caller of this endpoint is untouched.
        LocalDate dateFrom = null;
        LocalDate dateTo = null;
        String dateFromStr = param(req, "dateFrom");
        String dateToStr = param(req, "dateTo");
        String category = param(req, "category");
        if (dateFromStr != null) {
            try {
                dateFrom = LocalDate.parse(dateFromStr);
            } catch (DateTimeParseException e) {
                badRequest(resp, "Invalid dateFrom. Use YYYY-MM-DD."); return;
            }
        }
        if (dateToStr != null) {
            try {
                dateTo = LocalDate.parse(dateToStr);
            } catch (DateTimeParseException e) {
                badRequest(resp, "Invalid dateTo. Use YYYY-MM-DD."); return;
            }
        }
        if ((dateFrom != null && dateFrom.isBefore(MIN_REPORT_DATE))
                || (dateTo != null && dateTo.isBefore(MIN_REPORT_DATE))) {
            badRequest(resp, "dateFrom/dateTo must not be before " + MIN_REPORT_DATE + "."); return;
        }
        if (dateTo != null && dateTo.isAfter(LocalDate.now().plusDays(1))) {
            badRequest(resp, "dateTo must not be in the future."); return;
        }
        if (dateFrom != null && dateTo != null) {
            if (dateFrom.isAfter(dateTo)) {
                badRequest(resp, "dateFrom must not be after dateTo."); return;
            }
            if (ChronoUnit.DAYS.between(dateFrom, dateTo) > MAX_REPORT_RANGE_DAYS) {
                badRequest(resp, "Date range is too large (maximum " + MAX_REPORT_RANGE_DAYS
                        + " days)."); return;
            }
        }
        boolean hasReportFilters = dateFrom != null || dateTo != null
                || (category != null && !category.isBlank());

        try {
            String body;
            String filename;
            switch (type.toLowerCase()) {
                case "revenue":
                    // Filters currently apply to the revenue report only: it is the report with
                    // both a time dimension (revenue by period) and a per-listing dimension
                    // (top sellers) that a date range and category meaningfully narrow. The
                    // user-activity and moderation reports below are unaffected by these
                    // parameters and keep calling their original no-arg methods.
                    body = hasReportFilters
                            ? adminReportDAO.generateRevenueReport(dateFrom, dateTo, category)
                            : adminReportDAO.generateRevenueReport();
                    filename = "revenue-report.txt";
                    break;
                case "moderation":
                    body = adminReportDAO.generateModerationReport();
                    filename = "moderation-report.txt";
                    break;
                case "user-activity":
                default:
                    body = adminReportDAO.generateUserActivityReport();
                    filename = "user-activity-report.txt";
                    break;
            }
            resp.setContentType("text/plain;charset=UTF-8");
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            resp.setStatus(200);
            resp.getWriter().write(body);
        } catch (Exception e) {
            serverError(resp, "Could not generate report.");
        }
    }

    /**
     * GET /api/admin/listings — the moderation table, with the product/service kind.
     *
     * <p>The rows are widened here rather than on {@code AdminListingRow} so the kind can be
     * surfaced without changing a model another part of the admin UI also serialises.</p>
     */
    private void handleGetListings(HttpServletResponse resp) throws IOException {
        try {
            Map<Long, String> kinds = adminManagementDAO.listingKinds();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (com.auction.model.admin.AdminListingRow l : auctionDAO.listListingsForModeration()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("auctionId", l.getAuctionId());
                row.put("title", l.getTitle());
                row.put("listedDate", l.getListedDate());
                row.put("sellerUsername", l.getSellerUsername());
                row.put("category", l.getCategory());
                row.put("currentBid", l.getCurrentBid());
                row.put("reportCount", l.getReportCount());
                row.put("moderationState", l.getModerationState());
                row.put("featured", l.isFeatured());
                row.put("auctionStatus", l.getAuctionStatus());
                row.put("listingKind", kinds.getOrDefault(l.getAuctionId(), "PRODUCT"));
                rows.add(row);
            }
            ok(resp, rows);
        } catch (Exception e) {
            serverError(resp, "Could not load listings.");
        }
    }

    /**
     * GET /api/admin/listings/content?auctionId=N — the editable fields of one listing.
     * Fetched on demand so full descriptions stay out of the moderation table payload.
     */
    private void handleGetListingContent(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String idStr = param(req, "auctionId");
        if (idStr == null) { badRequest(resp, "auctionId is required."); return; }
        long auctionId;
        try { auctionId = Long.parseLong(idStr.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }
        try {
            Map<String, Object> content = adminManagementDAO.getListingContent(auctionId);
            if (content == null) { error(resp, 404, "Listing not found."); return; }
            ok(resp, content);
        } catch (Exception e) {
            serverError(resp, "Could not load the listing.");
        }
    }

    /** GET /api/admin/audit-log?limit=N — the trail of admin management actions. */
    private void handleGetAuditLog(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            ok(resp, adminManagementDAO.listAuditLog(parseInt(param(req, "limit"), 100)));
        } catch (Exception e) {
            serverError(resp, "Could not load the admin audit log.");
        }
    }

    /** GET /api/admin/database/status. Connection and table information for the backup page. */
    private void handleDatabaseStatus(HttpServletResponse resp) throws IOException {
        try {
            ok(resp, DatabaseBackupUtil.status());
        } catch (Exception e) {
            serverError(resp, "Could not load database status.");
        }
    }

    /**
     * GET /api/admin/database/backup. Streams a SQL dump as a download. Sensitive by definition,
     * since the file contains every table including password hashes, so it lives behind the same
     * ADMIN gate as the rest of the console.
     */
    private void handleDatabaseBackup(HttpServletResponse resp) throws IOException {
        try {
            byte[] data = DatabaseBackupUtil.exportSql();
            resp.setContentType("application/sql;charset=UTF-8");
            resp.setHeader("Content-Disposition", "attachment; filename=\"auctionhub-backup.sql\"");
            resp.setStatus(200);
            resp.getOutputStream().write(data);
        } catch (Exception e) {
            serverError(resp, "Could not export backup.");
        }
    }

    /**
     * POST /api/admin/database/restore. The request body is the SQL dump text. Replies with the
     * statement and row counts rather than a bare acknowledgement, so a restore that parsed but
     * inserted nothing is visible instead of looking like a success.
     */
    private void handleDatabaseRestore(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = req.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }
            DatabaseBackupUtil.RestoreResult r = DatabaseBackupUtil.restoreSql(sb.toString());
            // Report what actually landed. A bare "completed" hid a parser bug that
            // silently discarded the first row of every table.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "Database restore completed: " + r.getStatements()
                    + " statement(s) applied, " + r.getRowsInserted() + " row(s) inserted"
                    + (r.getRowsInserted() == 0
                        ? " (every row was already present)." : "."));
            body.put("statements", r.getStatements());
            body.put("rowsInserted", r.getRowsInserted());
            ok(resp, body);
        } catch (IllegalArgumentException e) {
            badRequest(resp, e.getMessage());
        } catch (Exception e) {
            serverError(resp, "Could not restore backup: " + e.getMessage());
        }
    }

    /** What actually happened to one seller's analytics report. */
    private enum MailOutcome { SENT, NOT_CONFIGURED, FAILED }

    /** Pairs the outcome with the generated text, so a report can be shown inline when mail is off. */
    private static final class AnalyticsMailResult {
        final MailOutcome outcome;
        final String report;

        AnalyticsMailResult(MailOutcome outcome, String report) {
            this.outcome = outcome;
            this.report = report;
        }
    }

    /**
     * GET /api/admin/sellers/analytics?sellerId=N — the generated report, for reading.
     *
     * <p>Requirement (d) is about the admin producing this data for a seller. Until now the
     * only place the report rendered was the seller's own dashboard, so with SMTP off the
     * admin had no way to see what they had generated. This endpoint makes the requirement
     * demonstrable whether or not mail is configured.</p>
     */
    private void handleSellerAnalyticsView(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String sellerIdStr = param(req, "sellerId");
        if (sellerIdStr == null) { badRequest(resp, "sellerId is required."); return; }
        int sellerId;
        try { sellerId = Integer.parseInt(sellerIdStr.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid seller ID."); return; }

        User seller = userDAO.getUserById(sellerId);
        if (seller == null || !seller.canSell()) { error(resp, 404, "Seller not found."); return; }

        try {
            Map<String, Object> analytics = sellerAnalyticsDAO.generate(sellerId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sellerId", sellerId);
            body.put("sellerUsername", seller.getUsername());
            body.put("sellerEmail", seller.getEmail());
            body.put("emailConfigured", MailConfig.isSmtpConfigured());
            body.put("analytics", analytics);
            body.put("report", SellerAnalyticsDAO.toEmailBody(seller.getUsername(), analytics));
            ok(resp, body);
        } catch (Exception e) {
            serverError(resp, "Could not generate the seller analytics report.");
        }
    }

    /**
     * POST /api/admin/sellers/analytics-email with either {@code sellerId} or {@code all=true}.
     * Generates a seller's analytics report and emails it. The three outcomes are reported
     * distinctly: sent, generated but not sent because SMTP is off, and failed.
     */
    private void handleSellerAnalyticsEmail(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sellerIdStr = param(req, "sellerId");
        String sendAll = param(req, "all");
        try {
            if ("true".equalsIgnoreCase(sendAll)) {
                handleSellerAnalyticsEmailAll(resp);
                return;
            }
            if (sellerIdStr == null) { badRequest(resp, "sellerId or all=true is required."); return; }
            int sellerId = Integer.parseInt(sellerIdStr.trim());
            User seller = userDAO.getUserById(sellerId);
            if (seller == null || !seller.canSell()) {
                error(resp, 404, "Seller not found."); return;
            }

            AnalyticsMailResult result =
                    emailSellerAnalytics(sellerId, seller.getUsername(), seller.getEmail());
            switch (result.outcome) {
                case SENT:
                    Map<String, Object> sentBody = new LinkedHashMap<>();
                    sentBody.put("message", "Analytics report emailed to " + seller.getEmail() + ".");
                    sentBody.put("emailConfigured", true);
                    sentBody.put("report", result.report);
                    ok(resp, sentBody);
                    break;
                case NOT_CONFIGURED:
                    // The report was generated but nothing left the server, so say so and hand
                    // the report back inline. Reporting success here is what made the admin UI
                    // claim it had mailed a seller on a server with no SMTP at all.
                    Map<String, Object> inline = new LinkedHashMap<>();
                    inline.put("message", "Email is not configured on this server, so nothing was "
                            + "sent. The report is shown below instead.");
                    inline.put("emailConfigured", false);
                    inline.put("report", result.report);
                    ok(resp, inline);
                    break;
                case FAILED:
                default:
                    serverError(resp, "Could not send the analytics report to " + seller.getEmail() + ".");
            }
        } catch (NumberFormatException e) {
            badRequest(resp, "Invalid seller ID.");
        } catch (Exception e) {
            serverError(resp, "Could not send analytics emails.");
        }
    }

    /**
     * Sends the report to every active seller, counting the three outcomes so the admin sees how
     * many went out and which sellers failed. Inactive and non-selling accounts are skipped.
     */
    private void handleSellerAnalyticsEmailAll(HttpServletResponse resp) throws IOException {
        int sent = 0;
        int generatedOnly = 0;
        int failed = 0;
        List<String> failedSellers = new ArrayList<>();
        for (AdminUserSummary u : userDAO.listUsersForAdminTable()) {
            if (!u.canSell() || u.getStatusId() != Status.ACTIVE.getId()) continue;
            AnalyticsMailResult r = emailSellerAnalytics(u.getId(), u.getUsername(), u.getEmail());
            switch (r.outcome) {
                case SENT:           sent++; break;
                case NOT_CONFIGURED: generatedOnly++; break;
                default:             failed++; failedSellers.add(u.getUsername());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        boolean configured = MailConfig.isSmtpConfigured();
        StringBuilder msg = new StringBuilder();
        if (!configured) {
            msg.append("Email is not configured on this server, so no report was sent. Generated ")
               .append(generatedOnly)
               .append(" report(s) — pick a seller above to read one inline.");
        } else {
            msg.append("Emailed ").append(sent).append(" seller(s).");
        }
        if (failed > 0) {
            msg.append(" Failed for ").append(failed).append(": ")
               .append(String.join(", ", failedSellers)).append('.');
        }
        body.put("message", msg.toString());
        body.put("emailConfigured", configured);
        body.put("sent", sent);
        body.put("generatedNotSent", generatedOnly);
        body.put("failed", failed);
        ok(resp, body);
    }

    /**
     * Generates one seller's report and tries to mail it. Returns the outcome rather than
     * throwing, so the bulk send can carry on past a seller with no email address or a rejected
     * message. The report text comes back either way.
     */
    private AnalyticsMailResult emailSellerAnalytics(int sellerId, String username, String email) {
        String report;
        try {
            report = SellerAnalyticsDAO.toEmailBody(username, sellerAnalyticsDAO.generate(sellerId));
        } catch (Exception e) {
            return new AnalyticsMailResult(MailOutcome.FAILED, null);
        }
        if (!MailConfig.isSmtpConfigured()) {
            return new AnalyticsMailResult(MailOutcome.NOT_CONFIGURED, report);
        }
        if (email == null || email.isBlank()) {
            return new AnalyticsMailResult(MailOutcome.FAILED, report);
        }
        try {
            OtpMailer.sendNotification(email, "Your AuctionHub seller analytics report", report);
            return new AnalyticsMailResult(MailOutcome.SENT, report);
        } catch (Exception e) {
            return new AnalyticsMailResult(MailOutcome.FAILED, report);
        }
    }

    // ── POST: user action ─────────────────────────────────────────────────────

    /**
     * POST /api/admin/users with {@code action} and {@code userid}. Handles suspend, unban,
     * approve, reject and deactivate.
     *
     * <p>Two guards run before any action: an admin cannot change their own status, which stops
     * the last admin locking themselves out, and no admin account can be acted on here, so admins
     * cannot ban each other through the console. The current status is also checked, so a
     * repeated click reports the state rather than silently re-applying it.</p>
     */
    private void handleUserAction(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AuthSession session = authSession(req);
        int adminId = ((Number) session.getAttribute("userId")).intValue();

        String action = param(req, "action");
        String useridStr = param(req, "userid");
        if (action == null || useridStr == null) { badRequest(resp, "action and userid are required."); return; }

        int targetId;
        try { targetId = Integer.parseInt(useridStr.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid user ID."); return; }

        if (adminId == targetId) { error(resp, 400, "You cannot change your own account status."); return; }

        User target = userDAO.getUserById(targetId);
        if (target == null) { error(resp, 404, "User not found."); return; }
        if (target.getRole() == Role.ADMIN) {
            error(resp, 400, "Admin accounts cannot be banned or unbanned here."); return;
        }

        switch (action.toLowerCase()) {
            case "suspend":
                if (target.getStatusId() == Status.SUSPENDED.getId()) {
                    error(resp, 400, "User account is already banned."); return;
                }
                if (userDAO.updateStatus(targetId, Status.SUSPENDED.getId())) {
                    okMsg(resp, "Account successfully banned.");
                } else {
                    serverError(resp, "Could not ban account.");
                }
                break;
            case "active":
            case "unban":
                if (target.getStatusId() != Status.SUSPENDED.getId()) {
                    error(resp, 400, "User account is not currently banned."); return;
                }
                if (userDAO.updateStatus(targetId, Status.ACTIVE.getId())) {
                    okMsg(resp, "Account successfully unbanned.");
                } else {
                    serverError(resp, "Could not unban account.");
                }
                break;
            case "approve":
                if (target.getStatusId() != Status.PENDING.getId()) {
                    error(resp, 400, "This account is not awaiting approval."); return;
                }
                if (userDAO.updateStatus(targetId, Status.ACTIVE.getId())) {
                    com.auction.notification.NotificationService.notifyAccountApproved(targetId);
                    okMsg(resp, "Account approved.");
                } else {
                    serverError(resp, "Could not approve account.");
                }
                break;
            case "reject":
                if (target.getStatusId() != Status.PENDING.getId()) {
                    error(resp, 400, "This account is not awaiting approval."); return;
                }
                if (userDAO.updateStatus(targetId, Status.REJECTED.getId())) {
                    com.auction.notification.NotificationService.notifyAccountRejected(targetId);
                    okMsg(resp, "Account rejected.");
                } else {
                    serverError(resp, "Could not reject account.");
                }
                break;
            case "deactivate":
                customerDeactivate(req, resp, adminId, targetId);
                break;
            default:
                badRequest(resp, "Unknown action: " + action);
        }
    }

    /**
     * POST /api/admin/users action=deactivate, userid, reason — the Delete that
     * "manage ... customers" was missing.
     *
     * <p>A soft delete to the existing {@code Deleted} status, so the account's bids, orders
     * and reviews keep an author and the action stays reversible. Refused while the account
     * has a live listing or an unsettled order, which would strand the counterparty.</p>
     */
    private void customerDeactivate(HttpServletRequest req, HttpServletResponse resp,
                                    int adminId, int targetId) throws IOException {
        String reason = SecurityUtil.sanitize(param(req, "reason"));
        if (reason == null || reason.isBlank()) {
            badRequest(resp, "A reason is required when deactivating an account."); return;
        }
        try {
            AdminManagementDAO.Outcome outcome = adminManagementDAO.deactivateCustomer(
                    adminId, targetId, Status.DELETED.getId(), reason);
            switch (outcome) {
                case SUCCESS:
                    okMsg(resp, "Account deactivated and recorded in the audit log.");
                    break;
                case UNCHANGED:
                    error(resp, 400, "This account is already deactivated.");
                    break;
                case NOT_FOUND:
                    error(resp, 404, "User not found.");
                    break;
                case INVALID:
                default:
                    error(resp, 400, "This account still has a live listing or an unsettled order. "
                            + "Settle those first, or ban the account instead.");
            }
        } catch (Exception e) {
            serverError(resp, "Could not deactivate the account.");
        }
    }

    // ── POST: listing action ──────────────────────────────────────────────────

    /**
     * POST /api/admin/listings with {@code action} and {@code auctionId}. The moderation verbs.
     * FLAG marks a listing for review, REMOVE hides it from public view, RESTORE undoes that,
     * FEATURE and UNFEATURE control paid promotion, and EDIT and SET_KIND correct its content.
     *
     * <p>REMOVE only changes the moderation state, so the row and its bid history survive. That
     * keeps the action reversible and leaves an audit trail if the removal was a mistake.</p>
     */
    private void handleListingAction(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action    = param(req, "action");
        String idStr     = param(req, "auctionId");
        if (action == null || idStr == null) { badRequest(resp, "action and auctionId are required."); return; }

        long auctionId;
        try { auctionId = Long.parseLong(idStr.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid auction ID."); return; }

        boolean ok;
        switch (action.trim().toUpperCase()) {
            case "FLAG":
                ok = auctionDAO.incrementReports(auctionId) && auctionDAO.updateModerationState(auctionId, "flagged");
                if (ok) okMsg(resp, "Listing flagged for review.");
                else serverError(resp, "Could not flag listing.");
                break;
            case "REMOVE":
                ok = auctionDAO.updateModerationState(auctionId, "removed");
                if (ok) okMsg(resp, "Listing removed from public view.");
                else serverError(resp, "Could not remove listing.");
                break;
            case "RESTORE":
                ok = auctionDAO.updateModerationState(auctionId, "active");
                if (ok) okMsg(resp, "Listing restored.");
                else serverError(resp, "Could not restore listing.");
                break;
            case "FEATURE": {
                int days = parseInt(param(req, "days"), 7);
                int sellerId = featuredListingDAO.sellerIdForAuction(auctionId);
                if (sellerId < 0) { error(resp, 404, "Auction not found."); return; }
                ok = featuredListingDAO.featureAuction(auctionId, days);
                if (ok) {
                    // Featuring is a paid promotion, so it books revenue against the seller.
                    // Wrapped because the listing is already featured by this point and a
                    // bookkeeping failure should not undo that.
                    try { platformRevenueDAO.recordFeaturedListing(auctionId, sellerId); } catch (Exception ignored) { }
                    okMsg(resp, "Listing featured for " + days + " days.");
                } else {
                    serverError(resp, "Could not feature listing.");
                }
                break;
            }
            case "UNFEATURE":
                ok = featuredListingDAO.unfeatureAuction(auctionId);
                if (ok) okMsg(resp, "Listing removed from featured.");
                else serverError(resp, "Could not unfeature listing.");
                break;
            case "EDIT":
                listingEdit(req, resp, auctionId);
                break;
            case "SET_KIND":
                listingSetKind(req, resp, auctionId);
                break;
            default:
                badRequest(resp, "Unknown action: " + action);
        }
    }

    /**
     * POST /api/admin/listings action=EDIT — corrects a listing's title, description,
     * category and product/service kind.
     *
     * <p>Price and quantity are intentionally not editable here: a bid is an offer against a
     * published price, so changing it mid-auction would rewrite the terms buyers already
     * committed to. Content correction is the moderation need — previously the only remedy
     * for an inappropriate title was removing the whole listing.</p>
     */
    private void listingEdit(HttpServletRequest req, HttpServletResponse resp, long auctionId)
            throws IOException {
        int adminId = adminId(req);
        String title = SecurityUtil.sanitize(param(req, "title"));
        String description = SecurityUtil.sanitize(param(req, "description"));
        String category = SecurityUtil.sanitize(param(req, "category"));
        String kind = param(req, "listingKind");
        String reason = SecurityUtil.sanitize(param(req, "reason"));

        if (title == null || title.isBlank()) { badRequest(resp, "title is required."); return; }
        if (title.length() > 255) { badRequest(resp, "Title must be 255 characters or fewer."); return; }
        if (description == null || description.isBlank()) {
            badRequest(resp, "description is required."); return;
        }

        try {
            AdminManagementDAO.Outcome outcome = adminManagementDAO.updateListingContent(
                    adminId, auctionId, title, description, category, kind, reason);
            switch (outcome) {
                case SUCCESS:   okMsg(resp, "Listing content updated and recorded in the audit log."); break;
                case UNCHANGED: okMsg(resp, "No changes to save."); break;
                case NOT_FOUND: error(resp, 404, "Listing not found."); break;
                case INVALID:
                default:        badRequest(resp, "listingKind must be PRODUCT or SERVICE.");
            }
        } catch (Exception e) {
            serverError(resp, "Could not update the listing.");
        }
    }

    /** POST /api/admin/listings action=SET_KIND — reclassifies product vs service. */
    private void listingSetKind(HttpServletRequest req, HttpServletResponse resp, long auctionId)
            throws IOException {
        String kind = param(req, "listingKind");
        String reason = SecurityUtil.sanitize(param(req, "reason"));
        try {
            AdminManagementDAO.Outcome outcome =
                    adminManagementDAO.updateListingKind(adminId(req), auctionId, kind, reason);
            switch (outcome) {
                case SUCCESS:   okMsg(resp, "Listing reclassified as " + kind.trim().toUpperCase() + "."); break;
                case UNCHANGED: okMsg(resp, "Listing was already classified that way."); break;
                case NOT_FOUND: error(resp, 404, "Listing not found."); break;
                case INVALID:
                default:        badRequest(resp, "listingKind must be PRODUCT or SERVICE.");
            }
        } catch (Exception e) {
            serverError(resp, "Could not reclassify the listing.");
        }
    }

    /** The acting admin's own id, taken from the session, for stamping the audit log entry. */
    private int adminId(HttpServletRequest req) {
        return ((Number) authSession(req).getAttribute("userId")).intValue();
    }

    // ── POST: category action ─────────────────────────────────────────────────

    /** POST /api/admin/categories. Dispatches CREATE, EDIT, DELETE or RESTORE on the category list. */
    private void handleCategoryAction(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = param(req, "action");
        if (action == null) { badRequest(resp, "action is required."); return; }

        switch (action.trim().toUpperCase()) {
            case "CREATE": catCreate(req, resp); break;
            case "EDIT":   catEdit(req, resp);   break;
            case "DELETE": catDelete(req, resp);  break;
            case "RESTORE":catRestore(req, resp); break;
            default: badRequest(resp, "Unknown action: " + action);
        }
    }

    /**
     * Creates a category from {@code name}, {@code description} and optional
     * {@code displayOrder}. Names must be unique because buyers filter by them, and the URL slug
     * is derived from the name rather than accepted from the request.
     */
    private void catCreate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // sanitizeText, not sanitize: the name is echoed back through escaping views, so
        // entity-encoding it here would store "Home &amp; Garden" / "Men&#39;s Fashion".
        String name = SecurityUtil.sanitizeText(param(req, "name"));
        String desc = SecurityUtil.sanitizeText(param(req, "description"));
        int order   = parseInt(param(req, "displayOrder"), 0);

        String nameErr = InputValidator.getCategoryNameViolation(name);
        if (nameErr != null) { badRequest(resp, nameErr); return; }
        String descErr = InputValidator.getCategoryDescriptionViolation(desc);
        if (descErr != null) { badRequest(resp, descErr); return; }
        if (catDAO.nameExists(name)) { error(resp, 400, "A category with that name already exists."); return; }

        String slug = resolveSlug(name, -1);
        int newId = catDAO.insert(name, desc, order, slug);
        if (newId > 0) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("categoryId", newId);
            body.put("message", "Category created.");
            ok(resp, body);
        } else {
            serverError(resp, "Could not create category.");
        }
    }

    /**
     * Renames or reorders a category. The uniqueness check excludes the row being edited, so
     * saving a category without changing its name is not rejected as a duplicate of itself.
     */
    private void catEdit(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int id = parseCatId(req, resp); if (id < 0) return;

        String name = SecurityUtil.sanitizeText(param(req, "name"));
        String desc = SecurityUtil.sanitizeText(param(req, "description"));
        int order   = parseInt(param(req, "displayOrder"), 0);

        String nameErr = InputValidator.getCategoryNameViolation(name);
        if (nameErr != null) { badRequest(resp, nameErr); return; }
        String descErr = InputValidator.getCategoryDescriptionViolation(desc);
        if (descErr != null) { badRequest(resp, descErr); return; }
        if (catDAO.nameExistsExcluding(name, id)) { error(resp, 400, "A category with that name already exists."); return; }
        if (catDAO.findById(id) == null) { error(resp, 404, "Category not found."); return; }

        String slug = resolveSlug(name, id);
        boolean ok  = catDAO.update(id, name, desc, order, slug);
        if (ok) okMsg(resp, "Category updated.");
        else serverError(resp, "Could not update category.");
    }

    /**
     * Deactivates a category. A soft delete, and refused while any auction still points at it,
     * which would otherwise leave those listings with a category that no longer resolves.
     */
    private void catDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int id = parseCatId(req, resp); if (id < 0) return;
        if (catDAO.findById(id) == null) { error(resp, 404, "Category not found."); return; }
        int count = catDAO.countAuctions(id);
        if (count > 0) {
            error(resp, 400, "Category has " + count + " linked auction(s). Remove or recategorize them first."); return;
        }
        boolean ok = catDAO.softDelete(id);
        if (ok) okMsg(resp, "Category deactivated.");
        else serverError(resp, "Could not deactivate category.");
    }

    /** Reactivates a soft-deleted category so it can be selected on new listings again. */
    private void catRestore(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int id = parseCatId(req, resp); if (id < 0) return;
        if (catDAO.findById(id) == null) { error(resp, 404, "Category not found."); return; }
        boolean ok = catDAO.restore(id);
        if (ok) okMsg(resp, "Category restored.");
        else serverError(resp, "Could not restore category.");
    }

    // ── GET: reports ─────────────────────────────────────────────────────────

    /** GET /api/admin/reports. Listing reports and account reports merged into one moderation queue. */
    private void handleGetReports(HttpServletResponse resp) throws IOException {
        try {
            ok(resp, reportDAO.getAllReportsUnified());
        } catch (Exception e) {
            serverError(resp, "Could not load reports.");
        }
    }

    // ── POST: report action ───────────────────────────────────────────────────

    /**
     * POST /api/admin/reports with {@code action}, {@code reportId} and {@code type}. Resolves or
     * dismisses a report, or saves a reply the reporter will see on their own reports page.
     * {@code type} is mandatory because the two report tables number their rows independently.
     */
    private void handleReportAction(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action    = param(req, "action");
        String idStr     = param(req, "reportId");
        String type      = param(req, "type");
        if (action == null || idStr == null) { badRequest(resp, "action and reportId are required."); return; }

        long reportId;
        try { reportId = Long.parseLong(idStr.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid report ID."); return; }

        // Listing and account reports number their rows independently, so an id on its own
        // does not identify a report. Refuse the request rather than pick a table and risk
        // moderating a stranger's report.
        if (ReportDAO.reportTable(type) == null) {
            badRequest(resp, "type must be 'listing' or 'account'."); return;
        }
        boolean listing = "listing".equalsIgnoreCase(type);

        try {
            if ("reply".equalsIgnoreCase(action.trim())) {
                String reply = param(req, "reply");
                if (reply == null || reply.isBlank()) { badRequest(resp, "reply is required."); return; }
                boolean ok = reportDAO.replyToReport(reportId, type, reply.trim());
                if (ok) okMsg(resp, "Reply saved.");
                else error(resp, 404, "Report not found.");
                return;
            }
            boolean resolved;
            switch (action.trim().toLowerCase()) {
                case "resolve":  resolved = true;  break;
                case "dismiss":  resolved = false; break;
                default: badRequest(resp, "Unknown action: " + action); return;
            }
            boolean ok = listing
                    ? reportDAO.setSellerReportStatus(reportId, resolved)
                    : reportDAO.setReportStatus(reportId, String.valueOf(resolved));
            if (ok) okMsg(resp, "Report updated.");
            else error(resp, 404, "Report not found.");
        } catch (Exception e) {
            serverError(resp, "Could not update report.");
        }
    }

    /** GET /api/admin/orders. Every order on the platform, for dispute handling and correction. */
    private void handleGetOrders(HttpServletResponse resp) throws IOException {
        try {
            ok(resp, orderDAO.listAllForAdmin());
        } catch (Exception e) {
            serverError(resp, "Could not load orders.");
        }
    }

    /**
     * POST /api/admin/orders  action=refund-approve|refund-decline, orderId —
     * admin dispute resolution (SCRUM-70). Overrides the seller and settles a
     * pending refund request; both parties are notified.
     */
    private void handleOrderAction(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = param(req, "action");
        long orderId;
        try { orderId = Long.parseLong(param(req, "orderId")); }
        catch (Exception e) { badRequest(resp, "orderId is required."); return; }

        if ("correct-status".equalsIgnoreCase(action)) {
            orderCorrectStatus(req, resp, orderId);
            return;
        }
        boolean approve = "refund-approve".equalsIgnoreCase(action);
        if (!approve && !"refund-decline".equalsIgnoreCase(action)) {
            badRequest(resp,
                    "action must be 'refund-approve', 'refund-decline' or 'correct-status'."); return;
        }

        try {
            OrderDAO.RefundDecision d = orderDAO.adminResolveRefund(orderId, approve);
            switch (d) {
                case SUCCESS:
                    com.auction.notification.NotificationService
                            .notifyBuyerRefundResolved(orderId, approve, "An AuctionHub admin");
                    okMsg(resp, approve
                            ? "Refund approved — the order was cancelled and the buyer was notified."
                            : "Refund declined — the order stays active and the buyer was notified.");
                    break;
                case NOT_FOUND:
                    error(resp, 404, "Order not found.");
                    break;
                case NOT_REQUESTED:
                default:
                    error(resp, 400, "There is no pending refund request for this order.");
            }
        } catch (Exception e) {
            serverError(resp, "Could not resolve the refund request.");
        }
    }

    /**
     * POST /api/admin/orders action=correct-status, orderId, status, reason —
     * reconciles an order whose recorded state has drifted from reality.
     *
     * <p>The order's {@code amount} is not editable. That figure is the settled sale value
     * and it feeds platform revenue and the seller's earnings, so an admin able to rewrite
     * it could falsify the platform's own books. State is a record of what happened and can
     * legitimately be wrong; the money is the transaction itself.</p>
     */
    private void orderCorrectStatus(HttpServletRequest req, HttpServletResponse resp, long orderId)
            throws IOException {
        String status = param(req, "status");
        String reason = SecurityUtil.sanitize(param(req, "reason"));
        if (status == null || status.isBlank()) { badRequest(resp, "status is required."); return; }
        if (reason == null || reason.isBlank()) {
            badRequest(resp, "A reason is required when correcting an order's state."); return;
        }
        try {
            AdminManagementDAO.Outcome outcome =
                    adminManagementDAO.correctOrderStatus(adminId(req), orderId, status, reason);
            switch (outcome) {
                case SUCCESS:
                    okMsg(resp, "Order state corrected to " + status.trim().toUpperCase()
                            + " and recorded in the audit log.");
                    break;
                case UNCHANGED: okMsg(resp, "Order is already in that state."); break;
                case NOT_FOUND: error(resp, 404, "Order not found."); break;
                case INVALID:
                default:
                    badRequest(resp, "status must be one of PENDING_PAYMENT, PAID, COMPLETED, CANCELLED.");
            }
        } catch (Exception e) {
            serverError(resp, "Could not correct the order state.");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** First path segment after /api/admin, which selects the resource in both routing switches. */
    private String sub(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return "";
        return p.replaceFirst("^/", "").split("/")[0];
    }

    /** Parses {@code categoryId}, writing the 400 itself and returning -1 so callers guard on a negative. */
    private int parseCatId(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String s = param(req, "categoryId");
        if (s == null || s.isBlank()) { badRequest(resp, "categoryId is required."); return -1; }
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid category ID."); return -1; }
    }

    /** Parses an optional numeric parameter, falling back to {@code def} when absent or malformed. */
    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /**
     * Builds a URL-safe slug from a category name and makes it unique by appending -2, -3 and so
     * on. {@code excludeId} is the row being edited, or -1 when creating, so a category keeps its
     * own slug on save instead of colliding with itself.
     */
    private String resolveSlug(String name, int excludeId) {
        String base = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "category";
        String candidate = base;
        int suffix = 2;
        while (excludeId < 0 ? catDAO.slugExists(candidate) : catDAO.slugExistsExcluding(candidate, excludeId)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    /** First {@code n} items, used to trim the dashboard previews without a second query. */
    private static <T> List<T> slice(List<T> list, int n) {
        return list.size() > n ? list.subList(0, n) : list;
    }
}
