package com.auction.servlet.api;

import com.auction.dao.NotificationDAO;
import com.auction.model.Notification;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET  /api/notifications             — recent notifications + unread count for the user
 * POST /api/notifications   action=read [id] | readAll
 * GET  /api/notifications/preferences — the user's notification preferences
 * POST /api/notifications/preferences — save preferences (params: outbid, endingSoon, wonAuction)
 * Requires any authenticated user.
 */
@WebServlet({"/api/notifications", "/api/notifications/preferences"})
public class NotificationApiServlet extends ApiBase {

    private static final int LIST_LIMIT = 30;
    private NotificationDAO dao;

    public NotificationApiServlet() {
        this.dao = new NotificationDAO();
    }

    /** Test hook */
    public void setNotificationDAO(NotificationDAO dao) { this.dao = dao; }

    private static boolean isPreferencesPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri != null && uri.endsWith("/preferences");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        if (isPreferencesPath(req)) {
            NotificationDAO.Preferences p = dao.getUserPreferences(userId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("outbid", p.outbid);
            body.put("endingSoon", p.endingSoon);
            body.put("wonAuction", p.wonAuction);
            ok(resp, body);
            return;
        }

        List<Notification> items = dao.listForUser(userId, LIST_LIMIT);
        int unread = dao.countUnread(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notifications", items);
        body.put("unreadCount", unread);
        ok(resp, body);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAuth(req, resp)) return;
        int userId = sessionUserId(req);

        if (isPreferencesPath(req)) {
            NotificationDAO.Preferences current = dao.getUserPreferences(userId);
            boolean outbid     = boolParam(req, "outbid", current.outbid);
            boolean endingSoon = boolParam(req, "endingSoon", current.endingSoon);
            boolean won        = boolParam(req, "wonAuction", current.wonAuction);
            try {
                dao.saveUserPreferences(userId, outbid, endingSoon, won);
            } catch (Exception e) {
                serverError(resp, "Failed to save notification preferences.");
                return;
            }
            okMsg(resp, "Notification preferences saved.");
            return;
        }

        String action = param(req, "action");
        if (action == null) action = "read";

        if ("readAll".equalsIgnoreCase(action)) {
            int n = dao.markAllRead(userId);
            okMsg(resp, "Marked " + n + " notification(s) read.");
            return;
        }

        String idStr = param(req, "id");
        if (idStr == null) { badRequest(resp, "id is required."); return; }
        long id;
        try { id = Long.parseLong(idStr); }
        catch (NumberFormatException e) { badRequest(resp, "Invalid notification id."); return; }

        dao.markRead(userId, id);
        okMsg(resp, "Notification marked read.");
    }

    private boolean boolParam(HttpServletRequest req, String name, boolean fallback) {
        String v = param(req, name);
        if (v == null) return fallback;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v);
    }
}
