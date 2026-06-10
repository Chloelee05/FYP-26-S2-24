package com.auction.servlet.api;

import com.auction.dao.AuctionDAO;
import com.auction.dao.CategoryDAO;
import com.auction.dao.ReportDAO;
import com.auction.dao.UserDAO;
import com.auction.model.Role;
import com.auction.model.Status;
import com.auction.model.User;
import com.auction.model.admin.DashboardMetrics;
import com.auction.util.InputValidator;
import com.auction.util.RelativeTime;
import com.auction.util.AuthSession;
import com.auction.util.SecurityUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET  /api/admin/dashboard
 * GET  /api/admin/users
 * POST /api/admin/users           (action: suspend|active, userid)
 * GET  /api/admin/listings
 * POST /api/admin/listings        (action: FLAG|REMOVE|RESTORE, auctionId)
 * GET  /api/admin/categories
 * POST /api/admin/categories      (action: CREATE|EDIT|DELETE|RESTORE)
 * GET  /api/admin/analytics
 * All require ADMIN role.
 */
@WebServlet("/api/admin/*")
public class AdminApiServlet extends ApiBase {

    private final UserDAO     userDAO    = new UserDAO();
    private final AuctionDAO  auctionDAO = new AuctionDAO();
    private final CategoryDAO catDAO     = new CategoryDAO();
    private final ReportDAO   reportDAO  = new ReportDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;
        switch (sub(req)) {
            case "dashboard":   handleDashboard(resp);         break;
            case "users":       ok(resp, userDAO.listUsersForAdminTable()); break;
            case "listings":    ok(resp, auctionDAO.listListingsForModeration()); break;
            case "categories":  ok(resp, catDAO.listAll());    break;
            case "analytics":   handleAnalytics(resp);         break;
            case "reports":     handleGetReports(resp);        break;
            default: error(resp, 404, "Not found.");            break;
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireRole(req, resp, "ADMIN")) return;
        switch (sub(req)) {
            case "users":      handleUserAction(req, resp);      break;
            case "listings":   handleListingAction(req, resp);   break;
            case "categories": handleCategoryAction(req, resp);  break;
            case "reports":    handleReportAction(req, resp);    break;
            default: error(resp, 404, "Not found.");             break;
        }
    }

    // ── GET: dashboard ───────────────────────────────────────────────────────

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
                "+ 12.5% this month");

        List<Map<String, Object>> activities = buildActivity(now, zone);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics",    metrics);
        body.put("activities", activities);
        body.put("previewUsers",    slice(userDAO.listUsersForAdminTable(),    5));
        body.put("previewListings", slice(auctionDAO.listListingsForModeration(), 5));
        ok(resp, body);
    }

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

    private static Map<String, Object> actItem(String sev, String msg, String time, Instant at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity",  sev);
        m.put("message",   msg);
        m.put("timeLabel", time);
        m.put("_at",       at);   // removed before serializing
        return m;
    }

    // ── GET: analytics ────────────────────────────────────────────────────────

    private void handleAnalytics(HttpServletResponse resp) throws IOException {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("totalUsers",    userDAO.countNonDeletedUsers());
            body.put("activeUsers",   userDAO.countActiveUsers());
            body.put("totalListings", auctionDAO.countListingsTotal());
            body.put("activeListings",auctionDAO.countListingsModerationActive());
            body.put("flagged",       auctionDAO.countListingsFlagged());
            body.put("revenue",       auctionDAO.sumWinningBidDollars());
            body.put("topCreators",   auctionDAO.getTopAuctionCreator());
            body.put("topRevenue",    auctionDAO.getTopSellerRevenue());
            ok(resp, body);
        } catch (Exception e) {
            serverError(resp, "Could not load analytics.");
        }
    }

    // ── POST: user action ─────────────────────────────────────────────────────

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
            default:
                badRequest(resp, "Unknown action: " + action);
        }
    }

    // ── POST: listing action ──────────────────────────────────────────────────

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
            default:
                badRequest(resp, "Unknown action: " + action);
        }
    }

    // ── POST: category action ─────────────────────────────────────────────────

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

    private void catCreate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String name = SecurityUtil.sanitize(param(req, "name"));
        String desc = SecurityUtil.sanitize(param(req, "description"));
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

    private void catEdit(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int id = parseCatId(req, resp); if (id < 0) return;

        String name = SecurityUtil.sanitize(param(req, "name"));
        String desc = SecurityUtil.sanitize(param(req, "description"));
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

    private void catRestore(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int id = parseCatId(req, resp); if (id < 0) return;
        if (catDAO.findById(id) == null) { error(resp, 404, "Category not found."); return; }
        boolean ok = catDAO.restore(id);
        if (ok) okMsg(resp, "Category restored.");
        else serverError(resp, "Could not restore category.");
    }

    // ── GET: reports ─────────────────────────────────────────────────────────

    private void handleGetReports(HttpServletResponse resp) throws IOException {
        try {
            ok(resp, reportDAO.getAllReportsUnified());
        } catch (Exception e) {
            serverError(resp, "Could not load reports.");
        }
    }

    // ── POST: report action ───────────────────────────────────────────────────

    private void handleReportAction(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action    = param(req, "action");
        String idStr     = param(req, "reportId");
        String type      = param(req, "type");
        if (action == null || idStr == null) { badRequest(resp, "action and reportId are required."); return; }

        long reportId;
        try { reportId = Long.parseLong(idStr.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid report ID."); return; }

        try {
            boolean resolved;
            switch (action.trim().toLowerCase()) {
                case "resolve":  resolved = true;  break;
                case "dismiss":  resolved = false; break;
                default: badRequest(resp, "Unknown action: " + action); return;
            }
            boolean ok = "listing".equalsIgnoreCase(type)
                    ? reportDAO.setSellerReportStatus(reportId, resolved)
                    : reportDAO.setReportStatus(reportId, String.valueOf(resolved));
            if (ok) okMsg(resp, "Report updated.");
            else error(resp, 404, "Report not found.");
        } catch (Exception e) {
            serverError(resp, "Could not update report.");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String sub(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return "";
        return p.replaceFirst("^/", "").split("/")[0];
    }

    private int parseCatId(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String s = param(req, "categoryId");
        if (s == null || s.isBlank()) { badRequest(resp, "categoryId is required."); return -1; }
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid category ID."); return -1; }
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

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

    private static <T> List<T> slice(List<T> list, int n) {
        return list.size() > n ? list.subList(0, n) : list;
    }
}
